import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import java.util.*;

public  class ServerNode extends UntypedActor {
    protected int id;
    protected List<ActorRef> participants = new ArrayList<ActorRef>();

    //variables in Persistent State
    protected int currentTerm;
    protected int votedFor;
    protected List<Map<Integer, String>> log;

    //variables in Non-Persistent State
    private ServerState state;


    public ServerNode(int id){
        super();
        this.id = id;
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
        System.out.println("Participants.size() "+participants.size()+"  server id "+ id);
    }




}
