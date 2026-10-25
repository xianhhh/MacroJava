package xh.xianhhh.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves call arguments whose bytecode spans locals, expressions, or control-flow joins. */
final class ConstExprCallSiteAnalyzer {
    private final Frame<SourceValue>[] frames;
    private final Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
    private final Set<AbstractInsnNode> active = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode, Boolean>());
    private final Map<String, ConstExprValue> fields;

    private ConstExprCallSiteAnalyzer(String owner, MethodNode method, Map<String, ConstExprValue> fields)
            throws AnalyzerException {
        this.fields = fields;
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) positions.put(instructions[index], index);
        frames = new Analyzer<SourceValue>(new SourceInterpreter()).analyze(owner, method);
    }

    static List<ConstExprValue> arguments(String owner, MethodNode method, MethodInsnNode call,
                                          Map<String, ConstExprValue> fields) {
        try {
            ConstExprCallSiteAnalyzer analyzer = new ConstExprCallSiteAnalyzer(owner, method, fields);
            Frame<SourceValue> frame = analyzer.frame(call);
            Type[] types = Type.getArgumentTypes(call.desc);
            if (frame == null || frame.getStackSize() < types.length) return null;
            List<ConstExprValue> result = new ArrayList<>(types.length);
            for (int index = 0; index < types.length; index++) {
                ConstExprValue value = analyzer.resolve(frame.getStack(frame.getStackSize() - types.length + index));
                if (value == null) return null;
                result.add(value);
            }
            return result;
        } catch (AnalyzerException error) {
            return null;
        }
    }

    static Map<AbstractInsnNode, Integer> arrayLengths(String owner, MethodNode method,
                                                       Map<String, ConstExprValue> fields) {
        Map<AbstractInsnNode, Integer> lengths = new IdentityHashMap<>();
        List<AbstractInsnNode> candidates = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction.getOpcode() == Opcodes.ARRAYLENGTH) candidates.add(instruction);
        if (candidates.isEmpty()) return lengths;
        try {
            ConstExprCallSiteAnalyzer analyzer = new ConstExprCallSiteAnalyzer(owner, method, fields);
            for (AbstractInsnNode instruction : candidates) {
                Frame<SourceValue> frame = analyzer.frame(instruction);
                if (frame == null || frame.getStackSize() == 0) continue;
                Integer length = analyzer.arrayLength(frame.getStack(frame.getStackSize() - 1));
                if (length != null) lengths.put(instruction, length);
            }
        } catch (AnalyzerException ignored) {
            return Collections.emptyMap();
        }
        return lengths;
    }

    static Map<AbstractInsnNode, Boolean> arrayReferenceComparisons(String owner, MethodNode method) {
        Map<AbstractInsnNode, Boolean> comparisons = new IdentityHashMap<>();
        List<AbstractInsnNode> candidates = new ArrayList<>();
        int allocations = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.NEWARRAY || opcode == Opcodes.ANEWARRAY || opcode == Opcodes.MULTIANEWARRAY)
                allocations++;
            if (opcode != Opcodes.IF_ACMPEQ && opcode != Opcodes.IF_ACMPNE
                    && opcode != Opcodes.IFNULL && opcode != Opcodes.IFNONNULL) continue;
            if (opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE) {
                AbstractInsnNode right = previousExecutable(instruction.getPrevious());
                AbstractInsnNode left = previousExecutable(right == null ? null : right.getPrevious());
                if (left instanceof VarInsnNode && right instanceof VarInsnNode
                        && left.getOpcode() == Opcodes.ALOAD && right.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode) left).var == ((VarInsnNode) right).var) {
                    comparisons.put(instruction, true);
                    continue;
                }
            }
            candidates.add(instruction);
        }
        if (allocations == 0 || candidates.isEmpty()) return comparisons;
        if (allocations == 1) candidates.removeIf(instruction ->
                instruction.getOpcode() == Opcodes.IF_ACMPEQ || instruction.getOpcode() == Opcodes.IF_ACMPNE);
        if (candidates.isEmpty()) return comparisons;
        try {
            ConstExprCallSiteAnalyzer analyzer = new ConstExprCallSiteAnalyzer(owner, method,
                    Collections.<String, ConstExprValue>emptyMap());
            for (AbstractInsnNode instruction : candidates) {
                int opcode = instruction.getOpcode();
                Frame<SourceValue> frame = analyzer.frame(instruction);
                if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) {
                    if (frame != null && frame.getStackSize() > 0
                            && analyzer.arrayAllocation(frame.getStack(frame.getStackSize() - 1)) != null)
                        comparisons.put(instruction, false);
                    continue;
                }
                if (frame == null || frame.getStackSize() < 2) continue;
                AbstractInsnNode first = analyzer.arrayAllocation(frame.getStack(frame.getStackSize() - 2));
                AbstractInsnNode second = analyzer.arrayAllocation(frame.getStack(frame.getStackSize() - 1));
                if (first != null && second != null && first != second)
                    comparisons.put(instruction, false);
            }
        } catch (AnalyzerException ignored) {
            return comparisons;
        }
        return comparisons;
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getPrevious();
        return instruction;
    }

    private AbstractInsnNode arrayAllocation(SourceValue source) {
        if (source == null || source.insns.isEmpty()) return null;
        AbstractInsnNode result = null;
        for (AbstractInsnNode instruction : source.insns) {
            AbstractInsnNode candidate = arrayAllocation(instruction);
            if (candidate == null || result != null && result != candidate) return null;
            result = candidate;
        }
        return result;
    }

    private AbstractInsnNode arrayAllocation(AbstractInsnNode instruction) {
        if (!active.add(instruction)) return null;
        try {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.NEWARRAY || opcode == Opcodes.ANEWARRAY || opcode == Opcodes.MULTIANEWARRAY)
                return instruction;
            Frame<SourceValue> frame = frame(instruction);
            if (frame == null) return null;
            if (instruction instanceof VarInsnNode && opcode == Opcodes.ALOAD)
                return arrayAllocation(frame.getLocal(((VarInsnNode) instruction).var));
            if (instruction instanceof VarInsnNode && opcode == Opcodes.ASTORE)
                return frame.getStackSize() == 0 ? null : arrayAllocation(frame.getStack(frame.getStackSize() - 1));
            if (opcode == Opcodes.DUP || opcode == Opcodes.CHECKCAST)
                return frame.getStackSize() == 0 ? null : arrayAllocation(frame.getStack(frame.getStackSize() - 1));
            return null;
        } finally {
            active.remove(instruction);
        }
    }

    static Map<AbstractInsnNode, List<ConstExprValue>> branchInputs(String owner, MethodNode method,
                                                                    Map<String, ConstExprValue> fields) {
        Map<AbstractInsnNode, List<ConstExprValue>> values = new IdentityHashMap<>();
        List<AbstractInsnNode> candidates = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions)
            if (branchInputCount(instruction.getOpcode()) != 0) candidates.add(instruction);
        if (candidates.isEmpty()) return values;
        try {
            ConstExprCallSiteAnalyzer analyzer = new ConstExprCallSiteAnalyzer(owner, method, fields);
            for (AbstractInsnNode instruction : candidates) {
                int count = branchInputCount(instruction.getOpcode());
                Frame<SourceValue> frame = analyzer.frame(instruction);
                if (frame == null || frame.getStackSize() < count) continue;
                List<ConstExprValue> inputs = new ArrayList<>(count);
                boolean known = true;
                for (int index = count; index > 0; index--) {
                    ConstExprValue value = analyzer.resolve(frame.getStack(frame.getStackSize() - index));
                    if (value == null || value instanceof ConstExprArrayValue) { known = false; break; }
                    inputs.add(value);
                }
                if (known) values.put(instruction, inputs);
            }
        } catch (AnalyzerException ignored) {
            return Collections.emptyMap();
        }
        return values;
    }

    private static int branchInputCount(int opcode) {
        if (opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH
                || opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE
                || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) return 1;
        return opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE ? 2 : 0;
    }

    private Integer arrayLength(SourceValue source) {
        if (source == null || source.insns.isEmpty()) return null;
        Integer result = null;
        for (AbstractInsnNode instruction : source.insns) {
            Integer candidate = arrayLength(instruction);
            if (candidate == null || (result != null && !result.equals(candidate))) return null;
            result = candidate;
        }
        return result;
    }

    private Integer arrayLength(AbstractInsnNode instruction) {
        if (!active.add(instruction)) return null;
        try {
            int opcode = instruction.getOpcode();
            Frame<SourceValue> frame = frame(instruction);
            if (frame == null) return null;
            if (opcode == Opcodes.NEWARRAY || opcode == Opcodes.ANEWARRAY) {
                ConstExprValue count = top(frame);
                return count != null && count.value instanceof Number && ((Number) count.value).intValue() >= 0
                        ? ((Number) count.value).intValue() : null;
            }
            if (opcode == Opcodes.MULTIANEWARRAY) {
                int dimensions = ((MultiANewArrayInsnNode) instruction).dims;
                ConstExprValue count = frame.getStackSize() >= dimensions
                        ? resolve(frame.getStack(frame.getStackSize() - dimensions)) : null;
                return count != null && count.value instanceof Number && ((Number) count.value).intValue() >= 0
                        ? ((Number) count.value).intValue() : null;
            }
            if (instruction instanceof VarInsnNode && opcode == Opcodes.ALOAD)
                return arrayLength(frame.getLocal(((VarInsnNode) instruction).var));
            if (instruction instanceof VarInsnNode && opcode == Opcodes.ASTORE)
                return frame.getStackSize() == 0 ? null : arrayLength(frame.getStack(frame.getStackSize() - 1));
            if (opcode == Opcodes.DUP || opcode == Opcodes.CHECKCAST)
                return frame.getStackSize() == 0 ? null : arrayLength(frame.getStack(frame.getStackSize() - 1));
            return null;
        } finally {
            active.remove(instruction);
        }
    }

    private Frame<SourceValue> frame(AbstractInsnNode instruction) {
        Integer index = positions.get(instruction);
        return index == null ? null : frames[index];
    }

    private ConstExprValue resolve(SourceValue source) {
        if (source == null || source.insns.isEmpty()) return null;
        ConstExprValue result = null;
        for (AbstractInsnNode instruction : source.insns) {
            ConstExprValue candidate = resolve(instruction);
            if (candidate == null) return null;
            if (result != null && (!result.descriptor.equals(candidate.descriptor)
                    || !Objects.equals(result.value, candidate.value))) return null;
            result = candidate;
        }
        return result;
    }

    private ConstExprValue resolve(AbstractInsnNode instruction) {
        if (!active.add(instruction)) return null;
        try {
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
                return new ConstExprValue("I", opcode - Opcodes.ICONST_0);
            if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1)
                return new ConstExprValue("J", (long) (opcode - Opcodes.LCONST_0));
            if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2)
                return new ConstExprValue("F", (float) (opcode - Opcodes.FCONST_0));
            if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1)
                return new ConstExprValue("D", (double) (opcode - Opcodes.DCONST_0));
            if (opcode == Opcodes.ACONST_NULL) return new ConstExprValue("Ljava/lang/Object;", null);
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)
                return new ConstExprValue("I", ((IntInsnNode) instruction).operand);
            if (instruction instanceof LdcInsnNode) {
                Object value = ((LdcInsnNode) instruction).cst;
                if (value instanceof Integer) return new ConstExprValue("I", value);
                if (value instanceof Long) return new ConstExprValue("J", value);
                if (value instanceof Float) return new ConstExprValue("F", value);
                if (value instanceof Double) return new ConstExprValue("D", value);
                if (value instanceof String) return new ConstExprValue("Ljava/lang/String;", value);
                return null;
            }
            if (instruction instanceof FieldInsnNode && opcode == Opcodes.GETSTATIC) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                ConstExprValue value = fields.get(ConstExprTransformer.key(field.owner, field.name, field.desc));
                return value instanceof ConstExprArrayValue ? null : value;
            }
            Frame<SourceValue> frame = frame(instruction);
            if (frame == null) return null;
            if (instruction instanceof VarInsnNode && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD)
                return resolve(frame.getLocal(((VarInsnNode) instruction).var));
            if (instruction instanceof VarInsnNode && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE)
                return top(frame);
            if (opcode == Opcodes.DUP || opcode == Opcodes.CHECKCAST) return top(frame);
            if (instruction instanceof MethodInsnNode && opcode == Opcodes.INVOKESTATIC) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (!"valueOf".equals(call.name) || !call.owner.startsWith("java/lang/")
                        || Type.getArgumentTypes(call.desc).length != 1) return null;
                ConstExprValue argument = top(frame);
                return argument == null ? null : new ConstExprValue(Type.getReturnType(call.desc).getDescriptor(), argument.value);
            }
            if (opcode == Opcodes.I2L || opcode == Opcodes.I2F || opcode == Opcodes.I2D
                    || opcode == Opcodes.L2I || opcode == Opcodes.L2F || opcode == Opcodes.L2D
                    || opcode == Opcodes.F2I || opcode == Opcodes.F2L || opcode == Opcodes.F2D
                    || opcode == Opcodes.D2I || opcode == Opcodes.D2L || opcode == Opcodes.D2F
                    || opcode == Opcodes.I2B || opcode == Opcodes.I2C || opcode == Opcodes.I2S) {
                ConstExprValue operand = top(frame);
                return operand == null ? null : convert(opcode, (Number) operand.value);
            }
            if (frame.getStackSize() > 0) {
                ConstExprValue right = top(frame);
                if (right == null) return null;
                ConstExprValue left = frame.getStackSize() > 1
                        ? resolve(frame.getStack(frame.getStackSize() - 2)) : null;
                return ConstExprTransformer.arithmetic(opcode, left == null ? null : left.value, right.value);
            }
            return null;
        } finally {
            active.remove(instruction);
        }
    }

    private ConstExprValue top(Frame<SourceValue> frame) {
        return frame.getStackSize() == 0 ? null : resolve(frame.getStack(frame.getStackSize() - 1));
    }

    private static ConstExprValue convert(int opcode, Number value) {
        switch (opcode) {
            case Opcodes.I2L: return new ConstExprValue("J", value.longValue());
            case Opcodes.I2F: case Opcodes.L2F: case Opcodes.D2F: return new ConstExprValue("F", value.floatValue());
            case Opcodes.I2D: case Opcodes.L2D: case Opcodes.F2D: return new ConstExprValue("D", value.doubleValue());
            case Opcodes.L2I: case Opcodes.F2I: case Opcodes.D2I: return new ConstExprValue("I", value.intValue());
            case Opcodes.F2L: case Opcodes.D2L: return new ConstExprValue("J", value.longValue());
            case Opcodes.I2B: return new ConstExprValue("I", (int) value.byteValue());
            case Opcodes.I2C: return new ConstExprValue("I", (int) (char) value.intValue());
            case Opcodes.I2S: return new ConstExprValue("I", (int) value.shortValue());
            default: return null;
        }
    }
}
