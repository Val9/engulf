#!/bin/bash
curl -L http://engulf-project.org/latest.jar > ~/engulf.jar
curl http://169.254.169.254/latest/user-data | head -n1 > ~/engulf-opts
ENGULF_OPTS=`cat ~/engulf-opts`
MEMTOT=`free -m | grep '^Mem: ' | awk '{print $2}'`
MEMUNIT=m
MEMMIN=`expr $MEMTOT - 400`$MEMUNIT
MEMMAX=`expr $MEMTOT - 200`$MEMUNIT
CMD="java -Xms$MEMMIN -Xmx$MEMMAX -jar $HOME/engulf.jar --http-port 8080 $ENGULF_OPTS"
echo Will run: $CMD
$CMD