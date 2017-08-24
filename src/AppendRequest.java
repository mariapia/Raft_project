import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Map;

public class AppendRequest implements Serializable {
    public final int term;
    public final int prevIndex;
    public final int prevTerm;
    public final ArrayList<LogEntry> entries;
    public final int commitIndex;
    public final int leaderId;

    public AppendRequest(int leaderId, int term, int lastLogIndex, int lastTerm, ArrayList<LogEntry> entries, int commitIndex){
        this.leaderId = leaderId;
        this.term = term;
        this.prevIndex = lastLogIndex;
        this.prevTerm = lastTerm;
        this.commitIndex = commitIndex;
        this.entries = new ArrayList<>(entries.size());

        for(int i=0; i<entries.size(); i++){
            this.entries.add(entries.get(i));
        }
    }
}
