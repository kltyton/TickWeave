# Changelog

## 2.1.11 — Minecraft 1.20.1

- Run native game connection ticks in parallel ownership groups, coordinating connection closure, listener replacement and packet handling without a global player-tick lock.
- Initialize concurrent chunk visibility storage before entity sections capture it, preserving the native hidden default during parallel natural spawning.
- Protect supported shared collection access in otherwise unmixed mod classes through loader-specific bootstrap entrypoints; retain backing collection types, null behavior and callback semantics.
- Acquire member entity resources before guarded collection traversals, and protect compound operations on supported singleton multimaps, including their persistence calls.
- Cache class-invariant ownership-scan classifications and skip traversal of empty native NBT containers without caching mutable state across ticks.
- Exclude immutable NBT leaves before ownership traversal and construct the final concurrent map directly when the entire empty-map constructor path is verified free of other work.
- Require Fabric Loader 0.19.3 or newer.
- Keep native callback and collision code when a transformed method has inconsistent instruction-list metadata; optional bytecode rewrites do not scan those methods through ASM's cached-size array conversion.
- Preserve native random-source method bodies for other Mixin injections while protecting reseeding, concurrent LCG updates and the Gaussian cache.
- Allocate independent label nodes when copying matched callback handlers.
- Keep POI storage and complete lazy query operations on the server owner thread, including queries made by parallel natural-spawn callbacks.
- Signal queued requests and completed tasks directly. Release resource leases during cooperative waits so queued synchronous callbacks can progress; a complete entity tick is not a transaction.
- Avoid captured coordinate factories on native pathfinding cache hits, preserving missing-value computation, default values and custom map behavior.
- Remove argument arrays and captured operations from verified synchronized forwarding wrappers while preserving their monitor scope and adjacent wrapper order.
- Defer fresh callback objects until their first read in verified Mixin injection blocks, preserving callback order, cancellation and return values.
- Allocate Forge capability invalidation listener sets on first registration, preserving lazy value resolution and listener callbacks.
- Reuse the last thread's native fluid-occlusion cache without changing per-thread map identity, capacity, eviction order or fluid updates.
- Keep servicing ready chunk tasks while entity workers await chunk completion; park the owner thread only when no owner or chunk task made progress, including shutdown.
- Schedule all entity types through ownership groups, including players and modded entities, without annotation or class-based admission. Tick passengers with their root vehicle and do not replay failed ticks.
- Group native Ownable/Traceable source chains, passengers and detected shared mutable state. Respawn replacements retain their connection group for the active batch; unrelated entities can execute concurrently.
- Preserve the native effect-loop invalidation path when an effect disappears between key iteration and lookup, avoiding a null effect tick while still updating dirty effect metadata.
- Mix packed coordinate keys before concurrent long-map lookups to avoid clustered hash buckets and tree searches. Preserve original keys in callbacks, live key/entry views, equality and hash codes.
- Reuse ordered entity-section indexes and member snapshots until membership changes. Run query callbacks outside collection locks and refresh later X slices after reentrant section changes.
- Reuse immutable full-world lookup membership between registrations, removals and replacements. Existing iterators retain their membership; new iterators observe completed updates.
- Read existing attributes without concurrent-map bucket locking, retaining atomic creation for missing attributes and native behavior for custom map implementations.
- Publish immutable weak worker membership for allocation-free thread checks; guard nullable effect queries at method entry without wrapping ordinary queries.
- Cache hard-collision candidate membership for native collision queries. Verify the transformed base methods before excluding constant-false classes; retain custom collision methods, mutable state checks and multipart handling.
- Preserve native Brain memory normalization, clearing and expiry with one concurrent memory store.
- Release breeding guards when the second parent is already busy; read the selected breeding target once for each guarded operation.
- Preserve ordered block-event deduplication, conditional integer-map removal and stable short-array snapshots.
- Prepare natural-spawn player caches on the server thread before submitting worker tasks; resolve uncommon misses there as well, preserving the native distance-manager gate and cached empty results.
- Size adaptive batches for the actual worker count; the server thread remains available for worker requests.
- Expand fixed noise permutations into gradient tables during construction, preserving random consumption and floating-point evaluation order.

