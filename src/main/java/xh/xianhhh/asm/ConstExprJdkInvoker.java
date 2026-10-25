package xh.xianhhh.asm;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.util.List;

/** Generic adapter for Java platform classes whose bytecode is not part of the compile classpath. */
final class ConstExprJdkInvoker {
    private ConstExprJdkInvoker() { }

    static ConstExprValue invoke(MethodInsnNode call, ConstExprValue receiver, List<ConstExprValue> arguments) {
        return invoke(call, receiver, arguments, false);
    }

    static ConstExprValue invoke(MethodInsnNode call, ConstExprValue receiver, List<ConstExprValue> arguments, boolean allowAnyOwner) {
        if (!allowAnyOwner && !isEligible(call.owner, call.name)) return null;
        try {
            Class<?> type = Class.forName(call.owner.replace('/', '.'), true, Thread.currentThread().getContextClassLoader());
            Type[] parameterTypes = Type.getArgumentTypes(call.desc);
            Object[] values = new Object[arguments.size()];
            for (int index = 0; index < values.length; index++)
                values[index] = runtimeValue(arguments.get(index), parameterTypes[index]);
            if ("<init>".equals(call.name)) {
                if (receiver == null) throw new ConstExprException("JDK constructor is missing a receiver slot: " + call.owner);
                if (receiver.value instanceof ConstExprHeapObject) {
                    ConstExprHeapObject object = (ConstExprHeapObject) receiver.value;
                    object.runtimeValue = type.getConstructor(classes(Type.getArgumentTypes(call.desc))).newInstance(values);
                    object.constructorDescriptor = call.desc;
                    object.constructorArguments = new java.util.ArrayList<ConstExprValue>(arguments);
                }
                return null;
            }
            Method method = type.getMethod(call.name, classes(Type.getArgumentTypes(call.desc)));
            Object target = receiver == null ? null : (receiver.value instanceof ConstExprHeapObject ? object(receiver).runtimeValue : receiver.value);
            if (receiver != null && target == null) throw new ConstExprException("JDK object has not been constructed: " + call.owner);
            Object result = method.invoke(target, values);
            if (receiver != null && receiver.value instanceof ConstExprHeapObject) {
                ((ConstExprHeapObject) receiver.value).calls.add(new ConstExprHeapObject.Call(
                        call.owner, call.name, call.desc, new java.util.ArrayList<ConstExprValue>(arguments)));
            }
            Type returnType = Type.getReturnType(call.desc);
            if (receiver != null && receiver.value instanceof ConstExprHeapObject && result == target)
                return receiver;
            return returnType.getSort() == Type.VOID ? null : new ConstExprValue(returnType.getDescriptor(), result);
        } catch (ReflectiveOperationException | IllegalArgumentException error) {
            throw new ConstExprException("Cannot invoke JDK method at compile time: " + call.owner + "." + call.name + call.desc, error);
        }
    }

    static ConstExprValue staticField(org.objectweb.asm.tree.FieldInsnNode field) {
        try {
            Class<?> type = Class.forName(field.owner.replace('/', '.'), true, Thread.currentThread().getContextClassLoader());
            Field valueField = type.getField(field.name);
            Object value = valueField.get(null);
            return new ConstExprValue(field.desc, value);
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    private static boolean isEligible(String owner, String name) {
        return owner.startsWith("java/") && !owner.startsWith("java/lang/System")
                && !owner.startsWith("java/lang/Runtime") && !owner.startsWith("java/lang/Thread")
                && !owner.startsWith("java/lang/Process") && !owner.startsWith("java/lang/ClassLoader")
                && !owner.startsWith("java/lang/reflect/") && !name.equals("wait") && !name.equals("notify")
                && !name.equals("notifyAll") && !name.equals("close") && !name.equals("exit")
                && !name.equals("gc") && !name.startsWith("load");
    }

    static boolean isEligibleOwner(String owner) {
        return owner.startsWith("java/") && !owner.startsWith("java/lang/System")
                && !owner.startsWith("java/lang/Runtime") && !owner.startsWith("java/lang/Thread")
                && !owner.startsWith("java/lang/Process") && !owner.startsWith("java/lang/ClassLoader")
                && !owner.startsWith("java/lang/reflect/");
    }

    private static ConstExprHeapObject object(ConstExprValue value) {
        if (value != null && value.value instanceof ConstExprHeapObject) return (ConstExprHeapObject) value.value;
        throw new ConstExprException("Expected a JDK object");
    }

    private static Object runtimeValue(ConstExprValue value) {
        if (value == null) return null;
        if (value instanceof ConstExprArrayValue) {
            ConstExprArrayValue array = (ConstExprArrayValue) value;
            try {
                Object result = Array.newInstance(typeClass(Type.getType(array.descriptor.substring(1))), array.length());
                for (int index = 0; index < array.length(); index++) {
                    Object element = runtimeValue(array.element(index));
                    if (result instanceof boolean[] && element instanceof Number)
                        element = ((Number) element).intValue() != 0;
                    if (result instanceof char[] && element instanceof Number)
                        element = (char) ((Number) element).intValue();
                    Array.set(result, index, element);
                }
                return result;
            } catch (ClassNotFoundException error) {
                throw new ConstExprException("Cannot create compile-time array: " + array.descriptor, error);
            }
        }
        if (value.value instanceof ConstExprHeapObject) {
            Object result = ((ConstExprHeapObject) value.value).runtimeValue;
            if (result == null)
                throw new ConstExprException("Project objects cannot be passed directly to compile-time JDK reflective calls: "
                        + ((ConstExprHeapObject) value.value).internalName);
            return result;
        }
        return value.value;
    }

    private static Object runtimeValue(ConstExprValue value, Type expected) {
        Object result = runtimeValue(value);
        if (!(result instanceof Number)) return result;
        Number number = (Number) result;
        switch (expected.getSort()) {
            case Type.BOOLEAN: return number.intValue() != 0;
            case Type.BYTE: return number.byteValue();
            case Type.CHAR: return (char) number.intValue();
            case Type.SHORT: return number.shortValue();
            case Type.INT: return number.intValue();
            case Type.LONG: return number.longValue();
            case Type.FLOAT: return number.floatValue();
            case Type.DOUBLE: return number.doubleValue();
            default: return result;
        }
    }

    private static Class<?>[] classes(Type[] types) throws ClassNotFoundException {
        Class<?>[] result = new Class<?>[types.length];
        for (int index = 0; index < types.length; index++) result[index] = typeClass(types[index]);
        return result;
    }

    private static Class<?> typeClass(Type type) throws ClassNotFoundException {
        switch (type.getSort()) {
            case Type.BOOLEAN: return boolean.class; case Type.BYTE: return byte.class; case Type.CHAR: return char.class;
            case Type.SHORT: return short.class; case Type.INT: return int.class; case Type.LONG: return long.class;
            case Type.FLOAT: return float.class; case Type.DOUBLE: return double.class;
            case Type.ARRAY: return Class.forName(type.getDescriptor().replace('/', '.'));
            default: return Class.forName(type.getClassName());
        }
    }
}
