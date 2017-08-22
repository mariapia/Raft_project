import akka.actor.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class Client extends UntypedActor {
    Config config = ConfigFactory.load("application");
    protected int id;
    protected int leaderID = -1;
    ActorRef leader;

    protected String[] commandList = new String[10];
    protected int INDEXCOMMAND = 0;
    protected boolean stopSend = false;

    protected Boolean resultCommand = true;

    public Client(int id){
        this.id = id;
        for (int i=0; i<10; i++){
            this.commandList[i] = "command_"+i;
        }

    }

    @Override
    public void onReceive(Object message){
        if (message instanceof InformClient){
            this.leaderID = ((InformClient) message).leaderID;
            leader = getSender();
            resultCommand = ((InformClient) message).commandExecuted;
            sendCommands(resultCommand);
        }

    }

    public void sendCommands(boolean resultCommand){
        String commandToExecute;

        while(!stopSend && resultCommand){
            commandToExecute = getCommand(INDEXCOMMAND);
            SendCommand msgSendCommand = new SendCommand(commandToExecute);
            leader.tell(msgSendCommand, getSelf());
            System.out.println("Sono il client e ho inviato il comando"+msgSendCommand.command);
            if (INDEXCOMMAND == 9){
               //go to sleep
                stopSend = true;
                resultCommand = false;

            }
        }
    }

    private String getCommand(int indexCommand) {
        String res = commandList[indexCommand];
        INDEXCOMMAND = (indexCommand + 1) % commandList.length;
        return res;
    }
}
