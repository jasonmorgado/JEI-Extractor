# JEI-Extractor
 
## Overview

In modded Minecraft, it can be fun to look through all the different recipes available in a modpack and plan things out.
Because each modpack tends to modify recipes, static wikis aren't practical for them, making recipe viewing mods like JEI necessary.

I wonder if I could theoretically display the same data outside of the game.

This project is an attempt at 
This repo contains a Minecraft mod to extract recipe information from Just Enough Items (JEI) and produce JSON files.


### Design

Currently the main workflow should look like:
- JEI currently maintains a list of all recipes in the game (included modded recipe types)
- An extraction mod (this repo) runs in-game. Extracting data from the JEI recipe manager into some collection of JSON files.
- Some separate frontend app consumes the JSON files, and displays them.

### Docs

- [recipe_scraping.md](docs/recipe_scraping.md) how I scrape information from JEI.
- [exported_files.md](docs/exported_files.md) what files are output by the extractor.

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

Debugging should be done in IntelliJ, I couldn't get VSCode to work.

I yearn for something to just open the world on boot when I hit start.
