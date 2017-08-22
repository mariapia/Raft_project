import java.io.Serializable;

public class SendCommand implements Serializable {
    public final String command;

    public SendCommand(String command){
        this.command = command;
    }

}
