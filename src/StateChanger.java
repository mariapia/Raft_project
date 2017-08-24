/**
 * Created by mariapia on 22/08/17.
 */
public class StateChanger {


    public void onReceive(ServerNode node){
        switch (node.state){
            case FOLLOWER:
                if (node.stepdown == true) {
                    node.state = ServerState.FOLLOWER;
                } else {
                    node.state = ServerState.CANDIDATE;
                }
                break;

            case CANDIDATE:
                if (node.stepdown == true) {
                    node.state = ServerState.FOLLOWER;
                } else {
                    node.state = ServerState.LEADER;
                }
                break;

            case LEADER:
                if (node.stepdown == true){
                    node.state = ServerState.FOLLOWER;
                }
                break;
        }
    }
}