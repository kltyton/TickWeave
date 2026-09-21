package com.axalotl.async.fabric.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.ArrayList;

import static com.axalotl.async.common.config.AsyncConfig.*;

public class AsyncConfigFabric {
    private static final Supplier<CommentedFileConfig> configSupplier = () -> CommentedFileConfig
            .builder(FabricLoader.getInstance().getConfigDir().resolve("tickweave.toml"))
            .preserveInsertionOrder()
            .sync()
            .build();

    private static CommentedFileConfig CONFIG;

    public static void init() {
        LOGGER.info("Initializing TickWeave Config...");
        CONFIG = configSupplier.get();
        try {
            if (!CONFIG.getFile().exists()) {
                LOGGER.warn("Configuration file not found, creating default configuration.");
                setDefaultValues();
                saveConfig();
            } else {
                CONFIG.load();
                loadConfigValues();
                LOGGER.info("Configuration successfully loaded.");
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Cannot load tickweave.toml; the existing file has been preserved", failure);
        }
    }

    public static void saveConfig() {
        CONFIG.set("disabled", disabled.getValue());
        CONFIG.setComment("disabled",
                "Globally disable all toggleable functionality within the async system. Set to true to stop all asynchronous operations.");

        CONFIG.set("paraMax", maxThreads.getValue());
        CONFIG.setComment("paraMax",
                "Maximum worker threads. -1 = automatic platform-aware selection.");

        CONFIG.set("synchronizedEntities", new ArrayList<>(synchronizedEntities.getValue()));
        CONFIG.setComment("synchronizedEntities", "Legacy entries retained for migration; inactive with all-entity worker scheduling.");

        CONFIG.set("enableAsyncSpawn", enableAsyncSpawn.getValue());
        CONFIG.setComment("enableAsyncSpawn",
                "Enables parallel processing of entity spawns. Warning, incompatible with Carpet mod lagFreeSpawning rule.");

        CONFIG.set("enableAsyncRandomTicks", enableAsyncRandomTicks.getValue());
        CONFIG.setComment("enableAsyncRandomTicks",
                "Experimental! Enables async random ticks.");

        CONFIG.set("enableAffinityRouting", enableAffinityRouting.getValue());
        CONFIG.setComment("enableAffinityRouting", "Legacy setting retained for migration; entity ownership now determines scheduling.");
        CONFIG.set("enableCircuitBreaker", enableCircuitBreaker.getValue());
        CONFIG.setComment("enableCircuitBreaker", "Collect entity failure diagnostics; does not change worker eligibility.");
        CONFIG.set("entitiesPerWorker", entitiesPerWorker.getValue());
        CONFIG.setComment("entitiesPerWorker", "Maximum entities per task; actual batch size adapts to measured cost. Default: 25.");
        CONFIG.set("staleTaskTimeoutMs", staleTaskTimeoutMs.getValue());
        CONFIG.setComment("staleTaskTimeoutMs", "Warn about slow batches after this many milliseconds; never cancels a running tick.");

        CONFIG.save();
        onConfigLoaded();
        LOGGER.info("Configuration saved successfully.");
    }

    public static void loadConfig() {
        if (CONFIG == null) {
            init();
            return;
        }
        try {
            CONFIG.load();
            loadConfigValues();
            LOGGER.info("Configuration reloaded successfully.");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Cannot reload tickweave.toml; the existing file has been preserved", failure);
        }
    }

    private static void loadConfigValues() {
        disabled.setValue(CONFIG.getOrElse("disabled", disabled.getValue()));
        maxThreads.setValue(CONFIG.getOrElse("paraMax", maxThreads.getValue()));
        enableAsyncSpawn.setValue(CONFIG.getOrElse("enableAsyncSpawn", enableAsyncSpawn.getValue()));
        enableAsyncRandomTicks.setValue(CONFIG.getOrElse("enableAsyncRandomTicks", enableAsyncRandomTicks.getValue()));
        enableAffinityRouting.setValue(CONFIG.getOrElse("enableAffinityRouting", true));
        enableCircuitBreaker.setValue(CONFIG.getOrElse("enableCircuitBreaker", true));
        entitiesPerWorker.setValue(CONFIG.getOrElse("entitiesPerWorker", 25));
        staleTaskTimeoutMs.setValue(CONFIG.getOrElse("staleTaskTimeoutMs", 200));

        Set<String> entities = new HashSet<>();
        CONFIG.<List<String>>getOptional("synchronizedEntities").ifPresentOrElse(ids -> {
            for (String id : ids) {
                entities.add(id);
            }
        }, () -> entities.addAll(getDefaultSynchronizedEntities()));

        synchronizedEntities.setValue(entities);

        onConfigLoaded();
    }

    private static void setDefaultValues() {
        disabled.setValue(false);
        maxThreads.setValue(-1);
        enableAsyncSpawn.setValue(true);
        enableAsyncRandomTicks.setValue(false);
        enableAffinityRouting.setValue(true);
        enableCircuitBreaker.setValue(true);
        entitiesPerWorker.setValue(25);
        staleTaskTimeoutMs.setValue(200);
        synchronizedEntities.setValue(getDefaultSynchronizedEntities());
    }
}
