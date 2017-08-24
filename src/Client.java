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

    protected String[] commandList = new String[3];
    protected int INDEXCOMMAND = 0;

    protected Boolean resultCommand = true;

    public Client(int id){
        this.id = id;
        for (int i=0; i<3; i++){
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
        System.out.println();
        if(INDEXCOMMAND<commandList.length) {
            //commandToExecute = getCommand(INDEXCOMMAND);
            commandToExecute = commandList[INDEXCOMMAND];
            SendCommand msgSendCommand = new SendCommand(commandToExecute);
            System.out.println(" CLIENT -----> ho ricevuto l'OK dal leader. Comando "+msgSendCommand.command);
            leader.tell(msgSendCommand, getSelf());
            INDEXCOMMAND++;
        }
//            if (INDEXCOMMAND == 9){
//                System.out.println("Valore INDEXCOMMAND "+INDEXCOMMAND);
//               //go to sleep
//                resultCommand = false;
//            }
        //}
    }

    private String getCommand(int indexCommand) {
        String res = commandList[indexCommand];
        INDEXCOMMAND = (indexCommand + 1) % commandList.length;
        return res;
    }
}
