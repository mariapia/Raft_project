/**
 * Created by mariapia on 21/08/17.
 */
public class LogEntry {
    public int term;
    public String command;

    public LogEntry(int term, String command){
        this.term = term;
        this.command = command;
    }

    @Override
    public String toString() {
        return "[Term = " + this.term + ", Command = " + this.command + "]";
    }
}