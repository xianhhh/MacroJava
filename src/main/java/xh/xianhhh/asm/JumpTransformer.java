package xh.xianhhh.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.HashMap;
import java.util.Map;

/** Replaces literal Jump markers within one method. */
final class JumpTransformer {
    private static final String CHECKED_OWNER = "xh/xianhhh/macro/GOTO";
    private static final String DIRECT_OWNER = "xh/xianhhh/macro/JUMP";
    private static final String DESC = "(Ljava/lang/String;)V";

    static boolean transform(String owner, MethodNode method) {
        Map<String, LabelNode> labels = new HashMap<>();
        boolean hasMarkers = false;
        boolean hasCheckedMarkers = false;
        boolean hasDirectMarkers = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (!isMarker(insn)) continue;
            hasMarkers = true;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (DIRECT_OWNER.equals(call.owner)) hasDirectMarkers = true;
            else hasCheckedMarkers = true;
            String name = literalName(call, method);
            if ("label".equals(call.name) && labels.putIfAbsent(key(call.owner, name), new LabelNode()) != null)
                throw error(method, "Duplicate label: " + name);
        }
        if (!hasMarkers) return false;
        if (hasCheckedMarkers && hasDirectMarkers)
            throw error(method, "GOTO and JUMP cannot be mixed in the same method");
        if (hasCheckedMarkers && !method.tryCatchBlocks.isEmpty())
            throw error(method, "GOTO does not support jumps in methods with try/catch/finally");
        if (hasCheckedMarkers) try {
            Frame<BasicValue>[] frames = new Analyzer<>(new BasicVerifier()).analyze(owner, method);
            AbstractInsnNode[] instructions = method.instructions.toArray();
            for (int index = 0; index < instructions.length; index++) {
                AbstractInsnNode insn = instructions[index];
                if (!isMarker(insn) || DIRECT_OWNER.equals(((MethodInsnNode) insn).owner)) continue;
                Frame<BasicValue> frame = frames[index];
                if (frame == null || frame.getStackSize() != 1)
                    throw error(method, "Jump markers must be standalone statements in reachable code");
            }
        } catch (AnalyzerException exception) {
            throw new ConstExprException("Cannot validate jump markers: " + method.name + method.desc, exception);
        }
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!isMarker(insn)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            String name = literalName(call, method);
            AbstractInsnNode argument = previousOpcode(call);
            if ("label".equals(call.name)) {
                method.instructions.insert(call, labels.get(key(call.owner, name)));
            } else {
                LabelNode target = labels.get(key(call.owner, name));
                if (target == null) throw error(method, "Label not found: " + name);
                method.instructions.insert(call, new JumpInsnNode(Opcodes.GOTO, target));
            }
            method.instructions.remove(call);
            method.instructions.remove(argument);
        }
        if (hasCheckedMarkers) try {
            new Analyzer<>(new BasicVerifier()).analyze(owner, method);
        } catch (AnalyzerException exception) {
            throw new ConstExprException("Invalid control flow after jump: " + method.name + method.desc, exception);
        }
        return hasDirectMarkers;
    }

    private static boolean isMarker(AbstractInsnNode insn) {
        if (!(insn instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) insn;
        return call.getOpcode() == Opcodes.INVOKESTATIC
                && (CHECKED_OWNER.equals(call.owner) || DIRECT_OWNER.equals(call.owner))
                && DESC.equals(call.desc) && ("label".equals(call.name) || "goTo".equals(call.name));
    }

    private static String key(String owner, String name) {
        return owner + ':' + name;
    }

    private static String literalName(MethodInsnNode call, MethodNode method) {
        AbstractInsnNode argument = previousOpcode(call);
        if (!(argument instanceof LdcInsnNode) || !(((LdcInsnNode) argument).cst instanceof String))
            throw error(method, "Label name must be a string literal");
        String name = (String) ((LdcInsnNode) argument).cst;
        if (name.isEmpty()) throw error(method, "Label name must not be empty");
        return name;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode node) {
        AbstractInsnNode previous = node.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static ConstExprException error(MethodNode method, String message) {
        return new ConstExprException(method.name + method.desc + ": " + message);
    }
}
