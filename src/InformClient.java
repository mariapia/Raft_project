import java.io.Serializable;

public class InformClient implements Serializable {
    public final int leaderID;

    public InformClient(int leaderID){
        this.leaderID=leaderID;
    }

}
