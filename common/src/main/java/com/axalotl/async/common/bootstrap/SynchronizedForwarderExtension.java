package com.axalotl.async.common.bootstrap;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;
import org.spongepowered.asm.util.Bytecode;

/** Removes proven forwarding adapters without moving a wrapper's monitor across another wrapper. */
public final class SynchronizedForwarderExtension implements IExtension {
    private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String CALL = "([Ljava/lang/Object;)Ljava/lang/Object;";
    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

    @Override
    public boolean checkActive(MixinEnvironment environment) { return true; }

    @Override
    public void preApply(ITargetClassContext context) {}

    @Override
    public void postApply(ITargetClassContext context) {
        int changed = specialize(context.getClassNode());
        if (changed != 0) LogManager.getLogger().debug("Specialized {} synchronized forwarders in {}", changed, context.getClassNode().name);
    }

    public static int specialize(ClassNode owner) {
        if ((owner.access & Opcodes.ACC_INTERFACE) != 0) return 0;
        int changed = 0;
        for (MethodNode caller : List.copyOf(owner.methods)) {
            List<AbstractInsnNode> code = code(caller);
            if ((caller.access & Opcodes.ACC_STATIC) != 0 || code.size() < 5
                    || !(code.get(code.size() - 2) instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL || !call.owner.equals(owner.name)
                    || !(code.get(code.size() - 3) instanceof InvokeDynamicInsnNode factory)) continue;
            MethodNode handler = find(owner, call.name, call.desc);
            if (handler == null || (handler.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_STATIC))
                    != (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED)
                    || handler.visibleAnnotations == null || handler.visibleAnnotations.stream().noneMatch(annotation ->
                    annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;"))) continue;
            Type[] args = Type.getArgumentTypes(caller.desc);
            if (args.length > 127) continue;
            Type result = Type.getReturnType(caller.desc);
            Type[] handlerArgs = Arrays.copyOf(args, args.length + 1);
            handlerArgs[args.length] = Type.getObjectType(OPERATION);
            if (!handler.desc.equals(Type.getMethodDescriptor(result, handlerArgs))
                    || !same(code(handler), handlerCode(args, result))) continue;
            Type bridgeResult = boxed(result);
            String bridgeDesc = "([Ljava/lang/Object;)" + bridgeResult.getDescriptor();
            if (!factory.name.equals("call") || !factory.desc.equals("(L" + owner.name + ";)L" + OPERATION + ";")
                    || !METAFACTORY.equals(factory.bsm) || factory.bsmArgs.length != 3
                    || !Type.getMethodType(CALL).equals(factory.bsmArgs[0])
                    || !Type.getMethodType(bridgeDesc).equals(factory.bsmArgs[2])
                    || !(factory.bsmArgs[1] instanceof Handle binding)
                    || binding.getTag() != Opcodes.H_INVOKESPECIAL || binding.isInterface()
                    || !binding.getOwner().equals(owner.name) || !binding.getDesc().equals(bridgeDesc)) continue;
            MethodNode bridge = find(owner, binding.getName(), binding.getDesc());
            if (bridge == null || (bridge.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED)) != Opcodes.ACC_PRIVATE) continue;
            List<AbstractInsnNode> bridgeCode = code(bridge);
            MethodInsnNode innerCall = null;
            for (AbstractInsnNode instruction : bridgeCode) {
                if (instruction instanceof MethodInsnNode invoke && invoke.getOpcode() == Opcodes.INVOKESPECIAL
                        && invoke.owner.equals(owner.name) && invoke.desc.equals(caller.desc)) {
                    innerCall = invoke;
                    break;
                }
            }
            if (innerCall == null || innerCall.itf) continue;
            MethodNode inner = find(owner, innerCall.name, innerCall.desc);
            if (inner == null || inner == caller || (inner.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != Opcodes.ACC_PRIVATE
                    || !same(bridgeCode, bridgeCode(args, result, innerCall))) continue;
            InsnList expected = loads(args);
            expected.add(new VarInsnNode(Opcodes.ALOAD, 0));
            expected.add(factory.clone(null));
            expected.add(call.clone(null));
            expected.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
            if (!same(code, expected)) continue;

            String name = handler.name + "$tickweave$direct$" + changed;
            while (find(owner, name, caller.desc) != null) name += "$";
            MethodNode direct = new MethodNode(Opcodes.ASM9,
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_SYNCHRONIZED,
                    name, caller.desc, null, handler.exceptions == null ? null : handler.exceptions.toArray(String[]::new));
            direct.instructions = loads(args);
            direct.instructions.add(innerCall.clone(null));
            direct.instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
            direct.maxLocals = 1 + Arrays.stream(args).mapToInt(Type::getSize).sum();
            direct.maxStack = Math.max(direct.maxLocals, result.getSize());
            owner.methods.add(direct);
            // Keep the original handler callable with arbitrary Operations; only replace this proven binding.
            caller.instructions.remove(code.get(code.size() - 4));
            caller.instructions.remove(factory);
            caller.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, name, caller.desc, false));
            changed++;
        }
        return changed;
    }

