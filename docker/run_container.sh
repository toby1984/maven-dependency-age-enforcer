#!/bin/bash

set -e
set -o pipefail

source image_name 

REBUILD="0"
DOCKER_OPTS="-d"

function printHelp() {
    echo "Usage: [-b|--build][-d|--debug]"
    exit 1
}

while [ "$#" -gt "0" ] ; do
  
  case "$1" in
  
    -b | --build)
      FORCE="--force-image-removal"
      REBUILD="1" 
      ;;
    -d | --debug)
      REBUILD="1" 
      DOCKER_OPTS=""
      ;;
    -h | --help)
      printHelp
      ;;
    *)
      echo "ERROR: Unrecognized command-line option $1" 
      printHelp
      ;;
  esac

  shift
done

if ! docker image ls --format "{{.Repository}}:{{.Tag}}" | grep ${IMAGE_NAME} ; then
  echo "Docker image not found, building it..."
  REBUILD="1"
else
  RUNNING_CONTAINERS=`docker ps -a --filter ancestor=${IMAGE_NAME} --format="{{.ID}}"`
  if [ ! -z "$RUNNING_CONTAINERS" ] ; then
    echo "Stopping running containers."
    docker stop ${RUNNING_CONTAINERS} 
  fi
fi

if [ "$REBUILD" != "0" ] ; then
  build_image.sh ${FORCE}
fi

# docker run -itd --rm -p 8888:8080 $IMAGE_NAME
docker run -it ${DOCKER_OPTS} --rm --mount type=bind,src=./data,dst=/data -p 8888:8080 -p 8889:8889 $IMAGE_NAME
