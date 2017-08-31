/**
 * Created by mariapia on 24/08/17.
 */
public class Debugging {

    public void onReceive(ServerNode serverNode){
        System.out.println("Sono " + serverNode.id + " il mio stato è " + serverNode.state + " il leader è " + serverNode.leaderID + " il term è " + serverNode.currentTerm + " il mio log è " + serverNode.log);
    }
}