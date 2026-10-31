#!/bin/sh
# Emergent City workspace: the city mod (emergentcity/) and the plague mod (emergentplague/), built together.
#
#   ./play.sh           build both mods and start Minecraft with both; the game folder is instance/
#   ./play.sh server    the same, as a dedicated server (also in instance/)
#   ./play.sh build     only build the two mod jars, copied to jars/ (for a normal Minecraft with Forge 47 installed:
#                       put both in its mods folder; the plague jar needs the city jar)
set -e
cd "$(dirname "$0")"
case "${1:-play}" in
    play)   ./gradlew runClient ;;
    server) ./gradlew runServer ;;
    build)
        ./gradlew cityJar plagueJar
        mkdir -p jars
        city=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
        plague=$(grep '^plague_mod_version=' gradle.properties | cut -d= -f2)
        cp "build/libs/emergentcity-$city.jar" "build/libs/emergentplague-$plague.jar" jars/
        ls -l jars
        ;;
    *) echo "usage: ./play.sh [play | server | build]"; exit 1 ;;
esac
