import java.io.Serializable;

public class SendCommand implements Serializable {
    public final String command;
    public final String client_name;

    public SendCommand(String command, String client_name){
        this.command = command;
        this.client_name = client_name;
    }

}
