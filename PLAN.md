# IceWall Mod — Development Plan

## Overview

A Minecraft Java Edition mod (targeting **Fabric 1.21.x latest**) that spawns a full-height, full-width wall of ice that incrementally advances across the world, forcing players to stay ahead of it.

As the wall advances it **never retreats** — every column it passes through is permanently filled solid with ice, building into an ever-thickening glacier behind the leading edge. Any player caught inside the wall dies instantly.

---

## Core Concept

- A wall of **Packed Ice** advances along one axis (default: positive Z / south).
- The wall is:
  - **As tall as the world** — `minBuildHeight` to `maxBuildHeight` (y = −64 to y = 320 in 1.21+, bedrock to sky limit).
  - **As wide as all explored area** — tracked via the min/max X boundary of all loaded/visited chunks.
- The wall advances every configurable interval (default: 30 seconds per block).
- **Every block the wall has ever occupied stays as ice permanently.** The world behind the leading edge becomes a growing glacier — there is no going back.
- **Placement fills every non-solid block**: air, water, lava, snow, tall grass, etc. are all replaced with Packed Ice. Only pre-existing solid stone/dirt/ore is left untouched (configurable).
- **Instant death** for any player whose position is at or behind the leading edge Z.
- Players are warned with titles, sound, and a boss bar as the wall approaches.

---

## Tech Stack

| Component | Choice | Reason |
|-----------|--------|--------|
| Mod loader | **Fabric** | Modern, lightweight, fast update cycle |
| MC Version | **1.21.x** | Current stable; full height world support |
| Build tool | **Gradle + Loom** | Standard Fabric toolchain |
| Config | **Cloth Config + TOML** | Hot-reloadable, server-side config |
| Data persistence | **PersistentStateManager** | Saves wall state in world NBT |

---

## Architecture

```
icewall/
├── src/main/java/dev/icewall/
│   ├── IceWallMod.java               # Mod entry point
│   ├── wall/
│   │   ├── IceWallState.java         # PersistentState — wall position, explored bounds
│   │   ├── IceWallAdvancer.java      # Tick scheduler, advancement logic
│   │   ├── ChunkBoundaryTracker.java # Tracks min/max X of explored chunks
│   │   └── BlockPlacementQueue.java  # Batched block placement across ticks
│   ├── config/
│   │   └── IceWallConfig.java        # Speed, material, direction, warnings
│   ├── network/
│   │   └── IceWallPackets.java       # Sync wall position to clients (boss bar)
│   └── event/
│       ├── ChunkLoadHandler.java     # Update explored X bounds on chunk load
│       └── PlayerTickHandler.java    # Per-player warning / kill logic
├── src/main/resources/
│   ├── fabric.mod.json
│   └── assets/icewall/lang/en_us.json
└── build.gradle
```

---

## Wall State (Persisted)

Saved in `level.dat` via `PersistentStateManager`:

```java
// IceWallState fields
int wallZ;            // Current leading edge Z position (the "death line")
int minExploredX;     // Westernmost loaded chunk block X
int maxExploredX;     // Easternmost loaded chunk block X
long tickAccumulator; // Sub-advance tick counter
boolean active;       // Wall on/off toggle
// NOTE: No "wall thickness" counter needed — everything behind wallZ is
// already filled on disk. The glacier depth is simply (wallZ - startZ).
```

### What "behind the wall" means on disk

Because old placements are **never removed**, the chunk data on disk already stores the accumulated ice. The mod only needs to track the single leading-edge Z value — it does not need to re-fill past slices on load.

---

## Efficiency Strategy

### Problem
Placing 384 blocks tall × potentially thousands of blocks wide every advance = millions of block updates → **server freeze**.

Additionally, because old ice is **never removed**, we must ensure the fill logic only touches blocks that actually need replacing (air, water, etc.) and skips already-solid blocks to avoid redundant writes.

### Solution: Batched Column Placement with Skip-Solid Optimisation

1. **Per-tick budget**: Place a maximum of `N` blocks per tick (default: 512). Remaining work is queued.
2. **Skip-solid check**: Before placing, call `blockState.isSolidBlock()` — if already solid, skip the position entirely. This dramatically reduces work in already-generated terrain (caves, mountains).
3. **Chunk-gated placement**: Only place blocks in **currently loaded chunks**. Unloaded chunks get a deferred entry in the queue; placed when the chunk loads via `ChunkLoadEvent`.
4. **Column units**: Work is broken into columns (1 block wide × full height = 384 blocks). Each tick processes as many complete columns as the budget allows.
5. **Ahead-of-wall pre-generation**: Force-load `chunk_preload_ahead` chunks ahead of the leading edge using `ServerChunkManager.addTicket(ChunkTicketType.FORCED, ...)` so placements are never blocked on chunk generation.
6. **No cascading block updates**: Use `world.setBlockState(pos, state, Block.NOTIFY_LISTENERS | Block.NO_REDSTONE_UPDATE)` — suppresses redstone and neighbour-update cascades. Lighting updates remain async via vanilla.
7. **Fluid suppression**: Water/lava source blocks are replaced directly; no `FluidTickScheduler` entries are added for ice, preventing infinite fluid update loops behind the wall.

### Chunk Boundary Tracking

```
On CHUNK_LOAD event:
  minExploredX = min(minExploredX, chunk.getPos().getMinBlockX())
  maxExploredX = max(maxExploredX, chunk.getPos().getMaxBlockX() + 15)
```

