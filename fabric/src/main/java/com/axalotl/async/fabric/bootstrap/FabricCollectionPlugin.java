package com.axalotl.async.fabric.bootstrap;

import com.axalotl.async.bootstrap.collections.CollectionPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Makes selected, otherwise unmixed classes visible to the standard Mixin application pipeline. */
public final class FabricCollectionPlugin implements IMixinConfigPlugin {
    private static final System.Logger LOGGER = System.getLogger("TickWeave/Collections");
    private CollectionPlan plan;
    private List<String> mixins = List.of();
    private final List<Path> generatedFiles = new ArrayList<>();
    private final List<Path> generatedDirectories = new ArrayList<>();

    @Override public void onLoad(String mixinPackage) {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            List<Path> roots = loader.getAllMods().stream().flatMap(mod -> mod.getRootPaths().stream()).distinct().toList();
            var mappings = loader.getMappingResolver();
            plan = CollectionPlan.scan(roots,
                    mappings.mapClassName("intermediary", "net.minecraft.class_1297").replace('.', '/'),
                    mappings.mapClassName("intermediary", "net.minecraft.class_1320").replace('.', '/'));
            if (plan.targets().isEmpty()) return;
            var launcher = FabricLauncherBase.getLauncher();
            List<String> pendingTargets = new ArrayList<>();
            for (String target : plan.targets()) {
                if (launcher.isClassLoaded(target.replace('/', '.'))) {
                    if (!plan.isScalarCollectionOnly(target))
                        throw new IllegalStateException("Entity/shared-object target was loaded before Mixin preparation: " + target);
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Already-loaded scalar collection class retains its original storage: {0}", target);
                } else pendingTargets.add(target);
            }
            if (pendingTargets.isEmpty()) return;
            Path root = Files.createTempDirectory("tickweave-mixins-").toAbsolutePath().normalize();
            generatedDirectories.add(root);
            Path directory = root;
            for (String segment : mixinPackage.split("\\.")) {
                if (!segment.matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IllegalArgumentException("Invalid generated Mixin package");
                directory = directory.resolve(segment);
                Files.createDirectory(directory);
                generatedDirectories.add(directory);
            }
            List<String> names = new ArrayList<>();
            for (String target : pendingTargets.stream().sorted().toList()) {
                String name = "SharedCollection" + names.size();
                Path file = directory.resolve(name + ".class");
                try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    generatedFiles.add(file);
                    output.write(emptyMixin(mixinPackage.replace('.', '/') + "/" + name, target));
                }
                names.add(name);
            }
            // Loader 0.19.3 exposes code-source addition; no private Knot or Mixin state is replaced.
            launcher.addToClassPath(root, mixinPackage + ".");
            mixins = List.copyOf(names);
            Runtime.getRuntime().addShutdownHook(new Thread(this::removeGeneratedFiles, "TickWeave generated class cleanup"));
        } catch (IOException | RuntimeException failure) {
            removeGeneratedFiles();
            // Mixin config selection catches Exception; a required bootstrap failure must not be silently omitted.
            throw new ExceptionInInitializerError(new IllegalStateException("Cannot prepare TickWeave shared collection protection", failure));
        }
    }

    private static byte[] emptyMixin(String name, String target) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
        var mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        var targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(target));
        targets.visitEnd();
        mixin.visit("remap", false);
        mixin.visit("priority", 1);
        mixin.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void removeGeneratedFiles() {
        for (Path file : generatedFiles) remove(file);
        for (int i = generatedDirectories.size() - 1; i >= 0; i--) remove(generatedDirectories.get(i));
    }

    private static void remove(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException failure) { LOGGER.log(System.Logger.Level.WARNING, "Cannot remove generated Mixin path " + path, failure); }
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return mixins; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { plan.protect(targetClass); }
}
