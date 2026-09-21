package com.axalotl.async.common.bootstrap;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Restricts optional rewrites to complete, consistently sized instruction chains. */
final class BytecodeInstructions {
    private BytecodeInstructions() {}

    static List<AbstractInsnNode> opcodes(MethodNode method) {
        int expected = method.instructions.size();
        int observed = 0;
        AbstractInsnNode previous = null;
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (++observed > expected || instruction.getPrevious() != previous) return List.of();
            if (instruction.getOpcode() >= 0) code.add(instruction);
            previous = instruction;
        }
        return observed == expected && previous == method.instructions.getLast() ? code : List.of();
    }
}
