package com.axalotl.async.bootstrap.forge;

import com.axalotl.async.bootstrap.collections.CollectionPlan;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModProvider;
import net.minecraftforge.forgespi.locating.IDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import org.objectweb.asm.tree.ClassNode;

/** Keeps early class transformation separate from the normal Forge mod's game-layer classes. */
public final class ForgeCollectionBootstrap extends AbstractJarFileModProvider
        implements ITransformationService, IDependencyLocator {
    private FileSystem runtimeArchive;

    @Override public String name() { return "tickweave"; }
    @Override public void initialize(IEnvironment environment) {}
    @Override public void onLoad(IEnvironment environment, Set<String> otherServices) {}
    @Override public void initArguments(Map<String, ?> arguments) {}

    @Override public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
        for (IModFile file : loadedMods) {
            var metadata = file.getModFileInfo();
            if (metadata != null && metadata.getMods().stream().anyMatch(mod -> mod.getModId().equals("tickweave")))
                throw new IllegalStateException("Multiple TickWeave installations: " + file.getFilePath());
        }
        try {
            Path moduleRoot = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            Path nested = moduleRoot.resolve("tickweave-runtime.jar");
            if (!Files.isRegularFile(nested)) throw new IOException("Missing TickWeave runtime archive: " + nested);
            URI address = URI.create("jij:" + nested.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize();
            runtimeArchive = FileSystems.newFileSystem(address, Map.of("packagePath", nested));
            var located = createMod(runtimeArchive.getPath("/"));
            if (located.file() == null) throw new IllegalStateException("Cannot read TickWeave runtime metadata", located.ex());
            return List.of(located.file());
        } catch (IOException | URISyntaxException | RuntimeException failure) {
            if (runtimeArchive != null) {
                try { runtimeArchive.close(); }
                catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw new IllegalStateException("Cannot discover the packaged TickWeave runtime", failure);
        }
    }

    @Override public List<ITransformer> transformers() {
        // ModLauncher registers these after all completeScan callbacks and before creating the game class loader.
        var mods = FMLLoader.getLoadingModList();
        if (mods == null) throw new IllegalStateException("Forge mod discovery is incomplete during transformer registration");
        List<Path> roots = mods.getModFiles().stream().map(info -> info.getFile().getSecureJar().getRootPath()).distinct().toList();
        try {
            CollectionPlan plan = CollectionPlan.scan(roots, "net/minecraft/world/entity/Entity",
                    "net/minecraft/world/entity/ai/attributes/Attribute");
            return plan.targets().isEmpty() ? List.of() : List.of(new PlannedTransformer(plan));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect Forge shared collection access", failure);
        }
    }

    private static final class PlannedTransformer implements ITransformer<ClassNode> {
        private final CollectionPlan plan;
        private final Set<Target> targets;
        PlannedTransformer(CollectionPlan plan) {
            this.plan = plan;
            this.targets = plan.targets().stream().map(name -> Target.targetClass(name.replace('/', '.'))).collect(Collectors.toUnmodifiableSet());
        }
        @Override public ClassNode transform(ClassNode input, ITransformerVotingContext context) { plan.protect(input); return input; }
        @Override public TransformerVoteResult castVote(ITransformerVotingContext context) { return TransformerVoteResult.YES; }
        @Override public Set<Target> targets() { return targets; }
    }
}
