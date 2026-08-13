#!/bin/bash
set -e
set -x

SEED_FILE="/data/seed.artifacts.json.binary"
TARGET_FILE="/data/artifacts.json.binary"

if [ -e "$SEED_FILE" ]; then
    echo "Found seed file $SEED_FILE"
    if [ ! -e "$TARGET_FILE" ] ; then
      echo "No target file $TARGET_FILE yet, using $SEED_FILE to populate it"
      cp "$SEED_FILE" "$TARGET_FILE"
    else
      echo "Target file $TARGET_FILE already exists, doing nothing." 
      ls -l $TARGET_FILE
    fi
else
    echo "No seed file found at $SEED_FILE, not populating $TARGET_FILE"
fi

# Hand off to whatever CMD/ENTRYPOINT would normally run (catalina.sh run)
exec "$@"
