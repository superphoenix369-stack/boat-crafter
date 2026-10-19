# Boat Base (Fabric, Minecraft 26.3)

Craft a Boat Base (blast furnace "engine" + iron + planks + boat), place it on water, build on it, and sail.
More blocks on the boat = slower boat. A lone base moves ~40 blocks/second.

## Build the jar with GitHub
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the repo's **Actions** tab. The `build` workflow runs on every push.
3. Open the finished run and download the `boatbase-jar` artifact. Put the jar (not the `-sources` one) in your `mods` folder with Fabric API.

If the build fails, open the failed run and copy the error log.

## Controls
- Right-click the base: sail the way you face. Right-click again: stop.
- Sneak + right-click while sailing: turn to face your direction.

## Tuning
Speed constants are at the top of `src/main/java/com/example/boatbase/BoatMover.java`.
