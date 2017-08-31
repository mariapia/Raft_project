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
import java.util.concurrent.TimeoutException;

//import static com.typesafe.sslconfig.ssl.AlgorithmConstraintsParser.Failure;
//import static com.typesafe.sslconfig.ssl.AlgorithmConstraintsParser.Success;
import static java.util.concurrent.TimeUnit.MILLISECONDS;


public class Client extends UntypedActor {
    Config config = ConfigFactory.load("application");
    protected int id;
    protected int leaderID = -1;
    ActorRef leader;

    protected String[] commandList = new String[5];
    private Random randomGenerator = new Random();
    protected int INDEXCOMMAND = 0;

    protected Boolean resultCommand = true;
    protected List<ActorRef> participants = new ArrayList<ActorRef>();

    protected Timeout answerTimeout;

    public Client(int id){
        this.id = id;
        for (int i=0; i<5; i++){
            this.commandList[i] = "command_"+i;
        }
        this.answerTimeout = new Timeout(scala.concurrent.duration.Duration.create(3000, TimeUnit.MILLISECONDS));

    }

    @Override
    public void onReceive(Object message) throws InterruptedException {
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
            if(((InformClient) message).commandExecuted==true)
            {
                System.out.println("Command done, command done is " + commandList[this.INDEXCOMMAND] + " leader is " + this.leaderID);
                this.INDEXCOMMAND++;
                try {
                    Thread.sleep(1000);
                }catch (Exception e){
                    System.out.println("Exception in thread sleep: "+e.getMessage());
                }
            }

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
            }

        }

    }

    public void sendCommands(int INDEXCOMMAND){

        String commandToExecute = "";
        System.out.println();
        SendCommand msgSendCommand;
        if(INDEXCOMMAND<commandList.length) {
            commandToExecute = commandList[INDEXCOMMAND];
            System.out.println(" CLIENT -----> sto inviando il comando "+ commandToExecute +" a "+this.leader.path().name());
            msgSendCommand = new SendCommand(commandToExecute, this.getSelf().path().name());
            Future<Object> future = Patterns.ask(this.leader, msgSendCommand, answerTimeout);
            try {
                this.onReceive(Await.result(future, answerTimeout.duration()));

            } catch (TimeoutException ex) {
                System.out.println("\nCLIENT -Server didn't reply. Choosing another server");
                int pos_peer = randomGenerator.nextInt(this.participants.size());
                this.leader = this.participants.get(pos_peer);
                this.leaderID = returnIdPeer(leader);
                System.out.println("CLIENT - RIPROVO CON UN NUOVO PEER. IL MIO LEADER E' "+this.leaderID+"\n");
                sendCommands(this.INDEXCOMMAND);
            }
            catch (Exception ex)
            {
                System.out.println("Error");
            }
        }
        if(INDEXCOMMAND == commandList.length){
            commandToExecute = "FINISH";
            msgSendCommand = new SendCommand(commandToExecute, this.getSelf().path().name());
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
