# Raft_project

##On ServerNode.java


@Override
on receive (Object message){
  switch (state){
      case LEADER:
          leader(message);
          beak;
      case FOLLOWER;
          follower(message);
          .
          .
          .
  }
}


public void follower(Object message){
  if message instanceof StartMessage{
    .
    .
    .
    .
  }
  if message instance of VoteRequest{
    .
    .
    .
    .
  }
}
