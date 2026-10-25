package xh.xianhhh.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.Handle;

import java.util.*;

/** Small, deliberately restricted JVM interpreter for @ConstExpr method bodies. */
final class ConstExprInterpreter {
    static final String MAX_ARRAY_ELEMENTS_PROPERTY = "xh.xianhhh.constexpr.maxArrayElements";
    static final String STEPS_PER_ARRAY_ELEMENT_PROPERTY = "xh.xianhhh.constexpr.stepsPerArrayElement";
    static final String MAX_STEP_LIMIT_PROPERTY = "xh.xianhhh.constexpr.maxStepLimit";
    static final String BASE_STEP_LIMIT_PROPERTY = "xh.xianhhh.constexpr.baseStepLimit";
    private static final long BASE_STEP_LIMIT = 100_000_000L;
    private static final long DEFAULT_STEPS_PER_ARRAY_ELEMENT = 24L;
    private static final long DEFAULT_MAX_STEP_LIMIT = 300_000_000L;
    // 2048 * 2048 matrices need 4,194,304 elements; leave room for practical large inputs.
    private static final int DEFAULT_MAX_ARRAY_ELEMENTS = 8_388_608;
    interface MethodResolver {
        ConstExprValue invoke(MethodInsnNode call, ConstExprValue receiver, List<ConstExprValue> arguments);
    }

    private final Map<String, ConstExprValue> fields;
    private final MethodResolver methods;

    ConstExprInterpreter(Map<String, ConstExprValue> fields, MethodResolver methods) {
        this.fields = fields;
        this.methods = methods;
    }

