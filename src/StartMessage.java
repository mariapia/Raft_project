import akka.actor.ActorRef;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

//Message to inform all the nodes which are others participants.
//Class used in Raft_configuration.java

public class StartMessage implements Serializable {
    public final List<ActorRef> group;		// an array of group members (actor references)
    public final ActorRef client;
    public StartMessage(List<ActorRef> group, ActorRef client) {
        // Copying the group as an unmodifiable list
        this.group = Collections.unmodifiableList(new ArrayList<ActorRef>(group));
        this.client = client;
    }
}
