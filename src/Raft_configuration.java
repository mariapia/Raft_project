import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.Config;

/**
 * Created by mariapia on 19/08/17.
 */
public class Raft_configuration {
    public static void main(String[] args) throws InterruptedException  {

        Config config = ConfigFactory.load("application");
        System.out.println("N_server: "+ config.getInt("N_SERVER"));

        final ActorSystem system = ActorSystem.create("RaftSystem", config);
        List<ActorRef> group = new ArrayList<>();

        for (int i=0; i < config.getInt("N_SERVER"); i++){
            int index_node = i;
            ActorRef node = system.actorOf(Props.create(ServerNode.class, i), "node_"+index_node);
            group.add(node);
        }
        group = Collections.unmodifiableList(group);


        ActorRef client = system.actorOf(Props.create(Client.class, 101), "client_"+101);

        StartMessage start = new StartMessage(group, client);
        for (ActorRef peer : group) {
            peer.tell(start, null);
        }
        client.tell(start, client);

    }

}
