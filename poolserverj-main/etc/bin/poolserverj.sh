#!/bin/bash

java -classpath poolserverj.jar:../lib/*:../lib/lib_non-maven/*:../lib/plugins com.shadworld.poolserver.servicelauncher.PoolServerService $1 $2 $3
