package com.axalotl.async.common.bootstrap;

import com.axalotl.async.common.entity.query.CollisionClassFilter;
import com.axalotl.async.common.entity.query.CollisionClassFilter.Guards;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/** Inspects the methods after all mixins, before trusting the vanilla class-level collision rules. */
public final class CollisionFilterExtension implements IExtension {
    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
    }

    @Override
    public void postApply(ITargetClassContext context) {
        ClassNode node = context.getClassNode();
        markCollisionQueries(node);
        MethodNode target = invokedMethod(node, "tickweave$invokeCanBeCollidedWith");
        MethodNode source = invokedMethod(node, "tickweave$invokeCanCollideWith");
        MethodNode passenger = invokedMethod(node, "tickweave$invokeSameVehicle");
        MethodNode spectator = invokedMethod(node, "tickweave$invokeIsSpectator");
        if (target == null || source == null || passenger == null || spectator == null) return;
        int[] returnFalse = {Opcodes.ICONST_0, Opcodes.IRETURN};
        boolean constantFalse = Arrays.equals(opcodes(target), returnFalse) && Arrays.equals(opcodes(spectator), returnFalse);
        Guards guards = sourceGuards(node, source, target, passenger);
        CollisionClassFilter.configure(node.name, target.name, source.name, spectator.name, constantFalse, guards);
        LogManager.getLogger().debug("Collision class filter: constant target={}, source guards={}", constantFalse, guards);
    }

    private static void markCollisionQueries(ClassNode node) {
        MethodNode collisions = invokedMethod(node, "tickweave$invokeEntityCollisions");
        if (collisions == null) return;
        String entity = Type.getArgumentTypes(collisions.desc)[0].getDescriptor();
        List<AbstractInsnNode> code = instructions(collisions);
        for (int i = 4; i + 1 < code.size(); i++) {
            if (!(code.get(i) instanceof InvokeDynamicInsnNode factory)
                    || !factory.name.equals("test") || !factory.desc.equals("(" + entity + ")Ljava/util/function/Predicate;")
                    || !factory.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                    || !factory.bsm.getName().equals("metafactory") || factory.bsmArgs.length != 3
                    || !(factory.bsmArgs[1] instanceof Handle implementation)
                    || implementation.getTag() != Opcodes.H_INVOKEVIRTUAL
                    || !implementation.getOwner().equals(Type.getType(entity).getInternalName())
                    || !implementation.getDesc().equals("(" + entity + ")Z")
                    || !factory.bsmArgs[0].equals(Type.getMethodType("(Ljava/lang/Object;)Z"))
                    || !factory.bsmArgs[2].equals(Type.getMethodType("(" + entity + ")Z"))) continue;
            if (!(code.get(i - 4) instanceof VarInsnNode receiver) || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 1
                    || code.get(i - 3).getOpcode() != Opcodes.DUP || code.get(i - 1).getOpcode() != Opcodes.POP
                    || !(code.get(i - 2) instanceof MethodInsnNode nullCheck)
                    || nullCheck.getOpcode() != Opcodes.INVOKESTATIC || !nullCheck.owner.equals("java/util/Objects")
                    || !nullCheck.name.equals("requireNonNull") || !nullCheck.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")
                    || !(code.get(i + 1) instanceof MethodInsnNode and)
                    || and.getOpcode() != Opcodes.INVOKEINTERFACE || !and.owner.equals("java/util/function/Predicate")
                    || !and.name.equals("and") || !and.desc.equals("(Ljava/util/function/Predicate;)Ljava/util/function/Predicate;")) continue;
            // Mixin 0.8.5 cannot inject into default interface methods. Mark only this verified bound method reference.
            InsnList wrapper = new InsnList();
            wrapper.add(new VarInsnNode(Opcodes.ALOAD, 1));
            wrapper.add(new LdcInsnNode(implementation.getOwner().replace('/', '.')));
            wrapper.add(new LdcInsnNode(implementation.getName()));
            wrapper.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/axalotl/async/common/entity/query/CollisionQuery", "and",
                    "(Ljava/util/function/Predicate;Ljava/util/function/Predicate;" + entity
                            + "Ljava/lang/String;Ljava/lang/String;)Ljava/util/function/Predicate;", false));
            collisions.instructions.insertBefore(and, wrapper);
            collisions.instructions.remove(and);
            collisions.maxStack += 3;
        }
    }

    private static MethodNode invokedMethod(ClassNode node, String bridge) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(bridge)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                    return node.methods.stream().filter(target -> target.name.equals(call.name)
                            && target.desc.equals(call.desc)).findFirst().orElse(null);
                }
            }
        }
        return null;
    }

    private static int[] opcodes(MethodNode method) {
        if ((method.access & Opcodes.ACC_SYNCHRONIZED) != 0 || !method.tryCatchBlocks.isEmpty()) return new int[0];
        return instructions(method).stream().mapToInt(AbstractInsnNode::getOpcode).toArray();
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        return BytecodeInstructions.opcodes(method);
    }

    private static Guards sourceGuards(ClassNode owner, MethodNode source, MethodNode target, MethodNode passenger) {
        int[] expected = {Opcodes.ALOAD, Opcodes.INVOKEVIRTUAL, Opcodes.IFEQ,
                Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.INVOKEVIRTUAL, Opcodes.IFNE,
                Opcodes.ICONST_1, Opcodes.GOTO, Opcodes.ICONST_0, Opcodes.IRETURN};
        int[] actual = opcodes(source);
        if (actual.length < expected.length) return null;
        int start = actual.length - expected.length;
        if (!Arrays.equals(Arrays.copyOfRange(actual, start, actual.length), expected)) return null;
        List<AbstractInsnNode> code = instructions(source).subList(start, actual.length);
        boolean tailMatches = ((VarInsnNode) code.get(0)).var == 1
                && ((VarInsnNode) code.get(3)).var == 0 && ((VarInsnNode) code.get(4)).var == 1
                && calls(code.get(1), owner.name, target) && calls(code.get(5), owner.name, passenger)
                && jumpsTo(code.get(2), code.get(9)) && jumpsTo(code.get(6), code.get(9))
                && jumpsTo(code.get(8), code.get(10));
        return tailMatches ? CollisionGuards.prove(owner, source, start) : null;
    }

    private static boolean calls(AbstractInsnNode instruction, String owner, MethodNode method) {
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.owner.equals(owner) && call.name.equals(method.name) && call.desc.equals(method.desc);
    }

    private static boolean jumpsTo(AbstractInsnNode instruction, AbstractInsnNode expected) {
        AbstractInsnNode target = ((JumpInsnNode) instruction).label;
        while (target != null && target.getOpcode() < 0) target = target.getNext();
        return target == expected;
    }

    @Override
    public void export(MixinEnvironment environment, String name, boolean force, ClassNode classNode) {
    }
}