## 2.1.10 — Minecraft 1.20.1

- Store active effects in a concurrent map so color snapshots cannot acquire null array slots during concurrent changes through the public effects map. Preserve nullable effect queries and removals.
- Protect directly initialized private static final `Map` fields backed by `WeakHashMap` in Mixin-transformed classes, including fields added by other mods. Use the JDK synchronized wrapper to preserve weak keys, key equality and nullable results. Iteration and compound operations still require caller synchronization; classes outside the Mixin transformation pipeline are not covered.
- Use forward range iterators for entity section queries, avoiding an extra sorted-set wrapper and bidirectional cursor state.
- Use direct section lookups for small query ranges while retaining packed coordinate order and early termination.
- Evaluate Z noise interpolation only when a density value is consumed, preserving the previous value before X coefficients change. Defer this optimization to C2ME, Noisium and HariChunk when installed.
- Coalesce identical pending worker chunk requests within each world, including their status and creation policy; release completed and failed requests.

## 2.1.9 — Minecraft 1.20.1

- Restrict entity section queries to the requested Z ranges while preserving packed-coordinate order, accessibility checks and early termination.
- Reuse ordered chunk-holder membership snapshots between visible-map publications. Keep live readiness checks, save budgets and POI persistence unchanged.

## 2.1.8 — Minecraft 1.20.1

- Prioritize worker entity registration on the server thread before unrelated chunk tasks, including while stopping the worker pool. Preserve registration cancellation and original exceptions.

## 2.1.7 — Minecraft 1.20.1

- Synchronize direct effect lifecycle callbacks with effect updates so callers entering through callbacks also protect attribute update transactions.

## 2.1.6 — Minecraft 1.20.1

- Snapshot effects for color calculations so effect-changing callbacks cannot invalidate a listener's traversal. Synchronize forced additions, direct removals and Forge cures with the existing effect lifecycle lock.
- Reuse concurrent range views for entity section queries instead of copying each range. Preserve range bounds, backed mutations and bidirectional cursor behavior.
- Avoid duplicate missing-key lookups in entity section maps and preserve the configured default return value when inserting a new key.

## 2.1.5 — Minecraft 1.20.1

- Keep the server thread available for worker requests throughout each parallel batch. Run synchronous entity ticks after worker completion to prevent entity locks from blocking main-thread registration.

## 2.1.4 — Minecraft 1.20.1

- Complete worker-originated entity registration on the server thread, preserving registration results and cancellation for spawning, projectiles, drops, and teleport additions. Parallel spawn and entity calculations remain enabled.

## 2.1.3 — Minecraft 1.20.1

- Fix crashes while nearby entities, items, or players are sorted by AI sensors. Cache distance keys for each sort so movement cannot change comparison results.

## 2.1.2 — Minecraft 1.20.1

- Fix a server crash when entities have active potion effects. Use the native effect lifecycle under the existing synchronization lock on Forge and Fabric, preserving expiration events and effect state updates.

## 2.1.1 — Minecraft 1.20.1

- Fix startup with SophisticatedCore 1.3.21.1676 by adapting inventory hooks to `SlotValueMap`. Protect its paired indexes and return detached slot snapshots.
- Enable the shared scheduling improvements on Fabric, including platform service registration and dedicated-server dependencies.
- Align Fabric scheduling configuration with Forge and reload entity rules when a server starts. Preserve existing configuration files on load errors.
- Package the Forge Mixin configurations and generated refmap in release JARs.
- Introduce adaptive entity batches, bounded submission, main-thread assistance and phase completion barriers.
- Cache worker chunk lookups and commit entity tracking callbacks on the main thread.
- Keep random-tick batching experimental and disabled by default.
- Rename the mod to TickWeave (`tickweave`), use `/tickweave` and `config/tickweave.toml`. Old configurations require manual migration; remove the old mod JAR before upgrading.
