import java.io.Serializable;

public class AppendReply implements Serializable{
    public final int currentTerm;
    public final boolean success;
    public final int indexStories;
    public final int senderID;
    public final int lastTermSaved;

    public AppendReply(int senderID, int currentTerm, boolean success, int indexStories, int lastTermSaved){
        this.senderID = senderID;
        this.currentTerm = currentTerm;
        this.success=success;
        this.indexStories = indexStories;
        this.lastTermSaved = lastTermSaved;
    }
}
