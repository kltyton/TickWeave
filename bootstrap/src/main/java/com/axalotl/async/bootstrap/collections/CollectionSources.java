package com.axalotl.async.bootstrap.collections;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Reads loader-selected class roots without defining game or mod classes. */
final class CollectionSources {
    private final Map<String, Path> classes = new LinkedHashMap<>();
    private final List<Path> allClasses = new ArrayList<>();
    private final Set<String> ambiguous = new HashSet<>();
    private final Map<String, ClassNode> headers = new HashMap<>();

    CollectionSources(List<Path> roots) throws IOException {
        for (Path root : roots) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String name = root.relativize(path).toString().replace('\\', '/');
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")
                            || name.equals("module-info.class")) continue;
                    name = name.substring(0, name.length() - 6);
                    Path previous = classes.putIfAbsent(name, path);
                    if (previous == null || !previous.equals(path)) allClasses.add(path);
                    if (previous != null && !previous.equals(path)) ambiguous.add(name);
                }
            }
        }
    }

    Set<String> names() { return classes.keySet(); }
    List<Path> paths() { return allClasses; }
    boolean ambiguous(String name) { return ambiguous.contains(name); }

    ClassNode read(String name) throws IOException {
        Path source = classes.get(name);
        if (source == null || ambiguous.contains(name)) return null;
        ClassNode node = read(source);
        return node != null && node.name.equals(name) ? node : null;
    }

    ClassNode read(Path source) throws IOException {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        ClassReader reader = reader(source);
        if (reader == null) return null;
        reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    ClassNode header(String name) throws IOException {
        if (headers.containsKey(name)) return headers.get(name);
        Path source = classes.get(name);
        ClassNode node = null;
        if (source != null && !ambiguous.contains(name)) {
            ClassReader reader = reader(source);
            if (reader != null && reader.getClassName().equals(name)) {
                node = new ClassNode(Opcodes.ASM9);
                reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        headers.put(name, node);
        return node;
    }

    private static ClassReader reader(Path source) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        if (bytes.length < 8) throw new IOException("Truncated classfile: " + source);
        // Check before constructing ClassReader: a loader's ASM may reject newer optional classfiles.
        if ((Byte.toUnsignedInt(bytes[6]) << 8 | Byte.toUnsignedInt(bytes[7])) > Opcodes.V17) return null;
        try { return new ClassReader(bytes); }
        catch (IllegalArgumentException failure) { throw new IOException("Invalid Java 17 classfile: " + source, failure); }
    }

    boolean subtype(String name, String base) throws IOException {
        return subtype(name, base, new HashSet<>());
    }

    private boolean subtype(String name, String base, Set<String> visited) throws IOException {
        if (name == null || !visited.add(name)) return false;
        if (name.equals(base)) return true;
        ClassNode info = header(name);
        if (info == null) return false;
        if (subtype(info.superName, base, visited)) return true;
        for (String parent : info.interfaces) if (subtype(parent, base, visited)) return true;
        return false;
    }
}
