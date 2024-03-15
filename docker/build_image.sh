#!/bin/bash

set -e
set -o pipefail

export TMP_FOLDER="war_temp"
WAR_FILE="versiontracker.war"
WAR="../server/target/${WAR_FILE}"

trap "rm -rf ${TMP_FOLDER}; exit $?" INT TERM EXIT
trap 
source image_name
echo "Building ${IMAGE_NAME} ..."

cd "$(dirname "$0")"
( cd .. && mvn -DskipTests=true clean install)
if [ ! -e ${WAR} ] ; then
  echo "Something went wrong, did not find ${WAR}"
  exit 1
fi

if [ -e ${TMP_FOLDER} ] ; then
  rm -rf ${TMP_FOLDER}
fi
mkdir ${TMP_FOLDER}
cp ${WAR} ${TMP_FOLDER}/versiontracker.war

if docker image ls --format '{{.Repository}}:{{.Tag}}' | grep ${IMAGE_NAME} ; then
  docker image rm ${IMAGE_NAME}
fi

docker build --no-cache -t ${IMAGE_NAME} .
