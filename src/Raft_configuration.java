import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import
import akka.actor.ActorRef;
import akka.actor.ActorSystem;

/**
 * Created by mariapia on 19/08/17.
 */
public class Raft_configuration {
    public static void main(String[] args) throws InterruptedException  {

        final ActorSystem system = ActorSystem.create("RaftSystem");
        List<ActorRef> group = new ArrayList<>();

//        for(int i=0; i<Defines_Utilities.MAX_NUM_PEERS; i++)
//            group.add(system.actorOf(Props.create(PeerServer.class,i)));
//
//        group = Collections.unmodifiableList(group);
//
//
//        // messaggio prima della partenza del programma
//        // comunica il gruppo a tutti i peer
//        SetGroupMessage start = new SetGroupMessage(group);
//        for (ActorRef peer : group)
//            peer.tell(start, null);
//
//        // start programma
//        while(true){
//            TimeMessage tm = new TimeMessage("");
//            for (ActorRef peer : group)
//                peer.tell(tm, null);
//            Thread.sleep(1000);
//        }
    }

}
