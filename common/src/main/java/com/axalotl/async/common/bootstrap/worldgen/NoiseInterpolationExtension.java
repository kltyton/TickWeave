package com.axalotl.async.common.bootstrap.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/** Replaces only pure interpolation traversals after ordinary and late Mixin injections. */
public final class NoiseInterpolationExtension implements IExtension {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String STATE = "com/axalotl/async/common/worldgen/NoiseInterpolationState";
    private static final ConcurrentHashMap<String, String> PURE_UPDATES = new ConcurrentHashMap<>();

    public static boolean isPureZ(Class<?> type, String updateMethod) {
        return updateMethod.equals(PURE_UPDATES.get(type.getName()));
    }

    @Override public boolean checkActive(MixinEnvironment environment) { return true; }
    @Override public void preApply(ITargetClassContext context) { }

    @Override
    public void postApply(ITargetClassContext context) {
        ClassNode node = context.getClassNode();
        if (node.interfaces.contains(STATE + "$Reader")) {
            MethodNode update = invokedMethod(node, "(D)V");
            boolean pure = pureUpdate(node, update);
            if (pure) PURE_UPDATES.put(node.name.replace('/', '.'), update.name);
            else PURE_UPDATES.remove(node.name.replace('/', '.'));
            LOGGER.debug("Z interpolation updater {}.{} is pure: {}", node.name, update.name, pure);
        }
        if (node.interfaces.contains(STATE)) deferTraversal(node);
    }

