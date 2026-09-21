/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.commands.CommandSourceStack
 *  net.minecraft.resources.ResourceLocation
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.axalotl.async.common.config;

import com.axalotl.async.common.commands.AsyncCommand;
import com.axalotl.async.common.ParallelProcessor;
import java.util.Locale;
import com.axalotl.async.common.parallelised.utils.ModCompatibility;
import com.axalotl.async.common.platform.PlatformUtils;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncConfig {
    public static final Logger LOGGER = LoggerFactory.getLogger((String)"Async Config");
    public static Map.Entry<String, Boolean> disabled = new AbstractMap.SimpleEntry<String, Boolean>("disabled", false);
    public static Map.Entry<String, Integer> maxThreads = new AbstractMap.SimpleEntry<String, Integer>("paraMax", -1);
    public static Map.Entry<String, Boolean> enableAsyncSpawn = new AbstractMap.SimpleEntry<String, Boolean>("enableAsyncSpawn", true);
    public static Map.Entry<String, Boolean> enableAsyncRandomTicks = new AbstractMap.SimpleEntry<String, Boolean>("enableAsyncRandomTicks", false);
    public static Map.Entry<String, Boolean> enableAffinityRouting = new AbstractMap.SimpleEntry<String, Boolean>("enableAffinityRouting", true);
    public static Map.Entry<String, Boolean> enableCircuitBreaker = new AbstractMap.SimpleEntry<String, Boolean>("enableCircuitBreaker", true);
    public static Map.Entry<String, Integer> entitiesPerWorker = new AbstractMap.SimpleEntry<String, Integer>("entitiesPerWorker", 25);
    public static Map.Entry<String, Integer> staleTaskTimeoutMs = new AbstractMap.SimpleEntry<String, Integer>("staleTaskTimeoutMs", 200);
    public static Map.Entry<String, Set<String>> synchronizedEntities = new AbstractMap.SimpleEntry<String, Set<String>>("synchronizedEntities", AsyncConfig.getDefaultSynchronizedEntities());
    private record SyncRules(Set<String> exact, Set<String> namespaces, Map<ResourceLocation, Boolean> cache) {}
    private static volatile SyncRules rules = new SyncRules(Set.of(), Set.of(), new ConcurrentHashMap<>());

    public static Set<String> getDefaultSynchronizedEntities() {
        HashSet<String> defaultSynchronizedEntities = new HashSet<String>(ModCompatibility.addUnsupportedMods());
        defaultSynchronizedEntities.addAll(Set.of("minecraft:tnt", "minecraft:item", "minecraft:experience_orb", "minecraft:creeper", "minecraft:wither", "minecraft:end_crystal", "minecraft:ghast"));
        return defaultSynchronizedEntities;
    }

    public static int getParallelism() {
        int cores = Runtime.getRuntime().availableProcessors();
        if (maxThreads.getValue() > 0) return Math.max(1, Math.min(cores, maxThreads.getValue()));
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        int threads = (int) (cores / (windows ? 1.6 : 1.3));
        if (ParallelProcessor.getServer() != null && !ParallelProcessor.getServer().isDedicatedServer()) threads--;
        return Math.max(1, threads);
    }

    public static boolean isNamespaceWildcard(String input) {
        if (input == null) {
            return false;
        }
        int colon = input.indexOf(58);
        if (colon <= 0) {
            return false;
        }
        return input.substring(colon + 1).equals("*");
    }

    public static boolean existsNamespace(String namespace, CommandSourceStack source) {
        for (ResourceLocation id : AsyncCommand.getEntityAccess(source).keySet()) {
            if (!id.getNamespace().equals(namespace)) continue;
            return true;
        }
        return false;
    }

    public static boolean matchesExistingNamespaceWildcard(String input, CommandSourceStack source) {
        if (!AsyncConfig.isNamespaceWildcard(input)) {
            return false;
        }
        String ns = input.substring(0, input.indexOf(58));
        return AsyncConfig.existsNamespace(ns, source);
    }

    public static void syncEntity(String entity) {
        if (synchronizedEntities.getValue().add(entity)) {
            AsyncConfig.rebuildCaches();
            PlatformUtils.saveConfig();
            LOGGER.info("Added sync entity: {}", (Object)entity);
        } else {
            LOGGER.warn("Entity already synchronized: {}", (Object)entity);
        }
    }

    public static void removeEntity(String entity) {
        if (synchronizedEntities.getValue().remove(entity)) {
            AsyncConfig.rebuildCaches();
            PlatformUtils.saveConfig();
            LOGGER.info("Removed sync entity: {}", (Object)entity);
        } else {
            LOGGER.warn("Entity not found: {}", (Object)entity);
        }
    }

    private static void rebuildCaches() {
        Set<String> exact = new HashSet<>();
        Set<String> namespaces = new HashSet<>();
        for (String entry : synchronizedEntities.getValue()) {
            if (isNamespaceWildcard(entry)) namespaces.add(entry.substring(0, entry.indexOf(':')));
            else exact.add(entry);
        }
        rules = new SyncRules(Set.copyOf(exact), Set.copyOf(namespaces), new ConcurrentHashMap<>());
    }

    public static boolean isEntitySynchronized(ResourceLocation entityId) {
        SyncRules snapshot = rules;
        return snapshot.cache().computeIfAbsent(entityId,
                id -> snapshot.exact().contains(id.toString()) || snapshot.namespaces().contains(id.getNamespace()));
    }

    public static void onConfigLoaded() {
        AsyncConfig.rebuildCaches();
        LOGGER.info("Configuration loaded. All entity types use worker scheduling; legacy synchronizedEntities entries are retained but inactive.");
    }

    public static void clearCaches() {
        rules = new SyncRules(Set.of(), Set.of(), new ConcurrentHashMap<>());
    }
}

