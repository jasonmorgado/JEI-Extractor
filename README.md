# JEI-Extractor
 
## Overview

In modded Minecraft, each modpack customizes recipes differently, making static wikis impractical. It would be handy to have a way to explore these recipes outside the game. Like JEI for web.

This project extracts recipe data from JEI via a Minecraft mod (this repo) which gets used by a frontend ([other repo](https://github.com/jasonmorgado/JEI-Web-Viewer)) to display them in a similar fashion to JEI.

The frontend gets deployed to [GitHub Pages](https://jasonmorgado.github.io/JEI-Web-Viewer/).

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

## CI/CD: Automated Sync to JEI-Web-Viewer

A [GitHub Actions workflow](.github/workflows/sync-export-to-display.yml) automatically copies the extracted JSON files from this repo into [JEI-Web-Viewer](https://github.com/jasonmorgado/JEI-Web-Viewer) and opens a pull request.
