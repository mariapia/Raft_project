import java.io.Serializable;

public class UpdateCommitIndex implements Serializable {
    public final boolean increment;

    public UpdateCommitIndex(boolean increment){
     this.increment = increment;
    }
}