    private static MethodNode invokedMethod(ClassNode node, String descriptor) {
        MethodNode bridge = method(node, "tickweave$invokeUpdateForZ", descriptor);
        if (bridge != null) for (AbstractInsnNode instruction : bridge.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)
                    && call.desc.equals(descriptor)) return method(node, call.name, call.desc);
        }
        throw new IllegalStateException("Missing interpolation method bridge in " + node.name);
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                .findFirst().orElse(null);
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    private static boolean variable(AbstractInsnNode instruction, int opcode, int slot) {
        return instruction instanceof VarInsnNode value && value.getOpcode() == opcode && value.var == slot;
    }

    private static boolean call(AbstractInsnNode instruction, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode value && value.owner.equals(owner)
                && value.name.equals(name) && value.desc.equals(descriptor);
    }

    private static boolean sameField(AbstractInsnNode instruction, int opcode, FieldInsnNode field) {
        return instruction instanceof FieldInsnNode value && value.getOpcode() == opcode
                && value.owner.equals(field.owner) && value.name.equals(field.name) && value.desc.equals(field.desc);
    }

    private static boolean pureUpdate(ClassNode node, MethodNode update) {
        MethodNode resolver = method(node, "tickweave$resolveZ", "()V");
        if (resolver == null || (update.access & Opcodes.ACC_SYNCHRONIZED) != 0 || !update.tryCatchBlocks.isEmpty()) return false;
        List<FieldInsnNode> coefficients = new ArrayList<>();
        FieldInsnNode value = null;
        MethodInsnNode lerp = null;
        for (AbstractInsnNode instruction : resolver.instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals(node.name) && field.desc.equals("D")) {
                if (field.getOpcode() == Opcodes.GETFIELD) coefficients.add(field);
                else if (field.getOpcode() == Opcodes.PUTFIELD) value = field;
            }
            if (instruction instanceof MethodInsnNode invocation && invocation.getOpcode() == Opcodes.INVOKESTATIC
                    && invocation.desc.equals("(DDD)D")) lerp = invocation;
        }
        List<AbstractInsnNode> body = code(update);
        if (coefficients.size() != 2 || value == null || lerp == null || body.size() < 9
                || !variable(body.get(0), Opcodes.ALOAD, 0) || !variable(body.get(1), Opcodes.DLOAD, 1)
                || !variable(body.get(2), Opcodes.ALOAD, 0) || !sameField(body.get(3), Opcodes.GETFIELD, coefficients.get(0))
                || !variable(body.get(4), Opcodes.ALOAD, 0) || !sameField(body.get(5), Opcodes.GETFIELD, coefficients.get(1))
                || !call(body.get(6), lerp.owner, lerp.name, lerp.desc)
                || !sameField(body.get(7), Opcodes.PUTFIELD, value)) return false;
        int recorded = 0;
        for (AbstractInsnNode instruction : body.subList(8, body.size())) {
            if (instruction instanceof MethodInsnNode invocation) {
                if (invocation.owner.equals(node.name) && invocation.name.contains("tickweave$recordExplicitUpdate")
                        && pureRevisionRecorder(node, method(node, invocation.name, invocation.desc))) recorded++;
                else if (!call(invocation, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "<init>",
                        "(Ljava/lang/String;Z)V")) return false;
            } else if (instruction instanceof TypeInsnNode type) {
                if (type.getOpcode() != Opcodes.NEW
                        || !type.desc.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")) return false;
            } else if (instruction instanceof VarInsnNode local) {
                if (local.getOpcode() != Opcodes.ALOAD && local.getOpcode() != Opcodes.ASTORE
                        && !variable(local, Opcodes.DLOAD, 1)) return false;
            } else if (instruction.getOpcode() != Opcodes.DUP && instruction.getOpcode() != Opcodes.LDC
                    && instruction.getOpcode() != Opcodes.ICONST_0 && instruction.getOpcode() != Opcodes.ACONST_NULL
                    && instruction.getOpcode() != Opcodes.RETURN) return false;
        }
        if (recorded != 1) return false;
        for (MethodNode reader : node.methods) for (AbstractInsnNode instruction : reader.instructions) {
            if (!sameField(instruction, Opcodes.GETFIELD, value)) continue;
            AbstractInsnNode previous = instruction.getPrevious();
            while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
            if (!(previous instanceof MethodInsnNode callback) || !callback.owner.equals(node.name)
                    || !callback.name.contains("tickweave$readInterpolatedValue")) return false;
        }
        return true;
    }

    private static boolean pureRevisionRecorder(ClassNode node, MethodNode recorder) {
        if (recorder == null || !recorder.tryCatchBlocks.isEmpty()) return false;
        List<AbstractInsnNode> body = code(recorder);
        return body.size() == 6 && variable(body.get(0), Opcodes.ALOAD, 0) && variable(body.get(1), Opcodes.ALOAD, 0)
                && body.get(2) instanceof FieldInsnNode owner && owner.getOpcode() == Opcodes.GETFIELD
                && owner.owner.equals(node.name) && owner.desc.equals("L" + STATE + ";")
                && call(body.get(3), STATE, "tickweave$zRevision", "()J")
                && body.get(4) instanceof FieldInsnNode revision && revision.getOpcode() == Opcodes.PUTFIELD
                && revision.owner.equals(node.name) && revision.name.contains("tickweave$zRevision")
                && revision.desc.equals("J") && body.get(5).getOpcode() == Opcodes.RETURN;
    }

    private static void deferTraversal(ClassNode node) {
        MethodNode target = invokedMethod(node, "(ID)V");
        MethodNode gate = method(node, "tickweave$deferZ", "(DLjava/lang/String;)Z");
        FieldInsnNode list = null;
        String interpolator = null;
        for (AbstractInsnNode instruction : gate.instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals(node.name)
                    && field.desc.equals("Ljava/util/List;")) list = field;
            if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Type type
                    && type.getSort() == Type.OBJECT && !type.getInternalName().equals("java/util/ArrayList")) {
                interpolator = type.getInternalName();
            }
        }
        if (list == null || interpolator == null) throw new IllegalStateException("Missing interpolation traversal metadata");
        List<AbstractInsnNode> body = code(target);
        for (int i = 0; i < body.size(); i++) {
            if (i >= 4 && call(body.get(i), "java/util/List", "forEach", "(Ljava/util/function/Consumer;)V")
                    && variable(body.get(i - 4), Opcodes.ALOAD, 0)
                    && sameField(body.get(i - 3), Opcodes.GETFIELD, list)
                    && variable(body.get(i - 2), Opcodes.DLOAD, 2)
                    && body.get(i - 1) instanceof InvokeDynamicInsnNode factory) {
                String update = consumerUpdate(node, factory, interpolator);
                if (update == null) continue;
                closedRegion(target, body.get(i - 4), body.get(i), null);
                LabelNode after = new LabelNode();
                target.instructions.insert(body.get(i), after);
                InsnList guard = guard(node.name, update);
                guard.add(new JumpInsnNode(Opcodes.IFNE, after));
                target.instructions.insertBefore(body.get(i - 4), guard);
                finish(target);
                LOGGER.debug("Bound lazy Z interpolation in {}.{}", node.name, target.name);
                return;
            }
            if (i < 16 || i + 3 >= body.size() || !(body.get(i) instanceof MethodInsnNode update)
                    || !update.owner.equals(interpolator) || !update.desc.equals("(D)V")) continue;
            if (!(body.get(i - 5) instanceof VarInsnNode listLocal) || listLocal.getOpcode() != Opcodes.ALOAD
                    || !(body.get(i - 4) instanceof VarInsnNode index) || index.getOpcode() != Opcodes.ILOAD
                    || !call(body.get(i - 3), "java/util/List", "get", "(I)Ljava/lang/Object;")
                    || !(body.get(i - 2) instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST
                    || !cast.desc.equals(interpolator) || !variable(body.get(i - 1), Opcodes.DLOAD, 2)
                    || !(body.get(i - 6) instanceof JumpInsnNode exit) || exit.getOpcode() != Opcodes.IF_ICMPGE
                    || !(body.get(i - 7) instanceof VarInsnNode size) || size.getOpcode() != Opcodes.ILOAD
                    || !variable(body.get(i - 8), Opcodes.ILOAD, index.var)
                    || !variable(body.get(i - 9), Opcodes.ISTORE, size.var)
                    || !call(body.get(i - 10), "java/util/List", "size", "()I")
                    || !variable(body.get(i - 11), Opcodes.ALOAD, listLocal.var)
                    || !variable(body.get(i - 12), Opcodes.ISTORE, index.var)
                    || body.get(i - 13).getOpcode() != Opcodes.ICONST_0
                    || !variable(body.get(i - 14), Opcodes.ASTORE, listLocal.var)
                    || !sameField(body.get(i - 15), Opcodes.GETFIELD, list)
                    || !variable(body.get(i - 16), Opcodes.ALOAD, 0)
                    || !(body.get(i + 1) instanceof IincInsnNode increment) || increment.var != index.var || increment.incr != 1
                    || !(body.get(i + 2) instanceof JumpInsnNode back) || back.getOpcode() != Opcodes.GOTO
                    || next(back.label) != body.get(i - 8) || next(exit.label) != body.get(i + 3)) continue;
            closedRegion(target, body.get(i - 16), body.get(i + 2), back);
            InsnList guard = guard(node.name, update.name);
            guard.add(new JumpInsnNode(Opcodes.IFEQ, back.label));
            guard.add(new VarInsnNode(Opcodes.ILOAD, size.var));
            guard.add(new VarInsnNode(Opcodes.ISTORE, index.var));
            guard.add(new JumpInsnNode(Opcodes.GOTO, exit.label));
            target.instructions.insertBefore(back.label, guard);
            finish(target);
            LOGGER.debug("Bound lazy Z interpolation in {}.{}", node.name, target.name);
            return;
        }
        throw new IllegalStateException("Unrecognized Z interpolation traversal in " + node.name + "." + target.name);
    }

    private static String consumerUpdate(ClassNode node, InvokeDynamicInsnNode factory, String interpolator) {
        if (!factory.desc.equals("(D)Ljava/util/function/Consumer;")
                || !factory.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                || !factory.bsm.getName().equals("metafactory") || factory.bsmArgs.length != 3
                || !(factory.bsmArgs[1] instanceof Handle implementation)
                || implementation.getTag() != Opcodes.H_INVOKESTATIC || !implementation.getOwner().equals(node.name)
                || !implementation.getDesc().equals("(DL" + interpolator + ";)V")) return null;
        MethodNode method = method(node, implementation.getName(), implementation.getDesc());
        if (method == null || !method.tryCatchBlocks.isEmpty()) return null;
        List<AbstractInsnNode> body = code(method);
        return body.size() == 4 && variable(body.get(0), Opcodes.ALOAD, 2)
                && variable(body.get(1), Opcodes.DLOAD, 0)
                && body.get(2) instanceof MethodInsnNode update && update.owner.equals(interpolator)
                && update.desc.equals("(D)V") && body.get(3).getOpcode() == Opcodes.RETURN ? update.name : null;
    }

    private static InsnList guard(String owner, String update) {
        InsnList result = new InsnList();
        result.add(new VarInsnNode(Opcodes.ALOAD, 0));
        result.add(new VarInsnNode(Opcodes.DLOAD, 2));
        result.add(new LdcInsnNode(update));
        result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "tickweave$deferZ", "(DLjava/lang/String;)Z", false));
        return result;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getNext();
        return instruction;
    }

    private static void closedRegion(MethodNode method, AbstractInsnNode first, AbstractInsnNode last, JumpInsnNode back) {
        int start = method.instructions.indexOf(first);
        int end = method.instructions.indexOf(last);
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump != back && inside(method, jump.label, start, end)) {
                throw new IllegalStateException("Branch enters Z interpolation traversal");
            }
            if (instruction instanceof TableSwitchInsnNode table
                    && (inside(method, table.dflt, start, end) || table.labels.stream().anyMatch(l -> inside(method, l, start, end)))
                    || instruction instanceof LookupSwitchInsnNode lookup
                    && (inside(method, lookup.dflt, start, end) || lookup.labels.stream().anyMatch(l -> inside(method, l, start, end)))) {
                throw new IllegalStateException("Switch enters Z interpolation traversal");
            }
        }
        for (var handler : method.tryCatchBlocks) {
            if (inside(method, handler.start, start, end) || inside(method, handler.end, start, end)
                    || inside(method, handler.handler, start, end)) throw new IllegalStateException("Split interpolation exception region");
        }
    }

    private static boolean inside(MethodNode method, LabelNode label, int start, int end) {
        int index = method.instructions.indexOf(label);
        return index >= start && index <= end;
    }

    private static void finish(MethodNode method) {
        // Mixin 0.8.5 recomputes frames and maxima when writing the final class.
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode) method.instructions.remove(instruction);
        }
        method.maxStack = Math.max(method.maxStack, 6);
    }

    @Override public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) { }
}
