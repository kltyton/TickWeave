/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.Holder
 *  net.minecraft.world.entity.ai.attributes.Attribute
 *  net.minecraft.world.entity.ai.attributes.AttributeInstance
 *  net.minecraft.world.entity.ai.attributes.AttributeMap
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Shadow
 */
package com.axalotl.async.common.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps attribute storage concurrent without locking buckets for existing attribute reads. */
@Mixin(value={AttributeMap.class})
public class AttributeMapMixin {
    @Shadow @Final @Mutable
    private Set<AttributeInstance> dirtyAttributes;
    @Shadow @Final @Mutable
    private Map<Attribute, AttributeInstance> attributes;

    @Inject(method="<init>", at=@At("RETURN"))
    private void async$init(CallbackInfo ci) {
        Set<AttributeInstance> concurrent = ConcurrentHashMap.newKeySet();
        concurrent.addAll(dirtyAttributes);
        dirtyAttributes = concurrent;
        attributes = new ConcurrentHashMap<>(attributes);
    }

    @WrapOperation(method = "getInstance(Lnet/minecraft/world/entity/ai/attributes/Attribute;)Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object tickweave$existingAttribute(Map<?, ?> map, Object key,
                                              Function<?, ?> factory, Operation<Object> original) {
        if (map.getClass() == ConcurrentHashMap.class) {
            Object existing = map.get(key);
            if (existing != null) return existing;
        }
        return original.call(map, key, factory);
    }
}

