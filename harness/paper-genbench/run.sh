#!/bin/bash
# usage: run.sh X Z SIZE INFLIGHT  (fresh world each run)
cd ~/bench/paper && rm -rf world world_nether world_the_end
exec ~/.local/toolchains/jdk-25.0.4.1+1/bin/java -Xms2G -Xmx4G -Dgenbench.x=$1 -Dgenbench.z=$2 -Dgenbench.size=$3 -Dgenbench.inflight=$4 -Dgenbench.out=$HOME/bench/paper/genbench-results.txt -jar paper-26.2.jar nogui
