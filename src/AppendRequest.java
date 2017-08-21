import java.io.Serializable;
import java.util.Map;

public class AppendRequest implements Serializable {
    public final int senderID;
    public final int lastLogIndex;
    public final int nextLogIndex;
    public final int term;
    public final Map<Integer, String> logEntry;

    public AppendRequest(int senderID, int lastLogIndex, int nextLogIndex, int term, Map<Integer, String> logEntry){
        this.senderID = senderID;
        this.lastLogIndex = lastLogIndex;
        this.nextLogIndex = nextLogIndex;
        this.term = term;
        this.logEntry = logEntry;
    }
}
