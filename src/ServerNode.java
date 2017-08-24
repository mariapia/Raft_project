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

    private int indexStories=0;


    //private final static Logger fileLog = Logger.getLogger(ServerNode.class.getName());

    private Cancellable electionScheduler;
    private int receivedVote;
    private ArrayList<Integer> votes;
    private int candidate_state;

    //to check how many peer send me a positive reply (used only by leader)
    private boolean[] getReply = new boolean[config.getInt("N_SERVER")];
    private boolean alreadySent = false;


    public ServerNode(int id){
        super();
        this.id = id;
        this.currentTerm = 1;
        //forzatura per far eseguire il codice delle entries
        this.leaderID = 0;
        this.commitIndex = 0;
        this.state = ServerState.FOLLOWER;
        for (int i =0; i<config.getInt("N_SERVER"); i++) {
            nextIndex[i] = 0;
            //nextIndex[i] = 1;
            matchIndex[i] = 0;
        }

        this.log = new ArrayList<>();
        this.receivedVote = 0;
        this.votes = new ArrayList<>();
        //BASE CASE - no votes received
        this.candidate_state = 0;
        if(this.id == 0){
            this.state = ServerState.LEADER;
        }

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
            //temporary code to check entries ----------------------------------------------------
            if (this.state == ServerState.LEADER) {
                InformClient tmp = new InformClient(0, true);
                client.tell(tmp, getSelf());
            }
            //------------------------------------------------------------------------------------
        }

        if (message instanceof StateChanger) {
            ((StateChanger) message).onReceive(this);
        }


        switch (this.state) {
            case FOLLOWER:
                follower(message);
                break;
            case CANDIDATE:
                //candidate(message);
                break;
            case LEADER:
                leader(message);
                break;
        }
    }

    public void startElection(int id){
        int idSender = id;
        System.out.println("Start election for node " +idSender);
    }

    public void stepDown(int term){
        this.currentTerm = term;
        this.state = ServerState.FOLLOWER;
        this.votedFor = null;
        //need to change the timeout

    }

    private void leader(Object message) {
        //TODO: if votazione finita, sono il leader, comunico al client il mio id
        //inform client who is the leader and that it can start send messages
        //InformClient msgToClient = new InformClient(this.id, true);
        //client.tell(msgToClient, getSelf());
        //TODO: endif

        //se sono il leader inizio a ricevere i comandi dal client
        if (message instanceof SendCommand){
            String commandReceived = ((SendCommand) message).command;
            if (getSender().equals(client)){
                System.out.println("LEADER ------> ho ricevuto un comando dal client "+commandReceived);

                LogEntry newEntry = new LogEntry(currentTerm,commandReceived);
                log.add(newEntry);
                int[] ids = new int[this.participants.size()];
                for(int i=0; i<this.participants.size(); i++){
                    ids[i]=i;
                }
                for(ActorRef peer : this.participants){
                    //System.out.println("nome di ogni peer nel sistema  "+peer.path().name());
                    if(peer!=getSelf()) {
                        //System.out.println("--------------------------NUOVO PEER-----------------------------------------");
                        sendAppendEntries(peer);
                    }

                }

            }else{
                System.out.println("ERROR, sendCommand must be sent by client");
            }

        }
        if (message instanceof AppendReply){
            int termReceived = ((AppendReply) message).currentTerm;
            boolean successReceived = ((AppendReply) message).success;
            int indexStoriesReceived = ((AppendReply) message).indexStories;
            int senderID = ((AppendReply) message).senderID;
            System.out.println("in AppendReply. PeerSender ="+senderID+" indeStoriesReceived ="+indexStoriesReceived+"\n");

            getReply[this.id] = true;

            if (termReceived > this.currentTerm){
                stepDown(termReceived);
            }else if(termReceived == this.currentTerm){
                if (successReceived){
                    System.out.println("LEADER ---->  ho ricevuto un successo da un peer");
                    this.nextIndex[senderID] = indexStoriesReceived;
                    System.out.println("sender = "+senderID+"    nextIndex[senderID] = "+this.nextIndex[senderID]);
                    getReply[senderID] = true;
                }else{
                    //TODO: check if the range is correct
                    if(nextIndex[senderID] == 0){
                        nextIndex[senderID] = Math.max(indexStoriesReceived, nextIndex[senderID]);
                    }else {
                        nextIndex[senderID] = Math.max(indexStoriesReceived, nextIndex[senderID] - 1);
                    }
                }
            }

            for (int i=0; i<getReply.length; i++){
                //System.out.println("getReply[i] ----> "+getReply[i]);
            }
            if(checkMajorityReply(getReply)){
                Boolean commandCommited = true;
                InformClient resultCommand = new InformClient(this.leaderID, commandCommited);
                if(!alreadySent) {
                    System.out.println("LEADER ----> Il comando può essere committato. Il client viene informato");
                    client.tell(resultCommand, getSelf());
                    alreadySent = true;
                }
            }else{
                Boolean commandCommited = false;
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
        for(int i=0; i<this.log.size(); i++){
            //System.out.println("LOG node "+this.id+"  index = "+i+"  term: "+this.log.get(i).term+"   command "+this.log.get(i).command+"\n");
        }


        if (message instanceof StartMessage || message instanceof ElectionMessage) {
            int electionTimeout = ThreadLocalRandom.current().nextInt(config.getInt("MIN_TIMEOUT"), config.getInt("MAX_TIMEOUT") + 1);

            //scheduling of message to change state
            electionScheduler = getContext().system().scheduler().scheduleOnce(scala.concurrent.duration.Duration.create(electionTimeout, TimeUnit.MILLISECONDS), getSelf(), new StateChanger(), getContext().system().dispatcher(), getSelf());
        }
        //TODO
        //else if (message instanceof ...)
        if (message instanceof AppendRequest){
            System.out.println("FOLLOWER "+this.id+"---> Ho ricevuto un AppendRequest");
            int termReceived = ((AppendRequest) message).term;
            int prevIndexReceived = ((AppendRequest) message).prevIndex;
            int prevTermReceived = ((AppendRequest) message).prevTerm;
            ArrayList<LogEntry> entriesReceived = ((AppendRequest) message).entries;
            int commitIndexReceived = ((AppendRequest) message).commitIndex;

            boolean success = false;

            if (termReceived > this.currentTerm){
                System.out.println("STEPDOWN()");
                stepDown(termReceived);
            }
            else if (termReceived < this.currentTerm){
                System.out.println("success = FALSE, invio risposta al leader");
                success = false;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, indexStories);
                getSender().tell(response, getSelf());
            }else{
                //TODO: check if is correct the interpretation of index in slide 31
                if(!this.log.isEmpty()){
                    System.out.println("prevIndex Received  "+prevIndexReceived);
                    System.out.println("this.log.size()  "+this.log.size());
                    //System.out.println("this.log.get(prevIndexReceived).tern  "+this.log.get(prevIndexReceived).term);
                    System.out.println("prevTermReceived   "+prevTermReceived);
                }


                if(this.log.isEmpty()){
                    success = true;
                }else if(this.log.get(prevIndexReceived-1).term == prevTermReceived){
                    success = true;
                }
                if (success){
                    //System.out.println("NODE "+this.id+"_______indexStories before ____"+this.indexStories);
                    this.indexStories= storeEntries(prevIndexReceived, entriesReceived, commitIndexReceived);
                    //System.out.println("NODE "+this.id+"_______indexStories after ____"+this.indexStories);
                    for (int i=0; i<this.log.size(); i++){
                        System.out.println("LOG NODE "+this.id+" n_elements "+this.log.size()+" ----- command: "+log.get(i).command+" ------  term: "+log.get(i).term+"~~~~\n");
                    }
                    System.out.println("Cosa passo al leader come risposta  ID= "+this.id+",  CURRENTTERM= "+this.currentTerm+",  SUCCESS = "+success+",  INDEXSOTRIES= "+indexStories+"\n");
                    AppendReply response  = new AppendReply(this.id, this.currentTerm, success, indexStories);
                    getSender().tell(response, getSelf());

                }

            }

        }
    }

    private void sendAppendEntries(ActorRef peer){
        ActorRef leader = getSender();
        float timeoutSendAppendEntries = System.currentTimeMillis() + ((config.getInt("MAX_TIMEOUT")-config.getInt("MAX_TIMEOUT"))/2);
        int id_peer = returnIdPeer(peer);
        System.out.println("PEER ID ------>  "+id_peer+"\n");
        for(int i=0; i<config.getInt("N_SERVER"); i++){
            System.out.println("nextIndex["+i+"] = "+nextIndex[id_peer]+"\n");
        }

        int lastLogIndex = nextIndex[id_peer];
        this.nextIndex[id_peer] = lastLogIndex;

        //System.out.println("prima di chiamare getEntriesToSend. lastLogIndex = "+lastLogIndex+", logLeder.size= "+this.log.size()+"\n");

        ArrayList<LogEntry> entries = getEntriesToSend(this.log, (lastLogIndex));

        System.out.println("cosa mando nell'append Request --> currentTerm = "+this.currentTerm+",  lastLogIndex = "+(lastLogIndex)+", log[lastLogIndex-1].term = "+this.log.get(lastLogIndex).term+", entries.size() = "+entries.size()+",  this.commitIndex ="+this.commitIndex+"\n");
        AppendRequest appendRequest = new AppendRequest(this.id, this.currentTerm, (lastLogIndex), this.log.get(lastLogIndex).term, entries, this.commitIndex);
        //System.out.println("sendAppendEntries() --> sto inviando al peer un AppendRequest\n");
        peer.tell(appendRequest, getSelf());


    }


    private boolean checkMajorityReply(boolean[] getReply){
        int n_true=0;
        for(int i=0; i<getReply.length; i++){
            if (getReply[i]){
                n_true++;
            }
        }
        //majority obtained with "la metà +1 "
        if (n_true > (getReply.length/2)){
            return true;
        }else {
            return false;
        }
    }

    private int storeEntries(int prevIndex, ArrayList<LogEntry> entries,int commitIndex){
        indexStories = prevIndex;

        System.out.println("indexStories in storeEntries  ==== "+indexStories);
        if(this.log.isEmpty()){
            //System.out.println("STORE_ENTRIES --> log era vuoto\n");
            this.log.add(entries.get(indexStories));
            indexStories = indexStories+1;
            System.out.println("Il mio log è vuoto");
        }else {
            System.out.println("PEER ="+this.id+", il mio log NON è vuoto. E' di size = "+this.log.size());
            for (int j = 1; j <= getLastLogIndex(entries); j++) {
                //indexStories = indexStories + 1;
                if (this.log.get(indexStories).term != entries.get(j).term) {
                    this.log.get(indexStories).term = 0;
                    this.log.get(indexStories).command = "";
                    this.log.add(entries.get(indexStories));

                }
                indexStories = indexStories + 1;
            }
        }
        this.commitIndex = Math.min(commitIndex, indexStories);
        System.out.println("CommitIndex dopo lo storeEntries  --> "+this.commitIndex+"     indexStories return = "+indexStories);

        return indexStories;

    }

    private ArrayList<LogEntry> getEntriesToSend(ArrayList<LogEntry> logLeader, int lastLogIndex){
        System.out.println("Sono nella chiamata getEntriesToSend. logLeader.size() = "+logLeader.size()+",  lastLogIndex = "+lastLogIndex+"\n");

        ArrayList<LogEntry> entries = new ArrayList<>();
        int start = lastLogIndex;
        int end = logLeader.size();
        //System.out.println("START = "+start+"  END = "+end);
        for (int i=0; i<(end-start)+1; i++){
            //System.out.println("Sono nel ciclo i="+i);
            entries.add(logLeader.get(start));
            start++;
        }
        return  entries;
    }

    public int getLastLogIndex(ArrayList<LogEntry> log)
    {
        if(log.size() <=0)
        {
            return 0;
        }
        else {
            return log.size()-1;
        }
    }

    public int getID(){
        return this.id;
    }

    public int returnIdPeer(ActorRef peer){
        String name = peer.path().name();
        String[] tokens = name.split("_");
        int idPeer = Integer.parseInt(tokens[1])-1 ;
        //System.out.println("name peer "+name+"   idPeer "+idPeer+"\n");
        return idPeer;
    }
}


