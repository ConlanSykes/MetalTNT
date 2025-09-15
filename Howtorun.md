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

This will:
Watch for file changes
Automatically rebuild the mod
Restart the Minecraft client with the updated mod
