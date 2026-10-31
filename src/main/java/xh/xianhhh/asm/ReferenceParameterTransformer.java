package xh.xianhhh.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Rewrites supported annotated primitive parameters and their local call sites. */
final class ReferenceParameterTransformer {
    private static final String IN = "Lxh/xianhhh/macro/In;";
    private static final String OUT = "Lxh/xianhhh/macro/Out;";
    private static final String REF = "Lxh/xianhhh/macro/Ref;";
    private static final String PREFIX = "$macrojava$byref$";

    private ReferenceParameterTransformer() { }

    static void rewrite(ClassNode node, Map<String, ClassNode> projectClasses) {
        for (MethodNode method : new ArrayList<>(node.methods)) {
            int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
            Type[] parameters = Type.getArgumentTypes(method.desc);
            for (int index = 0; index < parameters.length; index++) {
                if (has(method, index, IN)) checkIn(node, method, slot);
                if (has(method, index, OUT) || has(method, index, REF)) {
                    checkSupported(node, method, parameters, index);
                    if (has(method, index, OUT)) checkOut(node, method);
                    node.methods.add(companion(method, parameters[0]));
                }
                slot += parameters[index].getSize();
            }
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                ClassNode target = projectClasses.get(call.owner);
                if (target == null) continue;
                MethodNode original = find(target, call.name, call.desc);
                if (original == null || !has(original, 0, OUT) && !has(original, 0, REF)) continue;
                Type parameter = Type.getArgumentTypes(call.desc)[0];
                AbstractInsnNode argument = previous(call.getPrevious());
                if (call.getOpcode() != Opcodes.INVOKESTATIC || !(argument instanceof VarInsnNode)
                        || argument.getOpcode() != parameter.getOpcode(Opcodes.ILOAD))
                    throw new ConstExprException("@Out/@Ref requires a local variable argument: "
                            + node.name + "." + method.name + method.desc + " -> " + call.owner + "." + call.name);
                int local = ((VarInsnNode) argument).var;
                if (has(original, 0, OUT)) method.instructions.set(argument, defaultValue(parameter));
                int holder = method.maxLocals++;
                InsnList allocation = new InsnList();
                allocation.add(new InsnNode(Opcodes.ICONST_1));
                allocation.add(new IntInsnNode(Opcodes.NEWARRAY, arrayType(parameter)));
                allocation.add(new VarInsnNode(Opcodes.ASTORE, holder));
                method.instructions.insertBefore(call, allocation);
                method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, holder));
                call.name = PREFIX + call.name;
                Type result = Type.getReturnType(call.desc);
                call.desc = Type.getMethodDescriptor(result, parameter, Type.getType("[" + parameter.getDescriptor()));
                InsnList writeBack = new InsnList();
                int savedResult = -1;
                if (result.getSort() != Type.VOID) {
                    savedResult = method.maxLocals;
                    method.maxLocals += result.getSize();
                    writeBack.add(new VarInsnNode(result.getOpcode(Opcodes.ISTORE), savedResult));
                }
                writeBack.add(new VarInsnNode(Opcodes.ALOAD, holder));
                writeBack.add(new InsnNode(Opcodes.ICONST_0));
                writeBack.add(new InsnNode(parameter.getOpcode(Opcodes.IALOAD)));
                writeBack.add(new VarInsnNode(parameter.getOpcode(Opcodes.ISTORE), local));
                if (savedResult >= 0)
                    writeBack.add(new VarInsnNode(result.getOpcode(Opcodes.ILOAD), savedResult));
                method.instructions.insert(call, writeBack);
                method.maxStack += 4;
            }
        }
    }

    private static void checkSupported(ClassNode owner, MethodNode method, Type[] parameters, int index) {
        if (parameters.length != 1 || index != 0 || (method.access & Opcodes.ACC_STATIC) == 0
                || !primitive(parameters[0]) || has(method, 0, IN) || has(method, 0, OUT) && has(method, 0, REF))
            throw new ConstExprException("@Out/@Ref requires a static method with exactly one primitive parameter: "
                    + owner.name + "." + method.name + method.desc);
    }

    private static MethodNode companion(MethodNode original, Type parameter) {
        Type result = Type.getReturnType(original.desc);
        String descriptor = Type.getMethodDescriptor(result, parameter, Type.getType("[" + parameter.getDescriptor()));
        MethodNode copy = new MethodNode(original.access | Opcodes.ACC_SYNTHETIC,
                PREFIX + original.name, descriptor, null,
                original.exceptions == null ? null : original.exceptions.toArray(new String[0]));
        original.accept(copy);
        int holder = parameter.getSize();
        for (AbstractInsnNode instruction : copy.instructions) {
            if (instruction instanceof VarInsnNode && ((VarInsnNode) instruction).var >= holder)
                ((VarInsnNode) instruction).var++;
            else if (instruction instanceof IincInsnNode && ((IincInsnNode) instruction).var >= holder)
                ((IincInsnNode) instruction).var++;
        }
        if (copy.localVariables != null) for (LocalVariableNode local : copy.localVariables)
            if (local.index >= holder) local.index++;
        shiftLocalAnnotations(copy.visibleLocalVariableAnnotations, holder);
        shiftLocalAnnotations(copy.invisibleLocalVariableAnnotations, holder);
        copy.maxLocals++;
        copy.maxStack += 4;
        int savedResult = copy.maxLocals;
        if (result.getSort() != Type.VOID) copy.maxLocals += result.getSize();
        for (AbstractInsnNode instruction : copy.instructions.toArray()) {
            int opcode = instruction.getOpcode();
            if (opcode < Opcodes.IRETURN || opcode > Opcodes.RETURN) continue;
            InsnList writeBack = new InsnList();
            if (result.getSort() != Type.VOID)
                writeBack.add(new VarInsnNode(result.getOpcode(Opcodes.ISTORE), savedResult));
            writeBack.add(new VarInsnNode(Opcodes.ALOAD, holder));
            writeBack.add(new InsnNode(Opcodes.ICONST_0));
            writeBack.add(new VarInsnNode(parameter.getOpcode(Opcodes.ILOAD), 0));
            writeBack.add(new InsnNode(parameter.getOpcode(Opcodes.IASTORE)));
            if (result.getSort() != Type.VOID)
                writeBack.add(new VarInsnNode(result.getOpcode(Opcodes.ILOAD), savedResult));
            copy.instructions.insertBefore(instruction, writeBack);
        }
        return copy;
    }

    private static void shiftLocalAnnotations(List<LocalVariableAnnotationNode> annotations, int holder) {
        if (annotations == null) return;
        for (LocalVariableAnnotationNode annotation : annotations)
            for (int index = 0; index < annotation.index.size(); index++)
                if (annotation.index.get(index) >= holder)
                    annotation.index.set(index, annotation.index.get(index) + 1);
    }

    private static int arrayType(Type parameter) {
        switch (parameter.getSort()) {
            case Type.BOOLEAN: return Opcodes.T_BOOLEAN;
            case Type.CHAR: return Opcodes.T_CHAR;
            case Type.BYTE: return Opcodes.T_BYTE;
            case Type.SHORT: return Opcodes.T_SHORT;
            case Type.INT: return Opcodes.T_INT;
            case Type.FLOAT: return Opcodes.T_FLOAT;
            case Type.LONG: return Opcodes.T_LONG;
            case Type.DOUBLE: return Opcodes.T_DOUBLE;
            default: throw new IllegalArgumentException("Not a primitive parameter: " + parameter);
        }
    }

    private static void checkIn(ClassNode owner, MethodNode method, int slot) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof VarInsnNode && ((VarInsnNode) instruction).var == slot
                    && instruction.getOpcode() >= Opcodes.ISTORE && instruction.getOpcode() <= Opcodes.ASTORE
                    || instruction instanceof IincInsnNode && ((IincInsnNode) instruction).var == slot)
                throw new ConstExprException("@In parameter cannot be reassigned: "
                        + owner.name + "." + method.name + method.desc);
        }
    }

    private static void checkOut(ClassNode owner, MethodNode method) {
        AbstractInsnNode[] instructions = method.instructions.toArray();
        Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < instructions.length; index++) positions.put(instructions[index], index);
        Boolean[] assignedAt = new Boolean[instructions.length];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        reach(assignedAt, pending, 0, false);
        while (!pending.isEmpty()) {
            int index = pending.removeFirst();
            AbstractInsnNode instruction = instructions[index];
            int opcode = instruction.getOpcode();
            boolean assigned = assignedAt[index];
            boolean after = assigned;
            if (instruction instanceof VarInsnNode && ((VarInsnNode) instruction).var == 0) {
                if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD && !assigned)
                    outError(owner, method, "reads the parameter before assignment");
                if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) after = true;
            } else if (instruction instanceof IincInsnNode && ((IincInsnNode) instruction).var == 0) {
                if (!assigned) outError(owner, method, "increments the parameter before assignment");
            }
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN && !after)
                outError(owner, method, "can return without assigning the parameter");
            if (method.tryCatchBlocks != null) for (TryCatchBlockNode handler : method.tryCatchBlocks) {
                int start = positions.get(handler.start), end = positions.get(handler.end);
                if (index >= start && index < end && mayThrow(opcode))
                    reach(assignedAt, pending, positions.get(handler.handler), assigned);
            }
            if (instruction instanceof JumpInsnNode) {
                if (opcode == Opcodes.JSR) outError(owner, method, "uses unsupported JSR control flow");
                reach(assignedAt, pending, positions.get(((JumpInsnNode) instruction).label), after);
                if (opcode != Opcodes.GOTO) reach(assignedAt, pending, index + 1, after);
            } else if (instruction instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode branch = (TableSwitchInsnNode) instruction;
                reach(assignedAt, pending, positions.get(branch.dflt), after);
                for (LabelNode label : branch.labels) reach(assignedAt, pending, positions.get(label), after);
            } else if (instruction instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode branch = (LookupSwitchInsnNode) instruction;
                reach(assignedAt, pending, positions.get(branch.dflt), after);
                for (LabelNode label : branch.labels) reach(assignedAt, pending, positions.get(label), after);
            } else if (opcode == Opcodes.RET) {
                outError(owner, method, "uses unsupported RET control flow");
            } else if ((opcode < Opcodes.IRETURN || opcode > Opcodes.RETURN) && opcode != Opcodes.ATHROW)
                reach(assignedAt, pending, index + 1, after);
        }
    }

    private static void reach(Boolean[] assignedAt, ArrayDeque<Integer> pending, int index, boolean assigned) {
        if (index < 0 || index >= assignedAt.length) return;
        Boolean previous = assignedAt[index];
        if (previous == null || previous && !assigned) {
            assignedAt[index] = assigned;
            pending.add(index);
        }
    }

    private static boolean mayThrow(int opcode) {
        return opcode == Opcodes.IDIV || opcode == Opcodes.IREM || opcode == Opcodes.LDIV
                || opcode == Opcodes.LREM || opcode == Opcodes.ATHROW
                || opcode == Opcodes.NEW || opcode == Opcodes.NEWARRAY || opcode == Opcodes.ANEWARRAY
                || opcode == Opcodes.MULTIANEWARRAY || opcode == Opcodes.ARRAYLENGTH
                || opcode == Opcodes.CHECKCAST
                || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT
                || opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD
                || opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE
                || opcode >= Opcodes.GETSTATIC && opcode <= Opcodes.PUTFIELD
                || opcode >= Opcodes.INVOKEVIRTUAL && opcode <= Opcodes.INVOKEDYNAMIC;
    }

    private static void outError(ClassNode owner, MethodNode method, String reason) {
        throw new ConstExprException("@Out " + reason + ": " + owner.name + "." + method.name + method.desc);
    }

    private static InsnNode defaultValue(Type parameter) {
        switch (parameter.getSort()) {
            case Type.LONG: return new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT: return new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE: return new InsnNode(Opcodes.DCONST_0);
            default: return new InsnNode(Opcodes.ICONST_0);
        }
    }

    private static boolean primitive(Type type) {
        return type.getSort() >= Type.BOOLEAN && type.getSort() <= Type.DOUBLE;
    }

    private static boolean has(MethodNode method, int index, String descriptor) {
        return contains(method.visibleParameterAnnotations, index, descriptor)
                || contains(method.invisibleParameterAnnotations, index, descriptor);
    }

    private static boolean contains(List<AnnotationNode>[] annotations, int index, String descriptor) {
        if (annotations == null || index >= annotations.length || annotations[index] == null) return false;
        for (AnnotationNode annotation : annotations[index]) if (descriptor.equals(annotation.desc)) return true;
        return false;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods)
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        return null;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        while (instruction instanceof LabelNode || instruction instanceof LineNumberNode
                || instruction instanceof FrameNode) instruction = instruction.getPrevious();
        return instruction;
    }
}
