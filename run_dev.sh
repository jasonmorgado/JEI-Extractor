#!/bin/bash
# Build and run the Minecraft client into cyclops-dev.
# Automatically terminates once the JEI recipe export completes.

COMPLETE_MARKER="JEI recipe export complete"
LOGFILE=$(mktemp /tmp/jei-extractor-XXXXX.log)

# Start the game with line-buffered output
stdbuf -oL ./gradlew runClient --args="--quickPlaySingleplayer cyclops-dev" > "$LOGFILE" 2>&1 &
GAME_PID=$!

echo "Extracting recipes (will auto-quit when done)..."

# Poll for completion marker
while kill -0 $GAME_PID 2>/dev/null; do
    if grep -m1 -q "$COMPLETE_MARKER" "$LOGFILE" 2>/dev/null; then
        sleep 2  # let file writes flush
        echo "Export complete. Stopping."
        break
    fi
    sleep 1
done

# Kill everything
kill $GAME_PID 2>/dev/null
pkill -f "forge-1.20.1-47.4.13" 2>/dev/null
wait $GAME_PID 2>/dev/null

rm -f "$LOGFILE"
echo "Done."
