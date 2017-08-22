import akka.actor.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public  class ServerNode extends UntypedActor {
    Config config = ConfigFactory.load("application");
    protected int id;
    protected List<ActorRef> participants = new ArrayList<ActorRef>();

    //variables in Persistent State
    protected Integer currentTerm;
    protected Integer votedFor;
    protected ArrayList<LogEntry> log;

    //variables in Non-Persistent State
    protected ServerState state;
    private Integer leaderID;
    private int commitIndex;
    private Integer [] nextIndex = new Integer[config.getInt("N_SERVER")];
    private Integer[] matchIndex = new Integer[config.getInt("N_SERVER")];


    //private final static Logger fileLog = Logger.getLogger(ServerNode.class.getName());

    private Cancellable electionScheduler;
    private Cancellable stepDownScheduler;
    private Cancellable voteRepScheduler;

    protected boolean stepdown;

    private int receivedVote;
    private ArrayList<Integer> votes;
    private int candidate_state;

    public ServerNode(int id){
        super();
        this.id = id;
        this.currentTerm = 0;
        this.leaderID = -1;
        this.commitIndex = 0;
        this.state = ServerState.FOLLOWER;
        for (int i =0; i<config.getInt("N_SERVER"); i++) {
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
    }

    @Override
    public void onReceive(Object message) throws Throwable{
        if (message instanceof StartMessage) {
            StartMessage msg = (StartMessage) message;
            try {
                for (int i = 0; i < msg.group.size(); i++) {
                    this.participants.add(msg.group.get(i));

                }

            } catch (Throwable e) {
                System.out.println(e.getStackTrace());
            }
        }
//            if (state == ServerState.FOLLOWER){
//                follower();
//                //startElection(id);
//            }else{
//                System.out.println("In the initial phase there are not other possible states");
//            }
//            System.out.println("Participants.size() "+participants.size()+"  server id "+ id);
//        }
//        if (message instanceof AppendRequest){
//            if (state == ServerState.LEADER){
//                AppendRequest msgAppend = (AppendRequest) message;
//                handleAppendRequest(msgAppend);
//            }else{
//                if (state != ServerState.LEADER){
//                    System.out.println("AppendRequest can not be handle by a non-LEADER");
//                }
//                if (message == null){
//                    System.out.println("No message to handle");
//                }
//            }
//        }

        if (message instanceof StateChanger) {
            ((StateChanger) message).onReceive(this);
        }

        if (message instanceof VoteReply) {
            //System.out.println("Il nodo " + this.id + " ha ricevuto un vote reply da " + ((VoteReply) message).senderID + " e ha votato per " + ((VoteReply) message).votedID + " stato: " + this.state);
        }

        //System.out.println("NODO : " + this.id + " stepdown: " + this.stepdown + " stato: " + this.state);

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

//        if (message instanceof VoteRequest){
//            ((VoteRequest) message).onReceive(this);
//        }
//        //System.out.println("Participants.size() "+participants.size()+"  server id "+ id);

    }


    public void startElection(int id){
        int idSender = id;
        System.out.println("Start election for node " +idSender);


    }

    public void sendAppendEntries(){
        System.out.println("Invio AppendEntries");
    }

    public void handleAppendRequest(AppendRequest message){}

    public void stepDown(int term){
        this.currentTerm = term;
        this.state = ServerState.FOLLOWER;
        this.votedFor = -1;
        this.stepdown = true;
        System.out.println("Sono "+ this.id + " faccio step down");
        //need to change the timeout
        stepDownScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(0, TimeUnit.MILLISECONDS), getSelf(), new StateChanger(), getContext().system().dispatcher(), getSelf());
    }

    private void leader(Object message) {
    }

    private void candidate(Object message) {
        if (electionScheduler != null && !electionScheduler.isCancelled())
            electionScheduler.cancel();

        if (message instanceof StateChanger || message instanceof ElectionMessage) {
            System.out.println("SONO CANDIDATE e sono " + this.id);
            this.votes.clear();
            switch (this.receivedVote) {

                //NO VOTES RECEIVED
                case 0:
                    this.currentTerm++;
                    this.votedFor = this.id;
                    this.votes.add(this.id);

                    int lastLogIndex = 0;
                    int lastLogTerm = 0;

                    if (this.log.size() > 0) {
                        lastLogIndex = getLastLogIndex();
                        lastLogTerm = getLastLogTerm(lastLogIndex);
                    }

                    for (ActorRef q : participants) {
                        if (q != getSelf()) {
                            //System.out.println("Sono " + this.id + " o meglio " + this.getSelf().path() + " sto mandando una vote request a " + q.path());
                            q.tell(new VoteRequest(this.id, this.currentTerm, lastLogIndex, lastLogTerm), getSelf());
                        }
                    }
            }
        }
        //DONE NOW
        else if (message instanceof VoteRequest) {
            if (((VoteRequest) message).currentTerm > currentTerm) {
                stepDown(((VoteRequest) message).currentTerm);
            }
            //DONE NOW
            if (((VoteRequest) message).currentTerm == currentTerm &&
                    (this.votedFor == -1 || this.votedFor == ((VoteRequest) message).senderID) &&
                    (((VoteRequest) message).lastLogTerm > getLastLogTerm(log.size()-1) || (((VoteRequest) message).lastLogTerm == getLastLogTerm(log.size()-1) && ((VoteRequest) message).lastLogIndex >= getLastLogIndex())
                    )){

                votedFor = ((VoteRequest) message).senderID;

                int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
                electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());

            }

            if (((VoteRequest) message).senderID == votedFor) {
                this.getSender().tell(new VoteReply(votedFor, currentTerm, this.id), getSelf());
            }
        }

        else if (message instanceof VoteReply) {
            System.out.println("Ho ricevuto un voteReply da " + ((VoteReply) message).senderID);
            if (((VoteReply) message).term > this.currentTerm) {
                stepDown(((VoteReply) message).term);
            }
            if (((VoteReply) message).term == this.currentTerm && this.state == ServerState.CANDIDATE){
                if (((VoteReply) message).votedID == this.id){
                    votes.add(((VoteReply) message).senderID);
                }
                electionScheduler.cancel();

                if (votes.size() > participants.size()/2){
                    this.state = ServerState.LEADER;
                    leaderID = this.id;

                    for (ActorRef q : participants) {
                        if (q != getSelf()) {
                            System.out.println("Sono " + this.id + " e sono " + this.state + " ho ricevuto i seguenti voti " + this.votes );
                            //sendAppendEntries();
                        }
                    }
                }

            }


        }
    }

    private void follower(Object message) {
        if (electionScheduler != null && !electionScheduler.isCancelled())
            electionScheduler.cancel();

        if (message instanceof StartMessage || message instanceof StateChanger || message instanceof ElectionMessage) {
            this.stepdown = false;
            int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
            System.out.println("Sono " + this.id + " ho settato il timeout a " + electionTimeout);
            //scheduling of message to change state
            electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new StateChanger(), getContext().system().dispatcher(), getSelf());
            //System.out.println("NODO : " + this.id + " stepdown: " + this.stepdown + " stato: " + this.state);
        }
        else if (message instanceof VoteRequest) {
            System.out.println("Sono " + this.id+ " ho ricevuto una VoteRequest da " + ((VoteRequest) message).senderID);
            if (((VoteRequest) message).currentTerm > currentTerm) {
                stepDown(((VoteRequest) message).currentTerm);
            }
            //DONE NOW
            if (((VoteRequest) message).currentTerm == currentTerm &&
                    (this.votedFor == -1 || this.votedFor == ((VoteRequest) message).senderID) &&
                    (((VoteRequest) message).lastLogTerm > getLastLogTerm(log.size()-1) || (((VoteRequest) message).lastLogTerm == getLastLogTerm(log.size()-1) && ((VoteRequest) message).lastLogIndex >= getLastLogIndex())
                    )){
                    this.votedFor = ((VoteRequest) message).senderID;
                System.out.println("Sono " + this.id + " ho votato per " + this.votedFor);

                //System.out.println("Sono nell'if con " + this.id + " voto per " + votedFor + " e io sono " + this.state);
                    int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);
                    electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new ElectionMessage(), getContext().system().dispatcher(), getSelf());

            }

            if (((VoteRequest) message).senderID == votedFor) {
                this.getSender().tell(new VoteReply(votedFor, currentTerm, this.id), getSelf());
            }
        }
        else if (message instanceof VoteReply) {
            if (((VoteReply) message).term > this.currentTerm) {
                stepDown(((VoteReply) message).term);
            }
        }
    }

    private int getLastLogIndex()
    {
        if(log.size() <=0)
        {
            return 0;
        }
        else {
            return log.size() -1;
        }
    }

    private int getLastLogTerm(int lastLogIndex)
    {
        if(lastLogIndex <=0)
        {
            return 0;
        }
        else
        {
            return log.get(lastLogIndex).term;
        }
    }
}


