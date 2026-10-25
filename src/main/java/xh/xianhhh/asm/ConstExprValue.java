package xh.xianhhh.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** A value known while transforming bytecode. */
class ConstExprValue {
    final String descriptor;
    final Object value;

    ConstExprValue(String descriptor, Object value) {
        this.descriptor = descriptor;
        this.value = value;
    }

    boolean isArray() { return false; }

    void push(InsnList out) {
        if (value == null) { out.add(new InsnNode(Opcodes.ACONST_NULL)); return; }
        if (value instanceof EnumConstant) {
            EnumConstant constant = (EnumConstant) value;
            out.add(new FieldInsnNode(Opcodes.GETSTATIC, constant.owner, constant.name, constant.descriptor));
            return;
        }
        switch (descriptor) {
            case "I": case "S": case "B": pushInt(out, ((Number) value).intValue()); break;
            case "C": pushInt(out, value instanceof Character ? (Character) value : ((Number) value).intValue()); break;
            case "Z": out.add(new InsnNode((value instanceof Boolean ? (Boolean) value
                    : ((Number) value).intValue() != 0) ? Opcodes.ICONST_1 : Opcodes.ICONST_0)); break;
            case "Ljava/lang/Class;": out.add(new LdcInsnNode((Type) value)); break;
            case "Ljava/lang/Integer;": pushInt(out, ((Number) value).intValue()); box(out, "java/lang/Integer", "(I)Ljava/lang/Integer;"); break;
            case "Ljava/lang/Long;": out.add(new LdcInsnNode(((Number) value).longValue())); box(out, "java/lang/Long", "(J)Ljava/lang/Long;"); break;
            case "Ljava/lang/Float;": out.add(new LdcInsnNode(((Number) value).floatValue())); box(out, "java/lang/Float", "(F)Ljava/lang/Float;"); break;
            case "Ljava/lang/Double;": out.add(new LdcInsnNode(((Number) value).doubleValue())); box(out, "java/lang/Double", "(D)Ljava/lang/Double;"); break;
            case "Ljava/lang/Boolean;": out.add(new InsnNode((Boolean) value ? Opcodes.ICONST_1 : Opcodes.ICONST_0)); box(out, "java/lang/Boolean", "(Z)Ljava/lang/Boolean;"); break;
            case "Ljava/lang/Byte;": pushInt(out, ((Number) value).byteValue()); box(out, "java/lang/Byte", "(B)Ljava/lang/Byte;"); break;
            case "Ljava/lang/Short;": pushInt(out, ((Number) value).shortValue()); box(out, "java/lang/Short", "(S)Ljava/lang/Short;"); break;
            case "Ljava/lang/Character;": pushInt(out, ((Number) value).intValue()); box(out, "java/lang/Character", "(C)Ljava/lang/Character;"); break;
            default: out.add(new LdcInsnNode(value)); break;
        }
    }

    private static void box(InsnList out, String owner, String descriptor) {
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", descriptor, false));
    }

    static void pushInt(InsnList out, int value) {
        if (value >= -1 && value <= 5) out.add(new InsnNode(Opcodes.ICONST_0 + value));
        else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) out.add(new IntInsnNode(Opcodes.BIPUSH, value));
        else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) out.add(new IntInsnNode(Opcodes.SIPUSH, value));
        else out.add(new LdcInsnNode(value));
    }

    static final class EnumConstant {
        final String owner; final String name; final String descriptor;
        EnumConstant(String owner, String name, String descriptor) { this.owner = owner; this.name = name; this.descriptor = descriptor; }
        String owner() { return owner; } String name() { return name; } String descriptor() { return descriptor; }
        public boolean equals(Object o) { if (!(o instanceof EnumConstant)) return false; EnumConstant e=(EnumConstant)o; return owner.equals(e.owner)&&name.equals(e.name)&&descriptor.equals(e.descriptor); }
        public int hashCode() { return owner.hashCode()*31*31+name.hashCode()*31+descriptor.hashCode(); }
    }
}
