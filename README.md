# Raft_project

Properly download and install JAVA and AKKA: 

1. Download and install Java 8 SDK, Standard Edition, from
   http://www.oracle.com/technetwork/java/javase/downloads/index.html

2. Download Akka 2.4.10:
     http://downloads.typesafe.com/akka/akka_2.11-2.4.10.zip

3. Unzip the archive

4. On a Unix-like system, set the following environment variables (replacing "path_to_akka" with 
   the actual path):
   
     `export AKKA_HOME=path_to_akka/akka-2.4.10
     export AKKA_CLASSPATH=$AKKA_HOME/lib/\*:$AKKA_HOME/config:$AKKA_HOME/lib/akka/\*`

   To preserve these settings you might add those lines to your ~/.bash_profile or ~/.zshenv, etc., depending
   on the shell you use.

Enter in Raft project folder

`cd out/production/Raft_project`

`java -cp $AKKA_CLASSPATH:. Raft_configuration`
