import akka.actor.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import jdk.nashorn.internal.runtime.options.LoggingOption;
import org.apache.log4j.Logger;
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
    private ServerState state;
    private Integer leaderID;
    private int commitIndex;
    private Integer [] nextIndex = new Integer[config.getInt("N_SERVER")];
    private Integer[] matchIndex = new Integer[config.getInt("N_SERVER")];


    //private final static Logger fileLog = Logger.getLogger(ServerNode.class.getName());

    private Cancellable electionScheduler;
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
            if (state == ServerState.FOLLOWER){
                follower();
                //startElection(id);
            }else{
                System.out.println("In the initial phase there are not other possible states");
            }
            System.out.println("Participants.size() "+participants.size()+"  server id "+ id);
        }
        if (message instanceof AppendRequest){
            if (state == ServerState.LEADER){
                AppendRequest msgAppend = (AppendRequest) message;
                handleAppendRequest(msgAppend);
            }else{
                if (state != ServerState.LEADER){
                    System.out.println("AppendRequest can not be handle by a non-LEADER");
                }
                if (message == null){
                    System.out.println("No message to handle");
                }
            }
        }
        if (message.equals("CANDIDATE")){
            candidate();
        }


    }


    public void startElection(int id){
        int idSender = id;
        System.out.println("Start election for node " +idSender);


    }

    public void sendAppendEntries(){}

    public void handleAppendRequest(AppendRequest message){}

    public void stepDown(int term){
        this.currentTerm = term;
        this.state = ServerState.FOLLOWER;
        this.votedFor = null;
        //need to change the timeout

    }

    private void follower() {
        int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT")+1);

        //scheduling of message to change state
        electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), "CANDIDATE", getContext().system().dispatcher(), getSelf());
    }

    private void candidate() {
        System.out.println("SONO CANDIDATE e sono " + this.id);
        switch(this.receivedVote) {

            //NO VOTES RECEIVED
            case 0:
                this.currentTerm++;
                this.votedFor = this.id;
                this.votes.add(this.id);

                int lastLogIndex = 0;
                int lastLogTerm = 0;

                if(this.log.size() > 0){
                    lastLogIndex = this.log.size()-1;
                    lastLogTerm = this.log.get(lastLogIndex).term;
                }

                for ( ActorRef q : participants) {
                    if (q != getSelf()){
                        q.tell(new VoteRequest(this.id, this.currentTerm, lastLogIndex, lastLogTerm), getSelf());
                    }
                }
        }
    }

}


