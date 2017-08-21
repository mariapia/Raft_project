import java.io.Serializable;

public class VoteReply implements Serializable {
    public final int votedID;
    public final int term;
    public final int senderID;

    public VoteReply(int votedID, int term, int senderID){
        this.votedID = votedID;
        this.term = term;
        this. senderID = senderID;
    }

}
