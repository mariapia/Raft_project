import java.io.Serializable;

public class VoteReply implements Serializable {
    public final int votedID;
    public final int term;
    public final int senderID;
    public final boolean granted;

    public VoteReply(int votedID, int term, int senderID, boolean granted){
        this.votedID = votedID;
        this.term = term;
        this. senderID = senderID;
        this.granted = granted;
    }

}
