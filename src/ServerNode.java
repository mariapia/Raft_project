import akka.actor.*;
import akka.japi.Procedure;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import scala.concurrent.duration.Duration;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import akka.persistence.*;

public class ServerNode extends UntypedActor {
    Config config = ConfigFactory.load("application");
    protected int id;
    protected List<ActorRef> participants = new ArrayList<ActorRef>();

    //variables in Persistent State
    protected Integer currentTerm;
    protected Integer votedFor;
    protected ArrayList<LogEntry> log;

    //variables in Non-Persistent State
    protected ServerState state;
    protected Integer leaderID;
    protected int commitIndex;
    private Integer[] nextIndex = new Integer[config.getInt("N_SERVER")];
    private Integer[] matchIndex = new Integer[config.getInt("N_SERVER")];
    private ActorRef client;
    private ActorRef client_address;
    private int indexStories = 0;

    private Cancellable electionScheduler;
    private Cancellable stepDownScheduler;
    private Cancellable changeToLeader;
    private Cancellable debugging;
    private Cancellable heartbeatScheduler;

    private Cancellable commandScheduler;

    protected boolean stepdown;

    private int receivedVote;
    private ArrayList<Integer> votes;
    private int candidate_state;

    //to check how many peer send me a positive reply (used only by leader)
    private boolean[] getReply = new boolean[config.getInt("N_SERVER")];
    private int[] termsPeers = new int[config.getInt("N_SERVER")];
    private List<Integer> responseReceived = new ArrayList<>();
    //flag variable to avoid multiple confirmation message to client
    private boolean alreadySent = false;




    public ServerNode(int id) {
        super();
        this.id = id;
        this.currentTerm = 0;
        this.leaderID = -1;
        this.commitIndex = 0;
        this.state = ServerState.FOLLOWER;
        for (int i = 0; i < config.getInt("N_SERVER"); i++) {
            //nextIndex[i] = 0;
            nextIndex[i] = 1;
            matchIndex[i] = 0;
        }

        this.log = new ArrayList<>();
        this.receivedVote = 0;
        this.votes = new ArrayList<>();
        //BASE CASE - no votes received
        this.candidate_state = 0;
        this.stepdown = false;
        this.votedFor = -1;
        //first entry of log is used only to have index that starts from 1
        this.log.add(new LogEntry(0, "START PROTOCOL"));
    }

    public Procedure<Object> getPauseActor() {
        return pauseActor;
    }

    public Procedure<Object> getResumeActor() {
        return resumeActor;
    }


    //procedure to simulate a temporaney stop of a sever
    private Procedure<Object> resumeActor = message -> {
        if (!(message instanceof ResumeActor)) { // discard ResumeMessage when actor is already active
            try {
                onReceive(message); // the behaviour when active is the same as usual
            } catch (Throwable throwable) {
                System.err.println(throwable.getMessage());
                throwable.printStackTrace();
            }
        }
    };

    //procedure to resume an actor after it stops for a while
    private Procedure<Object> pauseActor = message -> {
        if (!(message instanceof PauseActor)) {
            if (message instanceof ResumeActor) {
                this.getContext().become(this.getResumeActor());
                System.out.println("SERVER " + this.id+" RESUMED");
            }
            else{
                System.out.println("Sono " + this.id + " message "  + message.toString() + " avoided");
            }
        }
    };

