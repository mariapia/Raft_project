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


    //private final static Logger fileLog = Logger.getLogger(ServerNode.class.getName());

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
    private boolean alreadySent = false;

    private List<Integer> responseReceived = new ArrayList<>();


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
        //aggiungo al log con index = 0 una entry nulla, per far partire il log dalla posizione 1 così che combaci con l'index dell'algoritmo
        this.log.add(new LogEntry(0, "START PROTOCOL"));
    }

    public Procedure<Object> getPauseActor() {
        return pauseActor;
    }

    public Procedure<Object> getResumeActor() {
        return resumeActor;
    }

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


    private Procedure<Object> pauseActor = message -> {
        if (!(message instanceof PauseActor)) {
            if (message instanceof ResumeActor) {
                this.getContext().become(this.getResumeActor());
                System.out.println("OH YES, I'M BACK! " + this.id);
            }
            else{
                System.out.println("Sono " + this.id + " message "  + message.toString() + " avoided");
            }
        }
    };

    @Override
    public void onReceive(Object message) throws Throwable {
        //System.out.println("Sono " + this.id + " messaggio di tipo " + message.getClass().getName());
        if (message instanceof StartMessage) {
            debugging = getContext().system().scheduler().schedule(Duration.Zero(), Duration.create(300, TimeUnit.MILLISECONDS), getSelf(), new Debugging(), getContext().system().dispatcher(), getSelf());
            StartMessage msg = (StartMessage) message;
            client = msg.client;
            System.out.println("IL CLIENT È" + client.path().name());
            try {
                for (int i = 0; i < msg.group.size(); i++) {
                    this.participants.add(msg.group.get(i));

                }
            } catch (Throwable e) {
                System.out.println(e.getStackTrace());
            }

        }
        if (message instanceof PauseActor) {
            System.out.println("OH NO, SYSTEM " + this.id +" PAUSED");
            Cancellable crashTimeout = getContext().system().scheduler().scheduleOnce(Duration.create(2000, TimeUnit.MILLISECONDS), getSelf(), new ResumeActor(), getContext().system().dispatcher(), getSelf());
            this.getContext().become(this.getPauseActor());
        }
        if (message instanceof StateChanger) {
            ((StateChanger) message).onReceive(this);
        }
        int chanceToFail = ThreadLocalRandom.current().nextInt(1, 101);
        if (chanceToFail > 99)
        {
            this.getSelf().tell(new PauseActor(), getSelf());
        }


        if (message instanceof Debugging) {
            ((Debugging) message).onReceive(this);
        } else {
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

//    public void startElection(int id){
//        int idSender = id;
//        System.out.println("Start election for node " +idSender);
//    }

//    public void sendAppendEntries(){
    //System.out.println("Invio AppendEntries");
//    }

//    public void handleAppendRequest(AppendRequest message){}

    public void stepDown(int term) {
        this.currentTerm = term;
        this.state = ServerState.FOLLOWER;
        this.votedFor = -1;
        this.leaderID = -1;
        this.stepdown = true;
        //System.out.println("Sono " + this.id + " faccio step down");
        //need to change the timeout
        stepDownScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(0, TimeUnit.MILLISECONDS), getSelf(), new StateChanger(), getContext().system().dispatcher(), getSelf());
    }

    private void leader(Object message) {
        //System.out.println("LEADER NAME --> " + this.participants.get(this.id).path().name() + ", LEADER ID --> " + this.leaderID);
        if (message instanceof StateChanger) {
            leaderID = this.id;

            if (electionScheduler != null && !electionScheduler.isCancelled()) {
                electionScheduler.cancel();
            }

            if (heartbeatScheduler != null && !heartbeatScheduler.isCancelled()) {
                heartbeatScheduler.cancel();
            }

            heartbeatScheduler = getContext().system().scheduler().schedule(Duration.Zero(), Duration.create(config.getInt("HEARTBEAT_TIMEOUT"), TimeUnit.MILLISECONDS), getSelf(), new HeartBeat(), getContext().system().dispatcher(), getSelf());
//            if (this.state == ServerState.LEADER) {
//                ActorRef leader = this.participants.get(this.id);
//                InformClient tmp = new InformClient(this.id, leader,true);
//                client.tell(tmp, getSelf());
//            }
//            for (ActorRef q : participants) {
//                if (q != getSelf()) {
//                    System.out.println("Sono " + this.id + " e sono " + this.state + " ho ricevuto i seguenti voti " + this.votes );
//                    //sendAppendEntries();
//                }
//            }
            if (message instanceof AppendRequest) {
                //System.out.println("PEER " + this.id + "---> Ho ricevuto un AppendRequest");
                System.out.println("SONO LEADER E HO RICEVUTO UN APPEN REQUEST");
                int termReceived = ((AppendRequest) message).term;
                int prevIndexReceived = ((AppendRequest) message).prevIndex;
                int prevTermReceived = ((AppendRequest) message).prevTerm;
                ArrayList<LogEntry> entriesReceived = ((AppendRequest) message).entries;
                int commitIndexReceived = ((AppendRequest) message).commitIndex;

                boolean success = false;

                if (termReceived > this.currentTerm) {
                    System.out.println("STEPDOWN() ricevuto un term maggiore da " + getSender().path().name() + " io sono " + this.id);
                    stepDown(termReceived);
                } else if (termReceived < this.currentTerm) {
                    System.out.println("success = FALSE, send answer to leader i'm " + this.id + "  leader is " + this.leaderID + " meesage received from " + getSender().path().name());
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

                    //System.out.println("Ho ricevuto un HEARTBEAT da " + this.getSender().path().name() + " mando un ACK. Sono " + this.id + " stato:" + this.state);

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
                    //System.out.println("PEER " + this.id + " ---> ho avuto successo\n");
                    this.indexStories = storeEntries(prevIndexReceived, entriesReceived, commitIndexReceived);
                    //System.out.println("NODE "+this.id+"_______indexStories after ____"+this.indexStories);
                    for (int i = 1; i < this.log.size(); i++) {
                        System.out.println("LOG NODE " + this.id + " n_elements " + (this.log.size() - 1) + " -----> command: " + log.get(i).command + ",  term: " + log.get(i).term);
                    }
                    System.out.println("\n");
                    int lastTermSaved = this.log.get(this.indexStories).term;
                    AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, lastTermSaved, this.commitIndex);
                    //System.out.println("PEER " + this.id + " --> invio di AppendReply al leader\n");
                    getSender().tell(response, getSelf());
                } else {
                    //commit non andato a buon fine
                    AppendReply appRepMessage = new AppendReply(this.id, this.currentTerm, success, -1, -1, 0);
                }
            }
        }
        if (message instanceof VoteRequest) {
            //System.out.println("Sono " + this.id + " ho ricevuto una VoteRequest da " + ((VoteRequest) message).senderID);
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
                //System.out.println("Sono " + this.id + " ho votato per " + this.votedFor);

                if (electionScheduler != null && !electionScheduler.isCancelled())
                    electionScheduler.cancel();
                int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
                electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());
                this.getSender().tell(new VoteReply(votedFor, currentTerm, this.id, true), getSelf());
                return;
            }
                this.getSender().tell(new VoteReply(votedFor, currentTerm, this.id, false), getSelf());
        }

        if (message instanceof HeartBeat) {
            ((HeartBeat) message).onReceive(this);
        }

        //se sono il leader inizio a ricevere i comandi dal client
        if (message instanceof SendCommand) {
            String commandReceived = ((SendCommand) message).command;
            responseReceived.clear();
            //this.client = this.getSender();

            if (commandReceived.equals("FINISH")) {
                System.out.println("\n____________________________________________________IL CLIENT NON HA PIÙ COMANDI__________________________________________________________\n");
                getSender().tell(new InformClient(this.leaderID, this.participants.get(this.leaderID), true), getSelf());
                context().system().shutdown();
            } else {

                alreadySent = false;
                //responseReceived.clear();
                //System.out.println("\n RESPONSE RECEIVED SIZE = "+responseReceived.size()+" QUANDO HO APPENA RICEVUTO UN COMANDO DAL CLIENT\n");
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
                    LogEntry newEntry = new LogEntry(currentTerm, commandReceived);
                    log.add(newEntry);
                    this.nextIndex[this.id] = this.nextIndex[this.id] + 1;
                    //System.out.println("LEADER LOG size " + (this.log.size() - 1));
                    for (int i = 1; i < this.log.size(); i++) {
                        System.out.println("LEADER LOG entry n. " + i + "   term -> " + this.log.get(i).term + ",  command -> " + this.log.get(i).command);
                    }
                    System.out.println("\n");
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
        if (message instanceof AppendReply) {
            int termReceived = ((AppendReply) message).currentTerm;
            boolean successReceived = ((AppendReply) message).success;
            int indexStoriesReceived = ((AppendReply) message).indexStories;
            int senderID = ((AppendReply) message).senderID;
            int lastTermSavedPeer = ((AppendReply) message).lastTermSaved;
            int commitIndexPeer = ((AppendReply) message).commitIndex;
            //responseReceivd.add(senderID);

            Boolean failure = false;

            //System.out.println("LEADER ---> ricevuto AppendReply da PEER " + senderID + "\n");

            if (lastTermSavedPeer >=0) {
                if (commitIndexPeer == this.commitIndex) {
                    getReply[this.id] = true;
                    termsPeers[this.id] = this.log.get(nextIndex[this.id] - 1).term;
                    termsPeers[senderID] = lastTermSavedPeer;
                    responseReceived.add(senderID);
                } else {
                    System.out.println("\nLEADER - Received old answer\n");
                }
                //responseReceived.add(senderID);
                //System.out.println("\n RESPONSE RECEIVED SIZE = "+responseReceived.size()+" QUANDO HO APPENA RICEVUTO UN APPENDY REPLY NON NULLO DA UN PEER\n");
            }

            if (termReceived > this.currentTerm) {
                System.out.println("\nterm received greater than currentTerm\n");
                stepDown(termReceived);
            }
            if (this.state != ServerState.LEADER || lastTermSavedPeer == -2) {
                //Ricevo un ACK di un heartbeat
                //System.out.println("Sono " + this.id + " stato " + this.state + " e ho ricevuto un ACK di heartbeat da " + ((AppendReply) message).senderID);
                return;
            }

            if (termReceived == this.currentTerm) {
                if (successReceived) {
                    System.out.println("\nLEADER - received success by peer " + senderID + "\n");
                    //update information about next free slot for peer's log
                    this.nextIndex[senderID] = indexStoriesReceived + 1;
                    getReply[senderID] = true;
                } else {
                    System.out.println("\nLEADER - received failure by peer " + senderID + "\n");
                    this.nextIndex[senderID] = Math.max(1, indexStoriesReceived);
                }
                if (this.nextIndex[senderID] < (this.log.size() - 1)) {
                    System.out.println("\n LEADER - adjust peer log \n");
                    sendAppendEntries(getSender());
                }

            }
            //if leader received more than an half number of answer, check the consistency of positive response and term
            if (responseReceived.size() > (this.participants.size() / 2)) {
                if (checkMajorityReply(getReply) && checkMajorityCurrentTerm(this.currentTerm, termsPeers)) {
                    //System.out.println("\nin DOUBLE CHECK\n");
                    Boolean commandCommited = true;
                    ActorRef leader = this.participants.get(this.leaderID);
                    InformClient resultCommand = new InformClient(this.leaderID, leader, commandCommited);
                    this.commitIndex++;
                    if (!alreadySent) {
                        System.out.println("LEADER - command has been committed. Client informed");
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
            if (alreadySent) {
                for (int i = 0; i < getReply.length; i++) {
                    getReply[i] = false;
                }
            }


        }
    }

    private void candidate(Object message) {
        //TODO BUGGONE GIGANTE



        if (message instanceof StateChanger || message instanceof ElectionMessage) {
            if (electionScheduler != null && !electionScheduler.isCancelled())
                electionScheduler.cancel();
            System.out.println("SONO CANDIDATE e sono " + this.id + " start new election");
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
            int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
            electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());
            System.out.println("Mando una VoteRequest ai partecipanti sono " + this.id);
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
            //DONE NOW
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
            //System.out.println("Ho ricevuto un voteReply da " + ((VoteReply) message).senderID);
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
            //System.out.println("PEER " + this.id + "---> Ho ricevuto un AppendRequest");
            int termReceived = ((AppendRequest) message).term;
            int prevIndexReceived = ((AppendRequest) message).prevIndex;
            int prevTermReceived = ((AppendRequest) message).prevTerm;
            ArrayList<LogEntry> entriesReceived = ((AppendRequest) message).entries;
            int commitIndexReceived = ((AppendRequest) message).commitIndex;

            boolean success = false;

            if (termReceived > this.currentTerm) {
                System.out.println("STEPDOWN() ricevuto un term maggiore da " + getSender().path().name() + " io sono " + this.id);
                stepDown(termReceived);
            } else if (termReceived < this.currentTerm) {
                System.out.println("success = FALSE, send answer to leader i'm " + this.id + "  leader is " + this.leaderID + " meesage received from " + getSender().path().name());
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

                //System.out.println("Ho ricevuto un HEARTBEAT da " + this.getSender().path().name() + " mando un ACK. Sono " + this.id + " stato:" + this.state);

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
                //System.out.println("PEER " + this.id + " ---> ho avuto successo\n");
                this.indexStories = storeEntries(prevIndexReceived, entriesReceived, commitIndexReceived);
                //System.out.println("NODE "+this.id+"_______indexStories after ____"+this.indexStories);
                for (int i = 1; i < this.log.size(); i++) {
                    //System.out.println("LOG NODE " + this.id + " n_elements " + (this.log.size() - 1) + " -----> command: " + log.get(i).command + ",  term: " + log.get(i).term);
                }
                System.out.println("\n");
                int lastTermSaved = this.log.get(this.indexStories).term;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, lastTermSaved, this.commitIndex);
                //System.out.println("PEER " + this.id + " --> invio di AppendReply al leader\n");
                getSender().tell(response, getSelf());
            } else {
                //commit non andato a buon fine
                AppendReply appRepMessage = new AppendReply(this.id, this.currentTerm, success, -1, -1, 0);
            }
        }

    }

    private void follower(Object message) {
        if (heartbeatScheduler != null && !heartbeatScheduler.isCancelled())
            heartbeatScheduler.cancel();
        if (message instanceof StartMessage || message instanceof StateChanger || message instanceof ElectionMessage) {
            if (electionScheduler != null && !electionScheduler.isCancelled())
                electionScheduler.cancel();
            this.stepdown = false;
            int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
            System.out.println("Sono " + this.id + " ho settato il timeout a " + electionTimeout);
            //scheduling of message to change state
            electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new StateChanger(), getContext().system().dispatcher(), getSelf());
            //System.out.println("NODO : " + this.id + " stepdown: " + this.stepdown + " stato: " + this.state);
        } else if (message instanceof SendCommand) {
            //ricevo un comando dal client ma io non sono il leader, quindi comunico al client chi è il leader
            ActorRef leader;
            if (this.leaderID == -1) {
                leader = null;

            } else {
                leader = this.participants.get(this.leaderID);
            }
            //System.out.println("SONO UN FOLLOWER. HO RICEVUTO UN SendCommand DAL CLIENT. L'ID LEADER CHE GLI STO MANDANDO E' "+this.leaderID+"\n");
            InformClient inform = new InformClient(this.leaderID, leader, false);
            getSender().tell(inform, getSelf());
        } else if (message instanceof VoteRequest) {
            System.out.println("Sono " + this.id+ " ho ricevuto una VoteRequest da " + ((VoteRequest) message).senderID);
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
                System.out.println("Sono " + this.id + " ho votato per " + this.votedFor);
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
            //System.out.println("PEER " + this.id + "---> Ho ricevuto un AppendRequest");
            int termReceived = ((AppendRequest) message).term;
            int prevIndexReceived = ((AppendRequest) message).prevIndex;
            int prevTermReceived = ((AppendRequest) message).prevTerm;
            ArrayList<LogEntry> entriesReceived = ((AppendRequest) message).entries;
            int commitIndexReceived = ((AppendRequest) message).commitIndex;

            boolean success = false;

            if (termReceived > this.currentTerm) {
                System.out.println("STEPDOWN() ricevuto un term maggiore da " + getSender().path().name() + " io sono " + this.id);
                stepDown(termReceived);
            } else if (termReceived < this.currentTerm) {
                System.out.println("Ricevuta appendEntries con term minore del mio, invio risposta al leader, message received from " + getSender().path().name());
                success = false;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, -1, this.commitIndex);
                getSender().tell(response, getSelf());
                return;
            }
            this.leaderID = ((AppendRequest) message).leaderId;
            if (entriesReceived.isEmpty()) {
                System.out.println("Rec heartbeat from " + this.leaderID);
                if (electionScheduler != null && !electionScheduler.isCancelled())
                    electionScheduler.cancel();

                int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
                electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());

                //System.out.println("Ho ricevuto un HEARTBEAT da " + this.getSender().path().name() + " mando un ACK. Sono " + this.id + " stato:" + this.state);
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
                //System.out.println("PEER " + this.id + " ---> ho avuto successo\n");
                this.indexStories = storeEntries(prevIndexReceived, entriesReceived, commitIndexReceived);
                //System.out.println("NODE "+this.id+"_______indexStories after ____"+this.indexStories);
                for (int i = 1; i < this.log.size(); i++) {
                    System.out.println("LOG NODE " + this.id + " of size " + (this.log.size() - 1)+" , element n. "+i+ " -----> command: " + log.get(i).command + ",  term: " + log.get(i).term);
                }
                System.out.println("\n");
                int lastTermSaved = this.log.get(this.indexStories).term;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, lastTermSaved, this.commitIndex);
                //System.out.println("PEER " + this.id + " --> invio di AppendReply al leader\n");
                getSender().tell(response, getSelf());
            } else {
                //commit non andato a buon fine
                AppendReply appRepMessage = new AppendReply(this.id, this.currentTerm, success, -1, -1, 0);
            }
        }
        if (message instanceof UpdateCommitIndex) {
            System.out.println("PEER " + this.id + " ---> AGGIORNO IL MIO COMMIT INDEX");
            this.commitIndex++;

        }
    }

    private void sendAppendEntries(ActorRef peer) {
        ActorRef leader = getSender();
        int id_peer = returnIdPeer(peer);
        //System.out.println("PEER ID ------>  " + id_peer + "\n");

        int nextIndexLogPeer = nextIndex[id_peer];
        this.nextIndex[id_peer] = nextIndexLogPeer;

        ArrayList<LogEntry> entries = getEntriesToSend(this.log, (nextIndexLogPeer - 1));
        System.out.println("JKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKMD " + this.log.get(nextIndexLogPeer).term);
        AppendRequest appendRequest = new AppendRequest(this.id, this.currentTerm, (nextIndexLogPeer - 1), this.log.get(nextIndexLogPeer-1).term, entries, this.commitIndex);
        //System.out.println("Invio al peer " + id_peer + " di una AppendRequest con index di riferimento " + (nextIndexLogPeer - 1) + "\n");
        peer.tell(appendRequest, getSelf());


    }

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

    private int storeEntries(int prevIndex, ArrayList<LogEntry> entries, int commitIndex) {
        //System.out.println("\n in storeEntries. entries.size() "+entries.size()+"\n");
        int index = prevIndex;

        if (this.log.isEmpty()) {
            for (int i = 0; i < entries.size(); i++) {
                this.log.add(entries.get(i));
                index = index + 1;
            }
        } else {
            for (int j = 0; j < entries.size(); j++) {
                index = index + 1;

                //controllo se non ho l'entry nel log per quell'indice
                int lastEntry = getLastLogIndex(this.log);
                //System.out.println("\n in storeEntries. index = "+index+", peerLog.size() = "+(this.log.size()-1)+"  indexLastEntry ="+lastEntry+", prevIndex ="+prevIndex+"\n");
                if (lastEntry == prevIndex) {
                    this.log.add(entries.get(j));
                } else if (this.log.get(index).term != entries.get(j).term) {
                    //se ho un term, ma non combacia con i termi presenti nel log, lo rimuovo e aggiungo quello corretto del leader
                    //remove entry that does not fit with leader's entry
                    this.log.remove(index);
                    this.log.add(entries.get(j));
                }
                prevIndex++;
            }
        }
        //at the end of the for loop, index point to the next free index
        //commitIndex is the minimum between what the peer has stored and what the peer has reched adding entries
        this.commitIndex = Math.min(commitIndex, index);
        //return the index to the first slot free in peer's log
        return index;

    }

    private ArrayList<LogEntry> getEntriesToSend(ArrayList<LogEntry> logLeader, int lastLogIndex) {
        //System.out.println("Sono nella chiamata getEntriesToSend. logLeader.size() = "+logLeader.size()+",  lastLogIndex = "+lastLogIndex+"\n");
        //lastLogIndex is the index of the last log written by peer
        ArrayList<LogEntry> entries = new ArrayList<>();
        int start = lastLogIndex + 1;
        int end = logLeader.size();
        //System.out.println("START = "+start+"  END = "+end);
        for (int i = start; i < end; i++) {
            //System.out.println("Sono nel ciclo i="+i);
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

    private int getLastLogTerm(int lastLogIndex) {
        if (lastLogIndex <= 0) {
            return 0;
        } else {
            return log.get(lastLogIndex).term;
        }
    }

    public int getLastLogIndex(ArrayList<LogEntry> log) {
        if (log.size() <= 0) {
            return 0;
        } else {
            return log.size() - 1;
        }
    }

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

    public int returnIdPeer(ActorRef peer) {
        String name = peer.path().name();
        String[] tokens = name.split("_");
        int idPeer = Integer.parseInt(tokens[1]);
        //System.out.println("name peer "+name+"   idPeer "+idPeer+"\n");
        return idPeer;
    }

}

