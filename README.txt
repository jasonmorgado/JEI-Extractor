## Installation

Make sure Java 17.x.x is installed
```bash
java -version
// needs 17.x.x
sudo apt update
sudo apt install openjdk-17-jdk
```

Then add it to ENV vars

```bash
nano ~/.bashrc
```

add at the bottom:
```
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

Then run `./gradlew runClient`

Or even `./gradlew clean runClient --refresh-dependencies` to install dependencies and boot.