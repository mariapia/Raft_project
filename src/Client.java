import akka.actor.*;
import akka.pattern.Patterns;
import akka.util.Timeout;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.camel.util.jsse.FilterParameters;
import scala.Option;
import scala.concurrent.Await;
import scala.concurrent.CanAwait;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.time.Duration;
import java.util.*;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.typesafe.sslconfig.ssl.AlgorithmConstraintsParser.Failure;
import static com.typesafe.sslconfig.ssl.AlgorithmConstraintsParser.Success;
import static java.util.concurrent.TimeUnit.MILLISECONDS;


public class Client extends UntypedActor {
    Config config = ConfigFactory.load("application");
    protected int id;
    protected int leaderID = -1;
    ActorRef leader;

    protected String[] commandList = new String[3];
    private Random randomGenerator = new Random();
    protected int INDEXCOMMAND = 0;

    protected Boolean resultCommand = true;
    protected List<ActorRef> participants = new ArrayList<ActorRef>();

    protected Timeout duration = new Timeout(scala.concurrent.duration.Duration.create(100, TimeUnit.MILLISECONDS));

    public Client(int id){
        this.id = id;
        for (int i=0; i<3; i++){
            this.commandList[i] = "command_"+i;
        }

    }

    @Override
    public void onReceive(Object message){
        if(message instanceof StartMessage){

            StartMessage msg = (StartMessage) message;
            try {
                for (int i = 0; i < msg.group.size(); i++) {
                    this.participants.add(msg.group.get(i));

                }
            } catch (Throwable e) {
                System.out.println(e.getStackTrace());
            }
            int pos_peer = randomGenerator.nextInt(this.participants.size());
            this.leader = this.participants.get(pos_peer);
            this.leaderID = returnIdPeer(leader);
            System.out.println("CLIENT - SONO IN START MESSAGE. IL MIO LEADER E' "+this.leaderID+"\n");
            sendCommands(this.INDEXCOMMAND);
        }

        if (message instanceof InformClient){
            int id = ((InformClient) message).leaderID;
            if(id == -1){
                //if leader not decided yet, wait
                System.out.println("CLIENT - SONO IN INFORM CLIENT. IL LEADER NON È ANCORA STATO DECISO\n");
                try {
                    Thread.sleep(1000);
                }catch (Exception e){
                    System.out.println("Exception in thread sleep: "+e.getMessage());
                }
                sendCommands(this.INDEXCOMMAND);
            }else{
                this.leader = ((InformClient) message).leader;
                //TODO: adjust ActorSelection in ActorRef
                //this.leader = getContext().getChild(address);
                System.out.println("CLIENT - SONO IN INFORM CLIENT. Il LEADER È STATO DECISO ED È ' "+this.leader.path().name()+"\n");
                sendCommands(this.INDEXCOMMAND);
                this.INDEXCOMMAND++;
            }

        }

    }

    public void sendCommands(int INDEXCOMMAND){
        String commandToExecute;
        System.out.println();
        if(INDEXCOMMAND<commandList.length) {
            commandToExecute = commandList[INDEXCOMMAND];
            SendCommand msgSendCommand = new SendCommand(commandToExecute);
            System.out.println(" CLIENT -----> sto inviando il comando "+msgSendCommand.command+" a "+this.leader.path().name());
            this.leader.tell(msgSendCommand, getSelf());
        }
        if(INDEXCOMMAND == commandList.length){
            commandToExecute = "FINISH";
            SendCommand msgSendCommand = new SendCommand(commandToExecute);
            this.leader.tell(msgSendCommand, getSelf());
        }
    }
//
//    private String getCommand(int indexCommand) {
//        String res = commandList[indexCommand];
//        INDEXCOMMAND = (indexCommand + 1) % commandList.length;
//        return res;
//    }
    public int returnIdPeer(ActorRef peer) {
        String name = peer.path().name();
        String[] tokens = name.split("_");
        int idPeer = Integer.parseInt(tokens[1]);
        //System.out.println("name peer "+name+"   idPeer "+idPeer+"\n");
        return idPeer;
    }


}
