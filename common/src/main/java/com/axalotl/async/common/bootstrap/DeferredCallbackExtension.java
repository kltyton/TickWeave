package com.axalotl.async.common.bootstrap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
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

/** Defers unobserved callback objects in fully matched cancellable injection blocks. */
public final class DeferredCallbackExtension implements IExtension {
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String CALLBACK_DESC = "L" + CALLBACK + ";";
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
    }

    @Override
    public void postApply(ITargetClassContext context) {
        ClassNode owner = context.getClassNode();
        int count = deferCallbacks(owner);
        if (count != 0) LogManager.getLogger().debug("Deferred {} callback allocations in {}", count, owner.name);
    }

    public static int deferCallbacks(ClassNode owner) {
        if ((owner.access & Opcodes.ACC_INTERFACE) != 0) return 0;
        List<MethodNode> additions = new ArrayList<>();
        for (MethodNode caller : owner.methods) {
            if (caller.name.startsWith("<")) continue;
            List<AbstractInsnNode> code = BytecodeInstructions.opcodes(caller);
            for (int start = 0; start + 12 < code.size(); start++) {
                Match match = match(owner, caller, code, start);
                if (match == null) continue;
                String name = match.handler.name + "$tickweave$deferred$" + additions.size();
                if (owner.methods.stream().anyMatch(method -> method.name.equals(name))) continue;
                MethodNode deferred = copyHandler(match, name);
                additions.add(deferred);
                // Keep the original private method callable by reflection and other injection sites.
                match.call.name = deferred.name;
                match.call.desc = deferred.desc;
                caller.instructions.insert(match.call, new VarInsnNode(Opcodes.ASTORE, match.local));
                caller.instructions.insertBefore(code.get(start), new InsnNode(Opcodes.ACONST_NULL));
                for (int offset = 0; offset < match.constructorSize; offset++) caller.instructions.remove(code.get(start + offset));
                InsnList absent = new InsnList();
                absent.add(new VarInsnNode(Opcodes.ALOAD, match.local));
                absent.add(new JumpInsnNode(Opcodes.IFNULL, match.continuation));
                caller.instructions.insertBefore(match.cancelLoad, absent);
                caller.maxStack += 1;
            }
        }
        owner.methods.addAll(additions);
        return additions.size();
    }

    private static Match match(ClassNode owner, MethodNode caller, List<AbstractInsnNode> code, int start) {
        if (!(code.get(start) instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                || !allocation.desc.equals(CALLBACK) || code.get(start + 1).getOpcode() != Opcodes.DUP
                || !(code.get(start + 2) instanceof LdcInsnNode id) || !(id.cst instanceof String)
                || code.get(start + 3).getOpcode() != Opcodes.ICONST_1) return null;
        boolean atReturn = code.get(start + 4) instanceof VarInsnNode;
        int constructorSize = atReturn ? 6 : 5;
        if (!calls(code.get(start + constructorSize - 1), Opcodes.INVOKESPECIAL, CALLBACK, "<init>",
                atReturn ? "(Ljava/lang/String;ZLjava/lang/Object;)V" : "(Ljava/lang/String;Z)V")
                || !(code.get(start + constructorSize) instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE) return null;
        if (atReturn && (start < 2 || code.get(start - 2).getOpcode() != Opcodes.DUP
                || !loads(code.get(start - 1), Opcodes.ASTORE, store.var)
                || !loads(code.get(start + 4), Opcodes.ALOAD, store.var))) return null;
        int cursor = start + constructorSize + 1;
        while (cursor < code.size() && code.get(cursor) instanceof VarInsnNode load
                && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD) cursor++;
        if (cursor + 6 >= code.size() || !(code.get(cursor) instanceof MethodInsnNode call)
                || !call.owner.equals(owner.name)) return null;
        MethodNode handler = owner.methods.stream().filter(method -> method.name.equals(call.name)
                && method.desc.equals(call.desc)).findFirst().orElse(null);
        if (handler == null || (handler.access & Opcodes.ACC_PRIVATE) == 0
                || (handler.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0
                || !handler.tryCatchBlocks.isEmpty() || !hasMergedAnnotation(handler)
                || !Type.getReturnType(handler.desc).equals(Type.VOID_TYPE)) return null;
        if (BytecodeInstructions.opcodes(handler).isEmpty()) return null;
        Type[] parameters = Type.getArgumentTypes(handler.desc);
        if (parameters.length == 0 || !parameters[parameters.length - 1].getDescriptor().equals(CALLBACK_DESC)) return null;
        boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
        if (isStatic ? call.getOpcode() != Opcodes.INVOKESTATIC
                : call.getOpcode() != Opcodes.INVOKESPECIAL && call.getOpcode() != Opcodes.INVOKEVIRTUAL) return null;
        int argumentStart = start + constructorSize + 1;
        if (!isStatic && !loads(code.get(argumentStart++), Opcodes.ALOAD, 0)) return null;
        if (cursor - argumentStart != parameters.length) return null;
        int parameterLocal = isStatic ? 0 : 1;
        for (int index = 0; index < parameters.length - 1; index++) {
            if (code.get(argumentStart + index).getOpcode() != parameters[index].getOpcode(Opcodes.ILOAD)) return null;
            parameterLocal += parameters[index].getSize();
        }
        AbstractInsnNode argument = code.get(cursor - 1);
        AbstractInsnNode cancelLoad = code.get(cursor + 1);
        AbstractInsnNode returnLoad = code.get(cursor + 4);
        if (!loads(argument, Opcodes.ALOAD, store.var) || !loads(cancelLoad, Opcodes.ALOAD, store.var)
                || !calls(code.get(cursor + 2), Opcodes.INVOKEVIRTUAL, CALLBACK, "isCancelled", "()Z")
                || !(code.get(cursor + 3) instanceof JumpInsnNode branch) || branch.getOpcode() != Opcodes.IFEQ
                || !loads(returnLoad, Opcodes.ALOAD, store.var)) return null;
        Type result = Type.getReturnType(caller.desc);
        if (result.equals(Type.VOID_TYPE)) return null;
        boolean reference = result.getSort() >= Type.ARRAY;
        String accessor = reference ? "getReturnValue" : "getReturnValue" + result.getDescriptor();
        String accessorDesc = reference ? "()Ljava/lang/Object;" : "()" + result.getDescriptor();
        if (!calls(code.get(cursor + 5), Opcodes.INVOKEVIRTUAL, CALLBACK, accessor, accessorDesc)) return null;
        int end = cursor + 6;
        if (reference) {
            if (!(code.get(end++) instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST
                    || !cast.desc.equals(result.getInternalName())) return null;
        }
        if (end + 1 >= code.size() || code.get(end).getOpcode() != result.getOpcode(Opcodes.IRETURN)
                || nextInstruction(branch.label) != code.get(end + 1)) return null;
        // No external branch/exception edge may enter the construction, argument or cancellation block.
        Set<LabelNode> targets = branchTargets(caller);
        for (AbstractInsnNode instruction = allocation.getNext(); instruction != code.get(end); instruction = instruction.getNext()) {
            if (instruction instanceof LabelNode label && targets.contains(label)) return null;
        }
        for (AbstractInsnNode instruction : caller.instructions) {
            if (instruction == store || instruction == argument || instruction == cancelLoad || instruction == returnLoad) continue;
            if (atReturn && (instruction == code.get(start - 1) || instruction == code.get(start + 4))) continue;
            if (touches(instruction, store.var)) return null;
        }
        for (AbstractInsnNode instruction : handler.instructions) {
            if (touches(instruction, parameterLocal) && !loads(instruction, Opcodes.ALOAD, parameterLocal)) return null;
            // A return-site callback may discard its initial value only if every observation overwrites it.
            if (atReturn && loads(instruction, Opcodes.ALOAD, parameterLocal) && !onlySetsReturnValue(instruction, parameterLocal)) return null;
        }
        return new Match(handler, call, store.var, parameterLocal, (String) id.cst, cancelLoad, branch.label, constructorSize);
    }

    private static boolean onlySetsReturnValue(AbstractInsnNode load, int local) {
        AbstractInsnNode value = nextInstruction(load.getNext());
        if (value == null) return false;
        boolean reference = value.getOpcode() == Opcodes.ACONST_NULL
                || value instanceof LdcInsnNode constant && (constant.cst instanceof String || constant.cst instanceof Type)
                || value instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD && variable.var != local
                || value instanceof MethodInsnNode factory && factory.getOpcode() == Opcodes.INVOKESTATIC
                && Type.getArgumentTypes(factory.desc).length == 0 && Type.getReturnType(factory.desc).getSort() >= Type.ARRAY;
        return reference && calls(nextInstruction(value.getNext()), Opcodes.INVOKEVIRTUAL, CALLBACK,
                "setReturnValue", "(Ljava/lang/Object;)V");
    }

    private static MethodNode copyHandler(Match match, String name) {
        String descriptor = match.handler.desc.substring(0, match.handler.desc.length() - 1) + CALLBACK_DESC;
        MethodNode copy = new MethodNode(Opcodes.ASM9, match.handler.access | Opcodes.ACC_SYNTHETIC,
                name, descriptor, null, match.handler.exceptions.toArray(String[]::new)) {
            private final IdentityHashMap<Label, LabelNode> labels = new IdentityHashMap<>();

            @Override
            protected LabelNode getLabelNode(Label label) {
                // A live Mixin tree can retain source nodes in Label.info.
                return labels.computeIfAbsent(label, ignored -> new LabelNode());
            }
        };
        match.handler.accept(copy);
        copy.visibleAnnotations = null;
        copy.invisibleAnnotations = null;
        for (AbstractInsnNode instruction : copy.instructions.toArray()) {
            if (loads(instruction, Opcodes.ALOAD, match.parameterLocal)) {
                InsnList materialize = new InsnList();
                materialize.add(new LdcInsnNode(match.id));
                materialize.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "com/axalotl/async/common/callback/DeferredCallback", "materialize",
                        "(" + CALLBACK_DESC + "Ljava/lang/String;)" + CALLBACK_DESC, false));
                materialize.add(new InsnNode(Opcodes.DUP));
                materialize.add(new VarInsnNode(Opcodes.ASTORE, match.parameterLocal));
                copy.instructions.insert(instruction, materialize);
            } else if (instruction.getOpcode() == Opcodes.RETURN) {
                copy.instructions.insertBefore(instruction, new VarInsnNode(Opcodes.ALOAD, match.parameterLocal));
                copy.instructions.set(instruction, new InsnNode(Opcodes.ARETURN));
            }
        }
        copy.maxStack += 2;
        return copy;
    }

    private static boolean hasMergedAnnotation(MethodNode method) {
        return hasAnnotation(method.visibleAnnotations) || hasAnnotation(method.invisibleAnnotations);
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(annotation -> annotation.desc.equals(MERGED));
    }

    private static boolean touches(AbstractInsnNode instruction, int local) {
        if (instruction instanceof IincInsnNode increment) return increment.var == local;
        if (!(instruction instanceof VarInsnNode variable)) return false;
        int opcode = variable.getOpcode();
        return variable.var == local || variable.var + 1 == local
                && (opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD || opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE);
    }

    private static Set<LabelNode> branchTargets(MethodNode method) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump) targets.add(jump.label);
            if (instruction instanceof LookupSwitchInsnNode selection) {
                targets.add(selection.dflt);
                targets.addAll(selection.labels);
            }
            if (instruction instanceof TableSwitchInsnNode selection) {
                targets.add(selection.dflt);
                targets.addAll(selection.labels);
            }
        }
        for (var handler : method.tryCatchBlocks) {
            targets.add(handler.start);
            targets.add(handler.end);
            targets.add(handler.handler);
        }
        return targets;
    }

    private static boolean calls(AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals(descriptor);
    }

    private static boolean loads(AbstractInsnNode instruction, int opcode, int local) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode && variable.var == local;
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getNext();
        return instruction;
    }

    @Override
    public void export(MixinEnvironment environment, String name, boolean force, ClassNode classNode) {
    }

    private record Match(MethodNode handler, MethodInsnNode call, int local, int parameterLocal, String id,
                         AbstractInsnNode cancelLoad, LabelNode continuation, int constructorSize) {
    }
}
