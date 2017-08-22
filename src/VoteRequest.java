import java.io.Serializable;

public class VoteRequest implements Serializable {
    public final int senderID;
    public final int currentTerm;
    public int lastLogTerm;
    public int lastLogIndex;

    public VoteRequest(int senderID, int currentTerm, int lastLogIndex, int lastLogTerm){
        this.senderID = senderID;
        this.currentTerm = currentTerm;
        this.lastLogIndex = lastLogIndex;
        this.lastLogTerm = lastLogTerm;
    }

    public void onReceive(ServerNode node){

    }
}
