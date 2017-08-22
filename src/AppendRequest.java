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

    public AppendRequest(int term, int lastLogIndex, int lastTerm, ArrayList<LogEntry> entries, int commitIndex){
        this.term = term;
        this.prevIndex = lastLogIndex;
        this.prevTerm = lastTerm;
        this.entries = entries;
        this.commitIndex = commitIndex;
    }
}
