package com.axalotl.async.common.entity.query;

import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.entity.EntityTypeTest;

public final class CollisionQuery {
    public static final EntityTypeTest<Entity, Entity> TYPE = new EntityTypeTest<>() {
        @Override
        public Entity tryCast(Entity entity) {
            return entity;
        }

        @Override
        public Class<? extends Entity> getBaseClass() {
            return Entity.class;
        }
    };

    private CollisionQuery() {
    }

    public static Predicate<Entity> and(Predicate<Entity> left, Predicate<Entity> right, Entity source,
                                        String owner, String method) {
        Predicate<Entity> result = left.and(right);
        if (left == EntitySelector.NO_SPECTATORS && CollisionClassFilter.isSourceReference(owner, method)
                && CollisionClassFilter.canFilter(source.getClass())) {
            return new Filter(result);
        }
        return result;
    }

    public record Filter(Predicate<? super Entity> delegate) implements Predicate<Entity> {
        @Override
        public boolean test(Entity entity) {
            return delegate.test(entity);
        }
    }
}