    @Override
    public void onReceive(Object message) throws Throwable {
        //StartMessage is inital message in order to allow each server to know which are other servers in the system
        if (message instanceof StartMessage) {
            debugging = getContext().system().scheduler().schedule(Duration.Zero(), Duration.create(300, TimeUnit.MILLISECONDS), getSelf(), new Debugging(), getContext().system().dispatcher(), getSelf());
            StartMessage msg = (StartMessage) message;
            client = msg.client;

            try {
                //add servers to a list
                for (int i = 0; i < msg.group.size(); i++) {
                    this.participants.add(msg.group.get(i));

                }
            } catch (Throwable e) {
                System.out.println(e.getStackTrace());
            }

        }
        //server informed of stop of another server
        if (message instanceof PauseActor) {
            System.out.println("OH NO, SYSTEM " + this.id +" PAUSED");
            Cancellable crashTimeout = getContext().system().scheduler().scheduleOnce(Duration.create(2000, TimeUnit.MILLISECONDS), getSelf(), new ResumeActor(), getContext().system().dispatcher(), getSelf());
            this.getContext().become(this.getPauseActor());
        }
        //change the state of a server, from Candidate to Follower
        if (message instanceof StateChanger) {
            ((StateChanger) message).onReceive(this);
        }
        int chanceToFail = ThreadLocalRandom.current().nextInt(1, 101);
        if (chanceToFail > 99)
        {
            this.getSelf().tell(new PauseActor(), getSelf());
        }

        //print information about server
        if (message instanceof Debugging) {
            ((Debugging) message).onReceive(this);
        } else {
            //different behaviour for each server state
            switch (this.state) {
                case FOLLOWER:
                    follower(message);
                    break;
                case CANDIDATE:
                    candidate(message);
                    break;
                case LEADER:
                    leader(message);
                    break;
            }
        }
    }

