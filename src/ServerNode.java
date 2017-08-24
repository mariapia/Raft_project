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
    private int[] termsPeers = new int[config.getInt("N_SERVER")];
    private boolean alreadySent = false;


    //indice di controllo per debug, TODO: da rimuovere
    private int contatoreNumeroReplyRicevute =0;

    public ServerNode(int id){
        super();
        this.id = id;
        this.currentTerm = 1;
        //forzatura per far eseguire il codice delle entries
        this.leaderID = 0;
        this.commitIndex = 0;
        this.state = ServerState.FOLLOWER;
        for (int i =0; i<config.getInt("N_SERVER"); i++) {
            //nextIndex[i] = 0;
            nextIndex[i] = 1;
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
        //aggiungo al log con index = 0 una entry nulla, per far partire il log dalla posizione 1 così che combaci con l'index dell'algoritmo
        this.log.add(0, null);

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
            if(commandReceived.equals("FINISH")){
                System.out.println("\n____________________________________________________IL CLIENT NON HA PIÙ COMANDI__________________________________________________________\n");
                context().system().shutdown();
            }else {

                alreadySent = false;
                if (getSender().equals(client)) {
                    System.out.println("\n_____________________________________________________NEW COMMAND BY CLIENT: " + commandReceived + "____________________________________________\n");
                    LogEntry newEntry = new LogEntry(currentTerm, commandReceived);
                    log.add(newEntry);
                    this.nextIndex[this.id] = this.nextIndex[this.id] + 1;
                    System.out.println("LEADER LOG size " + (this.log.size() - 1));
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
                    System.out.println("ERROR, sendCommand must be sent by client");
                }
            }

        }
        if (message instanceof AppendReply){
            int termReceived = ((AppendReply) message).currentTerm;
            boolean successReceived = ((AppendReply) message).success;
            int indexStoriesReceived = ((AppendReply) message).indexStories;
            int senderID = ((AppendReply) message).senderID;
            int lastTermSavedPeer = ((AppendReply) message).lastTermSaved;

            System.out.println("LEADER ---> ricevuto AppendReply da PEER "+senderID+"\n");

            getReply[this.id] = true;
            termsPeers[this.id] = this.log.get(nextIndex[this.id]-1).term;
            termsPeers[senderID]=lastTermSavedPeer;

            if (termReceived > this.currentTerm){
                System.out.println("\nterm received greater than currentTerm\n");
                stepDown(termReceived);
            }else if(termReceived == this.currentTerm){
                if (successReceived){
                    System.out.println("\nreceived success by peer "+senderID+"\n");
                    //update information about next free slot for peer's log
                    this.nextIndex[senderID] = indexStoriesReceived+1;
                    getReply[senderID] = true;
                }else{
                    System.out.println("\nreceived failure by peer "+senderID+"\n");
                    this.nextIndex[senderID] = Math.max(1, indexStoriesReceived);
                }
                if(this.nextIndex[senderID]<(this.log.size()-1)){
                    System.out.println("\n l'index del peer è minore rispetto al mio index ---> Bisogna aggiustare\n");
                    sendAppendEntries(getSender());
                }

                //adjust peer's log
//                if(this.nextIndex[senderID]<= this.log.size()){
//                    sendAppendEntries(getSender());
//                }
            }

            if(checkMajorityReply(getReply) && checkMajorityCurrentTerm(this.currentTerm, termsPeers)){
                System.out.println("\nin DOUBLE CHECK\n");
                Boolean commandCommited = true;
                InformClient resultCommand = new InformClient(this.leaderID, commandCommited);
                this.commitIndex++;
                if(!alreadySent) {
                    System.out.println("LEADER ----> Il comando può essere committato. Il client viene informato");
                    client.tell(resultCommand, getSelf());
                    alreadySent = true;
                    UpdateCommitIndex update = new UpdateCommitIndex(true);
                    for(ActorRef peer : this.participants){
                        if(peer!=getSelf()){
                            //peer.tell(update, getSelf());
                        }
                    }
                }
            }else{
                Boolean commandCommited = false;
                InformClient resultCommand = new InformClient(this.leaderID, commandCommited);
                //client.tell(resultCommand, getSelf());
            }
            if(alreadySent){
                for(int i=0; i<getReply.length; i++){
                    getReply[i]=false;
                }
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
            System.out.println("PEER "+this.id+"---> Ho ricevuto un AppendRequest");
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
                int lastTermSaved = this.log.get(this.indexStories-1).term;
                AppendReply response = new AppendReply(this.id, this.currentTerm, success, this.indexStories, lastTermSaved);
                getSender().tell(response, getSelf());
            }else{
                this.indexStories = 0;
                if(prevIndexReceived == 0){
                    success = true;
                }else if(this.log.get(prevIndexReceived).term == prevTermReceived){
                    success = true;
                }
                if (success){
                    System.out.println("PEER "+this.id+" ---> ho avuto successo\n");
                    this.indexStories= storeEntries(prevIndexReceived, entriesReceived, commitIndexReceived);
                    //System.out.println("NODE "+this.id+"_______indexStories after ____"+this.indexStories);
                    for (int i=1; i<this.log.size(); i++){
                        System.out.println("LOG NODE "+this.id+" n_elements "+(this.log.size()-1)+" -----> command: "+log.get(i).command+",  term: "+log.get(i).term);
                    }
                    System.out.println("\n");
                    int lastTermSaved = this.log.get(this.indexStories).term;
                    AppendReply response  = new AppendReply(this.id, this.currentTerm, success, this.indexStories, lastTermSaved);
                    System.out.println("PEER "+this.id+" --> invio di AppendReply al leader\n");
                    getSender().tell(response, getSelf());

                }

            }

        }
        if(message instanceof UpdateCommitIndex){
            boolean result = ((UpdateCommitIndex) message).increment;
            System.out.println("PEER "+this.id+" ---> risltato operazione commit ricevuta = "+result);
            if(result){
                this.commitIndex++;
            }
        }
    }

    private void sendAppendEntries(ActorRef peer){
        ActorRef leader = getSender();
        float timeoutSendAppendEntries = System.currentTimeMillis() + ((config.getInt("MAX_TIMEOUT")-config.getInt("MAX_TIMEOUT"))/2);
        int id_peer = returnIdPeer(peer);
        System.out.println("PEER ID ------>  "+id_peer+"\n");
//        for(int i=0; i<config.getInt("N_SERVER"); i++){
//            System.out.println("nextIndex["+i+"] = "+nextIndex[id_peer]+"\n");
//        }

        int nextIndexLogPeer = nextIndex[id_peer];
        this.nextIndex[id_peer] = nextIndexLogPeer;

        //System.out.println("prima di chiamare getEntriesToSend. lastLogIndex = "+lastLogIndex+", logLeder.size= "+this.log.size()+"\n");

        ArrayList<LogEntry> entries = getEntriesToSend(this.log, (nextIndexLogPeer-1));

        AppendRequest appendRequest = new AppendRequest(this.id, this.currentTerm, (nextIndexLogPeer-1), this.log.get(nextIndexLogPeer).term, entries, this.commitIndex);
        System.out.println("Invio al peer "+id_peer+" di una AppendRequest con index di riferimento "+(nextIndexLogPeer-1) +"\n");
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

    private boolean checkMajorityCurrentTerm(int currentTerm, int[] termPeer){
        int nPeerWithMyTerm=0;
        for (int i=0; i<termPeer.length; i++){
            if(currentTerm == termPeer[i]){
                nPeerWithMyTerm++;
            }
        }
        if(nPeerWithMyTerm> termPeer.length/2){
            return true;
        }else{
            return false;
        }
    }

    private int storeEntries(int prevIndex, ArrayList<LogEntry> entries,int commitIndex){
        //System.out.println("\n in storeEntries. entries.size() "+entries.size()+"\n");
        int index = prevIndex;

        if(this.log.isEmpty()){
            for(int i=0; i<entries.size(); i++) {
                this.log.add(entries.get(i));
                index = index + 1;
            }
        }else {
            for (int j=0; j<entries.size(); j++) {
                index = index + 1;

                //controllo se non ho l'entry nel log per quell'indice
                int lastEntry = getLastLogIndex(this.log);
                //System.out.println("\n in storeEntries. index = "+index+", peerLog.size() = "+(this.log.size()-1)+"  indexLastEntry ="+lastEntry+", prevIndex ="+prevIndex+"\n");
                if(lastEntry == prevIndex){
                    this.log.add(entries.get(j));
                }else if (this.log.get(index).term != entries.get(j).term) {
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

    private ArrayList<LogEntry> getEntriesToSend(ArrayList<LogEntry> logLeader, int lastLogIndex){
        //System.out.println("Sono nella chiamata getEntriesToSend. logLeader.size() = "+logLeader.size()+",  lastLogIndex = "+lastLogIndex+"\n");
        //lastLogIndex is the index of the last log written by peer
        ArrayList<LogEntry> entries = new ArrayList<>();
        int start = lastLogIndex+1;
        int end = logLeader.size();
        //System.out.println("START = "+start+"  END = "+end);
        for (int i=start; i<end; i++){
            //System.out.println("Sono nel ciclo i="+i);
            entries.add(logLeader.get(i));
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


