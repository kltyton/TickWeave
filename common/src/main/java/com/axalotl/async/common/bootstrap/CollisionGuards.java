package com.axalotl.async.common.bootstrap;

import com.axalotl.async.common.entity.query.CollisionClassFilter.Guards;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Proves only empty type-guarded callback paths; every unsupported instruction keeps the native query. */
final class CollisionGuards {
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private final ClassNode owner;
    private final Set<String> sourceAbsent = new LinkedHashSet<>();
    private final Set<String> sourcePresent = new LinkedHashSet<>();
    private final Set<String> targetAbsent = new LinkedHashSet<>();
    private final Set<String> targetPresent = new LinkedHashSet<>();
    private final Set<String> virtualHandlers = new LinkedHashSet<>();

    private CollisionGuards(ClassNode owner) {
        this.owner = owner;
    }

    static Guards prove(ClassNode owner, MethodNode source, int suffixStart) {
        CollisionGuards proof = new CollisionGuards(owner);
        if (!proof.walk(source, suffixStart, true)) return null;
        return new Guards(List.copyOf(proof.sourceAbsent), List.copyOf(proof.sourcePresent),
                List.copyOf(proof.targetAbsent), List.copyOf(proof.targetPresent), List.copyOf(proof.virtualHandlers));
    }

    private boolean walk(MethodNode method, int end, boolean prefix) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED)) != 0
                || !method.tryCatchBlocks.isEmpty()) return false;
        List<AbstractInsnNode> code = BytecodeInstructions.opcodes(method);
        if (code.isEmpty()) return false;
        if (end < 0) end = code.size();
        Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        for (int i = 0; i < code.size(); i++) positions.put(code.get(i), i);
        Map<Integer, Value> locals = new HashMap<>();
        locals.put(0, Value.SOURCE);
        locals.put(1, Value.TARGET);
        if (!prefix) locals.put(2, Value.CALLBACK);
        ArrayDeque<Value> stack = new ArrayDeque<>();
        boolean[] visited = new boolean[code.size()];
        int cursor = 0;
        while (cursor != end) {
            if (cursor < 0 || cursor > end || visited[cursor]) return false;
            visited[cursor] = true;
            AbstractInsnNode instruction = code.get(cursor);
            int opcode = instruction.getOpcode();
            int next = cursor + 1;
            switch (opcode) {
                case Opcodes.NOP -> { }
                case Opcodes.ACONST_NULL -> stack.addLast(Value.NULL);
                case Opcodes.ICONST_0 -> stack.addLast(Value.FALSE);
                case Opcodes.ICONST_1 -> stack.addLast(Value.TRUE);
                case Opcodes.LDC -> {
                    if (!(((LdcInsnNode) instruction).cst instanceof String)) return false;
                    stack.addLast(Value.STRING);
                }
                case Opcodes.ALOAD -> {
                    Value value = locals.get(((VarInsnNode) instruction).var);
                    if (value == null) return false;
                    stack.addLast(value);
                }
                case Opcodes.ASTORE -> {
                    Value value = stack.pollLast();
                    if (value == null) return false;
                    locals.put(((VarInsnNode) instruction).var, value);
                }
                case Opcodes.DUP -> {
                    if (stack.peekLast() == null) return false;
                    stack.addLast(stack.peekLast());
                }
                case Opcodes.POP -> {
                    if (stack.pollLast() == null) return false;
                }
                case Opcodes.CHECKCAST -> {
                    Value value = stack.peekLast();
                    String type = ((TypeInsnNode) instruction).desc;
                    if (value == Value.SOURCE) this.sourcePresent.add(type);
                    else if (value == Value.TARGET) this.targetPresent.add(type);
                    else if (value != Value.NULL) return false;
                }
                case Opcodes.INSTANCEOF -> {
                    Value value = stack.pollLast();
                    String type = ((TypeInsnNode) instruction).desc;
                    if (value == Value.SOURCE) this.sourceAbsent.add(type);
                    else if (value == Value.TARGET) this.targetAbsent.add(type);
                    else if (value != Value.NULL) return false;
                    stack.addLast(Value.FALSE);
                }
                case Opcodes.IFEQ, Opcodes.IFNE -> {
                    Value value = stack.pollLast();
                    if (value != Value.FALSE && value != Value.TRUE) return false;
                    if ((opcode == Opcodes.IFEQ) == (value == Value.FALSE)) next = jump(instruction, positions);
                }
                case Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                    Value value = stack.pollLast();
                    if (value == null || value == Value.TRUE || value == Value.FALSE) return false;
                    if ((opcode == Opcodes.IFNULL) == (value == Value.NULL)) next = jump(instruction, positions);
                }
                case Opcodes.GOTO -> next = jump(instruction, positions);
                case Opcodes.NEW -> {
                    if (!prefix || !((TypeInsnNode) instruction).desc.equals(CALLBACK)) return false;
                    stack.addLast(Value.CALLBACK);
                }
                case Opcodes.INVOKESPECIAL, Opcodes.INVOKEVIRTUAL -> {
                    if (!prefix || !this.call((MethodInsnNode) instruction, stack)) return false;
                }
                case Opcodes.RETURN -> {
                    return !prefix && stack.isEmpty();
                }
                default -> { return false; }
            }
            cursor = next;
        }
        return prefix && stack.isEmpty() && locals.get(0) == Value.SOURCE && locals.get(1) == Value.TARGET;
    }

    private boolean call(MethodInsnNode call, ArrayDeque<Value> stack) {
        if (call.owner.equals(CALLBACK)) {
            if (call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")
                    && call.desc.equals("(Ljava/lang/String;Z)V")) {
                Value flag = stack.pollLast();
                return (flag == Value.FALSE || flag == Value.TRUE)
                        && stack.pollLast() == Value.STRING && stack.pollLast() == Value.CALLBACK;
            }
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.name.equals("isCancelled")
                    && call.desc.equals("()Z") && stack.pollLast() == Value.CALLBACK) {
                stack.addLast(Value.FALSE);
                return true;
            }
            return false;
        }
        if (!call.owner.equals(this.owner.name)
                || !call.desc.equals("(L" + this.owner.name + ";L" + CALLBACK + ";)V")
                || stack.pollLast() != Value.CALLBACK || stack.pollLast() != Value.TARGET
                || stack.pollLast() != Value.SOURCE) return false;
        MethodNode handler = this.owner.methods.stream().filter(method -> method.name.equals(call.name)
                && method.desc.equals(call.desc)).findFirst().orElse(null);
        if (handler == null || !this.walk(handler, -1, false)) return false;
        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL) this.virtualHandlers.add(call.name);
        return true;
    }

    private static int jump(AbstractInsnNode instruction, Map<AbstractInsnNode, Integer> positions) {
        AbstractInsnNode target = ((JumpInsnNode) instruction).label;
        while (target != null && target.getOpcode() < 0) target = target.getNext();
        return positions.getOrDefault(target, -1);
    }

    private enum Value { SOURCE, TARGET, CALLBACK, NULL, STRING, FALSE, TRUE }
}
