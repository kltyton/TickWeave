package com.axalotl.async.bootstrap.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Rewrites the exact adjacent native field chain
 * {@code ServerPlayer.connection -> ServerGamePacketListenerImpl.connection} into a public common
 * resolver call. This lets {@code SimpleChannel.sendTo} keep executing and encoding normally while
 * the second participant connection resolves to the real login transport before the listener is
 * attached.
 *
 * <p>Typed fields are discovered from the two mapped native class headers, so no mod name and no
 * literal field name participates in matching. Only strictly
 * adjacent {@code GETFIELD} pairs are rewritten; control-flow/frame-intersecting chains are left
 * untouched. This plan owns no loader state and never triggers a second class scan.</p>
 */
public final class PlayerConnectionPlan {
    private static final String RESOLVER = "com/axalotl/async/common/network/login/PlayerConnectionResolver";
    private static final String HELPER_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

    private final String serverPlayerName;
    private final String listenerName;
    private final String connectionName;
    private final Set<String> targets = new HashSet<>();
    private final String serverPlayerField;
    private final String listenerField;

    public PlayerConnectionPlan(List<Path> roots, String serverPlayerName, String listenerName,
                                String connectionName) throws IOException {
        this.serverPlayerName = serverPlayerName;
        this.listenerName = listenerName;
        this.connectionName = connectionName;
        serverPlayerField = uniqueField(header(roots, serverPlayerName), "L" + listenerName + ";");
        listenerField = uniqueField(header(roots, listenerName), "L" + connectionName + ";");
    }

    /** Records rewrite targets during the existing shared class scan. */
    public Consumer<ClassNode> observer() {
        return this::inspect;
    }

    public Set<String> targets() { return Set.copyOf(targets); }

    public void inspect(ClassNode node) {
        if (node.name.equals(RESOLVER)) return;
        for (MethodNode method : node.methods) {
            if (matches(method)) {
                targets.add(node.name);
                return;
            }
        }
    }

    public int protect(ClassNode node) {
        if (node.name.equals(RESOLVER)) return 0;
        int rewritten = 0;
        for (MethodNode method : node.methods) rewritten += rewrite(method);
        return rewritten;
    }

    private static ClassNode header(List<Path> roots, String name) throws IOException {
        byte[] bytes = null;
        for (Path root : roots) {
            Path file = root.resolve(name + ".class");
            if (!Files.isRegularFile(file)) continue;
            byte[] found = Files.readAllBytes(file);
            if (bytes != null && !Arrays.equals(bytes, found))
                throw new IOException("Ambiguous native connection class: " + name);
            bytes = found;
        }
        if (bytes == null) throw new IOException("Missing native connection class: " + name);
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!node.name.equals(name)) throw new IOException("Mismatched native connection class: " + name);
        return node;
    }

    private static String uniqueField(ClassNode owner, String descriptor) {
        String found = null;
        for (FieldNode field : owner.fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0 || !field.desc.equals(descriptor)) continue;
            if (found != null) throw new IllegalStateException("Ambiguous connection field: " + owner.name + descriptor);
            found = field.name;
        }
        if (found == null) throw new IllegalStateException("Missing connection field: " + owner.name + descriptor);
        return found;
    }

    private boolean matches(MethodNode method) {
        for (AbstractInsnNode first = method.instructions.getFirst(); first != null; first = first.getNext()) {
            if (isChain(first, first.getNext())) return true;
        }
        return false;
    }

    private int rewrite(MethodNode method) {
        int count = 0;
        AbstractInsnNode first = method.instructions.getFirst();
        while (first != null) {
            AbstractInsnNode second = first.getNext();
            if (!isChain(first, second)) {
                first = second;
                continue;
            }
            AbstractInsnNode after = second.getNext();
            method.instructions.insertBefore(first, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    RESOLVER, "connectionFor", HELPER_DESC, false));
            method.instructions.insertBefore(first, new TypeInsnNode(Opcodes.CHECKCAST, connectionName));
            method.instructions.remove(first);
            method.instructions.remove(second);
            count++;
            first = after;
        }
        return count;
    }

    private boolean isChain(AbstractInsnNode first, AbstractInsnNode second) {
        if (!(first instanceof FieldInsnNode readA) || !(second instanceof FieldInsnNode readB)) return false;
        return readA.getOpcode() == Opcodes.GETFIELD && readB.getOpcode() == Opcodes.GETFIELD
                && readA.owner.equals(serverPlayerName) && readA.name.equals(serverPlayerField)
                && readA.desc.equals("L" + listenerName + ";")
                && readB.owner.equals(listenerName) && readB.name.equals(listenerField)
                && readB.desc.equals("L" + connectionName + ";");
    }
}
