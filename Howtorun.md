// to view yarn mapping visit
https://maven.fabricmc.net/docs/yarn-1.21.1%2Bbuild.3/index.html

# to build the thing Once

./gradlew build

# To build out continuous

./gradlew build --continuous

# For just Testing

./gradlew compileJava --continuous

## Run me if things are not working

./gradlew clean build

# To start the thing Once

./gradlew runclient

# To run and have changed on file changes

./gradlew runClient --continuous

# To Run and also make a debug file

./gradlew runClient > run.log 2>&1

This will:
Watch for file changes
Automatically rebuild the mod
Restart the Minecraft client with the updated mod
