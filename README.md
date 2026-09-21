# TickWeave

<p align="center"><img src="common/src/main/resources/tickweave.png" alt="TickWeave" width="160"></p>

[English](README.md) | [简体中文](README.zh-CN.md)

TickWeave spreads Minecraft entity ticking across CPU workers to reduce server tick time in entity-heavy worlds. Built for **Minecraft 1.20.1**, with **Forge and Fabric** editions. It works on dedicated servers and the integrated server in single-player.

## Installation

Use Java 17 and choose the file matching your loader:

| Loader | Runtime | Release file |
| --- | --- | --- |
| Forge | Forge 47.x; built against 47.4.16 | `tickweave-forge-1.20.1-2.1.11-all.jar` |
| Fabric | Fabric Loader 0.19.3+ and Fabric API for 1.20.1 | `tickweave-fabric-1.20.1-2.1.11.jar` |

Place the JAR in `mods`. Dedicated-server players do not need TickWeave on their clients. For single-player, install it on the client. Back up your world before changing tick-processing mods. Remove older TickWeave, HariMultiThread or Async JARs before installing; these implementations must not run together.

## Features

- Entity ownership groups distribute work across workers; generic batches adapt their size to measured cost, with main-thread request processing.
- A bounded worker pool and completion barriers between processing phases.
- Run in-game player connections through parallel entity ownership groups; connection closure, listener replacement and packet handling share the connection lifecycle boundary.
- Inspect shared collection fields and access patterns during loading, protecting supported caches, collections and compound shared-object operations while preserving backing collection types and callback behavior.
- Worker-local chunk lookup caching and shared pending requests; missing chunks are requested through the server executor.
- Ordered entity-section indexes and member snapshots reused until membership changes, plus chunk-holder snapshots for repeated traversal.
- Native collision queries cache candidate classes only where the transformed collision methods and type guards prove exclusion safe. Custom collision behavior and mutable entity state keep their native checks.
- On-demand Z noise interpolation, preserving floating-point operation order. This optimization yields to C2ME, Noisium and HariChunk.
- Optional parallel natural spawning and failure diagnostics, without replaying a failed entity tick.
- POI queries, lazy query traversal and storage changes execute on the server owner thread, retaining the storage indexes supplied by other mods.
- Live statistics for server tick time, worker activity and entity costs.
- Experimental random-tick batching, disabled by default. Block and fluid callbacks remain on the server thread.

All entity types participate in worker scheduling; `AsyncCompatible` annotations and the legacy synchronous-entity list do not control admission. Passengers tick with their root vehicle. Native Ownable/Traceable source chains and detected shared mutable state form ownership groups; cross-entity calls acquire participant resources. Cooperative waits allow queued callbacks to run, so an entire entity tick is not isolated as one transaction. World registration, POI storage and other owner-thread work remain on the server thread. On Fabric, scalar collection classes already loaded before Mixin preparation retain their original storage and are listed in the log. This does not make arbitrary static state or every mod callback thread-safe. Parallel execution changes entity ordering; results depend on the world, CPU and modpack, and speedups are not guaranteed. Entity simulation uses the CPU.

## Configuration

The file is `config/tickweave.toml`. Forge stores these keys under `["Async Config"]`; Fabric uses top-level keys. When migrating from `harimt.toml`, copy values into the configuration generated for the same loader. Configuration files are not interchangeable between loaders.

| Key | Default | Purpose |
| --- | --- | --- |
| `disabled` | `false` | Disable asynchronous processing |
| `paraMax` | `-1` | Worker count; automatic at -1, capped by available processors; restart after changing |
| `enableAsyncSpawn` | `true` | Parallel natural spawning |
| `enableAsyncRandomTicks` | `false` | Experimental random-tick preparation |
| `enableAffinityRouting` | `true` | Legacy value retained in configuration; ownership determines scheduling |
| `enableCircuitBreaker` | `true` | Collect entity failure diagnostics; does not move entity types to a synchronous fallback |
| `entitiesPerWorker` | `25` | Generic batch-size limit; ownership groups are not split by this value |
| `staleTaskTimeoutMs` | `200` | Slow-batch warning threshold in milliseconds; does not cancel running ticks |
| `synchronizedEntities` | Built-in list | Legacy entries retained for migration; inactive in all-entity scheduling |

Administrative commands:

```text
/tickweave stats
/tickweave stats entity 10 100
/tickweave config toggle
/tickweave config reload
/tickweave config setAsyncEntitySpawn false
/tickweave config setAsyncRandomTicks false
/tickweave config synchronizedEntities
```

`stats entity 10 100` samples 100 server ticks and lists the ten most expensive entity types. Its entity-time totals overlap across threads and are not wall-clock savings. `Completed Worker Entity Ticks` confirms actual worker execution; thread-pool startup alone does not.

## Compatibility

Do not combine with Moonrise. Entity registration uses the server thread on both loaders, including when Cupboard checks its thread ownership; Cupboard's hooks remain enabled. Clickable Advancements and Biome Music provide separate advancement and music features that TickWeave does not replace. TickWeave does not register a MixinSquared canceller. Carpet's `lagFreeSpawning` rule conflicts with parallel spawning; disable parallel spawning when using that rule. Existing compatibility hooks are conditional on the corresponding mods being installed. Version 2.1.1 updates the Forge SophisticatedCore hooks for `SlotValueMap` (Core 1.3.21.1676 / Backpacks 3.24.35.1675).

Use a copy of your modpack and world when evaluating a new build. Changing `synchronizedEntities` does not bypass a failing callback in the current scheduler. Report the loader, mod versions, `latest.log`, crash report and reproduction steps through [GitHub Issues](https://github.com/kltyton/TickWeave/issues).

## Building

Use the included Gradle wrapper with Java 17:

```sh
./gradlew :forge:build :fabric:build
./gradlew :forge:runClient
./gradlew :fabric:runClient
```

On Windows use `gradlew.bat`. Release files are in `forge/build/libs` (use the `-all.jar`) and `fabric/build/libs` (use the remapped JAR, not sources). The existing `common/libs/Harium-1.0.0.jar` is a compile-only integration dependency, not bundled into the releases.

## Credits and license

Derived from HariMultiThread and Async. Thanks to HariMT, Axalotl, Alchemy, Bliss, FurryMileon, Grider and jediminer543 for their upstream work; PaperMC / Folia informed thread-ownership design research. TickWeave is not a Folia server and does not imply upstream endorsement.

Licensed under [GPL-3.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) and the [changelog](CHANGELOG.md).
