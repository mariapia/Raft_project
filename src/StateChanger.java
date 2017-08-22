/**
 * Created by mariapia on 22/08/17.
 */
public class StateChanger {


    public void onReceive(ServerNode node){
        switch (node.state){
            case FOLLOWER:
                node.state = ServerState.CANDIDATE;
                break;
        }
    }
}
