import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.UntypedActor;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.time.Duration;
import java.util.concurrent.*;

import java.util.*;

public  class ServerNode extends UntypedActor {
    Config config = ConfigFactory.load("application");
    protected int id;
    protected List<ActorRef> participants = new ArrayList<ActorRef>();

    //variables in Persistent State
    protected int currentTerm;
    protected int votedFor;
    protected ArrayList<LogEntry> log;

    //variables in Non-Persistent State
    private ServerState state;
    private int leaderID;
    private int commitIndex;
    private Integer [] nextIndex = new Integer[config.getInt("N_SERVER")];
    private Integer[] matchIndex = new Integer[config.getInt("N_SERVER")];


    private Cancellable electionScheduler;
    private int receivedVote;
    private ArrayList<Integer> votes;
    private int candidate_state;

    public ServerNode(int id){
        super();
        this.id = id;
        this.currentTerm = 0;
        this.log = new ArrayList<>();

        this.leaderID = -1;
        this.commitIndex = 0;
        this.state = ServerState.FOLLOWER;
        for (int i =0; i<config.getInt("N_SERVER"); i++) {
            nextIndex[i] = 1;
            matchIndex[i] = 0;
        }

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
            }
        }
        if (message.equals("CANDIDATE")){
            candidate();
        }
        //System.out.println("Participants.size() "+participants.size()+"  server id "+ id);
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

    private void follower() {
        int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT")+1);

        //scheduling of message to change state
        electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), "CANDIDATE", getContext().system().dispatcher(), getSelf());
    }


}



