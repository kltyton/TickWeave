package com.axalotl.async.common.entity.query;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;

/** Immutable ordered section membership, rebuilt only when sections are created or removed. */
public final class SectionQueryIndex<T extends EntityAccess> {
    private final long[] positions;
    private final List<EntitySection<T>> sections;

    public SectionQueryIndex(LongSortedSet positions, Long2ObjectMap<EntitySection<T>> sections) {
        this.positions = positions.toLongArray();
        this.sections = new ArrayList<>(this.positions.length);
        for (long position : this.positions) this.sections.add(sections.get(position));
    }

    public int size() {
        return positions.length;
    }

    public long positionAt(int index) {
        return positions[index];
    }

    public EntitySection<T> sectionAt(int index) {
        return sections.get(index);
    }

    public int lowerBound(long position) {
        int low = 0;
        int high = positions.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (positions[middle] < position) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}
