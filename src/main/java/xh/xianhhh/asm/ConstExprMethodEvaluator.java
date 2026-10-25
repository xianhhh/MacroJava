package xh.xianhhh.asm;

import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;

import java.util.*;

/** Registers annotated methods and evaluates calls with their constant arguments. */
final class ConstExprMethodEvaluator {
    private final Map<String, MethodNode> methods = new HashMap<>();
    private final Map<String, MethodNode> executableMethods = new HashMap<>();
    private final Map<String, ClassNode> knownClasses = new HashMap<>();
    private final Set<String> unannotatedCalls = new HashSet<>();
    private final Map<String, ConstExprValue> fields;
    private final Map<String, ConstExprValue> fixed = new HashMap<>();
    private final Map<String, ExpressionMethod> expressions = new HashMap<>();
    private final Map<InvocationKey, ConstExprValue> evaluated = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private static final ThreadLocal<Integer> CALL_DEPTH = new ThreadLocal<Integer>();
    private static final String MAX_CALL_DEPTH_PROPERTY = "xh.xianhhh.constexpr.maxCallDepth";
    private final int maxCallDepth;
    private ConstExprClassPath classPath;

    ConstExprMethodEvaluator(Map<String, ConstExprValue> fields) {
        this.fields = fields;
        String configured = System.getProperty(MAX_CALL_DEPTH_PROPERTY);
        int value = 4096;
        if (configured != null && !configured.trim().isEmpty()) {
            try { value = Integer.parseInt(configured.trim()); }
            catch (NumberFormatException error) { throw new ConstExprException("System property " + MAX_CALL_DEPTH_PROPERTY + " must be a positive integer: " + configured, error); }
        }
        if (value < 1) throw new ConstExprException("System property " + MAX_CALL_DEPTH_PROPERTY + " must be a positive integer: " + configured);
        this.maxCallDepth = value;
    }
    void setClassPath(ConstExprClassPath classPath) { this.classPath = classPath; }
    void registerClass(ClassNode node) {
        knownClasses.put(node.name, node);
        for (MethodNode method : node.methods)
            executableMethods.put(ConstExprTransformer.key(node.name, method.name, method.desc), method);
    }
    void register(String key, MethodNode method) { methods.put(key, method); }
    void registerFixed(String key, ConstExprValue value) { fixed.put(key, value); }
    void registerExpression(String key, MethodNode method, String source) { expressions.put(key, new ExpressionMethod(method, source)); }
    boolean contains(String key) {
        if (methods.containsKey(key) || fixed.containsKey(key) || expressions.containsKey(key)) return true;
        if (unannotatedCalls.contains(key)) return false;
        int first = key.indexOf(':'), second = key.indexOf(':', first + 1);
        if (first < 0 || second < 0) return false;
        MethodNode method = resolveExecutable(key.substring(0, first), key.substring(first + 1, second),
                key.substring(second + 1), new HashSet<String>());
        if (method == null || (method.access & Opcodes.ACC_STATIC) == 0) return false;
        if (hasConstExprAnnotation(method)) {
            ConstExprTransformer.registerDependencyMethod(this, key, method);
            return true;
        }
        unannotatedCalls.add(key);
        return false;
    }
    boolean contains(MethodInsnNode call) { return contains(ConstExprTransformer.key(call.owner, call.name, call.desc)); }

    /** Eagerly catches unresolved bytecode in annotated methods before call-site transformation. */
    void validateAnnotatedMethods() {
        validateAnnotatedMethods(null);
    }

