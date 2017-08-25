import akka.actor.ActorRef;

import java.util.ArrayList;
import java.util.Map;

public class HeartBeat {

    public void onReceive (ServerNode node) {
        for (ActorRef q : node.participants) {
            if (q != node.getSelf()) {
                //System.out.println("Sono " + node.id + " e sono " + node.state + " ho ricevuto i seguenti voti " );
                //sendAppendEntries;
                if (node.id == node.leaderID) {
                    AppendRequest heartbeatMessage = new AppendRequest(node.id, node.currentTerm, -1, -1, new ArrayList<LogEntry>(), node.commitIndex);
                    q.tell(heartbeatMessage, node.getSelf());
                }
            }
        }
    }
}
