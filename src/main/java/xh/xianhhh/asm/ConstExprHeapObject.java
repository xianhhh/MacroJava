package xh.xianhhh.asm;

import java.util.HashMap;
import java.util.Map;

/** Object allocated by the compile-time interpreter. */
final class ConstExprHeapObject {
    final String internalName;
    final Map<String, ConstExprValue> fields = new HashMap<>();
    final Map<String, ConstExprValue> constructorFields = new HashMap<>();
    final java.util.List<Call> calls = new java.util.ArrayList<Call>();
    String constructorDescriptor;
    java.util.List<ConstExprValue> constructorArguments;
    Object runtimeValue;

    ConstExprHeapObject(String internalName) {
        this.internalName = internalName;
    }

    static final class Call {
        final String owner; final String name; final String descriptor;
        final java.util.List<ConstExprValue> arguments;
        Call(String owner, String name, String descriptor, java.util.List<ConstExprValue> arguments) {
            this.owner = owner; this.name = name; this.descriptor = descriptor; this.arguments = arguments;
        }
    }
}
