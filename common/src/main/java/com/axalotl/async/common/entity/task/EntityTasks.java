package com.axalotl.async.common.entity.task;

import com.axalotl.async.common.ParallelProcessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;

/** Forms batch-local owners for passengers and actual shared mutable state. */
public final class EntityTasks {
    private static volatile Batch active;
    private EntityTasks() {}

    public static void begin(ServerLevel world, List<Entity> entities, List<Entity> despawnOnly) {
        if (active != null) throw new IllegalStateException("Nested entity batches");
        Map<Entity, CooperativeTask> owners = new IdentityHashMap<>(entities.size()
                + (despawnOnly == null ? 0 : despawnOnly.size()));
        Map<Entity, Entity> sources = new IdentityHashMap<>();
        for (Entity entity : entities) bind(entity.getRootVehicle(), owners, sources);
        if (despawnOnly != null) for (Entity entity : despawnOnly) bind(entity.getRootVehicle(), owners, sources);
        Set<ServerLevel> levels = Collections.newSetFromMap(new IdentityHashMap<>());
        if (world != null) levels.add(world);
        for (Entity entity : owners.keySet()) {
            if (entity.level() instanceof ServerLevel level) levels.add(level);
        }
        for (ServerLevel level : levels) {
            for (Entity entity : level.getAllEntities()) {
                if (!entity.isRemoved()) bind(entity.getRootVehicle(), owners, sources);
            }
        }
        SharedEntityState.bind(owners, sources);
        active = new Batch(world, owners);
    }

    private static void bind(Entity root, Map<Entity, CooperativeTask> owners, Map<Entity, Entity> sources) {
        if (owners.containsKey(root)) return;
        CooperativeTask owner = ownTask(root);
        owners.put(root, owner);
        List<Entity> passengers = null;
        for (Entity passenger : root.getIndirectPassengers()) {
            owners.put(passenger, owner);
            if (passengers == null) passengers = new ArrayList<>();
            passengers.add(passenger);
        }
        bindSource(root, owners, sources);
        if (passengers != null) for (Entity passenger : passengers) bindSource(passenger, owners, sources);
    }

    private static void bindSource(Entity entity, Map<Entity, CooperativeTask> owners, Map<Entity, Entity> sources) {
        Entity source = sourceOwner(entity);
        if (source == null) return;
        sources.put(entity, source);
        Entity root = source.getRootVehicle();
        bind(root, owners, sources);
        owners.putIfAbsent(source, owners.get(root));
    }

    static Entity sourceOwner(Entity entity) {
        if (entity instanceof TraceableEntity traceable) return traceable.getOwner();
        if (entity instanceof OwnableEntity ownable) return ownable.getOwner();
        return null;
    }

    public static void end() { active = null; }
    public static boolean hasActiveContext() { return active != null && CooperativeTask.currentContext() != null; }
    public static void run(Entity entity, Runnable action) { task(entity).runOwned(action); }

    public static List<Group> groups(List<Entity> entities) {
        if (active == null) throw new IllegalStateException("Entity groups require an active batch");
        Map<CooperativeTask, List<Entity>> roots = new LinkedHashMap<>();
        for (Entity entity : entities) roots.computeIfAbsent(task(entity), ignored -> new ArrayList<>()).add(entity);
        List<Group> groups = new ArrayList<>(roots.size());
        roots.forEach((owner, actors) -> groups.add(new Group(actors)));
        return groups;
    }

    public record Group(List<Entity> roots) {}

    public static boolean claimTick(ServerLevel world, Entity entity) {
        Batch batch = active;
        return batch == null || batch.world != world || batch.ticked.add(entity);
    }

    public static <T> T call(Entity entity, Supplier<T> action) {
        if (entity.level().isClientSide()) return action.get();
        boolean worker = ParallelProcessor.isServerExecutionThread();
        Batch batch = active;
        CooperativeTask owner = batch == null ? null : batch.owner(entity);
        EntityTaskAccess access = (EntityTaskAccess) entity;
        if (owner == null && !access.tickweave$inLevel()) {
            return ownTask(entity).call(action, true, worker, ParallelProcessor::assistOwnerTasks);
        }
        var server = ParallelProcessor.getServer();
        if (!worker && server != null && !server.isSameThread()) {
            return await(CooperativeTask.supplyAsync(() -> call(entity, action), ParallelProcessor::queueMainThreadTask));
        }
        CooperativeTask target = owner == null ? ownTask(entity) : owner;
        return target.call(action, true, worker, ParallelProcessor::assistOwnerTasks);
    }