    ConstExprValue execute(String owner, MethodNode method, ConstExprValue receiver, List<ConstExprValue> arguments) {
        Deque<ConstExprValue> stack = new ArrayDeque<>();
        Map<Integer, ConstExprValue> locals = new HashMap<>();
        AbstractInsnNode[] instructions = method.instructions.toArray();
        Map<LabelNode, Integer> labels = new HashMap<>();
        for (int index = 0; index < instructions.length; index++) {
            if (instructions[index] instanceof LabelNode) labels.put((LabelNode) instructions[index], index);
        }
        int slot = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            if (receiver == null) fail(owner, method, "Instance method is missing a receiver");
            locals.put(slot++, receiver);
        }
        for (int index = 0; index < arguments.size(); index++) {
            Type type = Type.getArgumentTypes(method.desc)[index];
            locals.put(slot, arguments.get(index));
            slot += type.getSize();
        }
        int pc = 0;
        long steps = 0;
        long stepLimit = configuredLong(BASE_STEP_LIMIT_PROPERTY, BASE_STEP_LIMIT, 1L);
        long maxStepLimit = configuredLong(MAX_STEP_LIMIT_PROPERTY, DEFAULT_MAX_STEP_LIMIT, stepLimit);
        long stepsPerArrayElement = configuredLong(STEPS_PER_ARRAY_ELEMENT_PROPERTY,
                DEFAULT_STEPS_PER_ARRAY_ELEMENT, 0L);
        while (pc < instructions.length) {
            if (++steps > stepLimit) fail(owner, method, "Execution step limit exceeded (" + stepLimit + ")");
            AbstractInsnNode instruction = instructions[pc++];
            if (instruction instanceof LineNumberNode) {
                ConstExprException.setCurrentLine(((LineNumberNode) instruction).line);
            }
            int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            if (instruction instanceof LdcInsnNode) { stack.push(ldcValue(((LdcInsnNode) instruction).cst)); continue; }
            if (instruction instanceof FieldInsnNode && opcode == Opcodes.GETSTATIC) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                ConstExprValue value = fields.get(ConstExprTransformer.key(field.owner, field.name, field.desc));
                if (value == null && field.owner.startsWith("java/")) {
                    value = ConstExprJdkInvoker.staticField(field);
                }
                if (value == null) fail(owner, method, "Referenced field cannot be evaluated: " + field.name);
                stack.push(value); continue;
            }
            if (instruction instanceof FieldInsnNode && opcode == Opcodes.GETFIELD) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                ConstExprHeapObject object = object(stack.pop(), owner, method);
                stack.push(object.fields.getOrDefault(ConstExprTransformer.key(field.owner, field.name, field.desc), defaultValue(field.desc)));
                continue;
            }
            if (instruction instanceof FieldInsnNode && opcode == Opcodes.PUTFIELD) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                ConstExprValue value = stack.pop();
                ConstExprHeapObject object = object(stack.pop(), owner, method);
                object.fields.put(ConstExprTransformer.key(field.owner, field.name, field.desc), value);
                continue;
            }
            if (instruction instanceof VarInsnNode) {
                VarInsnNode variable = (VarInsnNode) instruction;
                if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) stack.push(required(locals.get(variable.var), owner, method));
                else if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) locals.put(variable.var, stack.pop());
                else fail(owner, method, "Unsupported local variable instruction: " + opcode);
                continue;
            }
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                Type[] types = Type.getArgumentTypes(call.desc);
                List<ConstExprValue> args = popArguments(stack, types.length);
                ConstExprValue target = opcode == Opcodes.INVOKESTATIC ? null : stack.pop();
                ConstExprValue result = methods.invoke(call, target, args);
                if (result != null) stack.push(result);
                continue;
            }
            if (instruction instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                if (isStringConcatBootstrap(dynamic)) {
                    Type[] argumentTypes = Type.getArgumentTypes(dynamic.desc);
                    List<ConstExprValue> values = popArguments(stack, argumentTypes.length);
                    stack.push(stringConcat(dynamic, values, owner, method));
                    continue;
                }
                if (!isLambdaBootstrap(dynamic)) {
                    fail(owner, method, "Unsupported invokedynamic: " + dynamic.name + dynamic.desc);
                }
                Type[] capturedTypes = Type.getArgumentTypes(dynamic.desc);
                List<ConstExprValue> captured = popArguments(stack, capturedTypes.length);
                Handle implementation = null;
                if (dynamic.bsmArgs != null) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle) { implementation = (Handle) argument; break; }
                    }
                }
                if (implementation == null) fail(owner, method, "Lambda has no implementation method handle: " + dynamic.name);
                stack.push(new ConstExprValue(Type.getReturnType(dynamic.desc).getDescriptor(),
                        new ConstExprLambda(implementation, captured)));
                continue;
            }
            switch (opcode) {
                case Opcodes.NOP: break;
                case Opcodes.ACONST_NULL: stack.push(new ConstExprValue("Ljava/lang/Object;", null)); break;
                case Opcodes.ICONST_M1: case Opcodes.ICONST_0: case Opcodes.ICONST_1: case Opcodes.ICONST_2: case Opcodes.ICONST_3: case Opcodes.ICONST_4: case Opcodes.ICONST_5: stack.push(new ConstExprValue("I", opcode - Opcodes.ICONST_0)); break;
                case Opcodes.LCONST_0: case Opcodes.LCONST_1: stack.push(new ConstExprValue("J", (long) (opcode - Opcodes.LCONST_0))); break;
                case Opcodes.FCONST_0: case Opcodes.FCONST_1: case Opcodes.FCONST_2: stack.push(new ConstExprValue("F", (float) (opcode - Opcodes.FCONST_0))); break;
                case Opcodes.DCONST_0: case Opcodes.DCONST_1: stack.push(new ConstExprValue("D", (double) (opcode - Opcodes.DCONST_0))); break;
                case Opcodes.BIPUSH: case Opcodes.SIPUSH: stack.push(new ConstExprValue("I", ((IntInsnNode) instruction).operand)); break;
                case Opcodes.NEW: {
                    TypeInsnNode type = (TypeInsnNode) instruction;
                    stack.push(new ConstExprValue("L" + type.desc + ";", new ConstExprHeapObject(type.desc)));
                    break;
                }
                case Opcodes.INSTANCEOF: {
                    ConstExprValue value = stack.pop();
                    String target = ((TypeInsnNode) instruction).desc.replace('/', '.');
                    boolean matches = value.value != null;
                    if (matches) try { matches = Class.forName(target).isInstance(value.value); }
                    catch (ClassNotFoundException error) { matches = ("L" + ((TypeInsnNode) instruction).desc + ";").equals(value.descriptor); }
                    stack.push(new ConstExprValue("I", matches ? 1 : 0));
                    break;
                }
                case Opcodes.CHECKCAST: break;
                case Opcodes.DUP: stack.push(stack.peek()); break;
                case Opcodes.POP: stack.pop(); break;
                case Opcodes.POP2: stack.pop(); break;
                case Opcodes.SWAP: { ConstExprValue a=stack.pop(), b=stack.pop(); stack.push(a); stack.push(b); break; }
                case Opcodes.DUP2: { ConstExprValue a=stack.pop(), b=stack.pop(); stack.push(b); stack.push(a); stack.push(b); stack.push(a); break; }
                case Opcodes.NEWARRAY: {
                    ConstExprArrayValue array = newArray((IntInsnNode) instruction, stack.pop(), owner, method);
                    long additionalSteps = saturatingMultiply(array.length(), stepsPerArrayElement);
                    stepLimit = Math.min(maxStepLimit, saturatingAdd(stepLimit, additionalSteps));
                    stack.push(array);
                    break;
                }
                case Opcodes.ANEWARRAY: {
                    TypeInsnNode type = (TypeInsnNode) instruction;
                    int count = intValue(stack.pop().value);
                    if (count < 0) fail(owner, method, "Array length must not be negative");
                    int maximum = maxArrayElements();
                    if (count > maximum) fail(owner, method, "Array length exceeds the compile-time limit: " + count + " (limit " + maximum + ")");
                    List<ConstExprValue> values = new ArrayList<ConstExprValue>(count);
                    String component = type.desc.startsWith("[") ? type.desc : "L" + type.desc + ";";
                    for (int index = 0; index < count; index++) values.add(defaultValue(component));
                    long additionalSteps = saturatingMultiply(count, stepsPerArrayElement);
                    stepLimit = Math.min(maxStepLimit, saturatingAdd(stepLimit, additionalSteps));
                    stack.push(ConstExprArrayValue.mutable("[" + component, values));
                    break;
                }
                case Opcodes.MULTIANEWARRAY: {
                    MultiANewArrayInsnNode multi = (MultiANewArrayInsnNode) instruction;
                    int[] lengths = new int[multi.dims];
                    for (int i = multi.dims - 1; i >= 0; i--) lengths[i] = intValue(stack.pop().value);
                    stack.push(multiArray(multi.desc, lengths, 0, owner, method));
                    break;
                }
                case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL: case Opcodes.IDIV: case Opcodes.IREM: stack.push(intOp(stack, opcode)); break;
                case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL: case Opcodes.LDIV: case Opcodes.LREM: stack.push(longOp(stack, opcode)); break;
                case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL: case Opcodes.FDIV: case Opcodes.FREM: stack.push(floatOp(stack, opcode)); break;
                case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL: case Opcodes.DDIV: case Opcodes.DREM: stack.push(doubleOp(stack, opcode)); break;
                case Opcodes.INEG: stack.push(new ConstExprValue("I", -intValue(stack.pop().value))); break;
                case Opcodes.LNEG: stack.push(new ConstExprValue("J", -((Number) stack.pop().value).longValue())); break;
                case Opcodes.FNEG: stack.push(new ConstExprValue("F", -((Number) stack.pop().value).floatValue())); break;
                case Opcodes.DNEG: stack.push(new ConstExprValue("D", -((Number) stack.pop().value).doubleValue())); break;
                case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR: {
                    int shift = ((Number) stack.pop().value).intValue();
                    long value = ((Number) stack.pop().value).longValue();
                    stack.push(new ConstExprValue("J", opcode == Opcodes.LSHL ? value << shift
                            : opcode == Opcodes.LSHR ? value >> shift : value >>> shift));
                    break;
                }
                case Opcodes.LOR: {
                    long right = ((Number) stack.pop().value).longValue();
                    stack.push(new ConstExprValue("J", ((Number) stack.pop().value).longValue() | right));
                    break;
                }
                case Opcodes.LAND: case Opcodes.LXOR: {
                    long right = ((Number) stack.pop().value).longValue();
                    long left = ((Number) stack.pop().value).longValue();
                    stack.push(new ConstExprValue("J", opcode == Opcodes.LAND ? left & right : left ^ right));
                    break;
                }
                case Opcodes.IAND: case Opcodes.IOR: case Opcodes.IXOR: {
                    int right = intValue(stack.pop().value);
                    int left = intValue(stack.pop().value);
                    stack.push(new ConstExprValue("I", opcode == Opcodes.IAND ? left & right
                            : opcode == Opcodes.IOR ? left | right : left ^ right));
                    break;
                }
                case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR: {
                    int shift = intValue(stack.pop().value) & 31;
                    int value = intValue(stack.pop().value);
                    stack.push(new ConstExprValue("I", opcode == Opcodes.ISHL ? value << shift
                            : opcode == Opcodes.ISHR ? value >> shift : value >>> shift));
                    break;
                }
                case Opcodes.I2L: stack.push(new ConstExprValue("J", (long) intValue(stack.pop().value))); break;
                case Opcodes.I2F: stack.push(new ConstExprValue("F", (float) intValue(stack.pop().value))); break;
                case Opcodes.I2D: stack.push(new ConstExprValue("D", (double) intValue(stack.pop().value))); break;
                case Opcodes.L2I: stack.push(new ConstExprValue("I", ((Number) stack.pop().value).intValue())); break;
                case Opcodes.L2D: stack.push(new ConstExprValue("D", ((Number) stack.pop().value).doubleValue())); break;
                case Opcodes.D2F: stack.push(new ConstExprValue("F", ((Number) stack.pop().value).floatValue())); break;
                case Opcodes.D2I: stack.push(new ConstExprValue("I", ((Number) stack.pop().value).intValue())); break;
                case Opcodes.D2L: stack.push(new ConstExprValue("J", ((Number) stack.pop().value).longValue())); break;
                case Opcodes.F2I: stack.push(new ConstExprValue("I", ((Number) stack.pop().value).intValue())); break;
                case Opcodes.F2L: stack.push(new ConstExprValue("J", ((Number) stack.pop().value).longValue())); break;
                case Opcodes.L2F: stack.push(new ConstExprValue("F", ((Number) stack.pop().value).floatValue())); break;
                case Opcodes.F2D: stack.push(new ConstExprValue("D", ((Number) stack.pop().value).doubleValue())); break;
                case Opcodes.I2B: stack.push(new ConstExprValue("I", (byte) intValue(stack.pop().value))); break;
                case Opcodes.I2C: stack.push(new ConstExprValue("I", (char) intValue(stack.pop().value))); break;
                case Opcodes.I2S: stack.push(new ConstExprValue("I", (short) intValue(stack.pop().value))); break;
                case Opcodes.ARRAYLENGTH: stack.push(new ConstExprValue("I", array(stack.pop(), owner, method).length())); break;
                case Opcodes.LCMP: {
                    long right = ((Number) stack.pop().value).longValue();
                    long left = ((Number) stack.pop().value).longValue();
                    stack.push(new ConstExprValue("I", left < right ? -1 : (left == right ? 0 : 1)));
                    break;
                }
                case Opcodes.FCMPL: case Opcodes.FCMPG: {
                    float right = ((Number) stack.pop().value).floatValue();
                    float left = ((Number) stack.pop().value).floatValue();
                    int result = (Float.isNaN(left) || Float.isNaN(right)) ? (opcode == Opcodes.FCMPL ? -1 : 1) : (left < right ? -1 : (left == right ? 0 : 1));
                    stack.push(new ConstExprValue("I", result));
                    break;
                }
                case Opcodes.DCMPL: case Opcodes.DCMPG: {
                    double right = ((Number) stack.pop().value).doubleValue();
                    double left = ((Number) stack.pop().value).doubleValue();
                    int result = (Double.isNaN(left) || Double.isNaN(right)) ? (opcode == Opcodes.DCMPL ? -1 : 1) : (left < right ? -1 : (left == right ? 0 : 1));
                    stack.push(new ConstExprValue("I", result));
                    break;
                }
                case Opcodes.IALOAD: case Opcodes.LALOAD: case Opcodes.FALOAD: case Opcodes.DALOAD: case Opcodes.AALOAD:
                        case Opcodes.BALOAD: case Opcodes.CALOAD: case Opcodes.SALOAD: {
                    int index = ((Number) stack.pop().value).intValue();
                    ConstExprArrayValue array = array(stack.pop(), owner, method);
                    if (index < 0 || index >= array.length()) fail(owner, method, "Array index out of bounds: " + index);
                    stack.push(array.element(index));
                    break;
                }
                case Opcodes.IASTORE: case Opcodes.LASTORE: case Opcodes.FASTORE: case Opcodes.DASTORE:
                        case Opcodes.AASTORE: case Opcodes.BASTORE: case Opcodes.CASTORE: case Opcodes.SASTORE: {
                    ConstExprValue value = stack.pop();
                    int index = ((Number) stack.pop().value).intValue();
                    ConstExprArrayValue array = array(stack.pop(), owner, method);
                    if (index < 0 || index >= array.length()) fail(owner, method, "Array index out of bounds: " + index);
                    array.set(index, value);
                    break;
                }
                case Opcodes.IINC: {
                    IincInsnNode increment = (IincInsnNode) instruction;
                    ConstExprValue current = required(locals.get(increment.var), owner, method);
                    locals.put(increment.var, new ConstExprValue("I", ((Number) current.value).intValue() + increment.incr));
                    break;
                }
                case Opcodes.GOTO: pc = label(labels, ((JumpInsnNode) instruction).label, owner, method); break;
                case Opcodes.TABLESWITCH: {
                    int selector = intValue(stack.pop().value);
                    TableSwitchInsnNode table = (TableSwitchInsnNode) instruction;
                    LabelNode target = selector < table.min || selector > table.max
                            ? table.dflt : table.labels.get(selector - table.min);
                    pc = label(labels, target, owner, method);
                    break;
                }
                case Opcodes.LOOKUPSWITCH: {
                    int selector = intValue(stack.pop().value);
                    LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) instruction;
                    int index = lookup.keys.indexOf(selector);
                    pc = label(labels, index < 0 ? lookup.dflt : lookup.labels.get(index), owner, method);
                    break;
                }
                case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT: case Opcodes.IFGE: case Opcodes.IFGT: case Opcodes.IFLE: {
                    int value = intValue(stack.pop().value);
                    if (unaryBranch(opcode, value)) pc = label(labels, ((JumpInsnNode) instruction).label, owner, method);
                    break;
                }
                case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE: case Opcodes.IF_ICMPLT: case Opcodes.IF_ICMPGE:
                case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE: {
                    int right = intValue(stack.pop().value);
                    int left = intValue(stack.pop().value);
                    if (integerBranch(opcode, left, right)) pc = label(labels, ((JumpInsnNode) instruction).label, owner, method);
                    break;
                }
                case Opcodes.IFNULL: case Opcodes.IFNONNULL: {
                    ConstExprValue value = stack.pop();
                    boolean isNull = value.value == null;
                    if ((opcode == Opcodes.IFNULL && isNull) || (opcode == Opcodes.IFNONNULL && !isNull)) pc = label(labels, ((JumpInsnNode) instruction).label, owner, method);
                    break;
                }
                case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE: {
                    ConstExprValue right = stack.pop();
                    ConstExprValue left = stack.pop();
                    boolean equal = left.value == right.value;
                    if ((opcode == Opcodes.IF_ACMPEQ && equal) || (opcode == Opcodes.IF_ACMPNE && !equal)) pc = label(labels, ((JumpInsnNode) instruction).label, owner, method);
                    break;
                }
                case Opcodes.RETURN: { return null; }
                case Opcodes.IRETURN: case Opcodes.LRETURN: case Opcodes.FRETURN: case Opcodes.DRETURN: case Opcodes.ARETURN: { return stack.pop(); }
                default: fail(owner, method, "Contains an unsupported instruction: " + opcode);
            }
        }
        fail(owner, method, "Missing return instruction"); return null;
    }

    private static List<ConstExprValue> popArguments(Deque<ConstExprValue> stack, int count) {
        List<ConstExprValue> result = new ArrayList<>(Collections.nCopies(count, null));
        for (int index = count - 1; index >= 0; index--) result.set(index, stack.pop());
        return result;
    }
    private static ConstExprValue ldcValue(Object value) {
        if (value instanceof Integer) return new ConstExprValue("I", value);
        if (value instanceof Long) return new ConstExprValue("J", value);
        if (value instanceof Float) return new ConstExprValue("F", value);
        if (value instanceof Double) return new ConstExprValue("D", value);
        if (value instanceof String) return new ConstExprValue("Ljava/lang/String;", value);
        if (value instanceof Type) return new ConstExprValue("Ljava/lang/Class;", value);
        throw new ConstExprException("Unsupported LDC constant: " + value);
    }
    private static boolean isLambdaBootstrap(InvokeDynamicInsnNode dynamic) {
        if (dynamic == null || dynamic.bsm == null
                || !"java/lang/invoke/LambdaMetafactory".equals(dynamic.bsm.getOwner())) return false;
        return "metafactory".equals(dynamic.bsm.getName())
                || "altMetafactory".equals(dynamic.bsm.getName());
    }
    private static boolean isStringConcatBootstrap(InvokeDynamicInsnNode dynamic) {
        if (dynamic == null || dynamic.bsm == null
                || !"java/lang/invoke/StringConcatFactory".equals(dynamic.bsm.getOwner())) return false;
        return "makeConcat".equals(dynamic.bsm.getName())
                || "makeConcatWithConstants".equals(dynamic.bsm.getName());
    }
    private static ConstExprValue stringConcat(InvokeDynamicInsnNode dynamic,
                                                List<ConstExprValue> values,
                                                String owner, MethodNode method) {
        String recipe = null;
        List<Object> constants = new ArrayList<Object>();
        if (dynamic.bsmArgs != null) {
            for (Object argument : dynamic.bsmArgs) {
                if (recipe == null && argument instanceof String) recipe = (String) argument;
                else constants.add(argument);
            }
        }
        if (recipe == null) recipe = "\u0001";
        StringBuilder result = new StringBuilder();
        int valueIndex = 0;
        int constantIndex = 0;
        for (int index = 0; index < recipe.length(); index++) {
            char marker = recipe.charAt(index);
            if (marker == '\u0001') {
                if (valueIndex >= values.size()) fail(owner, method, "String concatenation argument count does not match");
                result.append(stringValue(values.get(valueIndex++)));
            } else if (marker == '\u0002') {
                if (constantIndex >= constants.size()) fail(owner, method, "String concatenation constant count does not match");
                result.append(String.valueOf(constants.get(constantIndex++)));
            } else result.append(marker);
        }
        while (valueIndex < values.size()) result.append(stringValue(values.get(valueIndex++)));
        return new ConstExprValue("Ljava/lang/String;", result.toString());
    }
    private static String stringValue(ConstExprValue value) {
        if (value == null || value.value == null) return "null";
        if (value.value instanceof Character) return String.valueOf(((Character) value.value).charValue());
        return String.valueOf(value.value);
    }
    private static ConstExprValue intOp(Deque<ConstExprValue> stack, int opcode) { int right=intValue(stack.pop().value), left=intValue(stack.pop().value); int v; switch(opcode){case Opcodes.IADD:v=left+right;break;case Opcodes.ISUB:v=left-right;break;case Opcodes.IMUL:v=left*right;break;case Opcodes.IDIV:v=left/right;break;default:v=left%right;} return new ConstExprValue("I",v); }
    private static ConstExprValue longOp(Deque<ConstExprValue> stack, int opcode) { long right=longValue(stack.pop().value), left=longValue(stack.pop().value); long v; switch(opcode){case Opcodes.LADD:v=left+right;break;case Opcodes.LSUB:v=left-right;break;case Opcodes.LMUL:v=left*right;break;case Opcodes.LDIV:v=left/right;break;default:v=left%right;} return new ConstExprValue("J",v); }
    private static ConstExprValue floatOp(Deque<ConstExprValue> stack, int opcode) { float right=((Number)stack.pop().value).floatValue(), left=((Number)stack.pop().value).floatValue(); float v; switch(opcode){case Opcodes.FADD:v=left+right;break;case Opcodes.FSUB:v=left-right;break;case Opcodes.FMUL:v=left*right;break;case Opcodes.FDIV:v=left/right;break;default:v=left%right;} return new ConstExprValue("F",v); }
    private static ConstExprValue doubleOp(Deque<ConstExprValue> stack, int opcode) { double right=((Number)stack.pop().value).doubleValue(), left=((Number)stack.pop().value).doubleValue(); double v; switch(opcode){case Opcodes.DADD:v=left+right;break;case Opcodes.DSUB:v=left-right;break;case Opcodes.DMUL:v=left*right;break;case Opcodes.DDIV:v=left/right;break;default:v=left%right;} return new ConstExprValue("D",v); }
    private static ConstExprArrayValue newArray(IntInsnNode instruction, ConstExprValue length, String owner, MethodNode method) {
        int count = ((Number) length.value).intValue();
        if (count < 0) fail(owner, method, "Array length must not be negative");
        int maximum = maxArrayElements();
        if (count > maximum) fail(owner, method, "Array length exceeds the compile-time limit: " + count + " (limit " + maximum + ")");
        String component;
        switch (instruction.operand) {
            case Opcodes.T_BOOLEAN: component="Z"; break; case Opcodes.T_BYTE: component="B"; break; case Opcodes.T_CHAR: component="C"; break;
            case Opcodes.T_SHORT: component="S"; break; case Opcodes.T_INT: component="I"; break; case Opcodes.T_LONG: component="J"; break;
            case Opcodes.T_FLOAT: component="F"; break; case Opcodes.T_DOUBLE: component="D"; break;
            default: throw new ConstExprException("Unsupported NEWARRAY type: " + instruction.operand);
        }
        Object zero;
        if ("Z".equals(component)) zero=false; else if ("J".equals(component)) zero=0L;
        else if ("F".equals(component)) zero=0F; else if ("D".equals(component)) zero=0D; else zero=0;
        List<ConstExprValue> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(new ConstExprValue(component, zero));
        return ConstExprArrayValue.mutable("[" + component, values);
    }

    private static ConstExprArrayValue multiArray(String descriptor, int[] lengths, int depth, String owner, MethodNode method) {
        int count = lengths[depth];
        if (count < 0 || count > maxArrayElements()) fail(owner, method, "Invalid array length: " + count);
        String component = descriptor.substring(1);
        List<ConstExprValue> values = new ArrayList<ConstExprValue>(count);
        for (int i = 0; i < count; i++) {
            if (depth + 1 < lengths.length) values.add(multiArray(component, lengths, depth + 1, owner, method));
            else values.add(defaultValue(component));
        }
        return ConstExprArrayValue.mutable(descriptor, values);
    }
    static int maxArrayElements() {
        String configured = System.getProperty(MAX_ARRAY_ELEMENTS_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) return DEFAULT_MAX_ARRAY_ELEMENTS;
        try {
            int value = Integer.parseInt(configured.trim());
            if (value < 1) throw new NumberFormatException("Must be greater than 0");
            return value;
        } catch (NumberFormatException error) {
            throw new ConstExprException("System property " + MAX_ARRAY_ELEMENTS_PROPERTY + " must be a positive integer: " + configured, error);
        }
    }
    private static long configuredLong(String property, long defaultValue, long minimum) {
        String configured = System.getProperty(property);
        if (configured == null || configured.trim().isEmpty()) return defaultValue;
        try {
            long value = Long.parseLong(configured.trim());
            if (value < minimum) throw new NumberFormatException("Below minimum " + minimum);
            return value;
        } catch (NumberFormatException error) {
            throw new ConstExprException("System property " + property + " must be an integer of at least " + minimum + ": " + configured, error);
        }
    }
    private static long saturatingMultiply(long left, long right) {
        if (left == 0 || right == 0) return 0;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }
    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
    private static boolean unaryBranch(int opcode, int value) { if(opcode==Opcodes.IFEQ)return value==0; if(opcode==Opcodes.IFNE)return value!=0; if(opcode==Opcodes.IFLT)return value<0; if(opcode==Opcodes.IFGE)return value>=0; if(opcode==Opcodes.IFGT)return value>0; return value<=0; }
    private static int intValue(Object value) {
        if (value instanceof Boolean) return ((Boolean) value) ? 1 : 0;
        if (value instanceof Character) return ((Character) value).charValue();
        return ((Number) value).intValue();
    }
    private static long longValue(Object value) {
        if (value instanceof Boolean) return ((Boolean) value) ? 1L : 0L;
        if (value instanceof Character) return ((Character) value).charValue();
        return ((Number) value).longValue();
    }
    private static boolean integerBranch(int opcode, int left, int right) { if(opcode==Opcodes.IF_ICMPEQ)return left==right; if(opcode==Opcodes.IF_ICMPNE)return left!=right; if(opcode==Opcodes.IF_ICMPLT)return left<right; if(opcode==Opcodes.IF_ICMPGE)return left>=right; if(opcode==Opcodes.IF_ICMPGT)return left>right; return left<=right; }
    private static int label(Map<LabelNode, Integer> labels, LabelNode label, String owner, MethodNode method) { Integer target = labels.get(label); if (target == null) fail(owner, method, "Jump label not found"); return target; }
    private static ConstExprArrayValue array(ConstExprValue value, String owner, MethodNode method) { if (value instanceof ConstExprArrayValue) return (ConstExprArrayValue) value; fail(owner, method, "Expected a compile-time array"); return null; }
    private static ConstExprHeapObject object(ConstExprValue value, String owner, MethodNode method) { if (value.value instanceof ConstExprHeapObject) return (ConstExprHeapObject) value.value; fail(owner, method, "Expected a compile-time object"); return null; }
    private static ConstExprValue defaultValue(String descriptor) {
        if (descriptor.startsWith("L") || descriptor.startsWith("[")) return new ConstExprValue(descriptor, null);
        if ("J".equals(descriptor)) return new ConstExprValue(descriptor, 0L);
        if ("F".equals(descriptor)) return new ConstExprValue(descriptor, 0F);
        if ("D".equals(descriptor)) return new ConstExprValue(descriptor, 0D);
        if ("Z".equals(descriptor)) return new ConstExprValue(descriptor, false);
        return new ConstExprValue(descriptor, 0);
    }
    private static ConstExprValue required(ConstExprValue value, String owner, MethodNode method) { if (value == null) fail(owner, method, "Read an uninitialized local variable"); return value; }
    private static void fail(String owner, MethodNode method, String message) {
        throw new ConstExprException("@ConstExpr method " + owner + "." + method.name + method.desc + ": " + message)
                .withFrame(owner, method.name, method.desc);
    }
}
