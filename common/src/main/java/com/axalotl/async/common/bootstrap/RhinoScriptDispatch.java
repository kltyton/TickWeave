package com.axalotl.async.common.bootstrap;

import java.util.ArrayList;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Routes complete Rhino calls before they acquire a monitor or modify script scope. */
public final class RhinoScriptDispatch {
    private static final String CALLBACKS = "com/axalotl/async/common/entity/task/ScriptCallbacks";
    private static final Type OBJECT = Type.getType(Object.class);
    private static final Type SUPPLIER = Type.getType(java.util.function.Supplier.class);
    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                    + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

    private RhinoScriptDispatch() {}

    public static int apply(ClassNode owner) {
        Set<String> names = switch (owner.name) {
            case "dev/latvian/mods/rhino/Context" -> Set.of("hasTopCallScope", "getTopCallScope",
                    "getTopCallOrThrow", "setTopCall", "storeScriptable", "lastStoredScriptable",
                    "callSync", "doTopCall");
            case "dev/latvian/mods/rhino/InterpretedFunction" -> Set.of("call", "exec", "resumeGenerator");
            case "dev/latvian/mods/rhino/InterfaceAdapter" -> Set.of("invoke");
            default -> Set.of();
        };
        if (names.isEmpty()) return 0;
        var additions = new ArrayList<MethodNode>();
        int count = 0;
        for (MethodNode body : owner.methods) {
            if (!names.contains(body.name)) continue;
            if ((body.access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT
                    | Opcodes.ACC_SYNCHRONIZED)) != 0) {
                throw new IllegalStateException("Unexpected Rhino script entry " + owner.name + "." + body.name);
            }
            String originalName = body.name;
            String bodyName = "tickweave$script$body$" + count;
            String bridgeName = "tickweave$script$invoke$" + count;
            MethodNode wrapper = new MethodNode(Opcodes.ASM9, body.access, originalName,
                    body.desc, body.signature, body.exceptions.toArray(String[]::new));
            wrapper.visibleAnnotations = body.visibleAnnotations;
            wrapper.invisibleAnnotations = body.invisibleAnnotations;
            wrapper.visibleParameterAnnotations = body.visibleParameterAnnotations;
            wrapper.invisibleParameterAnnotations = body.invisibleParameterAnnotations;
            Type[] args = Type.getArgumentTypes(body.desc);
            Type result = Type.getReturnType(body.desc);
            Type[] captured = new Type[args.length + 1];
            captured[0] = Type.getObjectType(owner.name);
            System.arraycopy(args, 0, captured, 1, args.length);
            String bridgeDesc = Type.getMethodDescriptor(OBJECT, captured);
            MethodNode bridge = new MethodNode(Opcodes.ASM9,
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    bridgeName, bridgeDesc, null, null);
            load(bridge.instructions, args);
            bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, bodyName, body.desc, false));
            if (result.equals(Type.VOID_TYPE)) bridge.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            else if (result.equals(Type.BOOLEAN_TYPE)) bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
            else if (result.getSort() < Type.ARRAY) throw new IllegalStateException("Unexpected Rhino return " + body.desc);
            bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
            LabelNode direct = new LabelNode();
            wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CALLBACKS,
                    "requiresDispatch", "()Z", false));
            wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, direct));
            load(wrapper.instructions, args);
            wrapper.instructions.add(new InvokeDynamicInsnNode("get", Type.getMethodDescriptor(SUPPLIER, captured),
                    METAFACTORY, Type.getMethodType("()Ljava/lang/Object;"),
                    new Handle(Opcodes.H_INVOKESTATIC, owner.name, bridgeName, bridgeDesc, false),
                    Type.getMethodType("()Ljava/lang/Object;")));
            wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CALLBACKS,
                    "call", "(Ljava/util/function/Supplier;)Ljava/lang/Object;", false));
            if (result.equals(Type.VOID_TYPE)) wrapper.instructions.add(new InsnNode(Opcodes.POP));
            else if (result.equals(Type.BOOLEAN_TYPE)) {
                wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
            } else wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, result.getInternalName()));
            wrapper.instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
            wrapper.instructions.add(direct);
            load(wrapper.instructions, args);
            wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, bodyName, body.desc, false));
            wrapper.instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
            int locals = 1;
            for (Type arg : args) locals += arg.getSize();
            wrapper.maxLocals = bridge.maxLocals = locals;
            wrapper.maxStack = bridge.maxStack = locals + 2;
            body.name = bodyName;
            body.access = (body.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                    | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
            additions.add(wrapper);
            additions.add(bridge);
            count++;
        }
        if (count != names.size()) throw new IllegalStateException("Missing Rhino script entries in " + owner.name + ": " + count);
        owner.methods.addAll(additions);
        return count;
    }

    private static void load(InsnList code, Type[] args) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int local = 1;
        for (Type arg : args) {
            code.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), local));
            local += arg.getSize();
        }
    }
}
