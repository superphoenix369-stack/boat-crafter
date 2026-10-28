# Boat Engine - dock and sail edition (Fabric, Minecraft 26.3)

Craft a Boat Engine (blast furnace + iron + planks + boat), float it on water, build on it, and sail.
More blocks on the boat = slower boat. A lone engine moves ~40 blocks/second.
Every extra Boat Engine on the same boat adds thrust, up to a top speed of 80 blocks/second.

(Internally the block is still called `boatbase:boat_base`, so worlds made with older versions keep working.)

## Build the jar with GitHub
1. Upload everything in this folder to your repo (keep the `.github` folder).
2. Open the **Actions** tab, open the finished `build` run, and download the `boatbase-jar` artifact.
3. Put the jar (not the `-sources` one) in your `mods` folder with Fabric API.

## Controls
**Ship's Wheel** (craft it from sticks, an iron ingot and 4 planks of ONE wood type: the wheel is made of that wood, so oak planks give an oak wheel. All 12 wood types work. Build it onto the boat, connected to a Boat Engine):
- It faces you when you place it. Right-click it to take the helm; you stand in front of it, so keep that space clear.
  The wheel is about 1.6 blocks across and sticks out past its own block, so leave room around it.
- **Hold W**: the boat speeds up. **Let go**: it slowly coasts to a stop. **Hold S**: it brakes.
- **A / D**: hold to turn left / right smoothly (60 degrees per second), so the boat can sail in any direction. The whole boat turns around the Boat Engine and your camera turns with it. When the boat stops it straightens onto the nearest north/east/south/west direction and docks as blocks (so a small turn that you stop in is undone, a quarter turn stays). You can also turn a docked boat by holding A / D at the wheel.
- **Sneak**: leave the helm (the boat coasts to a stop).

**Boat Engine** (no wheel needed):
- Right-click: sail at full throttle towards the nearest of the 8 compass directions you are looking at. Right-click again: stop at once.
  Sneak + right-click while sailing: turn.

Kelp, seagrass and corals are ignored by the boat. Chests and other containers move with it.

## Tuning
Speed, acceleration, braking and update rate are at the top of `src/main/java/com/example/boatbase/BoatMover.java`.

## Dock and sail (this version)
- **Docked** (stopped): the boat is made of real blocks. Build on it, open chests, place the wheel, as normal.
- **Sailing**: the moment the boat starts moving, its blocks are taken out of the world and become one smooth moving
  entity. Walk around on deck while it sails. When it has been still for a second it turns back into real blocks.
- Turning (A / D) turns the boat in 45-degree steps here (the smooth turning is for the sailing-entity mode).

Limits of the sailing entity:
- Blocks that are drawn by special renderers (chests, signs, beds, banners, shulker boxes, heads) are invisible while
  the boat sails and come back when it docks. Their contents are kept safe.
- Your own screen handles walking on deck. Mobs and items are carried along but do not collide with the sailing boat.
- On a dedicated server, set `allow-flight=true` in server.properties, or players standing on a sailing boat can be
  kicked for "flying". (Single player and LAN are fine.)
- Biggest boat that can sail: 1500 blocks. If it cannot dock where it stops (no room), steer to open water.
- To go back to the old move-the-blocks behaviour, set `ENTITY_MODE = false` in `BoatMover.java`.