    //stepDown procedure
    public void stepDown(int term) {
        this.currentTerm = term;
        this.state = ServerState.FOLLOWER;
        this.votedFor = -1;
        this.leaderID = -1;
        this.stepdown = true;
        stepDownScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(0, TimeUnit.MILLISECONDS), getSelf(), new StateChanger(), getContext().system().dispatcher(), getSelf());
    }

    //procedure that handle leader server state
    private void leader(Object message) {

        if (message instanceof StateChanger) {
            leaderID = this.id;

            if (electionScheduler != null && !electionScheduler.isCancelled()) {
                electionScheduler.cancel();
            }

            if (heartbeatScheduler != null && !heartbeatScheduler.isCancelled()) {
                heartbeatScheduler.cancel();
            }

            heartbeatScheduler = getContext().system().scheduler().schedule(Duration.Zero(), Duration.create(config.getInt("HEARTBEAT_TIMEOUT"), TimeUnit.MILLISECONDS), getSelf(), new HeartBeat(), getContext().system().dispatcher(), getSelf());
        }

        //old leaders may receive AppendRequest message
        //leader receives AppendRequest during Election steps
        if (message instanceof AppendRequest) {
            int termReceived = ((AppendRequest) message).term;
            int prevIndexReceived = ((AppendRequest) message).prevIndex;
            int prevTermReceived = ((AppendRequest) message).prevTerm;
            ArrayList<LogEntry> entriesReceived = ((AppendRequest) message).entries;
            int commitIndexReceived = ((AppendRequest) message).commitIndex;

            boolean success = false;

            if (termReceived > this.currentTerm) {
                stepDown(termReceived);
            } else if (termReceived < this.currentTerm) {
                success = false;
                int lastTermSaved = this.log.get(this.indexStories - 1).term;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, lastTermSaved, this.commitIndex);
                getSender().tell(response, getSelf());
                return;
            }

            this.leaderID = ((AppendRequest) message).leaderId;

            if (entriesReceived.isEmpty()) {
                if (electionScheduler != null && !electionScheduler.isCancelled())
                    electionScheduler.cancel();

                int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
                electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());
                success = true;
                AppendReply appRepMessage = new AppendReply(this.id, this.currentTerm, success, -2, -2, 0);

                this.getSender().tell(appRepMessage, this.getSelf());
                return;
            }

            this.indexStories = 0;

            if (prevIndexReceived == 0) {
                success = true;
            } else if (prevIndexReceived <= this.log.size() && this.log.get(prevIndexReceived).term == prevTermReceived) {
                success = true;
            }

            if (success) {
                this.indexStories = storeEntries(prevIndexReceived, entriesReceived, commitIndexReceived);

                int lastTermSaved = this.log.get(this.indexStories).term;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, lastTermSaved, this.commitIndex);
                getSender().tell(response, getSelf());
            } else {
                AppendReply appRepMessage = new AppendReply(this.id, this.currentTerm, success, -1, -1, 0);
            }
        }

        //received a VoteRequest message
        if (message instanceof VoteRequest) {
            if (((VoteRequest) message).currentTerm > currentTerm) {
                stepDown(((VoteRequest) message).currentTerm);
            }
            if (((VoteRequest) message).currentTerm < currentTerm) {
                this.getSender().tell(new VoteReply(-1, currentTerm, this.id, false), getSelf());
                return;
            }
            if (((VoteRequest) message).currentTerm == currentTerm &&
                    (this.votedFor == -1 || this.votedFor == ((VoteRequest) message).senderID) &&
                    (((VoteRequest) message).lastLogTerm > getLastLogTerm(log.size() - 1) || (((VoteRequest) message).lastLogTerm == getLastLogTerm(log.size() - 1) && ((VoteRequest) message).lastLogIndex >= getLastLogIndex())
                    )) {
                this.votedFor = ((VoteRequest) message).senderID;

                if (electionScheduler != null && !electionScheduler.isCancelled())
                    electionScheduler.cancel();
                int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
                electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());
                this.getSender().tell(new VoteReply(votedFor, currentTerm, this.id, true), getSelf());
                return;
            }
                this.getSender().tell(new VoteReply(votedFor, currentTerm, this.id, false), getSelf());
        }

        //heartbeat received
        if (message instanceof HeartBeat) {
            ((HeartBeat) message).onReceive(this);
        }

        //receive command from client
        if (message instanceof SendCommand) {
            String commandReceived = ((SendCommand) message).command;
            this.responseReceived.clear();

            if (commandReceived.equals("FINISH")) {
                System.out.println("\n____________________________________________________IL CLIENT NON HA PIÙ COMANDI__________________________________________________________\n");
                getSender().tell(new InformClient(this.leaderID, this.participants.get(this.leaderID), true), getSelf());
                context().system().shutdown();
            } else {

                alreadySent = false;
                if (((SendCommand) message).client_name.equals(client.path().name())) {
                    this.client_address = this.getSender();
                    if (checkCommandExecuted(commandReceived))
                    {
                        System.out.println("\n_____________________________________________________COMMAND " + commandReceived + " ALREADY EXECUTED____________________________________________\n");
                        InformClient resultCommand = new InformClient(this.leaderID, this.participants.get(this.leaderID), true);
                        this.client_address.tell(resultCommand, getSelf());
                        return;
                    }
                    System.out.println("\n_____________________________________________________NEW COMMAND BY CLIENT: " + commandReceived + "____________________________________________\n");
                    //received new command, start to process it
                    LogEntry newEntry = new LogEntry(currentTerm, commandReceived);
                    log.add(newEntry);
                    this.nextIndex[this.id] = this.nextIndex[this.id] + 1;
                    //inform follower of new command
                    for (ActorRef peer : this.participants) {
                        if (peer != getSelf()) {
                            sendAppendEntries(peer);
                        }
                    }
                } else {
                    System.out.println("ERROR, sendCommand must be sent by client, received from " + ((SendCommand) message).client_name);
                }
            }

        }

        //received AppendReply from followers
        if (message instanceof AppendReply) {
            int termReceived = ((AppendReply) message).currentTerm;
            boolean successReceived = ((AppendReply) message).success;
            int indexStoriesReceived = ((AppendReply) message).indexStories;
            int senderID = ((AppendReply) message).senderID;
            int lastTermSavedPeer = ((AppendReply) message).lastTermSaved;
            int commitIndexPeer = ((AppendReply) message).commitIndex;


            Boolean failure = false;

            if (lastTermSavedPeer >=0) {
                //check commitIndex to know if the reply from follower is regarding current command or a previous one
                if (commitIndexPeer == this.commitIndex) {
                    getReply[this.id] = true;
                    termsPeers[this.id] = this.log.get(nextIndex[this.id] - 1).term;
                    termsPeers[senderID] = lastTermSavedPeer;
                    responseReceived.add(senderID);
                }
            }

            if (termReceived > this.currentTerm) {
                stepDown(termReceived);
            }
            if (this.state != ServerState.LEADER || lastTermSavedPeer == -2) {
                return;
            }

            if (termReceived == this.currentTerm) {
                if (successReceived) {
                    //update information about next free slot for peer's log
                    this.nextIndex[senderID] = indexStoriesReceived + 1;
                    getReply[senderID] = true;
                } else {
                    this.nextIndex[senderID] = Math.max(1, indexStoriesReceived);
                }
                //adjust follower's log
                if (this.nextIndex[senderID] < (this.log.size() - 1)) {
                    sendAppendEntries(getSender());
                }

            }
            //if leader received more than an half number of answer, check the consistency of positive response and term
            if (responseReceived.size() > (this.participants.size() / 2)) {
                if (checkMajorityReply(getReply) && checkMajorityCurrentTerm(this.currentTerm, termsPeers)) {
                    Boolean commandCommited = true;
                    ActorRef leader = this.participants.get(this.leaderID);
                    InformClient resultCommand = new InformClient(this.leaderID, leader, commandCommited);
                    this.commitIndex++;
                    if (!alreadySent) {
                        client_address.tell(resultCommand, getSelf());
                        alreadySent = true;
                        UpdateCommitIndex update = new UpdateCommitIndex();
                        for (ActorRef peer : this.participants) {
                            if (peer != getSelf()) {
                                peer.tell(update, getSelf());
                            }
                        }
                    }else{
                        failure = true;
                    }

                }
            } else {
                failure = true;
            }
            //if leader received all answers for that command and failure is true, inform client of negative result
            if (responseReceived.size() == this.participants.size()) {
                if (failure) {
                    Boolean commandCommited = false;
                    ActorRef leader = this.participants.get(this.leaderID);
                    InformClient resultCommand = new InformClient(this.leaderID, leader, commandCommited);
                    client_address.tell(resultCommand, getSelf());
                }
            }
            //if leader send the result to the client, clear all variables
            if (alreadySent) {
                for (int i = 0; i < getReply.length; i++) {
                    getReply[i] = false;
                }
            }


        }
    }

    //procedure that handle candidate behaviour
    private void candidate(Object message) {
        if (message instanceof StateChanger || message instanceof ElectionMessage) {
            if (electionScheduler != null && !electionScheduler.isCancelled())
                electionScheduler.cancel();
            this.votes.clear();

            this.currentTerm++;
            this.votedFor = this.id;
            this.votes.add(this.id);

            int lastLogIndex = 0;
            int lastLogTerm = 0;

            if (this.log.size() > 0) {
                lastLogIndex = getLastLogIndex();
                lastLogTerm = getLastLogTerm(lastLogIndex);
            }

            //random timeout for the election
            int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
            //when timeout runs out, the server starts a new election
            electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());
            for (ActorRef q : participants) {
                if (q != getSelf()) {
                    q.tell(new VoteRequest(this.id, this.currentTerm, lastLogIndex, lastLogTerm), getSelf());
                }
            }
        } else if (message instanceof VoteRequest) {
            if (((VoteRequest) message).currentTerm > currentTerm) {
                stepDown(((VoteRequest) message).currentTerm);
            }

            if (((VoteRequest) message).currentTerm < currentTerm) {
                this.getSender().tell(new VoteReply(-1, currentTerm, this.id, false), getSelf());
                return;
            }

            if (((VoteRequest) message).currentTerm == currentTerm &&
                    (this.votedFor == -1 || this.votedFor == ((VoteRequest) message).senderID) &&
                    (((VoteRequest) message).lastLogTerm > getLastLogTerm(log.size() - 1) || (((VoteRequest) message).lastLogTerm == getLastLogTerm(log.size() - 1) && ((VoteRequest) message).lastLogIndex >= getLastLogIndex())
                    )) {

                votedFor = ((VoteRequest) message).senderID;
                if (electionScheduler != null && !electionScheduler.isCancelled())
                    electionScheduler.cancel();
                int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
                electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());
                this.getSender().tell(new VoteReply(votedFor, currentTerm, this.id, true), getSelf());
                return;
            }
            this.getSender().tell(new VoteReply(-1, currentTerm, this.id, false), getSelf());
        } else if (message instanceof VoteReply) {
            if (((VoteReply) message).term > this.currentTerm) {
                stepDown(((VoteReply) message).term);
            }
            if (((VoteReply) message).term == this.currentTerm && this.state == ServerState.CANDIDATE) {
                if (((VoteReply) message).votedID == this.id && ((VoteReply) message).granted == true) {
                    votes.add(((VoteReply) message).senderID);
                }
                if (votes.size() > participants.size() / 2) {
                    this.stepdown = false;
                    changeToLeader = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(0, TimeUnit.MILLISECONDS), getSelf(), new StateChanger(), getContext().system().dispatcher(), getSelf());
                }

            }
        } else if (message instanceof AppendRequest) {
            int termReceived = ((AppendRequest) message).term;
            int prevIndexReceived = ((AppendRequest) message).prevIndex;
            int prevTermReceived = ((AppendRequest) message).prevTerm;
            ArrayList<LogEntry> entriesReceived = ((AppendRequest) message).entries;
            int commitIndexReceived = ((AppendRequest) message).commitIndex;

            boolean success = false;

            if (termReceived > this.currentTerm) {
                stepDown(termReceived);
            } else if (termReceived < this.currentTerm) {
                              success = false;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, -1, this.commitIndex);
                getSender().tell(response, getSelf());
                return;
            }
            if (termReceived >= this.currentTerm)
                this.stepdown = true;
                stepDownScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(0, TimeUnit.MILLISECONDS), getSelf(), new StateChanger(), getContext().system().dispatcher(), getSelf());

            this.leaderID = ((AppendRequest) message).leaderId;
            if (entriesReceived.isEmpty()) {
                if (electionScheduler != null && !electionScheduler.isCancelled())
                    electionScheduler.cancel();

                int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
                electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());

                success = true;
                //send a null reply
                AppendReply appRepMessage = new AppendReply(this.id, this.currentTerm, success, -2, -2, 0);

                this.getSender().tell(appRepMessage, this.getSelf());
                return;
            }

            this.indexStories = 0;

            if (prevIndexReceived == 0) {
                success = true;
            } else if (prevIndexReceived <= this.log.size() && this.log.get(prevIndexReceived).term == prevTermReceived) {
                success = true;
            }
            if (success) {

                this.indexStories = storeEntries(prevIndexReceived, entriesReceived, commitIndexReceived);
                int lastTermSaved = this.log.get(this.indexStories).term;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, lastTermSaved, this.commitIndex);

                getSender().tell(response, getSelf());
            } else {
                //command not to commit
                AppendReply appRepMessage = new AppendReply(this.id, this.currentTerm, success, -1, -1, 0);
            }
        }

    }

    //procedure that handle follower behaviour
    private void follower(Object message) {
        if (heartbeatScheduler != null && !heartbeatScheduler.isCancelled())
            heartbeatScheduler.cancel();

        //if a follower receives a message of these type it will delete any previous timeout and starts a new ones. The range of the timeout is defined in the configuration file
        if (message instanceof StartMessage || message instanceof StateChanger || message instanceof ElectionMessage) {
            if (electionScheduler != null && !electionScheduler.isCancelled())
                electionScheduler.cancel();
            this.stepdown = false;
            int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);

            //scheduling of message to change state
            electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new StateChanger(), getContext().system().dispatcher(), getSelf());

        } else if (message instanceof SendCommand) {
            //if a follower receive a command from a client, it informs the client which node is the leader
            ActorRef leader;
            if (this.leaderID == -1) {
                leader = null;

            } else {
                leader = this.participants.get(this.leaderID);
            }
            InformClient inform = new InformClient(this.leaderID, leader, false);
            getSender().tell(inform, getSelf());

        } else if (message instanceof VoteRequest) {
            //VoteRequest received from the Candidate, it will check the consistency of the term and if Candidate's log is up-to-date
            //if peer's term is lower than the one receive, so it will perform a stepdown procedure to adjust its term
            if (((VoteRequest) message).currentTerm > currentTerm) {
                stepDown(((VoteRequest) message).currentTerm);
            }
            //if peer's term is greater than Candidate's term, so it will inform the Candidate of this inconsistency
            if (((VoteRequest) message).currentTerm < currentTerm) {
                this.getSender().tell(new VoteReply(-1, currentTerm, this.id, false), getSelf());
                return;
            }
            //grant the vote to the Candidate if the Candidate's log is up-to-date
            if (((VoteRequest) message).currentTerm == currentTerm &&
                    (this.votedFor == -1 || this.votedFor == ((VoteRequest) message).senderID) &&
                    (((VoteRequest) message).lastLogTerm > getLastLogTerm(log.size() - 1) || (((VoteRequest) message).lastLogTerm == getLastLogTerm(log.size() - 1) && ((VoteRequest) message).lastLogIndex >= getLastLogIndex())
                    )) {
                this.votedFor = ((VoteRequest) message).senderID;

                if (electionScheduler != null && !electionScheduler.isCancelled())
                    electionScheduler.cancel();
                int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
                electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());
                this.getSender().tell(new VoteReply(votedFor, currentTerm, this.id, true), getSelf());
                return;
            }
            this.getSender().tell(new VoteReply(-1, currentTerm, this.id, false), getSelf());

        } else if (message instanceof VoteReply) {
            if (((VoteReply) message).term > this.currentTerm) {
                stepDown(((VoteReply) message).term);
            }
        }
        if (message instanceof AppendRequest) {
            int termReceived = ((AppendRequest) message).term;
            int prevIndexReceived = ((AppendRequest) message).prevIndex;
            int prevTermReceived = ((AppendRequest) message).prevTerm;
            ArrayList<LogEntry> entriesReceived = ((AppendRequest) message).entries;
            int commitIndexReceived = ((AppendRequest) message).commitIndex;

            boolean success = false;
            //if peer's term is older than the one received, performs a stepDown operation to update the term
            if (termReceived > this.currentTerm) {
                stepDown(termReceived);
                //if peer's term is greater inform the leader
            } else if (termReceived < this.currentTerm) {
                success = false;
                //inform leader that server cannot commit command because terms are not correct
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, -1, this.commitIndex);
                getSender().tell(response, getSelf());
                return;
            }
            this.leaderID = ((AppendRequest) message).leaderId;
            //if entries is empty it means that the peer has received an heartbeat
            if (entriesReceived.isEmpty()) {
                if (electionScheduler != null && !electionScheduler.isCancelled())
                    electionScheduler.cancel();

                int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
                electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());

                success = true;
                AppendReply appRepMessage = new AppendReply(this.id, this.currentTerm, success, -2, -2, 0);

                this.getSender().tell(appRepMessage, this.getSelf());
                return;
            }

            this.indexStories = 0;
            //check log consistency and in case store the entries sent by the leader
            if (prevIndexReceived == 0) {
                success = true;
            } else if (prevIndexReceived <= this.log.size() && this.log.get(prevIndexReceived).term == prevTermReceived) {
                success = true;
            }
            if (success) {
                this.indexStories = storeEntries(prevIndexReceived, entriesReceived, commitIndexReceived);

                int lastTermSaved = this.log.get(this.indexStories).term;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, lastTermSaved, this.commitIndex);
                getSender().tell(response, getSelf());
            } else {
                //inform leader that follower cannot commit command
                AppendReply appRepMessage = new AppendReply(this.id, this.currentTerm, success, -1, -1, 0);
            }
        }
        if (message instanceof UpdateCommitIndex) {
            this.commitIndex++;

        }
    }


    //PROCEDURES TO FACILITATE ALGORITHM EXECUTION

    //inform follower that there are new entries to add to their log
    private void sendAppendEntries(ActorRef peer) {
        ActorRef leader = getSender();
        int id_peer = returnIdPeer(peer);

        int nextIndexLogPeer = nextIndex[id_peer];
        this.nextIndex[id_peer] = nextIndexLogPeer;

        ArrayList<LogEntry> entries = getEntriesToSend(this.log, (nextIndexLogPeer - 1));
        AppendRequest appendRequest = new AppendRequest(this.id, this.currentTerm, (nextIndexLogPeer - 1), this.log.get(nextIndexLogPeer-1).term, entries, this.commitIndex);
        peer.tell(appendRequest, getSelf());


    }

    //check number of positive reply from follower
    private boolean checkMajorityReply(boolean[] getReply) {
        int n_true = 0;
        for (int i = 0; i < getReply.length; i++) {
            if (getReply[i]) {
                n_true++;
            }
        }
        //majority obtained with "la metà +1 "
        if (n_true > (getReply.length / 2)) {
            return true;
        } else {
            return false;
        }
    }

    //check if leader term is known by majority of server
    private boolean checkMajorityCurrentTerm(int currentTerm, int[] termPeer) {
        int nPeerWithMyTerm = 0;
        for (int i = 0; i < termPeer.length; i++) {
            if (currentTerm == termPeer[i]) {
                nPeerWithMyTerm++;
            }
        }
        if (nPeerWithMyTerm > termPeer.length / 2) {
            return true;
        } else {
            return false;
        }
    }

    //store new entries in follower log, deleting unmatching entries
    private int storeEntries(int prevIndex, ArrayList<LogEntry> entries, int commitIndex) {
        int index = prevIndex;
        if (this.log.isEmpty()) {
            for (int i = 0; i < entries.size(); i++) {
                this.log.add(entries.get(i));
                index = index + 1;
            }
        } else {
            for (int j = 0; j < entries.size(); j++) {
                index = index + 1;

                //check if there is a not-null entry in that position
                int lastEntry = getLastLogIndex(this.log);

                if (lastEntry == prevIndex) {
                    this.log.add(entries.get(j));
                } else if (this.log.get(index).term != entries.get(j).term) {

                    //remove entry that does not fit with leader's entry
                    this.log.remove(index);
                    this.log.add(entries.get(j));
                }
                prevIndex++;
            }
        }
        this.commitIndex = Math.min(commitIndex, index);
        //return the index to the first slot free in peer's log
        return index;

    }

    //return only new leader entries to pass to followers
    private ArrayList<LogEntry> getEntriesToSend(ArrayList<LogEntry> logLeader, int lastLogIndex) {
        //lastLogIndex is the index of the last log written by peer
        ArrayList<LogEntry> entries = new ArrayList<>();
        int start = lastLogIndex + 1;
        int end = logLeader.size();

        for (int i = start; i < end; i++) {
            entries.add(logLeader.get(i));
        }
        return entries;
    }

    private int getLastLogIndex() {
        if (log.size() <= 0) {
            return 0;
        } else {
            return log.size() - 1;
        }
    }
    public int getLastLogIndex(ArrayList<LogEntry> log) {
        if (log.size() <= 0) {
            return 0;
        } else {
            return log.size() - 1;
        }
    }

    private int getLastLogTerm(int lastLogIndex) {
        if (lastLogIndex <= 0) {
            return 0;
        } else {
            return log.get(lastLogIndex).term;
        }
    }


    //check if commans is committed
    public boolean checkCommandExecuted(String command)
    {
        if(this.log.size() > 0)
        {
            for(LogEntry entry : this.log){
                if(entry != null && entry.command.equals(command))
                    return true;
            }
        }
        return false;
    }

    //return id server from his path name
    public int returnIdPeer(ActorRef peer) {
        String name = peer.path().name();
        String[] tokens = name.split("_");
        int idPeer = Integer.parseInt(tokens[1]);
        return idPeer;
    }

}