This runs in O(1) per chunk load event — no scanning required.

---

## Tick Scheduler

```
Every server tick (20/s):
  tickAccumulator += 1
  if tickAccumulator >= advanceIntervalTicks:
      tickAccumulator = 0
      wallZ += 1                          // advance wall
      enqueueColumn(wallZ, minX, maxX)    // add work to queue

  processQueue(maxBlocksPerTick = 512)    // drain placement queue
```

The queue decouples wall advancement from block placement, ensuring the server never spikes.

---

## Player Warning System

| Distance to wall | Action |
|-----------------|--------|
| 200 blocks | Boss bar appears showing distance + countdown |
| 100 blocks | Yellow title: "The ice wall approaches…" |
| 50 blocks | Red title + sound (`BLOCK_GLASS_BREAK`) |
| 10 blocks | Urgent red title flashing every second + heartbeat sound |
| **0 or behind** | **Instant death** — `player.kill()` with custom death message: *"[Player] was consumed by the glacier"* |

> There is no grace period or damage ramp — the wall is an instant kill zone. The warning system exists to give players time to run, not to survive inside the wall.

---

## Configuration (`icewall.toml`)

```toml
[wall]
enabled = true
block = "minecraft:packed_ice"         # Block placed by the wall
direction = "SOUTH"                    # NORTH | SOUTH | EAST | WEST
advance_interval_seconds = 30          # Seconds between each 1-block advance
chunk_preload_ahead = 4                # Chunks force-loaded ahead of wall
max_blocks_per_tick = 512              # Placement budget per server tick
replace_solids = false                 # If true, even stone/ore is replaced with ice

[warnings]
enable_bossbar = true
enable_titles = true
enable_sounds = true
warn_distances = [200, 100, 50, 10]    # Block distances that trigger warnings

[punishment]
# Punishment is always instant death — this section controls the death message only
custom_death_message = "%s was consumed by the glacier"
```

> `replace_solids = false` (default) means the wall only fills non-solid blocks (air, water, lava, snow, plants, etc.), preserving terrain. Setting it `true` turns the entire glacier zone to pure ice, including stone and ore.

---

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/icewall start` | OP | Activates the wall |
| `/icewall stop` | OP | Pauses advancement |
| `/icewall reset` | OP | Resets wall to world spawn edge |
| `/icewall setpos <z>` | OP | Teleport wall to Z coordinate |
| `/icewall speed <seconds>` | OP | Change advance interval live |
| `/icewall status` | any | Show current wall Z, speed, distance |
| `/icewall distance` | any | Show your distance to the wall |

---

## Event Hooks (Fabric API)

| Event | Handler | Purpose |
|-------|---------|---------|
| `ServerTickEvents.END_SERVER_TICK` | `IceWallAdvancer` | Drive tick scheduler |
| `ChunkEvents.CHUNK_LOAD` | `ChunkBoundaryTracker` | Expand X bounds |
| `ServerPlayerEvents.AFTER_RESPAWN` | `PlayerTickHandler` | Re-attach boss bar |
| `ServerWorldEvents.LOAD` | `IceWallMod` | Load wall state |
| `ServerLifecycleEvents.SERVER_STOPPED` | `IceWallMod` | Save wall state |

---

## Phase Plan

### Phase 1 — Core Wall
- [ ] Gradle/Fabric project scaffold
- [ ] `IceWallState` persistence (load/save NBT)
- [ ] `ChunkBoundaryTracker` with chunk load event
- [ ] Basic `IceWallAdvancer` (tick scheduler + wall Z increment)
- [ ] `BlockPlacementQueue` with per-tick budget
- [ ] Force-chunk-load ahead of wall

### Phase 2 — Player Experience
- [ ] Boss bar with distance display
- [ ] Title/subtitle warnings at distance thresholds
- [ ] Sound effects (approach sounds + heartbeat at 10 blocks)
- [ ] **Instant death** when player Z >= wallZ, with custom death message

### Phase 3 — Configuration & Commands
- [ ] `icewall.toml` with Cloth Config
- [ ] All `/icewall` commands
- [ ] Hot-reload config on `/icewall reload`

### Phase 4 — Polish & Performance
- [ ] Profiling: measure TPS impact at large widths
- [ ] Tune `maxBlocksPerTick` defaults
- [ ] Behind-wall cleanup queue
- [ ] Multiplayer sync (boss bar per-player distance)
- [ ] README + modrinth/curseforge metadata

---

## Decisions Made

| Question | Decision |
|----------|----------|
| Mod loader | **Fabric** |
| MC version | **1.21.x latest** |
| Wall direction | Configurable, default **SOUTH** (increasing Z) |
| Behind-wall cleanup | **None** — ice accumulates permanently, glacier grows thicker over time |
| Multiplayer | Designed for **SMP servers** (per-player boss bars, server-side state) |
| Punishment mechanic | **Instant death** (`player.kill()`) with custom death message |
| Wall material | **Packed Ice** (default, configurable) — fills all non-solid blocks including air, water, lava, fluids, plants |
| Replace solid terrain | **Off by default** — stone/ore preserved; configurable to full replacement |

## Remaining Open Questions

1. **Wall direction** — Should the wall always start at the world border on the trailing side, or start near spawn?
2. **Multiplayer** — Should death teleport the player to a safe spot ahead of the wall, or be a true death with item drops?
3. **Dimension support** — Overworld only, or should it also apply in the Nether (y=0–256) and End?
