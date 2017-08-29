import akka.actor.ActorRef;

import java.io.Serializable;

public class InformClient implements Serializable {
    public final int leaderID;
    public final ActorRef leader;

    //flag to inform client if its command has been executed or not
    public final Boolean commandExecuted;

    public InformClient(int leaderID, ActorRef leader, Boolean commandExecuted){
        this.leaderID=leaderID;
        this.leader = leader;
        this.commandExecuted = commandExecuted;
    }

}
