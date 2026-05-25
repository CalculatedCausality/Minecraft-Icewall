# Ice Wall

A Fabric mod for Minecraft Java Edition that spawns a full-height wall of packed ice and advances it across the world one block at a time. The wall never retreats — every column it passes is permanently glaciated, and any player it catches dies instantly.

The mod is a survival-pressure system: there is no "beating" it. Players are forced to keep moving south (or whichever direction is configured) while the world behind them turns into a growing glacier.

> Status: actively developed, not currently published to Modrinth or CurseForge. Build it from source.

---

## What it does

The core wall is just the starting point. Around it the mod layers a series of systems that turn the glacier into a living antagonist:

**The wall itself**
- Full world-height column of packed ice, world-wide on the X axis (tracked from explored chunks).
- Advances 1 block every 30 seconds by default; configurable.
- Uneven leading edge — each X column gets a small random lead so the face looks organic.
- Skip-solid placement, per-tick block budget, force-chunk-loading ahead of the front. The server is not supposed to TPS-spike at large widths.
- Persisted in `level.dat` via `SavedData` so the wall survives restarts.

**Environmental corruption (ahead of the wall)**
- Surface freeze: water → ice, lava → obsidian, grass → podzol/snow, vegetation killed within an aggressive zone.
- Underground: cave-ceiling gravel collapses, stalactite spears, permafrost conversion below y=0, cryo-flood powder snow pools, sculk spread, glow lichen freeze, amethyst shatter, gas pockets (Poison/Wither), Darkness pulses.
- Glass shatters, portals seal, torches/campfires can be snuffed by frost-snap.

**Player effects**
- Hypothermia: max-health attribute modifier scales with proximity. Saturation drain at the wall face.
- Whiteout blindness pulses, compass scramble, inventory "frost rename", armor durability drain, ice-toll damage when mining glacier blocks, freeze-wind knockback, temperature HUD on the actionbar.
- Boss bar at 200 blocks, escalating titles + sounds at 100 / 50 / 10 blocks.
- Instant death past the front, with custom death message.

**World reactions**
- Weather: forced snowfall, blizzard lock, random lightning strikes near the face.
- Mob conversion / freezing near the wall, animal panic (passive mobs flee north), bat flush, wolf flight, periodic Stray hunting parties.
- Tree snap, snowdrift accumulation, indoor icicle drops on sheltered players.
- Item entity magnetism pulls dropped items back into the glacier.

**Natural disasters** (random while the wall is active)
- Avalanche, ice meteor shower, permafrost heave, earthquake, frozen geyser, blizzard surge, ground crack, ice rain, hypothermic shock wave, frost snap, snowdrift tsunami, structural collapse, magnetic pulse, aurora borealis.

**Survivor systems (ahead of the wall)**
- Procedural survivor cities generated 220–900 blocks ahead of the front, with named residents, bulletins on entry, gate defense events, and evacuation alerts as the wall closes in.
- Villager communities are clustered, warned, and nudged south to flee. Behind-wall city chunks turn into frozen ruins with aftermath patrols.
- Frozen chest reroll: loot in glaciated territory gets re-themed.

**Rail network**
- A persistent NPC rail-worker crew lays powered minecart track ahead of the glacier, meanders the route, builds bridge decking, places waystations, repairs broken track, and runs spurs to nearby survivor cities.
- Periodic supply minecarts are dispatched and announced to nearby players.
- Standalone supply drops to the furthest-ahead player every ~10 minutes.

**Atmosphere**
- Glacial whisper messages (3 distance tiers, distinct intervals).
- Ice pillar "blooms" — rare dramatic eruptions of tall ice columns ahead of the wall.
- Heatmap tracker over a rolling 1200-tick window.
- Spectator drift cam that slowly pans along the wall face.

**Respawn safety**
- Player bed/anchor spawns inside the glacier are invalidated and re-rolled to a safe point ahead of the front.
- World spawn is kept a minimum buffer ahead of the wall.

---

## Build

Requires **JDK 25**. Gradle and the Fabric Loom toolchain are vendored via the wrapper.

```powershell
./gradlew build
```

The built jar lands in `build/libs/icewall-<version>.jar`. Drop it into your server or client `mods/` folder alongside the matching Fabric Loader and Fabric API versions.

Versions are pinned in [gradle.properties](gradle.properties):

| Property | Value |
| --- | --- |
| `minecraft_version` | `26.1.2` |
| `loader_version` | `0.19.2` |
| `fabric_api_version` | `0.146.1+26.1.2` |
| `loom_version` | `1.16-SNAPSHOT` |

## Run locally

```powershell
./gradlew runClient        # client with the mod loaded
./gradlew runServer        # dedicated server
```

---

## Commands

All commands live under `/icewall`.

| Command | Permission | Purpose |
| --- | --- | --- |
| `/icewall status` | any | Show front Z, start Z, explored width, speed |
| `/icewall distance` | any | Show your distance from the glacier face |
| `/icewall start` | OP | Activate the wall |
| `/icewall stop` | OP | Pause advancement |
| `/icewall setpos <z>` | OP | Teleport the wall front to a Z coordinate |
| `/icewall speed <seconds>` | OP | Live-tune seconds per block |
| `/icewall spectate` | OP | Enter the drift cam along the wall face |
| `/icewall spectate stop` | OP | Exit the drift cam |
| `/icewall heatmap` | OP | Print the top sampled chunks |
| `/icewall snapshot` | OP | Save an NBT snapshot of wall state to `<world>/icewall_snapshots/` |

---

## Configuration

All tunables live as `public static final` constants in [src/main/java/dev/icewall/config/IceWallConfig.java](src/main/java/dev/icewall/config/IceWallConfig.java) — there is no runtime config file yet. To change defaults, edit that file and rebuild.

The most useful knobs:

| Constant | Default | Effect |
| --- | --- | --- |
| `DEFAULT_ADVANCE_INTERVAL_TICKS` | `20 * 30` | One block every 30 s |
| `MAX_BLOCKS_PER_TICK` | `512` | Per-tick placement budget |
| `START_OFFSET_BLOCKS` | `128` | Where the wall begins, relative to world spawn |
| `LEAD_VARIATION` | `8` | Max leading-edge unevenness across X columns |
| `REPLACE_SOLIDS` | `false` | If `true`, stone and ore are also replaced with ice |
| `CHUNK_PRELOAD_AHEAD` | `4` | Chunks force-loaded ahead of the front |
| `BOSS_BAR_DISTANCE` | `200` | When the boss bar appears |
| `WARNING_DISTANCES` | `{200,100,50,10}` | Title / sound thresholds |

Subsystems (cave corruption, weather, disasters, rail, cities, …) have their own tuning constants in the same file, each one commented inline.

---

## License

All Rights Reserved. See [src/main/resources/fabric.mod.json](src/main/resources/fabric.mod.json). The repo is public for visibility; no redistribution or derivative-work license is granted at this time.
