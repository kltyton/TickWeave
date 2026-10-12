package com.axalotl.async.common.diagnostics.entity;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Keeps group membership and wall times for one explicit entity statistics sample. */
public final class EntityBatchStats {
    public enum Kind { ENTITY, CONNECTION }

    private final Phase entities = new Phase();
    private final Phase connections = new Phase();
    private final AtomicLong sequence = new AtomicLong();

    public long nextBatch() { return sequence.incrementAndGet(); }

    public void recordBatch(Kind kind, int groups) {
        Phase phase = phase(kind);
        phase.batches.increment();
        phase.groups.add(groups);
    }

    public Group recordGroup(Kind kind, long batch, String world, int groups, int totalEntries, int entries,
                             Map<String, Integer> members, List<Integer> actors,
                             Map<String, Integer> bindings) {
        Phase phase = phase(kind);
        Info info = new Info(batch, world, groups, totalEntries, entries, Map.copyOf(members),
                List.copyOf(actors), Map.copyOf(bindings));
        phase.largest.accumulateAndGet(info, (old, next) ->
                old == null || next.entries() > old.entries() ? next : old);
        return new Group(phase, info);
    }

    public Summary summary(Kind kind) {
        Phase phase = phase(kind);
        return new Summary(phase.batches.sum(), phase.groups.sum(), phase.largest.get(), phase.slowest.get());
    }

    private Phase phase(Kind kind) { return kind == Kind.ENTITY ? entities : connections; }

    private static final class Phase {
        final LongAdder batches = new LongAdder();
        final LongAdder groups = new LongAdder();
        final AtomicReference<Info> largest = new AtomicReference<>();
        final AtomicReference<Timing> slowest = new AtomicReference<>();
    }

    public static final class Group {
        private final Phase phase;
        private final Info info;

        private Group(Phase phase, Info info) { this.phase = phase; this.info = info; }

        public void complete(long nanos) {
            Timing old = phase.slowest.get();
            while (old == null || nanos > old.nanos()) {
                Timing next = new Timing(info, nanos);
                if (phase.slowest.compareAndSet(old, next)) return;
                old = phase.slowest.get();
            }
        }
    }

    public record Info(long batch, String world, int groups, int totalEntries, int entries, Map<String, Integer> members,
                       List<Integer> actors, Map<String, Integer> bindings) {}
    public record Timing(Info info, long nanos) {}
    public record Summary(long batches, long groups, Info largest, Timing slowest) {}
}