    public static <T> T call(Entity receiver, Entity participant, Supplier<T> action) {
        return call(receiver, () -> participant == null || participant == receiver
                ? action.get() : call(participant, action));
    }

    public static <T> T damage(Entity receiver, DamageSource source, Supplier<T> action) {
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        return call(receiver, attacker, () -> direct == null || direct == receiver || direct == attacker
                ? action.get() : call(direct, action));
    }

    public static void execute(Entity entity, Runnable action) {
        call(entity, () -> { action.run(); return null; });
    }

    public static boolean requiresOwnership(Object receiver) {
        if (!(receiver instanceof Entity entity) || entity.level() == null || entity.level().isClientSide()) return false;
        CooperativeTask owner = task(entity);
        return owner != null && !owner.isHeld();
    }

    public static Object callOwned(Object receiver, Supplier<?> action) {
        return call((Entity) receiver, action);
    }

    public static void executeOwned(Object receiver, Runnable action) {
        execute((Entity) receiver, action);
    }

    /** Acquires member resources before the native traversal starts; callbacks are never replayed. */
    public static <T> void forEachOwned(Collection<T> values, Consumer<? super T> action, Object receiver) {
        if (!(receiver instanceof Entity entity) || entity.level().isClientSide()) {
            values.forEach(action);
            return;
        }
        java.util.Objects.requireNonNull(action);
        call(entity, () -> {
            while (true) {
                Object[] before = values.toArray();
                List<CooperativeTask> participants = new ArrayList<>(before.length);
                for (Object member : before) if (member instanceof Entity participant) {
                    CooperativeTask owner = task(participant);
                    if (!owner.isHeld()) participants.add(owner);
                }
                if (participants.isEmpty()) {
                    values.forEach(action);
                    return null;
                }
                boolean completed = CooperativeTask.onThreads(participants, () -> {
                    Object[] current = values.toArray();
                    if (current.length != before.length) return false;
                    for (int i = 0; i < before.length; i++) if (before[i] != current[i]) return false;
                    values.forEach(action);
                    return true;
                }, ParallelProcessor.isServerExecutionThread(), ParallelProcessor::assistOwnerTasks);
                if (completed) return null;
                // Resource acquisition may have waited; only this side-effect-free preparation is retried.
            }
        });
    }

    public static <T> T onThread(Entity entity, Supplier<T> action) {
        if (entity.level().isClientSide()) return action.get();
        return task(entity).onThread(action, ParallelProcessor.isServerExecutionThread(), ParallelProcessor::assistOwnerTasks);
    }

    public static <T> T await(CompletableFuture<T> result) {
        return CooperativeTask.await(result, ParallelProcessor.isServerExecutionThread(), ParallelProcessor::assistOwnerTasks);
    }

    public static <T> T onMain(Supplier<T> action) {
        var server = ParallelProcessor.getServer();
        if (server == null || server.isSameThread()) return action.get();
        return await(CooperativeTask.supplyAsync(action, ParallelProcessor::queueMainThreadTask));
    }

    public static <T> T onMain(Entity entity, Supplier<T> action) {
        return entity.level().isClientSide() ? action.get() : onMain(action);
    }

    private static CooperativeTask task(Entity entity) {
        Batch batch = active;
        CooperativeTask owner = batch == null ? null : batch.owner(entity);
        return owner == null ? ownTask(entity) : owner;
    }

    private static CooperativeTask ownTask(Entity entity) {
        // Respawn installs this connection before restoreFrom and world registration.
        if (entity instanceof ServerPlayer player && player.connection != null)
            return ((ConnectionTaskAccess) player.connection.connection).tickweave$connectionTask();
        return ((EntityTaskAccess) entity).tickweave$taskOwner();
    }

    private static final class Batch {
        final ServerLevel world;
        final Map<Entity, CooperativeTask> owners;
        final Map<CooperativeTask, CooperativeTask> connections = new IdentityHashMap<>();
        final Set<Entity> ticked = ConcurrentHashMap.newKeySet();

        Batch(ServerLevel world, Map<Entity, CooperativeTask> owners) {
            this.world = world;
            this.owners = owners;
            owners.forEach((entity, owner) -> {
                if (entity instanceof ServerPlayer player && player.connection != null)
                    connections.put(ownTask(player), owner);
            });
        }

        CooperativeTask owner(Entity entity) {
            CooperativeTask owner = owners.get(entity);
            // A respawn replacement keeps the connection's current group until the batch ends.
            if (owner == null && entity instanceof ServerPlayer player && player.connection != null)
                return connections.get(ownTask(player));
            return owner;
        }
    }
}