    void validateAnnotatedMethods(Set<String> required) {
        for (Map.Entry<String, MethodNode> entry : methods.entrySet()) {
            String key = entry.getKey();
            if (required != null && !required.contains(key)) continue;
            String owner = key.substring(0, key.indexOf(':'));
            for (AbstractInsnNode instruction : entry.getValue().instructions) {
                if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW) {
                    TypeInsnNode type = (TypeInsnNode) instruction;
                    requireClass(owner, entry.getValue(), type.desc, "new");
                } else if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if ("java/lang/Object".equals(call.owner) && "<init>".equals(call.name)) continue;
                    requireMethod(owner, entry.getValue(), call);
                } else if (instruction instanceof FieldInsnNode && instruction.getOpcode() == Opcodes.GETSTATIC) {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (fields.containsKey(ConstExprTransformer.key(field.owner, field.name, field.desc))) continue;
                    throw invalid(owner, entry.getValue(), "Read a static field that is not annotated with @ConstExpr: "
                            + field.owner + "." + field.name);
                } else if (instruction instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                    if (!isLambdaBootstrap(dynamic) && !isStringConcatBootstrap(dynamic)) {
                        throw invalid(owner, entry.getValue(), "Contains an unsupported invokedynamic instruction");
                    }
                }
            }
        }
    }

    ConstExprValue evaluate(MethodInsnNode call, List<ConstExprValue> arguments) {
        String key = ConstExprTransformer.key(call.owner, call.name, call.desc);
        ConstExprValue fixedValue = fixed.get(key);
        if (fixedValue != null) {
            if (!arguments.isEmpty()) throw new ConstExprException("@ConstExpr methods with value cannot have parameters: " + call.name);
            return fixedValue;
        }
        ExpressionMethod expression = expressions.get(key);
        if (expression != null) return UnsafeConstExprExpression.evaluate(Type.getReturnType(call.desc).getDescriptor(),
                expression.source, arguments, expression.method, classPath);
        InvocationKey invocation = invocationKey(key, arguments);
        if (invocation != null) {
            ConstExprValue cached = evaluated.get(invocation);
            if (cached != null) return copyOf(cached);
        }
        ConstExprValue result = invoke(call, null, arguments);
        if (invocation != null && result != null && valueKey(result) != Uncacheable.INSTANCE)
            evaluated.put(invocation, copyOf(result));
        return result;
    }

    ConstExprValue invoke(MethodInsnNode call, ConstExprValue receiver, List<ConstExprValue> arguments) {
        if (receiver != null && receiver.value instanceof ConstExprLambda) {
            return invokeLambda(call, (ConstExprLambda) receiver.value, arguments);
        }
        String key = ConstExprTransformer.key(call.owner, call.name, call.desc);
        ConstExprValue fixedValue = fixed.get(key);
        if (fixedValue != null) {
            if (!arguments.isEmpty()) throw new ConstExprException("@ConstExpr methods with value cannot have parameters: " + call.name);
            return fixedValue;
        }
        if ("java/lang/Object".equals(call.owner) && "<init>".equals(call.name)) return null;
        if ("java/lang/Object".equals(call.owner) && "getClass".equals(call.name)
                && "()Ljava/lang/Class;".equals(call.desc) && receiver != null && receiver.value != null) {
            String descriptor = receiver.value instanceof ConstExprHeapObject
                    ? "L" + ((ConstExprHeapObject) receiver.value).internalName + ";"
                    : Type.getDescriptor(receiver.value.getClass());
            return new ConstExprValue("Ljava/lang/Class;", Type.getType(descriptor));
        }
        String lookupOwner = receiver != null && receiver.value instanceof ConstExprHeapObject
                && call.getOpcode() != Opcodes.INVOKESPECIAL
                ? ((ConstExprHeapObject) receiver.value).internalName : call.owner;
        MethodNode method = resolveExecutable(lookupOwner, call.name, call.desc, new HashSet<String>());
        if (method == null && !lookupOwner.equals(call.owner))
            method = resolveExecutable(call.owner, call.name, call.desc, new HashSet<String>());
        if (method == null) {
            ConstExprValue jdkResult = ConstExprJdkInvoker.invoke(call, receiver, arguments, !ConstExprTransformer.isCheckLegit());
            if (jdkResult != null || call.owner.startsWith("java/")) return jdkResult;
        }
        if (method == null) throw new ConstExprException("Cannot interpret method: " + call.owner + "." + call.name + call.desc);
        if ("<init>".equals(call.name) && receiver != null && receiver.value instanceof ConstExprHeapObject) {
            ConstExprHeapObject object = (ConstExprHeapObject) receiver.value;
            object.constructorDescriptor = call.desc;
            object.constructorArguments = new ArrayList<ConstExprValue>(arguments);
        }
        // Recursive calls are evaluated through invoke() directly, so the
        // outer evaluate() cache cannot see them. Memoize only calls whose
        // receiver and arguments are immutable compile-time values; heap
        // objects are deliberately excluded to preserve mutation semantics.
        InvocationKey recursiveKey = receiver == null ? invocationKey(key, arguments) : null;
        if (recursiveKey != null) {
            ConstExprValue cached = evaluated.get(recursiveKey);
            if (cached != null) return copyOf(cached);
        }
        Integer depth = CALL_DEPTH.get();
        int nextDepth = depth == null ? 1 : depth + 1;
        if (nextDepth > maxCallDepth) throw new ConstExprException("@ConstExpr method call depth exceeds the limit (" + maxCallDepth + "): " + call.name);
        CALL_DEPTH.set(nextDepth);
        try {
            ConstExprValue result = new ConstExprInterpreter(fields, ConstExprMethodEvaluator.this::invoke)
                    .execute(call.owner, method, receiver, arguments);
            if ("<init>".equals(call.name) && receiver != null
                    && receiver.value instanceof ConstExprHeapObject) {
                ConstExprHeapObject object = (ConstExprHeapObject) receiver.value;
                object.constructorFields.clear();
                object.constructorFields.putAll(object.fields);
            }
            if (recursiveKey != null && result != null && valueKey(result) != Uncacheable.INSTANCE)
                evaluated.put(recursiveKey, copyOf(result));
            return result;
        } catch (ConstExprException error) {
            throw error.withFrame(call.owner, call.name, call.desc);
        }
        finally {
            if (nextDepth <= 1) CALL_DEPTH.remove();
            else CALL_DEPTH.set(nextDepth - 1);
        }
    }

    private ConstExprValue invokeLambda(MethodInsnNode call, ConstExprLambda lambda,
                                         List<ConstExprValue> arguments) {
        Handle handle = lambda.implementation;
        List<ConstExprValue> targetArguments = new ArrayList<ConstExprValue>();
        targetArguments.addAll(lambda.captured);
        targetArguments.addAll(arguments);
        int tag = handle.getTag();
        if (tag == Opcodes.H_NEWINVOKESPECIAL) {
            // A constructor method reference (Type::new) has no receiver.  The
            // captured values and SAM arguments are constructor arguments.
            ConstExprHeapObject object = new ConstExprHeapObject(handle.getOwner());
            ConstExprValue receiver = new ConstExprValue("L" + handle.getOwner() + ";", object);
            MethodInsnNode constructor = new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    handle.getOwner(), "<init>", handle.getDesc(), false);
            ConstExprValue result = invoke(constructor, receiver, targetArguments);
            // Constructor invocation returns void; preserve the newly allocated
            // object as the value of the method reference.
            return receiver;
        }
        boolean staticMethod = tag == Opcodes.H_INVOKESTATIC;
        ConstExprValue target = staticMethod ? null : targetArguments.remove(0);
        int opcode = staticMethod ? Opcodes.INVOKESTATIC
                : tag == Opcodes.H_INVOKEINTERFACE ? Opcodes.INVOKEINTERFACE
                : tag == Opcodes.H_INVOKESPECIAL ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL;
        MethodInsnNode implementation = new MethodInsnNode(opcode, handle.getOwner(), handle.getName(), handle.getDesc(),
                tag == Opcodes.H_INVOKEINTERFACE);
        return invoke(implementation, target, targetArguments);
    }

    private static InvocationKey invocationKey(String method, List<ConstExprValue> arguments) {
        List<Object> values = new ArrayList<>(arguments.size());
        for (ConstExprValue argument : arguments) {
            Object value = valueKey(argument);
            if (value == Uncacheable.INSTANCE) return null;
            values.add(value);
        }
        return new InvocationKey(method, Collections.unmodifiableList(new ArrayList<Object>(values)));
    }

    private MethodNode resolveExecutable(String owner, String name, String descriptor, Set<String> visited) {
        if (!visited.add(owner)) return null;
        String key = ConstExprTransformer.key(owner, name, descriptor);
        MethodNode direct = executableMethods.get(key);
        if (direct != null) return direct;
        ClassNode node = knownClasses.get(owner);
        if (node == null && classPath != null) {
            node = classPath.resolve(owner);
            if (node != null) {
                registerClass(node);
                direct = executableMethods.get(key);
                if (direct != null) return direct;
            }
        }
        if (node == null || "<init>".equals(name) || "<clinit>".equals(name)) return null;
        if (node.superName != null) {
            MethodNode inherited = resolveExecutable(node.superName, name, descriptor, visited);
            if (inherited != null && (inherited.access & Opcodes.ACC_PRIVATE) == 0) return inherited;
        }
        if (node.interfaces != null) for (String parent : node.interfaces) {
            MethodNode inherited = resolveExecutable(parent, name, descriptor, visited);
            if (inherited != null && (inherited.access & Opcodes.ACC_PRIVATE) == 0) return inherited;
        }
        return null;
    }

    private static boolean hasConstExprAnnotation(MethodNode method) {
        for (List<AnnotationNode> annotations : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations)
                if (ConstExprTransformer.DESC.equals(annotation.desc)) return true;
        }
        return false;
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

    private static Object valueKey(ConstExprValue value) {
        if (value instanceof ConstExprArrayValue) {
            ConstExprArrayValue array = (ConstExprArrayValue) value;
            List<Object> elements = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                if (array.element(index) instanceof ConstExprArrayValue) return Uncacheable.INSTANCE;
                Object element = valueKey(array.element(index));
                if (element == Uncacheable.INSTANCE) return element;
                elements.add(element);
            }
            return new ArrayKey(array.descriptor, Collections.unmodifiableList(new ArrayList<Object>(elements)));
        }
        if (value.value instanceof ConstExprHeapObject) return Uncacheable.INSTANCE;
        if (value.value != null && !(value.value instanceof String) && !(value.value instanceof Number)
                && !(value.value instanceof Boolean) && !(value.value instanceof Character)
                && !(value.value instanceof ConstExprValue.EnumConstant)
                && !(value.value instanceof Type)) return Uncacheable.INSTANCE;
        return new ScalarKey(value.descriptor, value.value);
    }

    private static ConstExprValue copyOf(ConstExprValue value) {
        if (!(value instanceof ConstExprArrayValue)) return value;
        ConstExprArrayValue array = (ConstExprArrayValue) value;
        List<ConstExprValue> elements = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) elements.add(copyOf(array.element(index)));
        return new ConstExprArrayValue(array.descriptor, elements);
    }

    private static final class InvocationKey { final String method; final List<Object> arguments; InvocationKey(String m,List<Object>a){method=m;arguments=a;} public boolean equals(Object o){return o instanceof InvocationKey&&method.equals(((InvocationKey)o).method)&&arguments.equals(((InvocationKey)o).arguments);} public int hashCode(){return method.hashCode()*31+arguments.hashCode();} }
    private static final class ScalarKey { final String descriptor; final Object value; ScalarKey(String d,Object v){descriptor=d;value=v;} public boolean equals(Object o){return o instanceof ScalarKey&&descriptor.equals(((ScalarKey)o).descriptor)&&Objects.equals(value,((ScalarKey)o).value);} public int hashCode(){return descriptor.hashCode()*31+Objects.hashCode(value);} }
    private static final class ArrayKey { final String descriptor; final List<Object> elements; ArrayKey(String d,List<Object>e){descriptor=d;elements=e;} public boolean equals(Object o){return o instanceof ArrayKey&&descriptor.equals(((ArrayKey)o).descriptor)&&elements.equals(((ArrayKey)o).elements);} public int hashCode(){return descriptor.hashCode()*31+elements.hashCode();} }
    private enum Uncacheable { INSTANCE }
    private static final class ExpressionMethod { final MethodNode method; final String source; ExpressionMethod(MethodNode m,String s){method=m;source=s;} }

    private void requireClass(String owner, MethodNode source, String target, String operation) {
        if (executableMethods.keySet().stream().anyMatch(key -> key.startsWith(target + ":"))) return;
        if (classPath != null) {
            ClassNode node = classPath.resolve(target);
            if (node != null) {
                registerClass(node);
                return;
            }
        }
        if (ConstExprJdkInvoker.isEligibleOwner(target)) return;
        throw invalid(owner, source, operation + " type cannot be resolved from the project or compile classpath: " + target);
    }

    private void requireMethod(String owner, MethodNode source, MethodInsnNode call) {
        String target = ConstExprTransformer.key(call.owner, call.name, call.desc);
        if (fixed.containsKey(target)
                || resolveExecutable(call.owner, call.name, call.desc, new HashSet<String>()) != null) return;
        if (ConstExprJdkInvoker.isEligibleOwner(call.owner)) return;
        throw invalid(owner, source, "Called method cannot be resolved from the project or compile classpath: "
                + call.owner + "." + call.name + call.desc);
    }

    private static ConstExprException invalid(String owner, MethodNode method, String message) {
        return new ConstExprException("@ConstExpr validation failed for " + owner + "." + method.name + method.desc + ": " + message);
    }
}
