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

import static com.typesafe.sslconfig.ssl.AlgorithmConstraintsParser.Failure;
import static com.typesafe.sslconfig.ssl.AlgorithmConstraintsParser.Success;
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

    public Client(int id) {
        this.id = id;
        for (int i = 0; i < 5; i++) {
            this.commandList[i] = "command_" + i;
        }
        //timout to know how much wait for a reply from servers
        this.answerTimeout = new Timeout(scala.concurrent.duration.Duration.create(3000, TimeUnit.MILLISECONDS));

    }

    @Override
    public void onReceive(Object message) throws InterruptedException {
        if (message instanceof StartMessage) {
            //inform client of all servers in the system
            StartMessage msg = (StartMessage) message;
            try {
                for (int i = 0; i < msg.group.size(); i++) {
                    this.participants.add(msg.group.get(i));

                }
            } catch (Throwable e) {
                System.out.println(e.getStackTrace());
            }
            //at the beginning, set as leader a random server and send to it a command
            int pos_peer = randomGenerator.nextInt(this.participants.size());
            this.leader = this.participants.get(pos_peer);
            this.leaderID = returnIdPeer(leader);
            sendCommands(this.INDEXCOMMAND);
        }

        if (message instanceof InformClient) {
            int id = ((InformClient) message).leaderID;
            //if command has been committed, increment index of command list
            if (((InformClient) message).commandExecuted == true) {
                System.out.println("Command done, command done is " + commandList[this.INDEXCOMMAND] + " leader is " + this.leaderID);
                this.INDEXCOMMAND++;
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {
                    System.out.println("Exception in thread sleep: " + e.getMessage());
                }
            }

            if (id == -1) {
                //if leader not decided yet, wait
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {
                    System.out.println("Exception in thread sleep: " + e.getMessage());
                }
                sendCommands(this.INDEXCOMMAND);
            } else {
                this.leader = ((InformClient) message).leader;
                sendCommands(this.INDEXCOMMAND);
            }

        }

    }

    public void sendCommands(int INDEXCOMMAND) {

        String commandToExecute = "";
        System.out.println();
        SendCommand msgSendCommand = null;
        if (INDEXCOMMAND < commandList.length) {
            commandToExecute = commandList[INDEXCOMMAND];
            msgSendCommand = new SendCommand(commandToExecute, this.getSelf().path().name());
        }
        if (INDEXCOMMAND == commandList.length) {
            commandToExecute = "FINISH";
            msgSendCommand = new SendCommand(commandToExecute, this.getSelf().path().name());
        }
        Future<Object> future = Patterns.ask(this.leader, msgSendCommand, answerTimeout);
        try {
            this.onReceive(Await.result(future, answerTimeout.duration()));

        } catch (TimeoutException ex) {
            int pos_peer = randomGenerator.nextInt(this.participants.size());
            this.leader = this.participants.get(pos_peer);
            this.leaderID = returnIdPeer(leader);
            sendCommands(this.INDEXCOMMAND);
        } catch (Exception ex) {
            System.out.println("No other commands to send");
        }

    }

    //return id from path name
    public int returnIdPeer(ActorRef peer) {
        String name = peer.path().name();
        String[] tokens = name.split("_");
        int idPeer = Integer.parseInt(tokens[1]);
        //System.out.println("name peer "+name+"   idPeer "+idPeer+"\n");
        return idPeer;
    }


}
