import akka.actor.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.lang.reflect.Array;
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

    private ActorRef client;

    private int indexStories=-1;


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
            client = msg.client;
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



    public void handleAppendRequest(AppendRequest message){}

    public void stepDown(int term){
        this.currentTerm = term;
        this.state = ServerState.FOLLOWER;
        this.votedFor = null;
        //need to change the timeout

    }

    private void leader(Object message) {
        //TODO: if votazione finita, sono il leader, comunico al client il mio id
        //inform client who is the leader and that it can start send messages
        InformClient msgToClient = new InformClient(this.id, true);
        client.tell(msgToClient, getSelf());
        //TODO: endif

        //se sono il leader inizio a ricevere i comandi dal client
        if (message instanceof SendCommand){
            String commandReceived = ((SendCommand) message).command;
            if (getSender().equals(client)){
                LogEntry newEntry = new LogEntry(currentTerm,commandReceived);
                log.add(newEntry);
                for(ActorRef peer : this.participants){
                    if(peer!=getSelf()) {
                        sendAppendEntries(peer);
                    }
                }

            }else{
                System.out.println("ERROR, sendCommand must be sent by client");
            }

        }
        if (message instanceof AppendReply){
            //to check how many peer send me a positive reply
            boolean[] getReply = new boolean[this.participants.size()-1];

            int termReceived = ((AppendReply) message).currentTerm;
            boolean successReceived = ((AppendReply) message).success;
            int indexStoriesReceived = ((AppendReply) message).indexStories;
            int senderID = ((AppendReply) message).senderID;



            if (termReceived > this.currentTerm){
                stepDown(termReceived);
            }else if(termReceived == this.currentTerm){
                if (successReceived){
                    nextIndex[senderID] = indexStoriesReceived+1;
                    getReply[senderID] = true;
                }else{
                    //TODO: check if the range is correct
                    nextIndex[senderID] = Math.max(indexStoriesReceived, nextIndex[senderID]-1);
                }


            }
            if(checkMajorityReply(getReply)){
                //TODO: check if the command can be committed
                Boolean commandCommited = true;
                //inform the client about the operation result
                InformClient resultCommand = new InformClient(this.leaderID, commandCommited);
                client.tell(resultCommand, getSelf());
            }


        }
        if (message instanceof HeartBeat){

        }


    }

    private void candidate(Object message) {
        if (message instanceof StateChanger) {
            System.out.println("SONO CANDIDATE e sono " + this.id);
            switch (this.receivedVote) {

                //NO VOTES RECEIVED
                case 0:
                    this.currentTerm++;
                    this.votedFor = this.id;
                    this.votes.add(this.id);

                    int lastLogIndex = 0;
                    int lastLogTerm = 0;

                    if (this.log.size() > 0) {
                        lastLogIndex = this.log.size() - 1;
                        lastLogTerm = this.log.get(lastLogIndex).term;
                    }

                    for (ActorRef q : participants) {
                        if (q != getSelf()) {
                            q.tell(new VoteRequest(this.id, this.currentTerm, lastLogIndex, lastLogTerm), getSelf());
                        }
                    }
            }
        }
        //TODO
        //else if (message instanceof ...)
    }

    private void follower(Object message) {
        if (message instanceof StartMessage || message instanceof ElectionMessage) {
            int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);

            //scheduling of message to change state
            electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new StateChanger(), getContext().system().dispatcher(), getSelf());
        }
        //TODO
        //else if (message instanceof ...)
        if (message instanceof AppendRequest){
            int termReceived = ((AppendRequest) message).term;
            int prevIndexReceived = ((AppendRequest) message).prevIndex;
            int prevTermReceived = ((AppendRequest) message).prevTerm;
            ArrayList<LogEntry> entriesReceived = ((AppendRequest) message).entries;
            int commitIndexReceived = ((AppendRequest) message).commitIndex;

            boolean success = false;

            if (termReceived > this.currentTerm){
                stepDown(termReceived);
            }
            else if (termReceived < this.currentTerm){
                success = false;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, indexStories);
            }else{
                //TODO: check if is correct the interpretation of index in slide 31
                indexStories = 0;
                if(this.log.isEmpty()){
                    success = true;
                }
                if(this.log.get(prevIndexReceived).term == prevTermReceived){
                    success = true;
                }
                if (success){
                    //TODO: check what this parameter c is.
                    int c=0;
                    this.indexStories= storeEntries(entriesReceived, commitIndexReceived, c);
                }
                AppendReply response  = new AppendReply(this.id, this.currentTerm, success, indexStories);
                getSender().tell(response, getSelf());
            }

        }
    }

    private void sendAppendEntries(ActorRef peer){
        ActorRef leader = getSender();
        float timeoutSendAppendEntries = System.currentTimeMillis() + ((config.getInt("MAX_TIMEOUT")-config.getInt("MAX_TIMEOUT"))/2);
        int lastLogIndex = nextIndex[this.leaderID];
        this.nextIndex[this.leaderID] = lastLogIndex;
        AppendRequest appendRequest = new AppendRequest(this.currentTerm, lastLogIndex-1, this.log.get(lastLogIndex).term  , this.log, commitIndex );
        peer.tell(appendRequest, getSelf());
    }


    private boolean checkMajorityReply(boolean[] getReply){
        int n_true=0;
        int n_false=0;
        for(int i=0; i<getReply.length; i++){
            if (getReply[i]){
                n_true++;
            }else{
                n_false++;
            }
        }
        //majority obtained with "la metà +1 "
        if (n_true > (getReply.length/2)){
            return true;
        }else {
            return false;
        }
    }

    private int storeEntries(ArrayList<LogEntry> entries, int prevIndex, int c){
        indexStories = prevIndex;
        for( int j = 1; j<= getLastLogIndex(entries); j++){
            indexStories = indexStories +1;
            if (this.log.get(indexStories).term != entries.get(indexStories).term){
                log.get(indexStories).term = 0;
                log.get(indexStories).command = "";
                log.add(entries.get(indexStories));

            }
        }
        this.commitIndex = Math.min(c, indexStories);
        return indexStories;

    }

    public int getLastLogIndex(ArrayList<LogEntry> log)
    {
        if(log.size() <=0)
        {
            return 0;
        }
        else {
            return log.size() -1;
        }
    }


}