    private static InsnList handlerCode(Type[] args, Type result) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1 + Arrays.stream(args).mapToInt(Type::getSize).sum()));
        code.add(new IntInsnNode(Opcodes.BIPUSH, args.length));
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int local = 1;
        for (int i = 0; i < args.length; i++) {
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new IntInsnNode(Opcodes.BIPUSH, i));
            code.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), local));
            box(code, args[i]);
            code.add(new InsnNode(Opcodes.AASTORE));
            local += args[i].getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OPERATION, "call", CALL, true));
        if (result == Type.VOID_TYPE) code.add(new InsnNode(Opcodes.POP));
        else if (!result.equals(Type.getType(Object.class))) unbox(code, result);
        code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        return code;
    }

    private static InsnList bridgeCode(Type[] args, Type result, MethodInsnNode inner) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new IntInsnNode(Opcodes.BIPUSH, args.length));
        code.add(new LdcInsnNode(Arrays.stream(args).map(Type::getClassName).collect(Collectors.joining(", ", "[", "]"))));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationRuntime", "checkArgumentCount",
                "([Ljava/lang/Object;ILjava/lang/String;)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        for (int i = 0; i < args.length; i++) {
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new IntInsnNode(Opcodes.BIPUSH, i));
            code.add(new InsnNode(Opcodes.AALOAD));
            unbox(code, args[i]);
            if (args[i].getSize() == 2) {
                code.add(new InsnNode(Opcodes.DUP2_X1));
                code.add(new InsnNode(Opcodes.POP2));
            } else code.add(new InsnNode(Opcodes.SWAP));
        }
        code.add(new InsnNode(Opcodes.POP));
        code.add(inner.clone(null));
        if (result == Type.VOID_TYPE) {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Void"));
        } else box(code, result);
        code.add(new InsnNode(Opcodes.ARETURN));
        return code;
    }

    private static InsnList loads(Type[] args) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int local = 1;
        for (Type arg : args) {
            code.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), local));
            local += arg.getSize();
        }
        return code;
    }

    private static Type boxed(Type type) {
        if (type == Type.VOID_TYPE) return Type.getObjectType("java/lang/Void");
        return type.getSort() < Type.ARRAY ? Type.getObjectType(Bytecode.getBoxingType(type)) : type;
    }

    private static void box(InsnList code, Type type) {
        if (type.getSort() < Type.ARRAY) code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                boxed(type).getInternalName(), "valueOf", Type.getMethodDescriptor(boxed(type), type), false));
    }

    private static void unbox(InsnList code, Type type) {
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, boxed(type).getInternalName()));
        if (type.getSort() < Type.ARRAY) code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                boxed(type).getInternalName(), Bytecode.getUnboxingMethod(type), Type.getMethodDescriptor(type), false));
    }

    private static MethodNode find(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return List.of();
        List<AbstractInsnNode> code = BytecodeInstructions.opcodes(method);
        if (code.isEmpty()) return code;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FrameNode) return List.of();
        }
        return code;
    }

    private static boolean same(List<AbstractInsnNode> actual, InsnList expected) {
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) if (!same(actual.get(i), expected.get(i))) return false;
        return true;
    }

    private static boolean same(AbstractInsnNode a, AbstractInsnNode b) {
        Integer ai = integer(a), bi = integer(b);
        if (ai != null || bi != null) return ai != null && ai.equals(bi);
        if (a.getOpcode() != b.getOpcode()) return false;
        if (a instanceof VarInsnNode av && b instanceof VarInsnNode bv) return av.var == bv.var;
        if (a instanceof TypeInsnNode at && b instanceof TypeInsnNode bt) return at.desc.equals(bt.desc);
        if (a instanceof LdcInsnNode al && b instanceof LdcInsnNode bl) return al.cst.equals(bl.cst);
        if (a instanceof MethodInsnNode am && b instanceof MethodInsnNode bm)
            return am.owner.equals(bm.owner) && am.name.equals(bm.name) && am.desc.equals(bm.desc) && am.itf == bm.itf;
        if (a instanceof InvokeDynamicInsnNode ad && b instanceof InvokeDynamicInsnNode bd)
            return ad.name.equals(bd.name) && ad.desc.equals(bd.desc) && ad.bsm.equals(bd.bsm) && Arrays.equals(ad.bsmArgs, bd.bsmArgs);
        return a instanceof InsnNode && b instanceof InsnNode;
    }

    private static Integer integer(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) return ((IntInsnNode) instruction).operand;
        return instruction instanceof LdcInsnNode constant && constant.cst instanceof Integer value ? value : null;
    }

    @Override
    public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) {}
}
