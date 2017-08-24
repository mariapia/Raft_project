import akka.actor.ActorRef;

public class HeartBeat {

    public void onReceive (ServerNode node) {
        for (ActorRef q : node.participants) {
            if (q != node.getSelf()) {
   //             System.out.println("Sono " + this.id + " e sono " + this.state + " ho ricevuto i seguenti voti " + this.votes );
                //sendAppendEntries();
            }
        }
    }
}
