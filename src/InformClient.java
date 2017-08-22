import java.io.Serializable;

public class InformClient implements Serializable {
    public final int leaderID;

    //flag to inform client if its command has been executed or not
    public final Boolean commandExecuted;

    public InformClient(int leaderID, Boolean commandExecuted){
        this.leaderID=leaderID;
        this.commandExecuted = commandExecuted;
    }

}
