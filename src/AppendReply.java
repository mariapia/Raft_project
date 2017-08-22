import java.io.Serializable;

public class AppendReply implements Serializable{
    public final int currentTerm;
    public final boolean success;
    public final int indexStories;
    public final int senderID;

    public AppendReply(int senderID, int currentTerm, boolean success, int indexStories){
        this.senderID = senderID;
        this.currentTerm = currentTerm;
        this.success=success;
        this.indexStories = indexStories;
    }
}
