package xh.xianhhh.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;
import xh.xianhhh.macro.ConstExprMode;

import java.util.*;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;

/** Rewrites {@code @ConstExpr} references into JVM literals. */
public final class ConstExprTransformer {
    static final String DESC = "Lxh/xianhhh/macro/ConstExpr;";
    private static final Map<String, ConstExprValue> FIELDS = new HashMap<>();
    private static final Map<String, ClassNode> PROJECT_CLASSES = new HashMap<>();
    private static final Set<String> ANNOTATED_FIELDS = new HashSet<>();
    private static final Set<String> ANNOTATED_METHODS = new HashSet<>();
    private static final Set<String> CLASS_DISCOVERED_METHODS = new HashSet<>();
    private static final Set<String> EVALUATED_METHODS = new HashSet<>();
    private static final Set<String> FOLDED_CALLERS = new HashSet<>();
    private static final Set<String> FOLDED_INSTANCE_METHODS = new HashSet<>();
    private static final Map<String, int[]> ARRAY_SHAPES = new HashMap<>();
    private static final String PRESERVE_REFLECTIVE_MEMBERS_PROPERTY =
            "xh.xianhhh.constexpr.preserveReflectiveMembers";
    private static boolean preserveReflectiveMembers;
    private static ConstExprMethodEvaluator METHODS = new ConstExprMethodEvaluator(FIELDS);
    private static ConstExprClassPath CLASS_PATH = new ConstExprClassPath(Collections.<Path>emptyList());
    private static boolean CHECK_LEGIT = true;
    private static final String MODE_PROPERTY = "xh.xianhhh.constexpr.mode";
    private static ConstExprMode MODE = ConstExprMode.SMART;
    private static final int DIRECT_ARRAY_LIMIT = 2_048;
    private static final int MAX_REFERENCE_ARRAY_FILL_METHODS = 4_000;
    private static final int MAX_REFERENCE_ARRAY_EMBEDDED_ELEMENTS = 10_000;
    private static final int MAX_REFERENCE_ARRAY_OBJECT_FACTORIES = 512;
    private static final int MAX_LOCAL_ARRAY_KEY_ELEMENTS = 4_096;
    private static final int DATA_CHUNK_CHARACTERS = 60_000;
    private static final String SHARE_TEMPLATE_ARRAY_PROPERTY = "xh.xianhhh.constexpr.shareTemplateArray";
    private final Map<ArrayPayload, String> generatedArrays = new HashMap<>();
    private final Map<List<Object>, LocalArraySource> reusableLocalArrays = new HashMap<>();
    private final Map<String, String> arrayCloneMethods = new HashMap<>();
    private final Map<String, String> base64FillMethods = new HashMap<>();
    private final IdentityHashMap<Object, String> generatedObjects = new IdentityHashMap<>();
    private String generatedArrayOwner;
    private int generatedArrayIndex;
    private String floatFillMethod;
    private String intFillMethod;
    private String base64FloatFillMethod;
    private boolean shareTemplateArrays;
    private final Map<String, Integer> progressDetails;

    public ConstExprTransformer() {
        this(false);
    }

    public ConstExprTransformer(boolean collectProgress) {
        progressDetails = collectProgress ? new LinkedHashMap<String, Integer>() : null;
    }

    public List<String> progressDetails() {
        if (progressDetails == null) return Collections.emptyList();
        List<String> result = new ArrayList<String>(progressDetails.size());
        for (Map.Entry<String, Integer> entry : progressDetails.entrySet())
            result.add(entry.getKey() + (entry.getValue() == 1 ? "" : " x" + entry.getValue()));
        return result;
    }

    private void recordProgress(String detail) {
        if (progressDetails != null) progressDetails.merge(detail, 1, Integer::sum);
    }

    private static final Pattern PROPERTY = Pattern.compile(
            "System\\.getProperty\\(\\\"([^\\\"]+)\\\",\\s*\\\"([^\\\"]*)\\\"\\)(?:\\.equals\\(\\\"([^\\\"]*)\\\"\\))?");
    private static final Pattern ENV = Pattern.compile(
            "System\\.getenv\\(\\\"([^\\\"]+)\\\"\\)(?:\\.equals\\(\\\"([^\\\"]*)\\\"\\))?");

    public static void preScan(List<byte[]> classes) {
        preScan(classes, Collections.<Path>emptyList());
    }

    public static void preScan(List<byte[]> classes, List<java.nio.file.Path> classPath) {
        preScan(classes, classPath, true);
    }

    public static void preScan(List<byte[]> classes, List<Path> classPath, boolean checkLegit) {
        clear();
        UnsafeConstExprExpression.setFields(FIELDS);
        CHECK_LEGIT = checkLegit;
        preserveReflectiveMembers = configuredBoolean(PRESERVE_REFLECTIVE_MEMBERS_PROPERTY);
        MODE = configuredMode();
        CLASS_PATH = new ConstExprClassPath(classPath);
        METHODS.setClassPath(CLASS_PATH);
        List<ClassNode> nodes = new ArrayList<>();
        for (byte[] bytes : classes) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            nodes.add(node);
            PROJECT_CLASSES.put(node.name, node);
            if (usesReflection(node)) preserveReflectiveMembers = true;
            METHODS.registerClass(node);
            for (FieldNode field : node.fields) {
                String key = key(node.name, field.name, field.desc);
                if ((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
                        && field.value != null) {
                    FIELDS.putIfAbsent(key, javaConstant(field.desc, field.value));
                }
                AnnotationNode annotation = annotation(field.visibleAnnotations, field.invisibleAnnotations);
                if (annotation == null) continue;
                requireStatic(node.name, field.name, field.access);
                ANNOTATED_FIELDS.add(key);
                if (string(annotation, "value").isEmpty() && strings(annotation, "values").isEmpty()
                        && string(annotation, "build").isEmpty() && type(annotation, "classValue") == null
                        && type(annotation, "enumType") == null && !booleanValue(annotation, "nullValue")) {
                    // A final field may use its Java initializer as the ConstExpr source.
                    ANNOTATED_FIELDS.remove(key);
                    continue;
                }
                if (booleanValue(annotation, "nullValue")) {
                    if (isPrimitive(field.desc)) fail("nullValue requires a reference type: " + node.name + "." + field.name);
                    FIELDS.put(key, new ConstExprValue(field.desc, null));
                } else if (field.desc.startsWith("[")) {
                    int[] shape = ints(annotation, "shape");
                    if (shape.length > 0) ARRAY_SHAPES.put(key, shape);
                    List<String> values = strings(annotation, "values");
                    List<ConstExprValue> parsed = new ArrayList<>(values.size());
                    if (!field.desc.startsWith("[[")) {
                        for (String value : values) parsed.add(parse(field.desc.substring(1), value));
                    }
                    FIELDS.put(key, new ConstExprArrayValue(field.desc, parsed));
                } else {
                    FIELDS.put(key, valueFrom(annotation, field.desc));
                }
            }
        }
        for (ClassNode node : nodes) {
            boolean classTagged = annotation(node.visibleAnnotations, node.invisibleAnnotations) != null;
            boolean smartClass = MODE == ConstExprMode.SMART || classTagged;
            for (MethodNode method : node.methods) {
                AnnotationNode annotation = annotation(method.visibleAnnotations, method.invisibleAnnotations);
                if (annotation == null && !smartClass) continue;
                if (!"<init>".equals(method.name) && !"<clinit>".equals(method.name)) {
                    if ((method.access & Opcodes.ACC_STATIC) == 0) {
                        if (annotation != null) requireStatic(node.name, method.name, method.access);
                        continue;
                    }
                    String methodKey = key(node.name, method.name, method.desc);
                    if (annotation != null) ANNOTATED_METHODS.add(methodKey);
                    else if (classTagged) CLASS_DISCOVERED_METHODS.add(methodKey);
                    String returnDescriptor = Type.getReturnType(method.desc).getDescriptor();
                    if (annotation != null && returnDescriptor.startsWith("[") && !strings(annotation, "values").isEmpty()) {
                        List<String> rawValues = strings(annotation, "values");
                        List<ConstExprValue> elements = new ArrayList<>();
                        for (String raw : rawValues){
                            elements.add(parse(returnDescriptor.substring(1), raw));
                        }
                        METHODS.registerFixed(methodKey, new ConstExprArrayValue(returnDescriptor, elements));
                    } else if (annotation != null && (!string(annotation, "value").isEmpty() || booleanValue(annotation, "nullValue")
                            || type(annotation, "classValue") != null || type(annotation, "enumType") != null
                            || !string(annotation, "build").isEmpty())) {
                        if (!CHECK_LEGIT && !string(annotation, "value").isEmpty()
                                && Type.getArgumentTypes(method.desc).length != 0) {
                            METHODS.registerExpression(methodKey, method, string(annotation, "value"));
                        } else {
                            if (Type.getArgumentTypes(method.desc).length != 0)
                                fail("@ConstExpr methods with annotation values cannot have parameters: " + method.name);
                            METHODS.registerFixed(methodKey, valueFrom(annotation, returnDescriptor));
                        }
                    } else {
                        METHODS.register(methodKey, method);
                    }
                }
            }
        }
        // SMART discovers methods opportunistically. Validating every ordinary
        // method up front would reject code that is never used at compile time.
        if (checkLegit && MODE == ConstExprMode.TAG) METHODS.validateAnnotatedMethods(ANNOTATED_METHODS);
    }

    public byte[] transform(byte[] bytes) {
        if (progressDetails != null) progressDetails.clear();
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        if (hasGeneratedArtifacts(node)) return bytes;
        generatedArrayOwner = node.name;
        generatedArrays.clear();
        arrayCloneMethods.clear();
        base64FillMethods.clear();
        generatedObjects.clear();
        generatedArrayIndex = 0;
        floatFillMethod = null;
        intFillMethod = null;
        base64FloatFillMethod = null;
        shareTemplateArrays = configuredBoolean(SHARE_TEMPLATE_ARRAY_PROPERTY);
        for (MethodNode method : new ArrayList<>(node.methods)) {
            boolean directJump = JumpTransformer.transform(node.name, method);
            transformMethod(node, node.sourceFile, method, directJump);
        }
        initializeShapedArrays(node);
        if (preserveReflectiveMembers) initializeReflectiveFields(node);
        node.fields.removeIf(field -> !preserveReflectiveMembers
                && ANNOTATED_FIELDS.contains(key(node.name, field.name, field.desc))
                && !ARRAY_SHAPES.containsKey(key(node.name, field.name, field.desc)));
        // macroJavaBuild transforms every class in one output directory, so every supported
        // invocation has already become a literal before its declaration is removed.
        removeAnnotations(node);
        ClassWriter writer = hierarchyWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    private static ClassWriter hierarchyWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                String resolved = commonSuperFromPath(type1, type2);
                if (!"java/lang/Object".equals(resolved)) return resolved;
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (RuntimeException | LinkageError e) {
                    return resolved;
                }
            }
        };
    }

    static void registerDependencyMethod(ConstExprMethodEvaluator evaluator, String methodKey, MethodNode method) {
        AnnotationNode marker = annotation(method.visibleAnnotations, method.invisibleAnnotations);
        if (marker == null) return;
        String descriptor = Type.getReturnType(method.desc).getDescriptor();
        List<String> values = strings(marker, "values");
        if (descriptor.startsWith("[") && !values.isEmpty()) {
            List<ConstExprValue> elements = new ArrayList<>();
            for (String value : values) elements.add(parse(descriptor.substring(1), value));
            evaluator.registerFixed(methodKey, new ConstExprArrayValue(descriptor, elements));
        } else if (!string(marker, "value").isEmpty() || booleanValue(marker, "nullValue")
                || type(marker, "classValue") != null || type(marker, "enumType") != null
                || !string(marker, "build").isEmpty()) {
            if (Type.getArgumentTypes(method.desc).length != 0)
                fail("Dependency @ConstExpr methods with annotation values cannot have parameters: " + methodKey);
            evaluator.registerFixed(methodKey, valueFrom(marker, descriptor));
        } else evaluator.register(methodKey, method);
    }

    private static String commonSuperFromPath(String type1, String type2) {
        if (type1.equals(type2)) return type1;
        if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) return "java/lang/Object";
        if (isAssignableFromPath(type1, type2)) return type1;
        if (isAssignableFromPath(type2, type1)) return type2;
        String current = type1;
        for (int guard = 0; guard < 256 && current != null; guard++) {
            ClassNode node = resolveHierarchyClass(current);
            if (node == null) break;
            current = node.superName;
            if (current != null && isAssignableFromPath(current, type2)) return current;
        }
        return "java/lang/Object";
    }

    private static boolean isAssignableFromPath(String target, String source) {
        if (target.equals(source)) return true;
        String current = source;
        for (int guard = 0; guard < 256 && current != null; guard++) {
            if (target.equals(current)) return true;
            ClassNode node = resolveHierarchyClass(current);
            if (node == null) return false;
            if (node.interfaces != null) for (String implemented : node.interfaces) {
                if (target.equals(implemented) || isAssignableFromPath(target, implemented)) return true;
            }
            current = node.superName;
        }
        return false;
    }

    private static ClassNode resolveHierarchyClass(String internalName) {
        ClassNode projectClass = PROJECT_CLASSES.get(internalName);
        return projectClass != null ? projectClass : CLASS_PATH.resolve(internalName);
    }

    private static void initializeShapedArrays(ClassNode node) {
        for (FieldNode field : node.fields) {
            int[] shape = ARRAY_SHAPES.get(key(node.name, field.name, field.desc));
            if (shape == null || shape.length == 0) continue;
            if (shape.length > 255) fail("Array dimensions exceed the JVM limit: " + field.name);
            MethodNode clinit = node.methods.stream().filter(method -> "<clinit>".equals(method.name)).findFirst().orElseGet(() -> {
                MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                method.instructions.add(new InsnNode(Opcodes.RETURN));
                node.methods.add(method);
                return method;
            });
            AbstractInsnNode ret = clinit.instructions.getLast();
            InsnList init = new InsnList();
            for (int size : shape) pushInt(init, size);
            init.add(new MultiANewArrayInsnNode(field.desc, shape.length));
            init.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, field.name, field.desc));
            clinit.instructions.insertBefore(ret, init);
        }
    }

    private void initializeReflectiveFields(ClassNode node) {
        for (FieldNode field : node.fields) {
            String fieldKey = key(node.name, field.name, field.desc);
            if (!ANNOTATED_FIELDS.contains(fieldKey) || ARRAY_SHAPES.containsKey(fieldKey)) continue;
            ConstExprValue value = FIELDS.get(fieldKey);
            if (value == null) continue;
            InsnList initialization = new InsnList();
            emitMaterializedValue(node, initialization, value);
            initialization.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, field.name, field.desc));
            MethodNode clinit = node.methods.stream().filter(method -> "<clinit>".equals(method.name))
                    .findFirst().orElseGet(() -> {
                        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                        method.instructions.add(new InsnNode(Opcodes.RETURN));
                        node.methods.add(method);
                        return method;
                    });
            clinit.instructions.insertBefore(clinit.instructions.getLast(), initialization);
        }
    }

    private static boolean usesReflection(ClassNode node) {
        for (MethodNode method : node.methods)
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("java/lang/Class".equals(call.owner)
                        && (call.name.startsWith("getDeclared") || "forName".equals(call.name)
                        || "getField".equals(call.name) || "getMethod".equals(call.name)
                        || "getConstructor".equals(call.name))) return true;
                if (call.owner.startsWith("java/lang/reflect/")
                        || call.owner.startsWith("java/lang/invoke/MethodHandles")
                        || call.owner.startsWith("java/lang/invoke/VarHandle")
                        || "java/beans/Introspector".equals(call.owner)) return true;
            }
        return false;
    }

    private static boolean hasMethodReference(ClassNode node, String target) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (target.equals(key(call.owner, call.name, call.desc))) return true;
                }
            }
        }
        return false;
    }

    /** Removes folded private method chains after all transformed classes are available. */
    public static List<byte[]> pruneUnusedMethods(List<byte[]> classes) {
        List<ClassNode> nodes = new ArrayList<ClassNode>();
        boolean preserveMembers = configuredBoolean(PRESERVE_REFLECTIVE_MEMBERS_PROPERTY);
        Map<String, Set<String>> outgoing = new HashMap<String, Set<String>>();
        Map<String, Set<String>> incoming = new HashMap<String, Set<String>>();
        Set<String> privateMethods = new HashSet<String>();
        Set<String> referencedFields = new HashSet<String>();
        for (byte[] bytes : classes) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            nodes.add(node);
            PROJECT_CLASSES.put(node.name, node);
            if (usesReflection(node)) preserveMembers = true;
            for (MethodNode method : node.methods) {
                String source = key(node.name, method.name, method.desc);
                if ((method.access & Opcodes.ACC_PRIVATE) != 0 && !"<clinit>".equals(method.name))
                    privateMethods.add(source);
                Set<String> calls = new HashSet<String>();
                outgoing.put(source, calls);
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        calls.add(key(call.owner, call.name, call.desc));
                    } else if (instruction instanceof InvokeDynamicInsnNode) {
                        InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                        addHandle(calls, dynamic.bsm);
                        for (Object argument : dynamic.bsmArgs) addConstantHandles(calls, argument);
                    } else if (instruction instanceof LdcInsnNode) {
                        addConstantHandles(calls, ((LdcInsnNode) instruction).cst);
                    }
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : outgoing.entrySet()) {
            for (String target : entry.getValue()) {
                Set<String> callers = incoming.get(target);
                if (callers == null) {
                    callers = new HashSet<String>();
                    incoming.put(target, callers);
                }
                callers.add(entry.getKey());
            }
        }
        Set<String> removable = new HashSet<String>();
        Deque<String> pending = new ArrayDeque<String>();
        for (String evaluated : EVALUATED_METHODS)
            if (privateMethods.contains(evaluated)) pending.add(evaluated);
        for (String caller : FOLDED_CALLERS)
            if (privateMethods.contains(caller)) pending.add(caller);
        for (String folded : FOLDED_INSTANCE_METHODS)
            if (privateMethods.contains(folded)) pending.add(folded);
        while (!pending.isEmpty()) {
            String candidate = pending.removeFirst();
            if (!removable.add(candidate)) continue;
            for (String dependency : outgoing.getOrDefault(candidate, Collections.<String>emptySet())) {
                if (privateMethods.contains(dependency)) pending.addLast(dependency);
            }
            for (String caller : incoming.getOrDefault(candidate, Collections.<String>emptySet())) {
                if (privateMethods.contains(caller)) pending.addLast(caller);
            }
        }
        boolean changed;
        do {
            changed = false;
            for (String candidate : new HashSet<String>(removable)) {
                boolean externallyUsed = false;
                for (String caller : incoming.getOrDefault(candidate, Collections.<String>emptySet())) {
                    if (!removable.contains(caller)) {
                        externallyUsed = true;
                        break;
                    }
                }
                if (externallyUsed) {
                    removable.remove(candidate);
                    changed = true;
                }
            }
        } while (changed);
        for (ClassNode node : nodes) {
            if (!preserveMembers) {
                node.methods.removeIf(method -> removable.contains(key(node.name, method.name, method.desc)));
                pruneGeneratedMembers(node);
            }
        }
        referencedFields.clear();
        for (ClassNode node : nodes)
            for (MethodNode method : node.methods)
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode) {
                        FieldInsnNode field = (FieldInsnNode) instruction;
                        referencedFields.add(key(field.owner, field.name, field.desc));
                    } else if (instruction instanceof InvokeDynamicInsnNode) {
                        InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                        addFieldHandle(referencedFields, dynamic.bsm);
                        for (Object argument : dynamic.bsmArgs) addFieldConstantHandles(referencedFields, argument);
                    } else if (instruction instanceof LdcInsnNode) {
                        addFieldConstantHandles(referencedFields, ((LdcInsnNode) instruction).cst);
                    }
                }
        List<byte[]> result = new ArrayList<byte[]>(classes.size());
        for (ClassNode node : nodes) {
            if (!preserveMembers) node.fields.removeIf(field -> (field.access & Opcodes.ACC_PRIVATE) != 0
                    && !"serialVersionUID".equals(field.name)
                    && (field.visibleAnnotations == null || field.visibleAnnotations.isEmpty())
                    && (field.invisibleAnnotations == null || field.invisibleAnnotations.isEmpty())
                    && !referencedFields.contains(key(node.name, field.name, field.desc)));
            ClassWriter writer = hierarchyWriter();
            node.accept(writer);
            result.add(writer.toByteArray());
        }
        return result;
    }

    public static List<byte[]> verifyAndRepair(List<byte[]> classes) {
        List<byte[]> result = new ArrayList<byte[]>(classes.size());
        for (byte[] bytes : classes) {
            ClassReader reader = new ClassReader(bytes);
            String className = reader.getClassName();
            String defect;
            try {
                ClassNode node = new ClassNode();
                reader.accept(node, ClassReader.EXPAND_FRAMES);
                defect = frameDefect(node);
            } catch (RuntimeException | LinkageError error) {
                defect = "cannot read stack frames: " + error.getMessage();
            }
            if (defect == null) {
                result.add(bytes);
                continue;
            }
            ClassNode repair = new ClassNode();
            try {
                reader.accept(repair, ClassReader.SKIP_FRAMES);
                ClassWriter writer = hierarchyWriter();
                repair.accept(writer);
                byte[] repaired = writer.toByteArray();
                ClassNode checked = new ClassNode();
                new ClassReader(repaired).accept(checked, ClassReader.EXPAND_FRAMES);
                String remaining = frameDefect(checked);
                if (remaining == null) {
                    result.add(repaired);
                    continue;
                }
                defect = remaining;
            } catch (RuntimeException | LinkageError error) {
                throw new ConstExprException("Cannot repair bytecode in " + className + ": " + defect, error);
            }
            throw new ConstExprException("Cannot repair bytecode in " + className + ": " + defect);
        }
        return result;
    }

    private static String frameDefect(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.instructions.size() == 0) continue;
            Frame<BasicValue>[] frames;
            try {
                frames = new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (AnalyzerException | RuntimeException error) {
                return method.name + method.desc + ": " + error.getMessage();
            }
            AbstractInsnNode[] instructions = method.instructions.toArray();
            Frame<BasicValue>[] inferredFrames = null;
            boolean inferenceAttempted = false;
            for (int index = 0; index < instructions.length; index++) {
                if (!(instructions[index] instanceof FrameNode) || frames[index] == null) continue;
                FrameNode declared = (FrameNode) instructions[index];
                if (declared.stack == null) continue;
                String invocationDefect = incompatibleInvocationFrame(declared, instructions[index]);
                if (invocationDefect != null)
                    return method.name + method.desc + " frame at instruction " + index + ": " + invocationDefect;
                for (int slot = 0; slot < declared.stack.size() && slot < frames[index].getStackSize(); slot++) {
                    Object type = declared.stack.get(slot);
                    if (!"java/lang/Object".equals(type)) continue;
                    if (!inferenceAttempted) {
                        inferredFrames = inferredFrames(node, method);
                        inferenceAttempted = true;
                    }
                    String inferred = inferredStackType(inferredFrames, index, slot);
                    if (inferred != null && !"java/lang/Object".equals(inferred))
                        return method.name + method.desc + " frame at instruction " + index
                                + " declares Object instead of " + inferred;
                }
            }
        }
        return null;
    }

    private static String incompatibleInvocationFrame(FrameNode frame, AbstractInsnNode instruction) {
        AbstractInsnNode next = executable(instruction.getNext());
        if (!(next instanceof MethodInsnNode)) return null;
        MethodInsnNode call = (MethodInsnNode) next;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int first = frame.stack.size() - arguments.length
                - (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        if (first < 0) return null;
        if (call.getOpcode() != Opcodes.INVOKESTATIC
                && "java/lang/Object".equals(frame.stack.get(first))
                && !"java/lang/Object".equals(call.owner))
            return "Object receiver for " + call.owner + "." + call.name;
        for (int index = 0; index < arguments.length; index++) {
            Type expected = arguments[index];
            if ((expected.getSort() == Type.OBJECT && !"java/lang/Object".equals(expected.getInternalName())
                    || expected.getSort() == Type.ARRAY)
                    && "java/lang/Object".equals(frame.stack.get(first
                    + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1) + index)))
                return "Object argument #" + index + " for " + call.owner + "." + call.name;
        }
        return null;
    }

    private static Frame<BasicValue>[] inferredFrames(ClassNode node, MethodNode method) {
        try {
            SimpleVerifier verifier = new SimpleVerifier(Opcodes.ASM9, Type.getObjectType(node.name),
                    node.superName == null ? null : Type.getObjectType(node.superName), null,
                    (node.access & Opcodes.ACC_INTERFACE) != 0) {
                @Override protected boolean isInterface(Type type) {
                    ClassNode resolved = resolveHierarchyClass(type.getInternalName());
                    return resolved != null ? (resolved.access & Opcodes.ACC_INTERFACE) != 0 : super.isInterface(type);
                }
                @Override protected Type getSuperClass(Type type) {
                    ClassNode resolved = resolveHierarchyClass(type.getInternalName());
                    return resolved != null && resolved.superName != null
                            ? Type.getObjectType(resolved.superName) : super.getSuperClass(type);
                }
                @Override protected boolean isAssignableFrom(Type target, Type source) {
                    if (target.equals(source)) return true;
                    if (target.getSort() == Type.OBJECT && source.getSort() == Type.OBJECT
                            && resolveHierarchyClass(source.getInternalName()) != null)
                        return isAssignableFromPath(target.getInternalName(), source.getInternalName());
                    return super.isAssignableFrom(target, source);
                }
            };
            return new Analyzer<BasicValue>(verifier).analyze(node.name, method);
        } catch (AnalyzerException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static String inferredStackType(Frame<BasicValue>[] frames, int index, int slot) {
        if (frames == null || frames[index] == null || slot >= frames[index].getStackSize()) return null;
        Type type = frames[index].getStack(slot).getType();
        return type != null && type.getSort() == Type.OBJECT ? type.getInternalName() : null;
    }

    static void writePruningState(Path path) throws java.io.IOException {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            writeKeys(output, EVALUATED_METHODS);
            writeKeys(output, FOLDED_CALLERS);
            writeKeys(output, FOLDED_INSTANCE_METHODS);
        }
    }

    static void readPruningState(Path path) throws java.io.IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            readKeys(input, EVALUATED_METHODS);
            readKeys(input, FOLDED_CALLERS);
            readKeys(input, FOLDED_INSTANCE_METHODS);
        }
    }

    private static void writeKeys(DataOutputStream output, Set<String> keys) throws java.io.IOException {
        List<String> ordered = new ArrayList<>(keys);
        Collections.sort(ordered);
        output.writeInt(ordered.size());
        for (String key : ordered) output.writeUTF(key);
    }

    private static void readKeys(DataInputStream input, Set<String> keys) throws java.io.IOException {
        int count = input.readInt();
        if (count < 0) throw new java.io.IOException("Invalid ConstExpr pruning state");
        for (int index = 0; index < count; index++) keys.add(input.readUTF());
    }

    private static void pruneGeneratedMembers(ClassNode node) {
        boolean changed;
        do {
            changed = false;
            Set<String> called = new HashSet<String>();
            Set<String> readFields = new HashSet<String>();
            for (MethodNode method : node.methods)
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        called.add(key(call.owner, call.name, call.desc));
                    } else if (instruction instanceof FieldInsnNode && instruction.getOpcode() == Opcodes.GETSTATIC) {
                        FieldInsnNode field = (FieldInsnNode) instruction;
                        readFields.add(key(field.owner, field.name, field.desc));
                    } else if (instruction instanceof InvokeDynamicInsnNode) {
                        InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                        addHandle(called, dynamic.bsm);
                        for (Object argument : dynamic.bsmArgs) addConstantHandles(called, argument);
                    } else if (instruction instanceof LdcInsnNode) {
                        addConstantHandles(called, ((LdcInsnNode) instruction).cst);
                    }
                }
            for (FieldNode field : new ArrayList<FieldNode>(node.fields)) {
                if ((field.access & Opcodes.ACC_SYNTHETIC) == 0 || !field.name.startsWith("$constexpr$template$")
                        || readFields.contains(key(node.name, field.name, field.desc))) continue;
                MethodNode clinit = node.methods.stream().filter(method -> "<clinit>".equals(method.name)).findFirst().orElse(null);
                if (clinit != null) {
                    for (AbstractInsnNode instruction = clinit.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                        if (!(instruction instanceof FieldInsnNode) || instruction.getOpcode() != Opcodes.PUTSTATIC) continue;
                        FieldInsnNode store = (FieldInsnNode) instruction;
                        if (!node.name.equals(store.owner) || !field.name.equals(store.name)) continue;
                        AbstractInsnNode producer = executable(instruction.getPrevious());
                        if (producer instanceof MethodInsnNode && ((MethodInsnNode) producer).name.startsWith("$constexpr$decode$")) {
                            clinit.instructions.remove(producer);
                            clinit.instructions.remove(instruction);
                        }
                        break;
                    }
                }
                node.fields.remove(field);
                changed = true;
            }
            if (node.methods.removeIf(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0
                    && method.name.startsWith("$constexpr$")
                    && !called.contains(key(node.name, method.name, method.desc)))) changed = true;
        } while (changed);
    }

    private static void addHandle(Set<String> calls, Handle handle) {
        if (handle != null) calls.add(key(handle.getOwner(), handle.getName(), handle.getDesc()));
    }

    private static void addFieldHandle(Set<String> fields, Handle handle) {
        if (handle != null && handle.getTag() >= Opcodes.H_GETFIELD && handle.getTag() <= Opcodes.H_PUTSTATIC)
            fields.add(key(handle.getOwner(), handle.getName(), handle.getDesc()));
    }

    private static void addFieldConstantHandles(Set<String> fields, Object value) {
        if (value instanceof Handle) addFieldHandle(fields, (Handle) value);
        else if (value instanceof ConstantDynamic) {
            ConstantDynamic dynamic = (ConstantDynamic) value;
            addFieldHandle(fields, dynamic.getBootstrapMethod());
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++)
                addFieldConstantHandles(fields, dynamic.getBootstrapMethodArgument(index));
        }
    }

    private static void addConstantHandles(Set<String> calls, Object value) {
        if (value instanceof Handle) addHandle(calls, (Handle) value);
        else if (value instanceof ConstantDynamic) {
            ConstantDynamic dynamic = (ConstantDynamic) value;
            addHandle(calls, dynamic.getBootstrapMethod());
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++)
                addConstantHandles(calls, dynamic.getBootstrapMethodArgument(index));
        }
    }

    private void transformMethod(ClassNode node, String sourceFile, MethodNode method, boolean directJump) {
        reusableLocalArrays.clear();
        String owner = node.name;
        int originalInstructions = progressDetails == null ? 0 : opcodeCount(method);
        int originalBranches = progressDetails == null ? 0 : branchCount(method);
        boolean optimizeBody = !directJump && (MODE == ConstExprMode.SMART
                || ANNOTATED_METHODS.contains(key(node.name, method.name, method.desc))
                || CLASS_DISCOVERED_METHODS.contains(key(node.name, method.name, method.desc))
                || hasLocalMarker(method));
        // Reflective dispatch inserted during a pass must keep reading the live field.
        boolean reflectiveMethod = hasReflectiveCall(method);
        for (int pass = 0; pass < 8; pass++) {
            int beforeSize = method.instructions.size();
            int beforeCalls = methodCallCount(method);
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (foldLiteralArithmetic(method, insn)) {
                // The next instruction was captured before this arithmetic opcode was replaced.
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) insn;
                String key = key(field.owner, field.name, field.desc);
                ConstExprValue value = FIELDS.get(key);
                if (value != null) {
                    if (field.getOpcode() != Opcodes.GETSTATIC)
                        fail("Cannot write to @ConstExpr field: " + field.owner + "." + field.name);
                    if (value instanceof ConstExprArrayValue)
                        next = foldArray(node, method, field, (ConstExprArrayValue) value);
                    else next = foldBooleanBranch(method.instructions, field, value);
                    if (progressDetails != null)
                        recordProgress("inline field " + field.owner + "." + field.name + field.desc
                                + " into " + method.name + method.desc);
                }
            } else if (insn instanceof MethodInsnNode && insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                AbstractInsnNode after = optimizeBody ? foldDirectReflectiveFieldRead(node, method, (MethodInsnNode) insn) : null;
                if (after == null && optimizeBody) after = foldKnownStringBytes(node, method, (MethodInsnNode) insn);
                if (after == null && isSmartMode()) after = foldFreshInstanceCall(node, method, (MethodInsnNode) insn);
                if (after != null) {
                    next = after;
                    if (progressDetails != null)
                        recordProgress("optimize call " + ((MethodInsnNode) insn).owner + "."
                                + ((MethodInsnNode) insn).name + ((MethodInsnNode) insn).desc
                                + " in " + method.name + method.desc);
                }
            } else if (insn instanceof MethodInsnNode && insn.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode call = (MethodInsnNode) insn;
                AbstractInsnNode folded = optimizeBody ? foldLiteralArrayToString(node, method, call) : null;
                if (folded == null && optimizeBody) folded = foldPureJdkCall(node, sourceFile, method, call);
                if (folded != null) {
                    next = folded;
                    if (progressDetails != null)
                        recordProgress("inline JDK call " + call.owner + "." + call.name + call.desc
                                + " into " + method.name + method.desc);
                }
                else if (METHODS.contains(key(call.owner, call.name, call.desc))
                        && (!"V".equals(Type.getReturnType(call.desc).getDescriptor()) || isSmartMode())) {
                    List<AbstractInsnNode> arguments;
                    List<ConstExprValue> values = new ArrayList<>();
                    ConstExprValue value;
                    try {
                        if (Type.getArgumentTypes(call.desc).length > 1) {
                            values = ConstExprCallSiteAnalyzer.arguments(owner, method, call, FIELDS);
                            if (values == null) {
                                insn = next;
                                continue;
                            }
                            arguments = null;
                        } else {
                            try {
                                arguments = constantArguments(owner, sourceFile, method, call);
                                for (int index = 0; index < arguments.size(); index++) {
                                    values.add(literalValue(arguments.get(index), owner, sourceFile, method, call, index));
                                }
                            } catch (ConstExprException directError) {
                                List<ConstExprValue> analyzed = ConstExprCallSiteAnalyzer.arguments(owner, method, call, FIELDS);
                                if (analyzed == null) {
                                    insn = next;
                                    continue;
                                }
                                arguments = null;
                                values = analyzed;
                            }
                        }
                        value = coerceReference(METHODS.evaluate(call, values), Type.getReturnType(call.desc).getDescriptor());
                        EVALUATED_METHODS.add(key(call.owner, call.name, call.desc));
                    } catch (ConstExprException error) {
                        boolean recursiveVoid = "V".equals(Type.getReturnType(call.desc).getDescriptor())
                                && key(call.owner, call.name, call.desc).equals(key(node.name, method.name, method.desc));
                        if (recursiveVoid || !CHECK_LEGIT
                                || (isSmartMode() && !ANNOTATED_METHODS.contains(key(call.owner, call.name, call.desc)))
                                || CLASS_DISCOVERED_METHODS.contains(key(call.owner, call.name, call.desc))) {
                            next = call.getNext();
                            insn = next;
                            continue;
                        }
                        throw error;
                    }
                    if ("V".equals(Type.getReturnType(call.desc).getDescriptor())) {
                        removeCallArguments(method.instructions, call, arguments);
                        method.instructions.remove(call);
                        next = call.getNext();
                    } else if (value instanceof ConstExprArrayValue) {
                        ConstExprArrayValue array = (ConstExprArrayValue) value;
                        if (!canMaterializeGraph(array, new IdentityHashMap<Object, Boolean>())
                                || !withinReferenceArrayBudget(array)) {
                            next = call.getNext();
                            insn = next;
                            continue;
                        }
                        AbstractInsnNode index = executable(call.getNext());
                        AbstractInsnNode load = index == null ? null : executable(index.getNext());
                        Integer position = integer(index);
                        removeCallArguments(method.instructions, call, arguments);
                        if (position != null && load != null && load.getOpcode() == loadOpcode(value.descriptor.substring(1))) {
                            AbstractInsnNode after = load.getNext();
                            if (!CHECK_LEGIT && (position < 0 || position >= array.length())) {
                                materializeArray(node, method, call, array);
                                FOLDED_CALLERS.add(key(node.name, method.name, method.desc));
                                next = after;
                                insn = next;
                                continue;
                            }
                            if (position < 0 || position >= array.length())
                                fail("@ConstExpr array method index out of bounds: " + position);
                            replace(method.instructions, call, array.element(position));
                            method.instructions.remove(index);
                            method.instructions.remove(load);
                            next = after;
                        } else if (index != null && index.getOpcode() == Opcodes.ARRAYLENGTH) {
                            AbstractInsnNode after = index.getNext();
                            replace(method.instructions, call, new ConstExprValue("I", array.length()));
                            method.instructions.remove(index);
                            next = after;
                        } else {
                            AbstractInsnNode after = call.getNext();
                            materializeArray(node, method, call, array);
                            next = after;
                        }
                    } else {
                        if (requiresObjectFactory(value) && !canMaterialize(value)) {
                            next = call.getNext();
                            insn = next;
                            continue;
                        }
                        removeCallArguments(method.instructions, call, arguments);
                        try {
                            materializeValue(node, method.instructions, call, value);
                        } catch (ConstExprException error) {
                            throw error.withFrame(node.name, method.name, method.desc);
                        }
                    }
                    FOLDED_CALLERS.add(key(node.name, method.name, method.desc));
                    if (progressDetails != null)
                        recordProgress("inline call " + call.owner + "." + call.name + call.desc
                                + " into " + method.name + method.desc);
                }
            }
            insn = next;
        }
        if (optimizeBody) {
            optimizeMethodBody(owner, method);
        }
            if (reflectiveMethod || (method.instructions.size() == beforeSize
                    && methodCallCount(method) == beforeCalls)) break;
        }
        if (progressDetails != null) {
            int currentInstructions = opcodeCount(method);
            int currentBranches = branchCount(method);
            if (currentInstructions != originalInstructions || currentBranches != originalBranches) {
                String action = currentInstructions <= originalInstructions && currentBranches <= originalBranches
                        ? "optimize " : "rewrite ";
                recordProgress(action + method.name + method.desc + ": instructions "
                        + originalInstructions + "->" + currentInstructions + ", branches "
                        + originalBranches + "->" + currentBranches);
            }
        }
        // Removed blocks can invalidate local-variable label ranges in the original debug table.
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
    }

    private static int methodCallCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof MethodInsnNode) count++;
        return count;
    }

    private static int opcodeCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction.getOpcode() >= 0) count++;
        return count;
    }

    private static int branchCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode) count++;
        return count;
    }

    private static boolean hasReflectiveCall(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof MethodInsnNode
                    && ((MethodInsnNode) instruction).owner.startsWith("java/lang/reflect/")) return true;
        return false;
    }

    private static void optimizeMethodBody(String owner, MethodNode method) {
        foldLocalArrayLengths(owner, method);
        normalizeLoops(method);
        propagateLocalCopies(method);
        foldImmutableLocals(method.instructions);
        reassociateIntegerAdds(method.instructions);
        foldLiteralBranches(method.instructions);
        removeUnreachableInstructions(owner, method);
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = foldAnalyzedBranches(owner, method);
            changed |= foldArrayReferenceBranches(owner, method);
            if (!changed) break;
            removeUnreachableInstructions(owner, method);
            foldImmutableLocals(method.instructions);
            foldLiteralBranches(method.instructions);
            removeUnreachableInstructions(owner, method);
        }
        threadJumpTargets(method);
        removeUnreachableInstructions(owner, method);
        removeRedundantGotos(method.instructions);
        removeRedundantBranches(method.instructions);
        removeUnusedLiteralStores(method.instructions);
        compactLocalStack(method.instructions);
        // Dead blocks invalidate the label ranges in LocalVariableTable and local type annotations.
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
    }

    private static void propagateLocalCopies(MethodNode method) {
        if (method.instructions.size() > 1024
                || method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return;
        Map<Integer, Integer> writes = new HashMap<Integer, Integer>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode) return;
            if (instruction.getOpcode() == Opcodes.LLOAD || instruction.getOpcode() == Opcodes.DLOAD
                    || instruction.getOpcode() == Opcodes.LSTORE || instruction.getOpcode() == Opcodes.DSTORE)
                return;
            if (instruction instanceof IincInsnNode) {
                int slot = ((IincInsnNode) instruction).var;
                writes.merge(slot, 1, Integer::sum);
            } else if (instruction instanceof VarInsnNode) {
                VarInsnNode variable = (VarInsnNode) instruction;
                if (variable.getOpcode() >= Opcodes.ISTORE && variable.getOpcode() <= Opcodes.ASTORE) {
                    writes.merge(variable.var, 1, Integer::sum);
                    if (variable.getOpcode() == Opcodes.LSTORE || variable.getOpcode() == Opcodes.DSTORE)
                        writes.merge(variable.var + 1, 1, Integer::sum);
                }
            }
        }
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
            AbstractInsnNode next = instruction.getNext();
            if (!(instruction instanceof VarInsnNode)
                    || instruction.getOpcode() != Opcodes.ILOAD && instruction.getOpcode() != Opcodes.FLOAD
                    && instruction.getOpcode() != Opcodes.ALOAD
                    || !(next instanceof VarInsnNode) || next.getOpcode() != instruction.getOpcode() + 33) {
                instruction = next;
                continue;
            }
            VarInsnNode source = (VarInsnNode) instruction, target = (VarInsnNode) next;
            if (source.var == target.var || writes.getOrDefault(source.var, 0) != 0
                    || writes.getOrDefault(target.var, 0) != 1) {
                instruction = next;
                continue;
            }
            boolean incompatibleRead = false;
            for (AbstractInsnNode prior = method.instructions.getFirst(); prior != instruction; prior = prior.getNext())
                if (prior instanceof VarInsnNode && prior.getOpcode() >= Opcodes.ILOAD
                        && prior.getOpcode() <= Opcodes.ALOAD && ((VarInsnNode) prior).var == target.var)
                    incompatibleRead = true;
            for (AbstractInsnNode following = next.getNext(); following != null; following = following.getNext())
                if (following instanceof VarInsnNode && ((VarInsnNode) following).var == target.var
                        && following.getOpcode() >= Opcodes.ILOAD && following.getOpcode() <= Opcodes.ALOAD
                        && following.getOpcode() != source.getOpcode()) incompatibleRead = true;
            if (incompatibleRead) {
                instruction = next;
                continue;
            }
            for (AbstractInsnNode following = next.getNext(); following != null; following = following.getNext())
                if (following instanceof VarInsnNode && following.getOpcode() == source.getOpcode()
                        && ((VarInsnNode) following).var == target.var)
                    ((VarInsnNode) following).var = source.var;
            method.instructions.remove(instruction);
            method.instructions.remove(next);
            writes.remove(target.var);
            instruction = method.instructions.getFirst();
        }
    }

    private static void reassociateIntegerAdds(InsnList instructions) {
        for (AbstractInsnNode secondAdd = instructions.getFirst(); secondAdd != null; ) {
            AbstractInsnNode next = secondAdd.getNext();
            int opcode = secondAdd.getOpcode();
            if (opcode == Opcodes.IADD || opcode == Opcodes.LADD) {
                AbstractInsnNode secondConstant = previousExecutable(secondAdd.getPrevious());
                AbstractInsnNode firstAdd = secondConstant == null ? null
                        : previousExecutable(secondConstant.getPrevious());
                AbstractInsnNode firstConstant = firstAdd == null ? null
                        : previousExecutable(firstAdd.getPrevious());
                Object left = literal(firstConstant), right = literal(secondConstant);
                if (firstAdd != null && firstAdd.getOpcode() == opcode
                        && !hasLabelBetween(firstConstant, secondAdd)
                        && (opcode == Opcodes.IADD && left instanceof Integer && right instanceof Integer
                        || opcode == Opcodes.LADD && left instanceof Long && right instanceof Long)) {
                    ConstExprValue sum = opcode == Opcodes.IADD
                            ? new ConstExprValue("I", (Integer) left + (Integer) right)
                            : new ConstExprValue("J", (Long) left + (Long) right);
                    instructions.remove(firstConstant);
                    instructions.remove(firstAdd);
                    replace(instructions, secondConstant, sum);
                }
            }
            secondAdd = next;
        }
    }

    private static void threadJumpTargets(MethodNode method) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode) {
                JumpInsnNode jump = (JumpInsnNode) instruction;
                jump.label = finalJumpTarget(jump.label);
            } else if (instruction instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode branch = (TableSwitchInsnNode) instruction;
                branch.dflt = finalJumpTarget(branch.dflt);
                for (int index = 0; index < branch.labels.size(); index++)
                    branch.labels.set(index, finalJumpTarget(branch.labels.get(index)));
            } else if (instruction instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode branch = (LookupSwitchInsnNode) instruction;
                branch.dflt = finalJumpTarget(branch.dflt);
                for (int index = 0; index < branch.labels.size(); index++)
                    branch.labels.set(index, finalJumpTarget(branch.labels.get(index)));
            }
        }
    }

    private static LabelNode finalJumpTarget(LabelNode start) {
        LabelNode target = start;
        Set<LabelNode> visited = Collections.newSetFromMap(new IdentityHashMap<LabelNode, Boolean>());
        while (visited.add(target)) {
            AbstractInsnNode first = executable(target);
            if (!(first instanceof JumpInsnNode) || first.getOpcode() != Opcodes.GOTO) break;
            LabelNode next = ((JumpInsnNode) first).label;
            if (visited.contains(next)) break;
            target = next;
        }
        return target;
    }

    private static void normalizeLoops(MethodNode method) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return;
        InsnList instructions = method.instructions;
        AbstractInsnNode[] snapshot = instructions.toArray();
        Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        for (int index = 0; index < snapshot.length; index++) positions.put(snapshot[index], index);
        Map<LabelNode, Integer> incoming = new IdentityHashMap<>();
        Map<LabelNode, JumpInsnNode> backedges = new IdentityHashMap<>();
        for (AbstractInsnNode candidate : snapshot) {
            if (candidate instanceof JumpInsnNode) {
                JumpInsnNode jump = (JumpInsnNode) candidate;
                incoming.merge(jump.label, 1, Integer::sum);
                Integer target = positions.get(jump.label);
                if (jump.getOpcode() == Opcodes.GOTO && target != null && positions.get(candidate) > target)
                    backedges.put(jump.label, jump);
            } else if (candidate instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode branch = (TableSwitchInsnNode) candidate;
                incoming.merge(branch.dflt, 1, Integer::sum);
                for (LabelNode label : branch.labels) incoming.merge(label, 1, Integer::sum);
            } else if (candidate instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode branch = (LookupSwitchInsnNode) candidate;
                incoming.merge(branch.dflt, 1, Integer::sum);
                for (LabelNode label : branch.labels) incoming.merge(label, 1, Integer::sum);
            }
        }
        for (AbstractInsnNode instruction : snapshot) {
            if (!(instruction instanceof LabelNode)) continue;
            LabelNode header = (LabelNode) instruction;
            JumpInsnNode backedge = backedges.get(header);
            if (incoming.getOrDefault(header, 0) != 1 || backedge == null) continue;
            AbstractInsnNode first = executable(header.getNext());
            AbstractInsnNode second = first == null ? null : executable(first.getNext());
            AbstractInsnNode third = second == null ? null : executable(second.getNext());
            if (!(third instanceof JumpInsnNode) || third.getOpcode() < Opcodes.IF_ICMPEQ
                    || third.getOpcode() > Opcodes.IF_ICMPLE || hasLabelBetween(first, third)) continue;
            boolean alternateEntry = false;
            for (AbstractInsnNode between = header.getNext(); between != first; between = between.getNext())
                if (between instanceof LabelNode) alternateEntry = true;
            if (alternateEntry) continue;
            JumpInsnNode exit = (JumpInsnNode) third;
            Integer exitIndex = positions.get(exit.label);
            if (exitIndex == null || exitIndex <= positions.get(backedge)) continue;
            VarInsnNode counter;
            Integer bound;
            if (first instanceof VarInsnNode && first.getOpcode() == Opcodes.ILOAD
                    && literal(second) instanceof Integer) {
                counter = (VarInsnNode) first;
                bound = (Integer) literal(second);
            } else if (second instanceof VarInsnNode && second.getOpcode() == Opcodes.ILOAD
                    && literal(first) instanceof Integer) {
                counter = (VarInsnNode) second;
                bound = (Integer) literal(first);
                AbstractInsnNode constant = first.clone(Collections.<LabelNode, LabelNode>emptyMap());
                instructions.set(first, new VarInsnNode(Opcodes.ILOAD, counter.var));
                instructions.set(second, constant);
                exit.setOpcode(swappedIntegerComparison(exit.getOpcode()));
            } else continue;
            AbstractInsnNode initializer = previousExecutable(header.getPrevious());
            AbstractInsnNode initialValue = initializer == null ? null : previousExecutable(initializer.getPrevious());
            if (!(initializer instanceof VarInsnNode) || initializer.getOpcode() != Opcodes.ISTORE
                    || ((VarInsnNode) initializer).var != counter.var
                    || !(literal(initialValue) instanceof Integer)
                    || hasLabelBetween(initializer, header)) continue;
            if (Boolean.TRUE.equals(integerComparison(exit.getOpcode(), literal(initialValue), bound)))
                instructions.insertBefore(header, new JumpInsnNode(Opcodes.GOTO, exit.label));
        }
    }

    private static int swappedIntegerComparison(int opcode) {
        switch (opcode) {
            case Opcodes.IF_ICMPLT: return Opcodes.IF_ICMPGT;
            case Opcodes.IF_ICMPLE: return Opcodes.IF_ICMPGE;
            case Opcodes.IF_ICMPGT: return Opcodes.IF_ICMPLT;
            case Opcodes.IF_ICMPGE: return Opcodes.IF_ICMPLE;
            default: return opcode;
        }
    }

    private static void foldLocalArrayLengths(String owner, MethodNode method) {
        Map<AbstractInsnNode, Integer> lengths = ConstExprCallSiteAnalyzer.arrayLengths(owner, method, FIELDS);
        for (Map.Entry<AbstractInsnNode, Integer> entry : lengths.entrySet()) {
            AbstractInsnNode length = entry.getKey();
            AbstractInsnNode source = previousExecutable(length.getPrevious());
            if (source instanceof VarInsnNode && source.getOpcode() == Opcodes.ALOAD)
                method.instructions.remove(source);
            else method.instructions.insertBefore(length, new InsnNode(Opcodes.POP));
            replace(method.instructions, length, new ConstExprValue("I", entry.getValue()));
        }
    }

    private static void removeUnusedLiteralStores(InsnList instructions) {
        Set<Integer> readSlots = new HashSet<Integer>();
        for (AbstractInsnNode instruction : instructions) {
            if (instruction instanceof IincInsnNode) readSlots.add(((IincInsnNode) instruction).var);
            else if (instruction instanceof VarInsnNode) {
                VarInsnNode variable = (VarInsnNode) instruction;
                if (variable.getOpcode() >= Opcodes.ILOAD && variable.getOpcode() <= Opcodes.ALOAD)
                    readSlots.add(variable.var);
            }
        }
        for (AbstractInsnNode instruction = instructions.getFirst(); instruction != null; ) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof VarInsnNode) {
                VarInsnNode store = (VarInsnNode) instruction;
                if (store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE
                        && !readSlots.contains(store.var)) {
                    AbstractInsnNode value = previousExecutable(store.getPrevious());
                    AbstractInsnNode start = pureExpressionStart(value);
                    if (start != null && !hasLabelBetween(start, store)) {
                        for (AbstractInsnNode part = start; part != store; ) {
                            AbstractInsnNode following = part.getNext();
                            instructions.remove(part);
                            part = following;
                        }
                        instructions.remove(store);
                    }
                }
            }
            instruction = next;
        }
    }

    private static AbstractInsnNode pureExpressionStart(AbstractInsnNode end) {
        return pureExpressionStart(end, 0);
    }

    private static AbstractInsnNode pureExpressionStart(AbstractInsnNode end, int depth) {
        if (depth >= 64) return null;
        if (end == null) return null;
        int opcode = end.getOpcode();
        if (literal(end) != null || end instanceof VarInsnNode
                && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) return end;
        if (opcode == Opcodes.INEG || opcode == Opcodes.LNEG
                || opcode == Opcodes.FNEG || opcode == Opcodes.DNEG)
            return pureExpressionStart(previousExecutable(end.getPrevious()), depth + 1);
        if (opcode != Opcodes.IADD && opcode != Opcodes.ISUB && opcode != Opcodes.IMUL
                && opcode != Opcodes.IAND && opcode != Opcodes.IOR && opcode != Opcodes.IXOR
                && opcode != Opcodes.ISHL && opcode != Opcodes.ISHR && opcode != Opcodes.IUSHR
                && opcode != Opcodes.LADD && opcode != Opcodes.LSUB && opcode != Opcodes.LMUL
                && opcode != Opcodes.LAND && opcode != Opcodes.LOR && opcode != Opcodes.LXOR
                && opcode != Opcodes.LSHL && opcode != Opcodes.LSHR && opcode != Opcodes.LUSHR)
            return null;
        AbstractInsnNode right = previousExecutable(end.getPrevious());
        AbstractInsnNode rightStart = pureExpressionStart(right, depth + 1);
        AbstractInsnNode left = rightStart == null ? null : previousExecutable(rightStart.getPrevious());
        return pureExpressionStart(left, depth + 1);
    }

    private static void compactLocalStack(InsnList instructions) {
        for (AbstractInsnNode first = instructions.getFirst(); first != null; ) {
            AbstractInsnNode second = first.getNext();
            if (second == null) break;
            int producer = first.getOpcode();
            int consumer = second.getOpcode();
            boolean discard = consumer == Opcodes.POP || consumer == Opcodes.POP2;
            boolean redundant = false;
            if (first instanceof VarInsnNode && producer >= Opcodes.ILOAD && producer <= Opcodes.ALOAD) {
                VarInsnNode load = (VarInsnNode) first;
                int size = producer == Opcodes.LLOAD || producer == Opcodes.DLOAD ? 2 : 1;
                redundant = discard && consumer == (size == 2 ? Opcodes.POP2 : Opcodes.POP)
                        || second instanceof VarInsnNode && consumer == producer + 33
                        && ((VarInsnNode) second).var == load.var;
            } else if (discard && literal(first) != null) {
                Object constant = literal(first);
                int size = constant instanceof Long || constant instanceof Double ? 2 : 1;
                redundant = consumer == (size == 2 ? Opcodes.POP2 : Opcodes.POP);
            } else if (producer == Opcodes.DUP && consumer == Opcodes.POP
                    || producer == Opcodes.DUP2 && consumer == Opcodes.POP2) {
                redundant = true;
            }
            if (redundant) {
                AbstractInsnNode previous = first.getPrevious();
                instructions.remove(first);
                instructions.remove(second);
                first = previous == null ? instructions.getFirst() : previous;
            } else first = second;
        }
    }

    private static boolean hasLocalMarker(MethodNode method) {
        return hasLocalMarker(method.visibleLocalVariableAnnotations)
                || hasLocalMarker(method.invisibleLocalVariableAnnotations);
    }

    private static boolean hasLocalMarker(List<LocalVariableAnnotationNode> annotations) {
        if (annotations == null) return false;
        for (LocalVariableAnnotationNode annotation : annotations) {
            if (DESC.equals(annotation.desc)) return true;
        }
        return false;
    }

    private static void foldImmutableLocals(InsnList instructions) {
        Map<Integer, Integer> stores = new HashMap<Integer, Integer>();
        Map<Integer, ConstExprValue> constants = new HashMap<Integer, ConstExprValue>();
        for (AbstractInsnNode node = instructions.getFirst(); node != null; node = node.getNext()) {
            if (node instanceof IincInsnNode) {
                IincInsnNode increment = (IincInsnNode) node;
                int count = stores.containsKey(increment.var) ? stores.get(increment.var) + 1 : 1;
                stores.put(increment.var, count);
                constants.remove(increment.var);
                continue;
            }
            if (!(node instanceof VarInsnNode)) continue;
            VarInsnNode variable = (VarInsnNode) node;
            if (variable.getOpcode() < Opcodes.ISTORE || variable.getOpcode() > Opcodes.ASTORE) continue;
            int count = stores.containsKey(variable.var) ? stores.get(variable.var) + 1 : 1;
            stores.put(variable.var, count);
            AbstractInsnNode source = previousExecutable(node.getPrevious());
            ConstExprValue value = hasLabelBetween(source, node) ? null
                    : localConstant(source, variable.getOpcode());
            if (count == 1 && value != null) constants.put(variable.var, value);
            else constants.remove(variable.var);
        }
        for (AbstractInsnNode node = instructions.getFirst(); node != null; ) {
            AbstractInsnNode next = node.getNext();
            if (node instanceof VarInsnNode) {
                VarInsnNode variable = (VarInsnNode) node;
                if (variable.getOpcode() >= Opcodes.ILOAD && variable.getOpcode() <= Opcodes.ALOAD
                        && stores.get(variable.var) != null && stores.get(variable.var) == 1) {
                    ConstExprValue value = constants.get(variable.var);
                    if (value != null) {
                        InsnList replacement = new InsnList();
                        value.push(replacement);
                        instructions.insertBefore(node, replacement);
                        instructions.remove(node);
                    }
                }
            }
            node = next;
        }
    }

    private static ConstExprValue localConstant(AbstractInsnNode node, int storeOpcode) {
        if (node == null) return null;
        Object value = literal(node);
        if (value == null || value == NullLiteral.INSTANCE) return null;
        String descriptor;
        switch (storeOpcode) {
            case Opcodes.ISTORE:
                descriptor = "I";
                break;
            case Opcodes.LSTORE:
                descriptor = "J";
                break;
            case Opcodes.FSTORE:
                descriptor = "F";
                break;
            case Opcodes.DSTORE:
                descriptor = "D";
                break;
            case Opcodes.ASTORE:
                descriptor = value instanceof String ? "Ljava/lang/String;" : null;
                break;
            default:
                descriptor = null;
        }
        return descriptor == null ? null : new ConstExprValue(descriptor, value);
    }

    /**
     * Folds comparisons after ConstExpr reads and calls have been replaced with literals.
     */
    private static boolean foldLiteralArithmetic(MethodNode method, AbstractInsnNode operation) {
        InsnList instructions = method.instructions;
        int opcode = operation.getOpcode();
        boolean unary = opcode == Opcodes.INEG || opcode == Opcodes.LNEG
                || opcode == Opcodes.FNEG || opcode == Opcodes.DNEG;
        if (!unary && opcode != Opcodes.IADD && opcode != Opcodes.ISUB && opcode != Opcodes.IMUL && opcode != Opcodes.IDIV && opcode != Opcodes.IREM
                && opcode != Opcodes.LADD && opcode != Opcodes.LSUB && opcode != Opcodes.LMUL && opcode != Opcodes.LDIV && opcode != Opcodes.LREM
                && opcode != Opcodes.FADD && opcode != Opcodes.FSUB && opcode != Opcodes.FMUL && opcode != Opcodes.FDIV && opcode != Opcodes.FREM
                && opcode != Opcodes.DADD && opcode != Opcodes.DSUB && opcode != Opcodes.DMUL && opcode != Opcodes.DDIV && opcode != Opcodes.DREM
                && opcode != Opcodes.IAND && opcode != Opcodes.IOR && opcode != Opcodes.IXOR
                && opcode != Opcodes.ISHL && opcode != Opcodes.ISHR && opcode != Opcodes.IUSHR
                && opcode != Opcodes.LAND && opcode != Opcodes.LOR && opcode != Opcodes.LXOR
                && opcode != Opcodes.LSHL && opcode != Opcodes.LSHR && opcode != Opcodes.LUSHR)
            return false;
        AbstractInsnNode rightNode = previousExecutable(operation.getPrevious());
        AbstractInsnNode leftNode = unary ? null : previousExecutable(rightNode == null ? null : rightNode.getPrevious());
        if (hasLabelBetween(unary ? rightNode : leftNode, operation)) return false;
        Object left = unary ? null : literalOperand(method, leftNode), right = literalOperand(method, rightNode);
        ConstExprValue result = arithmetic(opcode, left, right);
        if (result == null) return false;
        if (leftNode != null) instructions.remove(leftNode);
        instructions.remove(rightNode);
        replace(instructions, operation, result);
        return true;
    }

    private static Object literalOperand(MethodNode method, AbstractInsnNode node) {
        Object direct = literal(node);
        if (direct != null) return direct;
        if (node instanceof VarInsnNode && node.getOpcode() >= Opcodes.ILOAD && node.getOpcode() <= Opcodes.ALOAD) {
            ConstExprValue local = immutableLocalAtCall(method, (VarInsnNode) node);
            if (local != null) return local.value;
        }
        return null;
    }

    private static boolean hasLabelBetween(AbstractInsnNode first, AbstractInsnNode last) {
        if (first == null) return true;
        for (AbstractInsnNode instruction = first.getNext(); instruction != null && instruction != last;
             instruction = instruction.getNext())
            if (instruction instanceof LabelNode) return true;
        return false;
    }

    static ConstExprValue arithmetic(int opcode, Object left, Object right) {
        boolean unary = opcode == Opcodes.INEG || opcode == Opcodes.LNEG
                || opcode == Opcodes.FNEG || opcode == Opcodes.DNEG;
        if ((!unary && !(left instanceof Number)) || !(right instanceof Number)) return null;
        Number first = unary ? null : (Number) left;
        Number second = (Number) right;
        try {
            switch (opcode) {
                case Opcodes.INEG:
                    return new ConstExprValue("I", -second.intValue());
                case Opcodes.LNEG:
                    return new ConstExprValue("J", -second.longValue());
                case Opcodes.FNEG:
                    return new ConstExprValue("F", -second.floatValue());
                case Opcodes.DNEG:
                    return new ConstExprValue("D", -second.doubleValue());
                case Opcodes.IADD:
                    return new ConstExprValue("I", first.intValue() + second.intValue());
                case Opcodes.ISUB:
                    return new ConstExprValue("I", first.intValue() - second.intValue());
                case Opcodes.IMUL:
                    return new ConstExprValue("I", first.intValue() * second.intValue());
                case Opcodes.IDIV:
                    return new ConstExprValue("I", first.intValue() / second.intValue());
                case Opcodes.IREM:
                    return new ConstExprValue("I", first.intValue() % second.intValue());
                case Opcodes.IAND:
                    return new ConstExprValue("I", first.intValue() & second.intValue());
                case Opcodes.IOR:
                    return new ConstExprValue("I", first.intValue() | second.intValue());
                case Opcodes.IXOR:
                    return new ConstExprValue("I", first.intValue() ^ second.intValue());
                case Opcodes.ISHL:
                    return new ConstExprValue("I", first.intValue() << second.intValue());
                case Opcodes.ISHR:
                    return new ConstExprValue("I", first.intValue() >> second.intValue());
                case Opcodes.IUSHR:
                    return new ConstExprValue("I", first.intValue() >>> second.intValue());
                case Opcodes.LADD:
                    return new ConstExprValue("J", first.longValue() + second.longValue());
                case Opcodes.LSUB:
                    return new ConstExprValue("J", first.longValue() - second.longValue());
                case Opcodes.LMUL:
                    return new ConstExprValue("J", first.longValue() * second.longValue());
                case Opcodes.LDIV:
                    return new ConstExprValue("J", first.longValue() / second.longValue());
                case Opcodes.LREM:
                    return new ConstExprValue("J", first.longValue() % second.longValue());
                case Opcodes.LAND:
                    return new ConstExprValue("J", first.longValue() & second.longValue());
                case Opcodes.LOR:
                    return new ConstExprValue("J", first.longValue() | second.longValue());
                case Opcodes.LXOR:
                    return new ConstExprValue("J", first.longValue() ^ second.longValue());
                case Opcodes.LSHL:
                    return new ConstExprValue("J", first.longValue() << second.intValue());
                case Opcodes.LSHR:
                    return new ConstExprValue("J", first.longValue() >> second.intValue());
                case Opcodes.LUSHR:
                    return new ConstExprValue("J", first.longValue() >>> second.intValue());
                case Opcodes.FADD:
                    return new ConstExprValue("F", first.floatValue() + second.floatValue());
                case Opcodes.FSUB:
                    return new ConstExprValue("F", first.floatValue() - second.floatValue());
                case Opcodes.FMUL:
                    return new ConstExprValue("F", first.floatValue() * second.floatValue());
                case Opcodes.FDIV:
                    return new ConstExprValue("F", first.floatValue() / second.floatValue());
                case Opcodes.FREM:
                    return new ConstExprValue("F", first.floatValue() % second.floatValue());
                case Opcodes.DADD:
                    return new ConstExprValue("D", first.doubleValue() + second.doubleValue());
                case Opcodes.DSUB:
                    return new ConstExprValue("D", first.doubleValue() - second.doubleValue());
                case Opcodes.DMUL:
                    return new ConstExprValue("D", first.doubleValue() * second.doubleValue());
                case Opcodes.DDIV:
                    return new ConstExprValue("D", first.doubleValue() / second.doubleValue());
                case Opcodes.DREM:
                    return new ConstExprValue("D", first.doubleValue() % second.doubleValue());
                default:
                    return null;
            }
        } catch (ArithmeticException error) {
            return null;
        }
    }

    private static void foldLiteralBranches(InsnList instructions) {
        for (AbstractInsnNode node = instructions.getFirst(); node != null; ) {
            AbstractInsnNode next = node.getNext();
            if (node instanceof JumpInsnNode) {
                JumpInsnNode jump = (JumpInsnNode) node;
                AbstractInsnNode right = previousExecutable(node.getPrevious());
                Boolean result = null;
                int branchOpcode = jump.getOpcode();
                if (branchOpcode == Opcodes.IFEQ || branchOpcode == Opcodes.IFNE || branchOpcode == Opcodes.IFLT || branchOpcode == Opcodes.IFGE || branchOpcode == Opcodes.IFGT || branchOpcode == Opcodes.IFLE)
                    result = unaryComparison(branchOpcode, literal(right));
                else if (branchOpcode == Opcodes.IF_ICMPEQ || branchOpcode == Opcodes.IF_ICMPNE || branchOpcode == Opcodes.IF_ICMPLT || branchOpcode == Opcodes.IF_ICMPGE || branchOpcode == Opcodes.IF_ICMPGT || branchOpcode == Opcodes.IF_ICMPLE)
                    result = integerComparison(branchOpcode, literal(previousExecutable(right == null ? null : right.getPrevious())), literal(right));
                else if (branchOpcode == Opcodes.IF_ACMPEQ || branchOpcode == Opcodes.IF_ACMPNE)
                    result = referenceComparison(branchOpcode, literal(previousExecutable(right == null ? null : right.getPrevious())), literal(right));
                if (result == null) result = comparisonInstruction(jump, right);
                if (result != null) {
                    int operands = isBinaryJump(jump.getOpcode()) ? 2 : 1;
                    AbstractInsnNode first = right != null && right.getOpcode() == Opcodes.LCMP
                            ? previousExecutable(previousExecutable(right.getPrevious()).getPrevious())
                            : operands == 2 ? previousExecutable(right == null ? null : right.getPrevious()) : right;
                    if (hasLabelBetween(first, node)) {
                        node = next;
                        continue;
                    }
                    if (right != null && right.getOpcode() == Opcodes.LCMP) {
                        removePreviousLiterals(instructions, right, 2);
                        instructions.remove(right);
                    } else removePreviousLiterals(instructions, node, operands);
                    replaceJump(instructions, jump, result);
                }
            } else if (node instanceof MethodInsnNode && isStringEquals((MethodInsnNode) node)) {
                MethodInsnNode call = (MethodInsnNode) node;
                AbstractInsnNode jumpNode = executable(call.getNext());
                AbstractInsnNode right = previousExecutable(call.getPrevious());
                AbstractInsnNode left = previousExecutable(right == null ? null : right.getPrevious());
                Object leftValue = literal(left);
                Object rightValue = literal(right);
                if (jumpNode instanceof JumpInsnNode
                        && (((JumpInsnNode) jumpNode).getOpcode() == Opcodes.IFEQ || ((JumpInsnNode) jumpNode).getOpcode() == Opcodes.IFNE)
                        && leftValue instanceof String && rightValue instanceof String
                        && !hasLabelBetween(left, jumpNode)) {
                    JumpInsnNode jump = (JumpInsnNode) jumpNode;
                    boolean equals = leftValue.equals(rightValue);
                    boolean branch = jump.getOpcode() == Opcodes.IFNE ? equals : !equals;
                    instructions.remove(left);
                    instructions.remove(right);
                    instructions.remove(call);
                    replaceJump(instructions, jump, branch);
                }
            } else if (node instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode tableSwitch = (TableSwitchInsnNode) node;
                Integer selector = integer(previousExecutable(node.getPrevious()));
                if (selector != null && !hasLabelBetween(previousExecutable(node.getPrevious()), node)) {
                    LabelNode target = selector < tableSwitch.min || selector > tableSwitch.max
                            ? tableSwitch.dflt : tableSwitch.labels.get(selector - tableSwitch.min);
                    instructions.remove(previousExecutable(node.getPrevious()));
                    instructions.set(tableSwitch, new JumpInsnNode(Opcodes.GOTO, target));
                }
            } else if (node instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode lookupSwitch = (LookupSwitchInsnNode) node;
                Integer selector = integer(previousExecutable(node.getPrevious()));
                if (selector != null && !hasLabelBetween(previousExecutable(node.getPrevious()), node)) {
                    int position = lookupSwitch.keys.indexOf(selector);
                    LabelNode target = position < 0 ? lookupSwitch.dflt : lookupSwitch.labels.get(position);
                    instructions.remove(previousExecutable(node.getPrevious()));
                    instructions.set(lookupSwitch, new JumpInsnNode(Opcodes.GOTO, target));
                }
            }
            node = next;
        }
    }

    private static boolean foldAnalyzedBranches(String owner, MethodNode method) {
        Map<AbstractInsnNode, List<ConstExprValue>> inputs =
                ConstExprCallSiteAnalyzer.branchInputs(owner, method, FIELDS);
        boolean changed = false;
        for (Map.Entry<AbstractInsnNode, List<ConstExprValue>> entry : inputs.entrySet()) {
            AbstractInsnNode instruction = entry.getKey();
            if (instruction.getPrevious() == null && method.instructions.getFirst() != instruction) continue;
            List<ConstExprValue> values = entry.getValue();
            int opcode = instruction.getOpcode();
            LabelNode target = null;
            if (instruction instanceof TableSwitchInsnNode && values.get(0).value instanceof Number) {
                TableSwitchInsnNode branch = (TableSwitchInsnNode) instruction;
                int selector = ((Number) values.get(0).value).intValue();
                target = selector < branch.min || selector > branch.max
                        ? branch.dflt : branch.labels.get(selector - branch.min);
            } else if (instruction instanceof LookupSwitchInsnNode && values.get(0).value instanceof Number) {
                LookupSwitchInsnNode branch = (LookupSwitchInsnNode) instruction;
                int position = branch.keys.indexOf(((Number) values.get(0).value).intValue());
                target = position < 0 ? branch.dflt : branch.labels.get(position);
            } else if (instruction instanceof JumpInsnNode) {
                Boolean taken = null;
                if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE)
                    taken = unaryComparison(opcode, values.get(0).value);
                else if (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE)
                    taken = integerComparison(opcode, values.get(0).value, values.get(1).value);
                else if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL)
                    taken = opcode == Opcodes.IFNULL ? values.get(0).value == null : values.get(0).value != null;
                else if ((opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE)
                        && (values.get(0).value == null || values.get(1).value == null)) {
                    boolean equal = values.get(0).value == null && values.get(1).value == null;
                    taken = opcode == Opcodes.IF_ACMPEQ ? equal : !equal;
                }
                if (taken == null) continue;
                if (taken) target = ((JumpInsnNode) instruction).label;
            } else continue;
            InsnList replacement = new InsnList();
            for (int index = 0; index < values.size(); index++) replacement.add(new InsnNode(Opcodes.POP));
            if (target != null) replacement.add(new JumpInsnNode(Opcodes.GOTO, target));
            method.instructions.insertBefore(instruction, replacement);
            method.instructions.remove(instruction);
            changed = true;
        }
        return changed;
    }

    private static boolean foldArrayReferenceBranches(String owner, MethodNode method) {
        Map<AbstractInsnNode, Boolean> comparisons = ConstExprCallSiteAnalyzer.arrayReferenceComparisons(owner, method);
        boolean changed = false;
        for (Map.Entry<AbstractInsnNode, Boolean> entry : comparisons.entrySet()) {
            AbstractInsnNode instruction = entry.getKey();
            if (instruction.getPrevious() == null && method.instructions.getFirst() != instruction) continue;
            JumpInsnNode jump = (JumpInsnNode) instruction;
            boolean unary = jump.getOpcode() == Opcodes.IFNULL || jump.getOpcode() == Opcodes.IFNONNULL;
            boolean taken = jump.getOpcode() == Opcodes.IF_ACMPEQ || jump.getOpcode() == Opcodes.IFNULL
                    ? entry.getValue() : !entry.getValue();
            InsnList replacement = new InsnList();
            replacement.add(new InsnNode(unary ? Opcodes.POP : Opcodes.POP2));
            if (taken) replacement.add(new JumpInsnNode(Opcodes.GOTO, jump.label));
            method.instructions.insertBefore(instruction, replacement);
            method.instructions.remove(instruction);
            changed = true;
        }
        return changed;
    }

    private static void removeRedundantGotos(InsnList instructions) {
        for (AbstractInsnNode instruction = instructions.getFirst(); instruction != null; ) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof JumpInsnNode && instruction.getOpcode() == Opcodes.GOTO) {
                JumpInsnNode jump = (JumpInsnNode) instruction;
                if (executable(next) == executable(jump.label)) instructions.remove(instruction);
            }
            instruction = next;
        }
    }

    private static void removeRedundantBranches(InsnList instructions) {
        for (AbstractInsnNode instruction = instructions.getFirst(); instruction != null; ) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof JumpInsnNode && isBinaryJump(instruction.getOpcode())
                    && executable(next) == executable(((JumpInsnNode) instruction).label)) {
                instructions.set(instruction, new InsnNode(Opcodes.POP2));
            } else if (instruction instanceof JumpInsnNode
                    && (instruction.getOpcode() >= Opcodes.IFEQ && instruction.getOpcode() <= Opcodes.IFLE
                    || instruction.getOpcode() == Opcodes.IFNULL || instruction.getOpcode() == Opcodes.IFNONNULL)
                    && executable(next) == executable(((JumpInsnNode) instruction).label)) {
                instructions.set(instruction, new InsnNode(Opcodes.POP));
            } else if (instruction instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode branch = (TableSwitchInsnNode) instruction;
                if (branch.labels.stream().allMatch(label -> label == branch.dflt)) {
                    instructions.insertBefore(instruction, new InsnNode(Opcodes.POP));
                    instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, branch.dflt));
                }
            } else if (instruction instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode branch = (LookupSwitchInsnNode) instruction;
                if (branch.labels.stream().allMatch(label -> label == branch.dflt)) {
                    instructions.insertBefore(instruction, new InsnNode(Opcodes.POP));
                    instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, branch.dflt));
                }
            }
            instruction = next;
        }
        removeRedundantGotos(instructions);
    }

    private static boolean isStringEquals(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "java/lang/String".equals(call.owner)
                && "equals".equals(call.name)
                && "(Ljava/lang/Object;)Z".equals(call.desc);
    }

    private static boolean isBinaryJump(int opcode) {
        return (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE)
                || opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE;
    }

    private static void removePreviousLiterals(InsnList instructions, AbstractInsnNode node, int count) {
        for (int index = 0; index < count; index++) {
            AbstractInsnNode literal = previousExecutable(node.getPrevious());
            instructions.remove(literal);
        }
    }

    private static void replaceJump(InsnList instructions, JumpInsnNode jump, boolean shouldJump) {
        if (shouldJump) instructions.set(jump, new JumpInsnNode(Opcodes.GOTO, jump.label));
        else instructions.remove(jump);
    }

    private static Boolean unaryComparison(int opcode, Object value) {
        if (!(value instanceof Number)) return null;
        int numberValue = ((Number) value).intValue();
        if (opcode == Opcodes.IFEQ) return numberValue == 0;
        if (opcode == Opcodes.IFNE) return numberValue != 0;
        if (opcode == Opcodes.IFLT) return numberValue < 0;
        if (opcode == Opcodes.IFGE) return numberValue >= 0;
        if (opcode == Opcodes.IFGT) return numberValue > 0;
        if (opcode == Opcodes.IFLE) return numberValue <= 0;
        return null;
    }

    private static Boolean integerComparison(int opcode, Object left, Object right) {
        if (!(left instanceof Number) || !(right instanceof Number)) return null;
        int comparison = Integer.compare(((Number) left).intValue(), ((Number) right).intValue());
        if (opcode == Opcodes.IF_ICMPEQ) return comparison == 0;
        if (opcode == Opcodes.IF_ICMPNE) return comparison != 0;
        if (opcode == Opcodes.IF_ICMPLT) return comparison < 0;
        if (opcode == Opcodes.IF_ICMPGE) return comparison >= 0;
        if (opcode == Opcodes.IF_ICMPGT) return comparison > 0;
        if (opcode == Opcodes.IF_ICMPLE) return comparison <= 0;
        return null;
    }

    private static Boolean comparisonInstruction(JumpInsnNode jump, AbstractInsnNode comparison) {
        if (comparison == null || comparison.getOpcode() != Opcodes.LCMP) return null;
        AbstractInsnNode right = previousExecutable(comparison.getPrevious());
        AbstractInsnNode left = previousExecutable(right == null ? null : right.getPrevious());
        Object first = literal(left);
        Object second = literal(right);
        if (!(first instanceof Long) || !(second instanceof Long)) return null;
        return unaryComparison(jump.getOpcode(), Long.compare(((Long) first).longValue(), ((Long) second).longValue()));
    }

    private static Boolean referenceComparison(int opcode, Object left, Object right) {
        if (left != NullLiteral.INSTANCE || right != NullLiteral.INSTANCE) return null;
        return opcode == Opcodes.IF_ACMPEQ;
    }

    /**
     * Removes blocks no longer reachable after a compile-time conditional has been folded.
     */
    private static void removeUnreachableInstructions(String owner, MethodNode method) {
        if (method.instructions.size() == 0) return;
        boolean needsAnalysis = !method.tryCatchBlocks.isEmpty();
        for (AbstractInsnNode instruction = method.instructions.getFirst(); !needsAnalysis && instruction != null;
             instruction = instruction.getNext()) {
            int opcode = instruction.getOpcode();
            if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode) needsAnalysis = true;
            else if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW)
                    && instruction.getNext() != null) needsAnalysis = true;
        }
        if (!needsAnalysis) return;
        try {
            Frame<?>[] frames = new Analyzer<>(new BasicInterpreter()).analyze(owner, method);
            AbstractInsnNode[] instructions = method.instructions.toArray();
            for (int index = 0; index < instructions.length; index++) {
                if (frames[index] == null) method.instructions.remove(instructions[index]);
            }
        } catch (AnalyzerException error) {
            throw new ConstExprException("Cannot analyze method control flow: " + owner + "." + method.name + method.desc, error);
        }
    }

    /**
     * Fold the direct bytecode shape emitted by javac for if (FLAG) and if (!FLAG).
     */
    private static AbstractInsnNode foldBooleanBranch(InsnList instructions, FieldInsnNode field, ConstExprValue value) {
        if (!"Z".equals(value.descriptor)) {
            AbstractInsnNode next = field.getNext();
            replace(instructions, field, value);
            return next;
        }
        AbstractInsnNode conditional = executable(field.getNext());
        if (!(conditional instanceof JumpInsnNode)
                || hasLabelBetween(field, conditional)
                || (((JumpInsnNode) conditional).getOpcode() != Opcodes.IFEQ && ((JumpInsnNode) conditional).getOpcode() != Opcodes.IFNE)) {
            AbstractInsnNode next = field.getNext();
            replace(instructions, field, value);
            return next;
        }
        JumpInsnNode jump = (JumpInsnNode) conditional;

        boolean jumpsWhenTrue = jump.getOpcode() == Opcodes.IFNE;
        boolean shouldJump = (Boolean) value.value == jumpsWhenTrue;
        AbstractInsnNode next = conditional.getNext();
        instructions.remove(field);
        if (shouldJump)
            instructions.set(conditional, new JumpInsnNode(Opcodes.GOTO, ((JumpInsnNode) conditional).label));
        else instructions.remove(conditional);
        return next;
    }

    private AbstractInsnNode foldArray(ClassNode node, MethodNode method, FieldInsnNode field, ConstExprArrayValue values) {
        InsnList instructions = method.instructions;
        if (field.getOpcode() != Opcodes.GETSTATIC) fail("Cannot write to @ConstExpr array: " + field.owner + "." + field.name);
        AbstractInsnNode index = executable(field.getNext());
        if (index != null && index.getOpcode() == Opcodes.ARRAYLENGTH) {
            AbstractInsnNode after = index.getNext();
            replace(instructions, field, new ConstExprValue("I", values.length()));
            instructions.remove(index);
            return after;
        }
        Integer position = integer(index);
        AbstractInsnNode load = index == null ? null : executable(index.getNext());
        if (position == null || load == null || load.getOpcode() != loadOpcode(field.desc.substring(1))) {
            AbstractInsnNode after = field.getNext();
            materializeArray(node, method, field, values);
            return after;
        }
        if (!CHECK_LEGIT && (position < 0 || position >= values.length())) {
            AbstractInsnNode after = field.getNext();
            materializeArray(node, method, field, values);
            return after;
        }
        if (position < 0 || position >= values.length()) fail("@ConstExpr array index out of bounds: " + position);
        AbstractInsnNode after = load.getNext();
        replace(instructions, field, values.element(position));
        instructions.remove(index);
        instructions.remove(load);
        return after;
    }

    private static void replace(InsnList instructions, AbstractInsnNode old, ConstExprValue value) {
        InsnList replacement = new InsnList();
        value.push(replacement);
        instructions.insertBefore(old, replacement);
        instructions.remove(old);
    }

    private void materializeValue(ClassNode node, InsnList instructions, AbstractInsnNode old, ConstExprValue value) {
        if (requiresObjectFactory(value)) {
            instructions.set(old, new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, objectFactory(node, value),
                    "()" + value.descriptor, false));
        } else replace(instructions, old, value);
    }

    private static boolean requiresObjectFactory(ConstExprValue value) {
        return value.value != null && value.descriptor.startsWith("L") && !isLiteralReference(value);
    }

    private static boolean canMaterialize(ConstExprValue value) {
        if (value.value instanceof ConstExprHeapObject) {
            Object snapshot = ((ConstExprHeapObject) value.value).runtimeValue;
            if (snapshot instanceof Serializable && !unsafeObject(snapshot)) return true;
            return canMaterializeGraph(value, new IdentityHashMap<Object, Boolean>())
                    || objectGraph((ConstExprHeapObject) value.value) != null;
        }
        if (value.value instanceof Optional || value.value instanceof OptionalInt
                || value.value instanceof OptionalLong || value.value instanceof OptionalDouble) return true;
        return value.value instanceof Serializable && !unsafeObject(value.value);
    }

    private static boolean canMaterializeGraph(ConstExprValue value,
                                               IdentityHashMap<Object, Boolean> seen) {
        if (value == null || value.value == null && !(value instanceof ConstExprArrayValue)) return true;
        if (value instanceof ConstExprArrayValue) {
            ConstExprArrayValue array = (ConstExprArrayValue) value;
            if (seen.put(array, Boolean.TRUE) != null) return false;
            for (int index = 0; index < array.length(); index++)
                if (!canMaterializeGraph(array.element(index), seen)) return false;
            return true;
        }
        if (value.value instanceof ConstExprHeapObject) {
            ConstExprHeapObject object = (ConstExprHeapObject) value.value;
            if (seen.put(object, Boolean.TRUE) != null) return false;
            if (!canReplayFields(object)) return false;
            for (Map.Entry<String, ConstExprValue> field : object.fields.entrySet())
                if (!constructorSelfReference(object, field.getKey(), field.getValue())
                        && !canMaterializeGraph(field.getValue(), seen)) return false;
            if (object.constructorArguments != null)
                for (ConstExprValue argument : object.constructorArguments)
                    if (!canMaterializeGraph(argument, seen)) return false;
            for (ConstExprHeapObject.Call call : object.calls)
                for (ConstExprValue argument : call.arguments)
                    if (!canMaterializeGraph(argument, seen)) return false;
        }
        return true;
    }

    private static boolean canReplayFields(ConstExprHeapObject object) {
        for (Map.Entry<String, ConstExprValue> entry : object.fields.entrySet()) {
            String fieldKey = entry.getKey();
            int first = fieldKey.indexOf(':');
            int second = fieldKey.indexOf(':', first + 1);
            if (first < 0 || second < 0) return false;
            String owner = fieldKey.substring(0, first);
            ClassNode declaringClass = PROJECT_CLASSES.get(owner);
            if (declaringClass == null && CLASS_PATH != null) declaringClass = CLASS_PATH.resolve(owner);
            if (declaringClass == null) return false;
            String name = fieldKey.substring(first + 1, second);
            String descriptor = fieldKey.substring(second + 1);
            boolean found = false;
            for (FieldNode field : declaringClass.fields) {
                if (!field.name.equals(name) || !field.desc.equals(descriptor)) continue;
                found = true;
                ConstExprValue initial = object.constructorFields.get(fieldKey);
                ConstExprValue current = entry.getValue();
                if (!constructorValueUnchanged(initial, current)
                        && !constructorSelfReference(object, fieldKey, current)
                        && ((field.access & Opcodes.ACC_FINAL) != 0
                        || (field.access & Opcodes.ACC_PUBLIC) == 0)) return false;
                break;
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean constructorValueUnchanged(ConstExprValue initial, ConstExprValue current) {
        return initial != null && current != null
                && !(initial instanceof ConstExprArrayValue) && !(current instanceof ConstExprArrayValue)
                && !(initial.value instanceof ConstExprHeapObject)
                && !(current.value instanceof ConstExprHeapObject)
                && Objects.equals(initial.descriptor, current.descriptor)
                && Objects.equals(initial.value, current.value);
    }

    private static boolean constructorSelfReference(ConstExprHeapObject object, String fieldKey,
                                                    ConstExprValue current) {
        ConstExprValue initial = object.constructorFields.get(fieldKey);
        return initial != null && current != null && initial.value == object && current.value == object;
    }

    private static boolean isLiteralReference(ConstExprValue value) {
        return "Ljava/lang/String;".equals(value.descriptor) || "Ljava/lang/Class;".equals(value.descriptor)
                || value.value instanceof ConstExprValue.EnumConstant || value.descriptor.startsWith("Ljava/lang/Integer;")
                || value.descriptor.startsWith("Ljava/lang/Long;") || value.descriptor.startsWith("Ljava/lang/Double;")
                || value.descriptor.startsWith("Ljava/lang/Float;") || value.descriptor.startsWith("Ljava/lang/Boolean;")
                || value.descriptor.startsWith("Ljava/lang/Byte;") || value.descriptor.startsWith("Ljava/lang/Short;")
                || value.descriptor.startsWith("Ljava/lang/Character;");
    }

    private String objectFactory(ClassNode node, ConstExprValue value) {
        String existing = generatedObjects.get(value.value);
        if (existing != null) return existing;
        if (value.value instanceof Optional || value.value instanceof OptionalInt
                || value.value instanceof OptionalLong || value.value instanceof OptionalDouble)
            return optionalFactory(node, value);
        Object snapshot = value.value instanceof ConstExprHeapObject
                ? ((ConstExprHeapObject) value.value).runtimeValue : value.value;
        if (value.value instanceof ConstExprHeapObject
                && (!(snapshot instanceof Serializable) || unsafeObject(snapshot))) {
            if (!canMaterializeGraph(value, new IdentityHashMap<Object, Boolean>()))
                return graphObjectFactory(node, value, objectGraph((ConstExprHeapObject) value.value));
            return directObjectFactory(node, (ConstExprHeapObject) value.value, value.descriptor);
        }
        if (!(snapshot instanceof Serializable) || unsafeObject(snapshot))
            throw new ConstExprException("@ConstExpr returned object cannot be materialized safely: " + value.value.getClass().getName());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject((Serializable) snapshot);
            }
            String name = uniqueGeneratedName(node, "$constexpr$object$");
            generatedObjects.put(value.value, name);
            MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name,
                    "()" + value.descriptor, null, null);
            InsnList code = method.instructions;
            code.add(new TypeInsnNode(Opcodes.NEW, "java/io/ByteArrayInputStream"));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", false));
            String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
            if (encoded.length() <= DATA_CHUNK_CHARACTERS) {
                code.add(new LdcInsnNode(encoded));
            } else {
                code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
                for (int offset = 0; offset < encoded.length(); offset += DATA_CHUNK_CHARACTERS) {
                    code.add(new LdcInsnNode(encoded.substring(offset,
                            Math.min(encoded.length(), offset + DATA_CHUNK_CHARACTERS))));
                    code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                }
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                        "()Ljava/lang/String;", false));
            }
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", false));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/ByteArrayInputStream", "<init>", "([B)V", false));
            code.add(new VarInsnNode(Opcodes.ASTORE, 0));
            code.add(new TypeInsnNode(Opcodes.NEW, "java/io/ObjectInputStream"));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/ObjectInputStream", "<init>", "(Ljava/io/InputStream;)V", false));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/ObjectInputStream", "readObject", "()Ljava/lang/Object;", false));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getType(value.descriptor).getInternalName()));
            code.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(method);
            return name;
        } catch (Exception error) {
            if (value.value instanceof ConstExprHeapObject && canMaterializeGraph(value,
                    new IdentityHashMap<Object, Boolean>()))
                return directObjectFactory(node, (ConstExprHeapObject) value.value, value.descriptor);
            if (value.value instanceof ConstExprHeapObject) {
                List<ConstExprHeapObject> graph = objectGraph((ConstExprHeapObject) value.value);
                if (graph != null) return graphObjectFactory(node, value, graph);
            }
            throw new ConstExprException("@ConstExpr returned object cannot be serialized", error);
        }
    }

    private static List<ConstExprHeapObject> objectGraph(ConstExprHeapObject root) {
        List<ConstExprHeapObject> nodes = new ArrayList<>();
        IdentityHashMap<ConstExprHeapObject, Boolean> seen = new IdentityHashMap<>();
        return collectObjectGraph(root, nodes, seen) ? nodes : null;
    }

    private static boolean collectObjectGraph(ConstExprHeapObject object, List<ConstExprHeapObject> nodes,
                                              IdentityHashMap<ConstExprHeapObject, Boolean> seen) {
        if (seen.containsKey(object)) return true;
        if (nodes.size() >= 128 || !canReplayFields(object) || !object.calls.isEmpty()
                || !trivialConstructor(object)) return false;
        int fieldCount = object.fields.size();
        for (ConstExprHeapObject existing : nodes) fieldCount += existing.fields.size();
        if (fieldCount > 1024) return false;
        seen.put(object, Boolean.TRUE);
        nodes.add(object);
        for (ConstExprValue field : object.fields.values()) {
            if (field == null || field instanceof ConstExprArrayValue) return false;
            if (field.value instanceof ConstExprHeapObject) {
                if (!collectObjectGraph((ConstExprHeapObject) field.value, nodes, seen)) return false;
            } else if (requiresObjectFactory(field)) return false;
        }
        return true;
    }

    private static boolean trivialConstructor(ConstExprHeapObject object) {
        if (object.constructorDescriptor != null && !"()V".equals(object.constructorDescriptor)) return false;
        ClassNode type = PROJECT_CLASSES.get(object.internalName);
        if (type == null && CLASS_PATH != null) type = CLASS_PATH.resolve(object.internalName);
        if (type == null || (type.access & Opcodes.ACC_PUBLIC) == 0
                || !"java/lang/Object".equals(type.superName)) return false;
        for (MethodNode method : type.methods) {
            if (!"<init>".equals(method.name) || !"()V".equals(method.desc)) continue;
            if ((method.access & Opcodes.ACC_PUBLIC) == 0) return false;
            int step = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() < 0) continue;
                if (step == 0 && instruction instanceof VarInsnNode && instruction.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode) instruction).var == 0) { step++; continue; }
                if (step == 1 && instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.getOpcode() == Opcodes.INVOKESPECIAL && "java/lang/Object".equals(call.owner)
                            && "<init>".equals(call.name) && "()V".equals(call.desc)) { step++; continue; }
                }
                if (step == 2 && instruction.getOpcode() == Opcodes.RETURN) { step++; continue; }
                return false;
            }
            return step == 3;
        }
        return false;
    }

    private String graphObjectFactory(ClassNode node, ConstExprValue root,
                                      List<ConstExprHeapObject> graph) {
        if (graph == null) throw new ConstExprException("Cyclic object graph cannot be materialized safely");
        String name = uniqueGeneratedName(node, "$constexpr$object$");
        generatedObjects.put(root.value, name);
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "()" + root.descriptor, null, null);
        InsnList code = method.instructions;
        IdentityHashMap<ConstExprHeapObject, Integer> slots = new IdentityHashMap<>();
        for (int index = 0; index < graph.size(); index++) slots.put(graph.get(index), index);
        for (ConstExprHeapObject object : graph) {
            code.add(new TypeInsnNode(Opcodes.NEW, object.internalName));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, object.internalName, "<init>", "()V", false));
            code.add(new VarInsnNode(Opcodes.ASTORE, slots.get(object)));
        }
        for (ConstExprHeapObject object : graph) {
            for (Map.Entry<String, ConstExprValue> field : object.fields.entrySet()) {
                String key = field.getKey();
                ConstExprValue current = field.getValue();
                if (constructorValueUnchanged(object.constructorFields.get(key), current)
                        || constructorSelfReference(object, key, current)) continue;
                int first = key.indexOf(':');
                int second = key.indexOf(':', first + 1);
                code.add(new VarInsnNode(Opcodes.ALOAD, slots.get(object)));
                if (current.value instanceof ConstExprHeapObject)
                    code.add(new VarInsnNode(Opcodes.ALOAD, slots.get((ConstExprHeapObject) current.value)));
                else current.push(code);
                code.add(new FieldInsnNode(Opcodes.PUTFIELD, key.substring(0, first),
                        key.substring(first + 1, second), key.substring(second + 1)));
            }
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, slots.get((ConstExprHeapObject) root.value)));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
        return name;
    }

    private String optionalFactory(ClassNode node, ConstExprValue value) {
        String name = uniqueGeneratedName(node, "$constexpr$object$");
        generatedObjects.put(value.value, name);
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "()" + value.descriptor, null, null);
        InsnList code = method.instructions;
        Object optional = value.value;
        if (optional instanceof Optional) {
            Optional<?> present = (Optional<?>) optional;
            if (present.isPresent()) {
                Object element = present.get();
                ConstExprValue nested = new ConstExprValue(Type.getDescriptor(element.getClass()), element);
                if (!isLiteralReference(nested) && !canMaterialize(nested))
                    throw new ConstExprException("Optional element cannot be materialized: " + element.getClass().getName());
                emitMaterializedValue(node, code, nested);
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "of",
                        "(Ljava/lang/Object;)Ljava/util/Optional;", false));
            } else code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty",
                    "()Ljava/util/Optional;", false));
        } else if (optional instanceof OptionalInt) {
            OptionalInt present = (OptionalInt) optional;
            if (present.isPresent()) pushInt(code, present.getAsInt());
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/OptionalInt",
                    present.isPresent() ? "of" : "empty",
                    present.isPresent() ? "(I)Ljava/util/OptionalInt;" : "()Ljava/util/OptionalInt;", false));
        } else if (optional instanceof OptionalLong) {
            OptionalLong present = (OptionalLong) optional;
            if (present.isPresent()) code.add(new LdcInsnNode(present.getAsLong()));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/OptionalLong",
                    present.isPresent() ? "of" : "empty",
                    present.isPresent() ? "(J)Ljava/util/OptionalLong;" : "()Ljava/util/OptionalLong;", false));
        } else {
            OptionalDouble present = (OptionalDouble) optional;
            if (present.isPresent()) code.add(new LdcInsnNode(present.getAsDouble()));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/OptionalDouble",
                    present.isPresent() ? "of" : "empty",
                    present.isPresent() ? "(D)Ljava/util/OptionalDouble;" : "()Ljava/util/OptionalDouble;", false));
        }
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
        return name;
    }

    private String directObjectFactory(ClassNode node, ConstExprHeapObject object, String descriptor) {
        String name = uniqueGeneratedName(node, "$constexpr$object$");
        generatedObjects.put(object, name);
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, "()" + descriptor, null, null);
        InsnList code = method.instructions;
        code.add(new TypeInsnNode(Opcodes.NEW, object.internalName));
        code.add(new InsnNode(Opcodes.DUP));
        String constructor = object.constructorDescriptor == null ? "()V" : object.constructorDescriptor;
        if (object.constructorArguments != null) {
            for (ConstExprValue argument : object.constructorArguments) emitMaterializedValue(node, code, argument);
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, object.internalName, "<init>", constructor, false));
        for (Map.Entry<String, ConstExprValue> entry : object.fields.entrySet()) {
            String fieldKey = entry.getKey();
            if (constructorValueUnchanged(object.constructorFields.get(fieldKey), entry.getValue())
                    || constructorSelfReference(object, fieldKey, entry.getValue())) continue;
            int first = fieldKey.indexOf(':');
            int second = fieldKey.indexOf(':', first + 1);
            if (first < 0 || second < 0) continue;
            String owner = fieldKey.substring(0, first);
            String fieldName = fieldKey.substring(first + 1, second);
            String fieldDescriptor = fieldKey.substring(second + 1);
            code.add(new InsnNode(Opcodes.DUP));
            emitMaterializedValue(node, code, entry.getValue());
            code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, fieldName, fieldDescriptor));
        }
        for (ConstExprHeapObject.Call call : object.calls) {
            code.add(new InsnNode(Opcodes.DUP));
            for (ConstExprValue argument : call.arguments) emitMaterializedValue(node, code, argument);
            boolean interfaceOwner;
            try {
                interfaceOwner = Class.forName(call.owner.replace('/', '.')).isInterface();
            } catch (Throwable ignored) {
                interfaceOwner = false;
            }
            code.add(new MethodInsnNode(interfaceOwner ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, call.owner, call.name, call.descriptor, interfaceOwner));
            if (Type.getReturnType(call.descriptor).getSort() != Type.VOID)
                code.add(new InsnNode(Type.getReturnType(call.descriptor).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
        return name;
    }

    private void emitMaterializedValue(ClassNode node, InsnList code, ConstExprValue value) {
        if (value instanceof ConstExprArrayValue) {
            emitArrayValue(node, code, (ConstExprArrayValue) value);
        } else if (requiresObjectFactory(value)) {
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, objectFactory(node, value),
                    "()" + value.descriptor, false));
        } else {
            value.push(code);
        }
    }

    private static ConstExprValue coerceReference(ConstExprValue value, String expectedDescriptor) {
        if (value == null || value.value == null) return value;
        if (!value.descriptor.startsWith("L") || !expectedDescriptor.startsWith("L")) return value;
        if (value.descriptor.equals(expectedDescriptor)) return value;
        return new ConstExprValue(expectedDescriptor, value.value);
    }

    private static boolean unsafeObject(Object value) {
        return ConstExprBlackList.isUnsafe(value);
    }

    private void materializeArray(ClassNode node, MethodNode method, AbstractInsnNode old, ConstExprArrayValue array) {
        InsnList instructions = method.instructions;
        method.maxStack = Math.min(65535, method.maxStack + 2 * Type.getType(array.descriptor).getDimensions() + 2);
        InsnList replacement = new InsnList();
        List<Object> content = arrayContentKey(array);
        LocalArraySource prior = content == null ? null : reusableLocalArrays.get(content);
        if (prior != null && canReuseLocalArray(method, prior, old)) {
            replacement.add(new VarInsnNode(Opcodes.ALOAD, prior.slot));
            if (shareTemplateArrays && array.descriptor.startsWith("[[")) {
                replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, array.descriptor, "clone",
                        "()Ljava/lang/Object;", false));
                replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, array.descriptor));
            } else if (!shareTemplateArrays) {
                if (array.descriptor.startsWith("[["))
                    replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name,
                            arrayCloneMethod(node, array.descriptor), "(" + array.descriptor + ")" + array.descriptor, false));
                else {
                    replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, array.descriptor, "clone",
                            "()Ljava/lang/Object;", false));
                    replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, array.descriptor));
                }
            }
        } else {
            emitArrayValue(node, replacement, array);
        }
        AbstractInsnNode following = executable(old.getNext());
        instructions.insertBefore(old, replacement);
        instructions.remove(old);
        if (content != null && following instanceof VarInsnNode && following.getOpcode() == Opcodes.ASTORE)
            reusableLocalArrays.put(content, new LocalArraySource((VarInsnNode) following));
    }

    private static List<Object> arrayContentKey(ConstExprArrayValue array) {
        return arrayContentKey(array, new int[]{MAX_LOCAL_ARRAY_KEY_ELEMENTS});
    }

    private static List<Object> arrayContentKey(ConstExprArrayValue array, int[] remaining) {
        if (array.length() > remaining[0]) return null;
        remaining[0] -= array.length();
        List<Object> content = new ArrayList<Object>(array.length() + 1);
        content.add(array.descriptor);
        for (int index = 0; index < array.length(); index++) {
            ConstExprValue element = array.element(index);
            if (element instanceof ConstExprArrayValue) {
                List<Object> nested = arrayContentKey((ConstExprArrayValue) element, remaining);
                if (nested == null) return null;
                content.add(nested);
            } else if (element == null || element.value == null) {
                content.add(null);
            } else if (element.descriptor.length() == 1 && "ZBCSIJFD".contains(element.descriptor)) {
                Object value = element.value;
                if (value instanceof Float) value = Float.floatToRawIntBits((Float) value);
                else if (value instanceof Double) value = Double.doubleToRawLongBits((Double) value);
                content.add(Arrays.asList(element.descriptor, value));
            } else return null;
        }
        return content;
    }

    private static boolean canReuseLocalArray(MethodNode method, LocalArraySource source, AbstractInsnNode target) {
        for (AbstractInsnNode instruction = source.store.getNext(); instruction != null && instruction != target;
             instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode) return false;
            if (instruction instanceof LabelNode && isControlFlowTarget(method, (LabelNode) instruction)) return false;
            if (instruction instanceof IincInsnNode && ((IincInsnNode) instruction).var == source.slot) return false;
            if (instruction instanceof VarInsnNode && ((VarInsnNode) instruction).var == source.slot) return false;
        }
        return source.store.getNext() != null && isBefore(source.store, target);
    }

    private static boolean isBefore(AbstractInsnNode start, AbstractInsnNode target) {
        for (AbstractInsnNode instruction = start.getNext(); instruction != null; instruction = instruction.getNext())
            if (instruction == target) return true;
        return false;
    }

    private String arrayCloneMethod(ClassNode node, String descriptor) {
        String existing = arrayCloneMethods.get(descriptor);
        if (existing != null) return existing;
        String name = uniqueGeneratedName(node, "$constexpr$clone$");
        arrayCloneMethods.put(descriptor, name);
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "(" + descriptor + ")" + descriptor, null, null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, descriptor, "clone", "()Ljava/lang/Object;", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, descriptor));
        if (descriptor.startsWith("[[")) {
            code.add(new VarInsnNode(Opcodes.ASTORE, 1));
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new VarInsnNode(Opcodes.ISTORE, 2));
            LabelNode loop = new LabelNode();
            LabelNode end = new LabelNode();
            LabelNode skip = new LabelNode();
            code.add(loop);
            code.add(new VarInsnNode(Opcodes.ILOAD, 2));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new InsnNode(Opcodes.ARRAYLENGTH));
            code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ILOAD, 2));
            code.add(new InsnNode(Opcodes.AALOAD));
            code.add(new JumpInsnNode(Opcodes.IFNULL, skip));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new VarInsnNode(Opcodes.ILOAD, 2));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ILOAD, 2));
            code.add(new InsnNode(Opcodes.AALOAD));
            String component = descriptor.substring(1);
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, arrayCloneMethod(node, component),
                    "(" + component + ")" + component, false));
            code.add(new InsnNode(Opcodes.AASTORE));
            code.add(skip);
            code.add(new IincInsnNode(2, 1));
            code.add(new JumpInsnNode(Opcodes.GOTO, loop));
            code.add(end);
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        }
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
        return name;
    }

    private static final class LocalArraySource {
        final VarInsnNode store;
        final int slot;

        LocalArraySource(VarInsnNode store) {
            this.store = store;
            this.slot = store.var;
        }
    }

    private void emitArrayValue(ClassNode node, InsnList replacement, ConstExprArrayValue array) {
        String component = array.descriptor.substring(1);
        if ((shareTemplateArrays && component.length() == 1) || array.length() > DIRECT_ARRAY_LIMIT) {
            String factory = component.length() == 1
                    ? largeArrayFactory(node, array) : largeReferenceArrayFactory(node, array);
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, factory, "()" + array.descriptor, false));
            return;
        }
        pushInt(replacement, array.length());
        if (component.length() == 1) replacement.add(new IntInsnNode(Opcodes.NEWARRAY, newArrayType(component)));
        else replacement.add(new TypeInsnNode(Opcodes.ANEWARRAY, Type.getType(component).getInternalName()));
        for (int index = 0; index < array.length(); index++) {
            if (isDefaultArrayElement(array.element(index), component)) continue;
            replacement.add(new InsnNode(Opcodes.DUP));
            pushInt(replacement, index);
            emitMaterializedValue(node, replacement, coerceReference(array.element(index), component));
            replacement.add(new InsnNode(arrayStoreOpcode(component)));
        }
    }

    private String largeReferenceArrayFactory(ClassNode node, ConstExprArrayValue array) {
        String component = array.descriptor.substring(1);
        if (!withinReferenceArrayBudget(array))
            throw new ConstExprException("@ConstExpr reference array exceeds the generation budget for one class: " + array.length());
        String factoryName = uniqueGeneratedName(node, "$constexpr$array$");
        MethodNode factory = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                factoryName, "()" + array.descriptor, null, null);
        InsnList code = factory.instructions;
        pushInt(code, array.length());
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, Type.getType(component).getInternalName()));
        code.add(new VarInsnNode(Opcodes.ASTORE, 0));
        for (int start = 0; start < array.length(); start += 128) {
            int end = Math.min(array.length(), start + 128);
            boolean populated = false;
            for (int index = start; index < end; index++)
                if (!isDefaultArrayElement(array.element(index), component)) populated = true;
            if (!populated) continue;
            String fillName = uniqueGeneratedName(node, "$constexpr$fillReference$");
            MethodNode fill = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    fillName, "(" + array.descriptor + ")V", null, null);
            for (int index = start; index < end; index++) {
                ConstExprValue element = array.element(index);
                if (isDefaultArrayElement(element, component)) continue;
                fill.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                pushInt(fill.instructions, index);
                emitMaterializedValue(node, fill.instructions, coerceReference(element, component));
                fill.instructions.add(new InsnNode(Opcodes.AASTORE));
            }
            fill.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(fill);
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, fillName,
                    "(" + array.descriptor + ")V", false));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(factory);
        return factoryName;
    }

    private static boolean withinReferenceArrayBudget(ConstExprArrayValue array) {
        String component = array.descriptor.substring(1);
        if (component.length() == 1 || array.length() <= DIRECT_ARRAY_LIMIT) return true;
        int elements = 0, blocks = 0, objectFactories = 0;
        for (int start = 0; start < array.length(); start += 128) {
            boolean populated = false;
            for (int index = start; index < Math.min(array.length(), start + 128); index++) {
                if (isDefaultArrayElement(array.element(index), component)) continue;
                populated = true;
                if (++elements > MAX_REFERENCE_ARRAY_EMBEDDED_ELEMENTS) return false;
                if (requiresObjectFactory(array.element(index))
                        && ++objectFactories > MAX_REFERENCE_ARRAY_OBJECT_FACTORIES) return false;
            }
            if (populated && ++blocks > MAX_REFERENCE_ARRAY_FILL_METHODS) return false;
        }
        return true;
    }

    private static boolean isDefaultArrayElement(ConstExprValue element, String component) {
        if (element instanceof ConstExprArrayValue) return false;
        if (element == null || element.value == null) return component.length() != 1;
        Object value = element.value;
        if ("Z".equals(component)) return Boolean.FALSE.equals(value)
                || value instanceof Number && ((Number) value).intValue() == 0;
        if ("C".equals(component)) return value instanceof Character && ((Character) value) == 0;
        if (!(value instanceof Number)) return false;
        if ("F".equals(component)) return Float.floatToRawIntBits(((Number) value).floatValue()) == 0;
        if ("D".equals(component)) return Double.doubleToRawLongBits(((Number) value).doubleValue()) == 0L;
        return component.length() == 1 && ((Number) value).longValue() == 0L;
    }

    /**
     * Keeps large constant arrays in the class file without exceeding the JVM's per-method
     * code-size or constant-pool entry limits. Dense payloads are split into Base64 strings;
     * sparse arrays emit only their non-default elements.
     */
    private String largeArrayFactory(ClassNode node, ConstExprArrayValue array) {
        String component = array.descriptor.substring(1);
        if (component.length() != 1)
            throw new ConstExprException("Large @ConstExpr arrays do not support reference elements: " + array.descriptor);
        int nonDefault = 0;
        for (int index = 0; index < array.length(); index++) {
            if (!isDefaultArrayElement(array.element(index), component)) nonDefault++;
        }
        boolean sparse = nonDefault > 0 && nonDefault <= 512 && nonDefault * 8 <= array.length();
        String payload = nonDefault == 0 ? "" : sparse ? encodeSparseArray(array, component)
                : encodeArrayBase64(array, component);
        ArrayPayload key = new ArrayPayload(component, array.length(), (sparse ? "S" : "B") + payload);
        String existing = generatedArrays.get(key);
        if (existing != null) return existing;

        String template = uniqueGeneratedName(node, "$constexpr$template$");
        String decoderName = uniqueGeneratedName(node, "$constexpr$decode$");
        String factoryName = uniqueGeneratedName(node, "$constexpr$array$");
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                template, array.descriptor, null, null));

        MethodNode decoder = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                decoderName, "()" + array.descriptor, null, null);
        InsnList code = decoder.instructions;
        pushInt(code, array.length());
        code.add(new IntInsnNode(Opcodes.NEWARRAY, newArrayType(component)));
        code.add(new VarInsnNode(Opcodes.ASTORE, 0));
        if (sparse) {
            for (int index = 0; index < array.length(); index++) {
                if (isDefaultArrayElement(array.element(index), component)) continue;
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                pushInt(code, index);
                array.element(index).push(code);
                code.add(new InsnNode(arrayStoreOpcode(component)));
            }
        } else if (!payload.isEmpty()) {
            int bytesPerElement = hexWidth(component) / 2;
            int chunkElements = Math.max(3, (DATA_CHUNK_CHARACTERS / 4 * 3 / bytesPerElement / 3) * 3);
            String fill = "F".equals(component) ? base64FloatFillMethod(node) : base64FillMethod(node, component);
            int charOffset = 0;
            for (int offset = 0; offset < array.length(); offset += chunkElements) {
                int end = Math.min(array.length(), offset + chunkElements);
                int characters = ((end - offset) * bytesPerElement + 2) / 3 * 4;
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                pushInt(code, offset);
                code.add(new LdcInsnNode(payload.substring(charOffset, charOffset + characters)));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, fill,
                        "(" + array.descriptor + "ILjava/lang/String;)V", false));
                charOffset += characters;
            }
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(decoder);

        MethodNode factory = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                factoryName, "()" + array.descriptor, null, null);
        factory.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, template, array.descriptor));
        if (!shareTemplateArrays) {
            factory.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, array.descriptor, "clone",
                    "()Ljava/lang/Object;", false));
            factory.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, array.descriptor));
        }
        factory.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(factory);

        addTemplateInitialization(node, template, array.descriptor, decoderName);
        generatedArrays.put(key, factoryName);
        return factoryName;
    }

    private static void addTemplateInitialization(ClassNode node, String field, String descriptor, String decoder) {
        MethodNode clinit = node.methods.stream().filter(method -> "<clinit>".equals(method.name)).findFirst().orElseGet(() -> {
            MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(method);
            return method;
        });
        AbstractInsnNode terminal = clinit.instructions.getLast();
        InsnList initialization = new InsnList();
        initialization.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, decoder, "()" + descriptor, false));
        initialization.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, field, descriptor));
        clinit.instructions.insertBefore(terminal, initialization);
    }

    private String fillMethod(ClassNode node, String component) {
        if ("F".equals(component)) {
            if (floatFillMethod == null) floatFillMethod = addIntFillMethod(node, component);
            return floatFillMethod;
        }
        if ("J".equals(component) || "D".equals(component)) {
            return addLongFillMethod(node, component);
        }
        // Integral primitive types share a 32-bit decoder, but their array descriptors differ.
        return addIntFillMethod(node, component);
    }

    private String base64FloatFillMethod(ClassNode node) {
        if (base64FloatFillMethod != null) return base64FloatFillMethod;
        String name = uniqueGeneratedName(node, "$constexpr$fillBase64$F$");
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "([FILjava/lang/String;)V", null, null);
        InsnList code = method.instructions;
        // Decode in the JDK and bulk-copy through FloatBuffer; no per-character Java loop at startup.
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/nio/ByteBuffer", "wrap", "([B)Ljava/nio/ByteBuffer;", false));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/ByteOrder", "BIG_ENDIAN", "Ljava/nio/ByteOrder;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer", "order", "(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer", "asFloatBuffer", "()Ljava/nio/FloatBuffer;", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        pushInt(code, Float.BYTES);
        code.add(new InsnNode(Opcodes.IDIV));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/nio/FloatBuffer", "get", "([FII)Ljava/nio/FloatBuffer;", false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        base64FloatFillMethod = name;
        return name;
    }

    private String base64FillMethod(ClassNode node, String component) {
        String existing = base64FillMethods.get(component);
        if (existing != null) return existing;
        String name = uniqueGeneratedName(node, "$constexpr$fillBase64$" + component + "$");
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "([" + component + "ILjava/lang/String;)V", null, null);
        InsnList code = method.instructions;
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        if ("B".equals(component)) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new InsnNode(Opcodes.ARRAYLENGTH));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy",
                    "(Ljava/lang/Object;ILjava/lang/Object;II)V", false));
        } else if ("Z".equals(component)) {
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new VarInsnNode(Opcodes.ISTORE, 4));
            LabelNode loop = new LabelNode(), end = new LabelNode();
            code.add(loop);
            code.add(new VarInsnNode(Opcodes.ILOAD, 4));
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new InsnNode(Opcodes.ARRAYLENGTH));
            code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            code.add(new VarInsnNode(Opcodes.ILOAD, 4));
            code.add(new InsnNode(Opcodes.IADD));
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new VarInsnNode(Opcodes.ILOAD, 4));
            code.add(new InsnNode(Opcodes.BALOAD));
            code.add(new InsnNode(Opcodes.BASTORE));
            code.add(new IincInsnNode(4, 1));
            code.add(new JumpInsnNode(Opcodes.GOTO, loop));
            code.add(end);
        } else {
            String buffer = "C".equals(component) ? "Char" : "S".equals(component) ? "Short"
                    : "I".equals(component) ? "Int" : "J".equals(component) ? "Long" : "Double";
            int width = hexWidth(component) / 2;
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/nio/ByteBuffer", "wrap", "([B)Ljava/nio/ByteBuffer;", false));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer", "as" + buffer + "Buffer",
                    "()Ljava/nio/" + buffer + "Buffer;", false));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new InsnNode(Opcodes.ARRAYLENGTH));
            pushInt(code, width);
            code.add(new InsnNode(Opcodes.IDIV));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/nio/" + buffer + "Buffer", "get",
                    "([" + component + "II)Ljava/nio/" + buffer + "Buffer;", false));
            code.add(new InsnNode(Opcodes.POP));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        base64FillMethods.put(component, name);
        return name;
    }

    private String addIntFillMethod(ClassNode node, String component) {
        String name = uniqueGeneratedName(node, "$constexpr$fill$" + component + "$");
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "([" + component + "ILjava/lang/String;)V", null, null);
        InsnList code = method.instructions;
        // locals: array=0, output index=1, text=2, character index=3, digit count=4, bits=5
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        LabelNode outer = new LabelNode(), done = new LabelNode(), digits = new LabelNode(), store = new LabelNode();
        code.add(outer);
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(digits);
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        pushInt(code, hexWidth(component));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, store));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        pushInt(code, 4);
        code.add(new InsnNode(Opcodes.ISHL));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false));
        pushInt(code, 16);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "digit", "(CI)I", false));
        code.add(new InsnNode(Opcodes.IOR));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new IincInsnNode(3, 1));
        code.add(new IincInsnNode(4, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, digits));
        code.add(store);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        if ("F".equals(component))
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false));
        code.add(new InsnNode(arrayStoreOpcode(component)));
        code.add(new IincInsnNode(1, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, outer));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        return name;
    }

    private String addLongFillMethod(ClassNode node, String component) {
        String name = uniqueGeneratedName(node, "$constexpr$fill$" + component + "$");
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "([" + component + "ILjava/lang/String;)V", null, null);
        InsnList code = method.instructions;
        // locals: array=0, output index=1, text=2, character index=3, digit count=4, bits=5/6
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        LabelNode outer = new LabelNode(), done = new LabelNode(), digits = new LabelNode(), store = new LabelNode();
        code.add(outer);
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new InsnNode(Opcodes.LCONST_0));
        code.add(new VarInsnNode(Opcodes.LSTORE, 5));
        code.add(digits);
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        pushInt(code, 16);
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, store));
        code.add(new VarInsnNode(Opcodes.LLOAD, 5));
        pushInt(code, 4);
        code.add(new InsnNode(Opcodes.LSHL));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false));
        pushInt(code, 16);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "digit", "(CI)I", false));
        code.add(new InsnNode(Opcodes.I2L));
        code.add(new InsnNode(Opcodes.LOR));
        code.add(new VarInsnNode(Opcodes.LSTORE, 5));
        code.add(new IincInsnNode(3, 1));
        code.add(new IincInsnNode(4, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, digits));
        code.add(store);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.LLOAD, 5));
        if ("D".equals(component))
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false));
        code.add(new InsnNode(arrayStoreOpcode(component)));
        code.add(new IincInsnNode(1, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, outer));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        return name;
    }

    private static int hexWidth(String component) {
        if ("J".equals(component) || "D".equals(component)) return 16;
        if ("I".equals(component) || "F".equals(component)) return 8;
        if ("S".equals(component) || "C".equals(component)) return 4;
        return 2;
    }

    private static String encodePrimitiveArray(ConstExprArrayValue array, String component) {
        int width = hexWidth(component);
        StringBuilder output = new StringBuilder(Math.multiplyExact(array.length(), width));
        for (int index = 0; index < array.length(); index++) {
            Object value = array.element(index).value;
            long bits;
            if ("F".equals(component))
                bits = Integer.toUnsignedLong(Float.floatToRawIntBits(((Number) value).floatValue()));
            else if ("D".equals(component)) bits = Double.doubleToRawLongBits(((Number) value).doubleValue());
            else if ("Z".equals(component)) bits = Boolean.TRUE.equals(value) ? 1L : 0L;
            else bits = ((Number) value).longValue();
            appendHex(output, bits, width);
        }
        return output.toString();
    }

    private static String encodeFloatArrayBase64(ConstExprArrayValue array) {
        byte[] bytes = new byte[Math.multiplyExact(array.length(), Float.BYTES)];
        for (int index = 0, offset = 0; index < array.length(); index++, offset += Float.BYTES) {
            int bits = Float.floatToRawIntBits(((Number) array.element(index).value).floatValue());
            bytes[offset] = (byte) (bits >>> 24);
            bytes[offset + 1] = (byte) (bits >>> 16);
            bytes[offset + 2] = (byte) (bits >>> 8);
            bytes[offset + 3] = (byte) bits;
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String encodeArrayBase64(ConstExprArrayValue array, String component) {
        if ("F".equals(component)) return encodeFloatArrayBase64(array);
        int width = hexWidth(component) / 2;
        byte[] bytes = new byte[Math.multiplyExact(array.length(), width)];
        for (int index = 0; index < array.length(); index++) {
            Object value = array.element(index).value;
            long bits = "D".equals(component) ? Double.doubleToRawLongBits(((Number) value).doubleValue())
                    : "Z".equals(component) ? Boolean.TRUE.equals(value)
                            || value instanceof Number && ((Number) value).intValue() != 0 ? 1L : 0L
                    : "C".equals(component) ? (Character) value
                    : ((Number) value).longValue();
            for (int byteIndex = 0; byteIndex < width; byteIndex++)
                bytes[index * width + byteIndex] = (byte) (bits >>> ((width - byteIndex - 1) * 8));
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String encodeSparseArray(ConstExprArrayValue array, String component) {
        StringBuilder key = new StringBuilder();
        for (int index = 0; index < array.length(); index++) {
            if (isDefaultArrayElement(array.element(index), component)) continue;
            key.append(index).append(':');
            Object value = array.element(index).value;
            long bits = "F".equals(component) ? Float.floatToRawIntBits(((Number) value).floatValue())
                    : "D".equals(component) ? Double.doubleToRawLongBits(((Number) value).doubleValue())
                    : "Z".equals(component) ? Boolean.TRUE.equals(value)
                            || value instanceof Number && ((Number) value).intValue() != 0 ? 1L : 0L
                    : "C".equals(component) ? (Character) value
                    : ((Number) value).longValue();
            key.append(Long.toHexString(bits)).append(';');
        }
        return key.toString();
    }

    private static void appendHex(StringBuilder output, long value, int digits) {
        for (int shift = (digits - 1) * 4; shift >= 0; shift -= 4)
            output.append("0123456789abcdef".charAt((int) (value >>> shift) & 15));
    }

    private String uniqueGeneratedName(ClassNode node, String prefix) {
        String candidate;
        do candidate = prefix + generatedArrayIndex++;
        while (hasMemberNamed(node, candidate));
        return candidate;
    }

    private static boolean hasMemberNamed(ClassNode node, String name) {
        for (MethodNode method : node.methods) if (name.equals(method.name)) return true;
        for (FieldNode field : node.fields) if (name.equals(field.name)) return true;
        return false;
    }

    private static boolean hasGeneratedArtifacts(ClassNode node) {
        for (FieldNode field : node.fields)
            if ((field.access & Opcodes.ACC_SYNTHETIC) != 0 && field.name.startsWith("$constexpr$template$")) return true;
        for (MethodNode method : node.methods)
            if ((method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.startsWith("$constexpr$")) return true;
        return false;
    }

    private static boolean configuredBoolean(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || "false".equalsIgnoreCase(value)) return false;
        if ("true".equalsIgnoreCase(value)) return true;
        throw new ConstExprException("System property " + property + " must be true or false: " + value);
    }

    private static final class ArrayPayload {
        final String component;
        final int length;
        final String data;

        ArrayPayload(String c, int length, String d) {
            component = c;
            this.length = length;
            data = d;
        }

        String component() {
            return component;
        }

        String data() {
            return data;
        }

        public boolean equals(Object o) {
            return o instanceof ArrayPayload && component.equals(((ArrayPayload) o).component)
                    && length == ((ArrayPayload) o).length && data.equals(((ArrayPayload) o).data);
        }

        public int hashCode() {
            return (component.hashCode() * 31 + length) * 31 + data.hashCode();
        }
    }

    private static int newArrayType(String descriptor) {
        if ("Z".equals(descriptor)) return Opcodes.T_BOOLEAN;
        if ("B".equals(descriptor)) return Opcodes.T_BYTE;
        if ("C".equals(descriptor)) return Opcodes.T_CHAR;
        if ("S".equals(descriptor)) return Opcodes.T_SHORT;
        if ("I".equals(descriptor)) return Opcodes.T_INT;
        if ("J".equals(descriptor)) return Opcodes.T_LONG;
        if ("F".equals(descriptor)) return Opcodes.T_FLOAT;
        if ("D".equals(descriptor)) return Opcodes.T_DOUBLE;
        throw new ConstExprException("Unsupported array element type: " + descriptor);
    }

    private static int arrayStoreOpcode(String descriptor) {
        if ("I".equals(descriptor)) return Opcodes.IASTORE;
        if ("J".equals(descriptor)) return Opcodes.LASTORE;
        if ("F".equals(descriptor)) return Opcodes.FASTORE;
        if ("D".equals(descriptor)) return Opcodes.DASTORE;
        if ("S".equals(descriptor)) return Opcodes.SASTORE;
        if ("B".equals(descriptor) || "Z".equals(descriptor)) return Opcodes.BASTORE;
        if ("C".equals(descriptor)) return Opcodes.CASTORE;
        return Opcodes.AASTORE;
    }

    private static void pushInt(InsnList out, int value) {
        if (value >= -1 && value <= 5) out.add(new InsnNode(Opcodes.ICONST_0 + value));
        else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) out.add(new IntInsnNode(Opcodes.BIPUSH, value));
        else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) out.add(new IntInsnNode(Opcodes.SIPUSH, value));
        else out.add(new LdcInsnNode(value));
    }

    private static ConstExprValue valueFrom(AnnotationNode annotation, String descriptor) {
        if (booleanValue(annotation, "nullValue")) {
            if (isPrimitive(descriptor)) fail("nullValue requires a reference type");
            return new ConstExprValue(descriptor, null);
        }
        Type classValue = type(annotation, "classValue");
        if (classValue != null && !"Ljava/lang/Void;".equals(classValue.getDescriptor())) {
            if (!"Ljava/lang/Class;".equals(descriptor)) fail("classValue requires a Class field or method return type");
            return new ConstExprValue(descriptor, classValue);
        }
        Type enumType = type(annotation, "enumType");
        if (enumType != null && !"Ljava/lang/Enum;".equals(enumType.getDescriptor())) {
            if (!descriptor.equals(enumType.getDescriptor())) fail("enumType must match the field or method return type");
            String name = string(annotation, "value");
            if (name.isEmpty()) fail("Enum constants require value");
            return new ConstExprValue(descriptor, new ConstExprValue.EnumConstant(enumType.getInternalName(), name, descriptor));
        }
        String build = string(annotation, "build");
        if (build.isEmpty()) return parse(descriptor, string(annotation, "value"));
        if (!"Z".equals(descriptor)) fail("build currently supports only boolean fields");
        if (!CHECK_LEGIT)
            return UnsafeConstExprExpression.evaluate(descriptor, build, Collections.<ConstExprValue>emptyList(), null, CLASS_PATH);
        return new ConstExprValue(descriptor, evaluateBoolean(build));
    }

    private static ConstExprValue javaConstant(String descriptor, Object value) {
        if ("Z".equals(descriptor)) return new ConstExprValue(descriptor, ((Number) value).intValue() != 0);
        if ("B".equals(descriptor) || "S".equals(descriptor) || "C".equals(descriptor) || "I".equals(descriptor))
            return new ConstExprValue(descriptor, ((Number) value).intValue());
        if ("J".equals(descriptor) || "F".equals(descriptor) || "D".equals(descriptor) || "Ljava/lang/String;".equals(descriptor))
            return new ConstExprValue(descriptor, value);
        throw new ConstExprException("Unsupported Java ConstantValue type: " + descriptor);
    }

    private static ConstExprValue parse(String descriptor, String raw) {
        try {
            if (!CHECK_LEGIT)
                return UnsafeConstExprExpression.evaluate(descriptor, raw, Collections.<ConstExprValue>emptyList(), null, CLASS_PATH);
            Object expression = ConstExprStaticExpression.evaluate(raw);
            if (expression != null) return staticExpressionValue(descriptor, expression, raw);
            if ("I".equals(descriptor)) return new ConstExprValue(descriptor, (int) NumericExpression.integral(raw));
            if ("J".equals(descriptor)) return new ConstExprValue(descriptor, NumericExpression.integral(raw));
            if ("F".equals(descriptor)) return new ConstExprValue(descriptor, (float) NumericExpression.floating(raw));
            if ("D".equals(descriptor)) return new ConstExprValue(descriptor, NumericExpression.floating(raw));
            if ("S".equals(descriptor)) return new ConstExprValue(descriptor, checkedShort(raw));
            if ("B".equals(descriptor)) return new ConstExprValue(descriptor, checkedByte(raw));
            if ("C".equals(descriptor))
                return new ConstExprValue(descriptor, raw.length() == 1 ? (int) raw.charAt(0) : checkedChar(raw));
            if ("Z".equals(descriptor)) return new ConstExprValue(descriptor, parseBoolean(raw));
            if ("Ljava/lang/String;".equals(descriptor)) return new ConstExprValue(descriptor, raw);
            throw new ConstExprException("Unsupported @ConstExpr type: " + descriptor);
        } catch (NumberFormatException | ArithmeticException error) {
            throw new ConstExprException("Cannot convert \"" + raw + "\" to " + descriptor, error);
        }
    }

    private static ConstExprValue staticExpressionValue(String descriptor, Object value, String source) {
        try {
            if ("I".equals(descriptor)) return new ConstExprValue(descriptor, ((Number) value).intValue());
            if ("J".equals(descriptor)) return new ConstExprValue(descriptor, ((Number) value).longValue());
            if ("F".equals(descriptor)) return new ConstExprValue(descriptor, ((Number) value).floatValue());
            if ("D".equals(descriptor)) return new ConstExprValue(descriptor, ((Number) value).doubleValue());
            if ("S".equals(descriptor)) return new ConstExprValue(descriptor, ((Number) value).shortValue());
            if ("B".equals(descriptor)) return new ConstExprValue(descriptor, ((Number) value).byteValue());
            if ("C".equals(descriptor))
                return new ConstExprValue(descriptor, value instanceof Character ? (int) ((Character) value).charValue() : ((Number) value).intValue());
            if ("Z".equals(descriptor)) return new ConstExprValue(descriptor, (Boolean) value);
            if ("Ljava/lang/String;".equals(descriptor)) return new ConstExprValue(descriptor, (String) value);
            throw new ConstExprException("Static expression is not supported for type: " + descriptor);
        } catch (ClassCastException error) {
            throw new ConstExprException("Cannot convert the result of static expression " + source + " to " + descriptor, error);
        }
    }

    private static short checkedShort(String raw) {
        long value = NumericExpression.integral(raw);
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) fail("short constant out of range: " + raw);
        return (short) value;
    }

    private static byte checkedByte(String raw) {
        long value = NumericExpression.integral(raw);
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) fail("byte constant out of range: " + raw);
        return (byte) value;
    }

    private static int checkedChar(String raw) {
        long value = NumericExpression.integral(raw);
        if (value < Character.MIN_VALUE || value > Character.MAX_VALUE) fail("char constant out of range: " + raw);
        return (int) value;
    }

    private static boolean isPrimitive(String descriptor) {
        return descriptor.length() == 1 && "V".indexOf(descriptor.charAt(0)) < 0;
    }

    private static boolean parseBoolean(String raw) {
        if (!"true".equals(raw) && !"false".equals(raw)) fail("boolean constant must be true or false");
        return Boolean.parseBoolean(raw);
    }

    private static boolean evaluateBoolean(String expr) {
        Matcher property = PROPERTY.matcher(expr);
        if (property.matches()) {
            String value = System.getProperty(property.group(1), property.group(2));
            return property.group(3) == null ? !value.isEmpty() : property.group(3).equals(value);
        }
        Matcher environment = ENV.matcher(expr);
        if (environment.matches()) {
            String value = System.getenv(environment.group(1));
            return environment.group(2) == null ? value != null && !value.isEmpty() : environment.group(2).equals(value);
        }
        fail("Unsupported build expression: " + expr);
        return false;
    }

    private static int loadOpcode(String component) {
        if ("I".equals(component)) return Opcodes.IALOAD;
        if ("J".equals(component)) return Opcodes.LALOAD;
        if ("F".equals(component)) return Opcodes.FALOAD;
        if ("D".equals(component)) return Opcodes.DALOAD;
        if ("S".equals(component)) return Opcodes.SALOAD;
        if ("B".equals(component) || "Z".equals(component)) return Opcodes.BALOAD;
        if ("C".equals(component)) return Opcodes.CALOAD;
        if ("Ljava/lang/String;".equals(component)) return Opcodes.AALOAD;
        throw new ConstExprException("Unsupported array type: [" + component);
    }

    private static List<AbstractInsnNode> constantArguments(String owner, String sourceFile, MethodNode method, MethodInsnNode call) {
        Type[] types = Type.getArgumentTypes(call.desc);
        List<AbstractInsnNode> result = new ArrayList<>(Collections.nCopies(types.length, null));
        AbstractInsnNode node = previousExecutable(call.getPrevious());
        for (int index = types.length - 1; index >= 0; index--) {
            if (node == null) fail(argumentError(owner, sourceFile, method, call, index, "is missing a value"));
            result.set(index, node);
            node = previousExecutable(node.getPrevious());
        }
        return result;
    }

    private static ConstExprValue literalValue(AbstractInsnNode node, String owner, String sourceFile, MethodNode method,
                                               MethodInsnNode call, int argumentIndex) {
        if (node instanceof VarInsnNode && node.getOpcode() >= Opcodes.ILOAD && node.getOpcode() <= Opcodes.ALOAD) {
            ConstExprValue local = immutableLocalAtCall(method, (VarInsnNode) node);
            if (local != null) return local;
        }
        if (node != null && node.getOpcode() == Opcodes.I2L) {
            ConstExprValue integer = literalValue(previousExecutable(node.getPrevious()), owner, sourceFile, method, call, argumentIndex);
            return new ConstExprValue("J", ((Number) integer.value).longValue());
        }
        if (node instanceof MethodInsnNode && ((MethodInsnNode) node).getOpcode() == Opcodes.INVOKESTATIC
                && ((MethodInsnNode) node).name.equals("valueOf") && Type.getArgumentTypes(((MethodInsnNode) node).desc).length == 1) {
            MethodInsnNode boxing = (MethodInsnNode) node;
            ConstExprValue primitive = literalValue(previousExecutable(boxing.getPrevious()), owner, sourceFile, method, call, argumentIndex);
            String wrapper = "L" + boxing.owner + ";";
            return new ConstExprValue(wrapper, primitive.value);
        }
        Integer integer = integer(node);
        if (integer != null) return new ConstExprValue("I", integer);
        if (node instanceof LdcInsnNode) {
            LdcInsnNode ldc = (LdcInsnNode) node;
            if (ldc.cst instanceof Long) return new ConstExprValue("J", ldc.cst);
            if (ldc.cst instanceof Float) return new ConstExprValue("F", ldc.cst);
            if (ldc.cst instanceof Double) return new ConstExprValue("D", ldc.cst);
            if (ldc.cst instanceof String) return new ConstExprValue("Ljava/lang/String;", ldc.cst);
        }
        fail(argumentError(owner, sourceFile, method, call, argumentIndex, "is not a compile-time literal"));
        return null;
    }

    private static ConstExprValue immutableLocalAtCall(MethodNode method, VarInsnNode load) {
        int parameterEnd = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type parameter : Type.getArgumentTypes(method.desc)) parameterEnd += parameter.getSize();
        if (load.var < parameterEnd) return null;

        VarInsnNode store = null;
        boolean beforeLoad = true;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction == load) beforeLoad = false;
            if (instruction instanceof IincInsnNode && ((IincInsnNode) instruction).var == load.var) return null;
            if (!(instruction instanceof VarInsnNode)) continue;
            VarInsnNode variable = (VarInsnNode) instruction;
            if (variable.var != load.var || variable.getOpcode() < Opcodes.ISTORE
                    || variable.getOpcode() > Opcodes.ASTORE) continue;
            if (store != null || !beforeLoad || variable.getOpcode() != load.getOpcode() + 33) return null;
            store = variable;
        }
        if (store == null) return null;
        AbstractInsnNode source = previousExecutable(store.getPrevious());
        return hasLabelBetween(source, store) ? null : localConstant(source, store.getOpcode());
    }

    private static void removeArgument(InsnList instructions, AbstractInsnNode argument) {
        if (argument != null && argument.getOpcode() == Opcodes.I2L) {
            AbstractInsnNode integer = previousExecutable(argument.getPrevious());
            if (integer != null) instructions.remove(integer);
        }
        if (argument instanceof MethodInsnNode && ((MethodInsnNode) argument).getOpcode() == Opcodes.INVOKESTATIC
                && ((MethodInsnNode) argument).name.equals("valueOf") && Type.getArgumentTypes(((MethodInsnNode) argument).desc).length == 1) {
            MethodInsnNode boxing = (MethodInsnNode) argument;
            AbstractInsnNode primitive = previousExecutable(boxing.getPrevious());
            if (primitive != null) instructions.remove(primitive);
        }
        instructions.remove(argument);
    }

    private static void removeCallArguments(InsnList instructions, MethodInsnNode call,
                                            List<AbstractInsnNode> arguments) {
        if (arguments != null) {
            for (AbstractInsnNode argument : arguments) removeArgument(instructions, argument);
            return;
        }
        Type[] types = Type.getArgumentTypes(call.desc);
        for (int index = types.length - 1; index >= 0; index--) {
            instructions.insertBefore(call, new InsnNode(types[index].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
    }

    private static AbstractInsnNode foldDirectReflectiveFieldRead(ClassNode owner, MethodNode method,
                                                                  MethodInsnNode read) {
        if (addDynamicReflectiveFieldDispatch(owner, method, read)) return null;
        if (!"java/lang/reflect/Field".equals(read.owner)
                || !"(Ljava/lang/Object;)I".equals(read.desc) && !"(Ljava/lang/Object;)J".equals(read.desc)
                && !"(Ljava/lang/Object;)Z".equals(read.desc) && !"(Ljava/lang/Object;)B".equals(read.desc)
                && !"(Ljava/lang/Object;)S".equals(read.desc) && !"(Ljava/lang/Object;)C".equals(read.desc)
                && !"(Ljava/lang/Object;)F".equals(read.desc) && !"(Ljava/lang/Object;)D".equals(read.desc)
                && !"(Ljava/lang/Object;)Ljava/lang/Object;".equals(read.desc)) return null;
        AbstractInsnNode target = previousExecutable(read.getPrevious());
        AbstractInsnNode lookup = previousExecutable(target == null ? null : target.getPrevious());
        AbstractInsnNode name = previousExecutable(lookup == null ? null : lookup.getPrevious());
        AbstractInsnNode type = previousExecutable(name == null ? null : name.getPrevious());
        if (target == null || target.getOpcode() != Opcodes.ACONST_NULL
                || !(lookup instanceof MethodInsnNode) || !(name instanceof LdcInsnNode)
                || !(type instanceof LdcInsnNode)) return null;
        MethodInsnNode find = (MethodInsnNode) lookup;
        if (find.getOpcode() != Opcodes.INVOKEVIRTUAL || !"java/lang/Class".equals(find.owner)
                || !"getDeclaredField".equals(find.name)
                || !"(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(find.desc)) return null;
        Object classLiteral = ((LdcInsnNode) type).cst;
        Object fieldName = ((LdcInsnNode) name).cst;
        if (!(classLiteral instanceof Type) || ((Type) classLiteral).getSort() != Type.OBJECT
                || !(fieldName instanceof String)
                || !owner.name.equals(((Type) classLiteral).getInternalName())) return null;
        for (FieldNode field : owner.fields) {
            if (!field.name.equals(fieldName) || (field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                    != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) continue;
            String expectedGetter = "get" + reflectiveGetterSuffix(field.desc);
            if (!expectedGetter.equals(read.name)) continue;
            AbstractInsnNode after = read.getNext();
            method.instructions.set(read, new FieldInsnNode(Opcodes.GETSTATIC, owner.name, field.name, field.desc));
            method.instructions.remove(target);
            method.instructions.remove(lookup);
            method.instructions.remove(name);
            method.instructions.remove(type);
            return after;
        }
        return null;
    }

    private static boolean addDynamicReflectiveFieldDispatch(ClassNode owner, MethodNode method,
                                                             MethodInsnNode read) {
        if (!"java/lang/reflect/Field".equals(read.owner) || !"get".equals(read.name)
                || !"(Ljava/lang/Object;)Ljava/lang/Object;".equals(read.desc)
                || !method.tryCatchBlocks.isEmpty()) return false;
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode) return false;
        AbstractInsnNode target = previousExecutable(read.getPrevious());
        AbstractInsnNode secondLoad = previousExecutable(target == null ? null : target.getPrevious());
        AbstractInsnNode accessible = previousExecutable(secondLoad == null ? null : secondLoad.getPrevious());
        AbstractInsnNode enabled = previousExecutable(accessible == null ? null : accessible.getPrevious());
        AbstractInsnNode firstLoad = previousExecutable(enabled == null ? null : enabled.getPrevious());
        AbstractInsnNode store = previousExecutable(firstLoad == null ? null : firstLoad.getPrevious());
        AbstractInsnNode lookup = previousExecutable(store == null ? null : store.getPrevious());
        AbstractInsnNode name = previousExecutable(lookup == null ? null : lookup.getPrevious());
        AbstractInsnNode type = previousExecutable(name == null ? null : name.getPrevious());
        if (target == null || target.getOpcode() != Opcodes.ACONST_NULL
                || !(secondLoad instanceof VarInsnNode) || secondLoad.getOpcode() != Opcodes.ALOAD
                || !(accessible instanceof MethodInsnNode) || enabled == null
                || enabled.getOpcode() != Opcodes.ICONST_1 || !(firstLoad instanceof VarInsnNode)
                || firstLoad.getOpcode() != Opcodes.ALOAD || !(store instanceof VarInsnNode)
                || store.getOpcode() != Opcodes.ASTORE || !(lookup instanceof MethodInsnNode)
                || !(name instanceof VarInsnNode) || name.getOpcode() != Opcodes.ALOAD
                || !(type instanceof LdcInsnNode)) return false;
        int fieldSlot = ((VarInsnNode) store).var;
        if (((VarInsnNode) firstLoad).var != fieldSlot || ((VarInsnNode) secondLoad).var != fieldSlot)
            return false;
        MethodInsnNode accessCall = (MethodInsnNode) accessible;
        MethodInsnNode find = (MethodInsnNode) lookup;
        if (accessCall.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !"java/lang/reflect/Field".equals(accessCall.owner)
                || !"setAccessible".equals(accessCall.name) || !"(Z)V".equals(accessCall.desc)
                || find.getOpcode() != Opcodes.INVOKEVIRTUAL || !"java/lang/Class".equals(find.owner)
                || !"getDeclaredField".equals(find.name)
                || !"(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(find.desc)) return false;
        Object classLiteral = ((LdcInsnNode) type).cst;
        if (!(classLiteral instanceof Type) || ((Type) classLiteral).getSort() != Type.OBJECT
                || !owner.name.equals(((Type) classLiteral).getInternalName())) return false;
        for (AbstractInsnNode instruction = read.getNext(); instruction != null; instruction = instruction.getNext())
            if (instruction instanceof VarInsnNode && ((VarInsnNode) instruction).var == fieldSlot)
                return false;
        List<FieldNode> candidates = new ArrayList<>();
        for (FieldNode field : owner.fields)
            if ((field.access & Opcodes.ACC_STATIC) != 0 && (field.access & Opcodes.ACC_FINAL) == 0)
                candidates.add(field);
        if (candidates.isEmpty() || candidates.size() > 16) return false;
        LabelNode join = new LabelNode();
        InsnList dispatch = new InsnList();
        for (FieldNode field : candidates) {
            LabelNode nextField = new LabelNode();
            dispatch.add(new LdcInsnNode(field.name));
            dispatch.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) name).var));
            dispatch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false));
            dispatch.add(new JumpInsnNode(Opcodes.IFEQ, nextField));
            dispatch.add(new FieldInsnNode(Opcodes.GETSTATIC, owner.name, field.name, field.desc));
            addBoxing(dispatch, field.desc);
            dispatch.add(new JumpInsnNode(Opcodes.GOTO, join));
            dispatch.add(nextField);
        }
        method.instructions.insertBefore(type, dispatch);
        method.instructions.insert(read, join);
        return true;
    }

    private static void addBoxing(InsnList instructions, String descriptor) {
        String wrapper;
        switch (descriptor) {
            case "I": wrapper = "Integer"; break;
            case "J": wrapper = "Long"; break;
            case "Z": wrapper = "Boolean"; break;
            case "B": wrapper = "Byte"; break;
            case "S": wrapper = "Short"; break;
            case "C": wrapper = "Character"; break;
            case "F": wrapper = "Float"; break;
            case "D": wrapper = "Double"; break;
            default: return;
        }
        String owner = "java/lang/" + wrapper;
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf",
                "(" + descriptor + ")L" + owner + ";", false));
    }

    private static String reflectiveGetterSuffix(String descriptor) {
        switch (descriptor) {
            case "I": return "Int";
            case "J": return "Long";
            case "Z": return "Boolean";
            case "B": return "Byte";
            case "S": return "Short";
            case "C": return "Char";
            case "F": return "Float";
            case "D": return "Double";
            default: return descriptor.startsWith("L") || descriptor.startsWith("[") ? "" : "?";
        }
    }

    private static AbstractInsnNode foldLiteralArrayToString(ClassNode owner, MethodNode method,
                                                             MethodInsnNode call) {
        if (!"java/util/Arrays".equals(call.owner) || !"toString".equals(call.name)
                || !"Ljava/lang/String;".equals(Type.getReturnType(call.desc).getDescriptor())) return null;
        Type[] parameters = Type.getArgumentTypes(call.desc);
        if (parameters.length != 1 || parameters[0].getSort() != Type.ARRAY
                || parameters[0].getDimensions() != 1
                || parameters[0].getElementType().getSort() > Type.DOUBLE) return null;
        String component = parameters[0].getElementType().getDescriptor();
        AbstractInsnNode cursor = previousExecutable(call.getPrevious());
        Map<Integer, ConstExprValue> writes = new HashMap<>();
        while (cursor != null && cursor.getOpcode() == arrayStoreOpcode(component)) {
            AbstractInsnNode valueInstruction = previousExecutable(cursor.getPrevious());
            AbstractInsnNode indexInstruction = previousExecutable(valueInstruction == null ? null : valueInstruction.getPrevious());
            AbstractInsnNode duplicate = previousExecutable(indexInstruction == null ? null : indexInstruction.getPrevious());
            Integer index = integer(indexInstruction);
            ConstExprValue value = directScalar(valueInstruction, parameters[0].getElementType());
            if (index == null || index < 0 || value == null || duplicate == null
                    || duplicate.getOpcode() != Opcodes.DUP) return null;
            if ("B".equals(component)) value = new ConstExprValue("B", ((Number) value.value).byteValue());
            if ("S".equals(component)) value = new ConstExprValue("S", ((Number) value.value).shortValue());
            writes.putIfAbsent(index, value);
            cursor = previousExecutable(duplicate.getPrevious());
        }
        if (!(cursor instanceof IntInsnNode) || cursor.getOpcode() != Opcodes.NEWARRAY
                || ((IntInsnNode) cursor).operand != newArrayType(component)) return null;
        AbstractInsnNode lengthInstruction = previousExecutable(cursor.getPrevious());
        Integer length = integer(lengthInstruction);
        if (length == null || length < 0 || length > ConstExprInterpreter.maxArrayElements()) return null;
        for (Integer index : writes.keySet()) if (index >= length) return null;
        for (AbstractInsnNode instruction = lengthInstruction; instruction != call; instruction = instruction.getNext())
            if (instruction instanceof LabelNode || instruction instanceof JumpInsnNode) return null;
        List<ConstExprValue> elements = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            ConstExprValue value = writes.get(index);
            if (value == null) value = zeroArrayElement(component);
            elements.add(value);
        }
        ConstExprValue result;
        try {
            result = ConstExprJdkInvoker.invoke(call, null,
                    Collections.<ConstExprValue>singletonList(new ConstExprArrayValue(parameters[0].getDescriptor(), elements)));
        } catch (ConstExprException error) {
            return null;
        }
        if (result == null || !(result.value instanceof String)
                || modifiedUtf8Length((String) result.value) > 65535) return null;
        AbstractInsnNode after = call.getNext();
        for (AbstractInsnNode instruction = lengthInstruction; instruction != call; ) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction.getOpcode() >= 0) method.instructions.remove(instruction);
            instruction = next;
        }
        replace(method.instructions, call, result);
        FOLDED_CALLERS.add(key(owner.name, method.name, method.desc));
        return after;
    }

    private static ConstExprValue zeroArrayElement(String component) {
        switch (component) {
            case "J": return new ConstExprValue(component, 0L);
            case "F": return new ConstExprValue(component, 0F);
            case "D": return new ConstExprValue(component, 0D);
            case "B": return new ConstExprValue(component, (byte) 0);
            case "S": return new ConstExprValue(component, (short) 0);
            default: return new ConstExprValue(component, 0);
        }
    }

    private static int modifiedUtf8Length(String text) {
        int length = 0;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            length += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3;
            if (length > 65535) return length;
        }
        return length;
    }

    private static AbstractInsnNode foldPureJdkCall(ClassNode owner, String sourceFile,
                                                    MethodNode method, MethodInsnNode call) {
        if (!isPureJdkCall(call)) return null;
        List<AbstractInsnNode> arguments = null;
        List<ConstExprValue> values = new ArrayList<>();
        try {
            arguments = constantArguments(owner.name, sourceFile, method, call);
            for (int index = 0; index < arguments.size(); index++)
                values.add(literalValue(arguments.get(index), owner.name, sourceFile, method, call, index));
        } catch (ConstExprException ignored) {
            arguments = null;
            values = ConstExprCallSiteAnalyzer.arguments(owner.name, method, call, FIELDS);
            if (values == null) return null;
        }
        ConstExprValue result;
        try {
            result = ConstExprJdkInvoker.invoke(call, null, values);
        } catch (ConstExprException ignored) {
            return null; // Preserve calls that would throw at runtime, such as floorDiv(x, 0).
        }
        if (result == null || !(result.value instanceof Number)) return null;
        AbstractInsnNode after = call.getNext();
        removeCallArguments(method.instructions, call, arguments);
        replace(method.instructions, call, result);
        return after;
    }

    private static boolean isPureJdkCall(MethodInsnNode call) {
        String signature = call.name + call.desc;
        if ("java/lang/Math".equals(call.owner) || "java/lang/StrictMath".equals(call.owner)) {
            switch (signature) {
                case "abs(I)I": case "abs(J)J":
                case "min(II)I": case "min(JJ)J": case "max(II)I": case "max(JJ)J":
                case "floorDiv(II)I": case "floorDiv(JJ)J":
                case "floorMod(II)I": case "floorMod(JJ)J":
                case "addExact(II)I": case "addExact(JJ)J":
                case "subtractExact(II)I": case "subtractExact(JJ)J":
                case "multiplyExact(II)I": case "multiplyExact(JJ)J":
                case "negateExact(I)I": case "negateExact(J)J":
                case "incrementExact(I)I": case "incrementExact(J)J":
                case "decrementExact(I)I": case "decrementExact(J)J":
                case "toIntExact(J)I": return true;
                default: return false;
            }
        }
        if ("java/lang/Integer".equals(call.owner)) {
            switch (signature) {
                case "bitCount(I)I": case "numberOfLeadingZeros(I)I":
                case "numberOfTrailingZeros(I)I": case "highestOneBit(I)I":
                case "lowestOneBit(I)I": case "reverse(I)I": case "reverseBytes(I)I":
                case "signum(I)I": case "rotateLeft(II)I": case "rotateRight(II)I":
                case "compareUnsigned(II)I": case "divideUnsigned(II)I":
                case "remainderUnsigned(II)I": return true;
                default: return false;
            }
        }
        if ("java/lang/Long".equals(call.owner)) {
            switch (signature) {
                case "bitCount(J)I": case "numberOfLeadingZeros(J)I":
                case "numberOfTrailingZeros(J)I": case "highestOneBit(J)J":
                case "lowestOneBit(J)J": case "reverse(J)J": case "reverseBytes(J)J":
                case "signum(J)I": case "rotateLeft(JI)J": case "rotateRight(JI)J":
                case "compareUnsigned(JJ)I": case "divideUnsigned(JJ)J":
                case "remainderUnsigned(JJ)J": return true;
                default: return false;
            }
        }
        return false;
    }

    private AbstractInsnNode foldKnownStringBytes(ClassNode owner, MethodNode method, MethodInsnNode call) {
        if (!"java/lang/String".equals(call.owner) || !"getBytes".equals(call.name)
                || !"(Ljava/nio/charset/Charset;)[B".equals(call.desc)) return null;
        AbstractInsnNode charsetSource = previousExecutable(call.getPrevious());
        AbstractInsnNode stringSource = previousExecutable(charsetSource == null ? null : charsetSource.getPrevious());
        if (!(charsetSource instanceof FieldInsnNode) || !(stringSource instanceof LdcInsnNode)) return null;
        FieldInsnNode field = (FieldInsnNode) charsetSource;
        Object string = ((LdcInsnNode) stringSource).cst;
        if (!(string instanceof String) || field.getOpcode() != Opcodes.GETSTATIC
                || !"java/nio/charset/StandardCharsets".equals(field.owner)
                || !"Ljava/nio/charset/Charset;".equals(field.desc)) return null;
        ConstExprValue charset = ConstExprJdkInvoker.staticField(field);
        if (charset == null || !(charset.value instanceof Charset)) return null;
        byte[] bytes = ((String) string).getBytes((Charset) charset.value);
        if (bytes.length > ConstExprInterpreter.maxArrayElements()) return null;
        List<ConstExprValue> elements = new ArrayList<ConstExprValue>(bytes.length);
        for (byte value : bytes) elements.add(new ConstExprValue("B", value));
        AbstractInsnNode after = call.getNext();
        method.instructions.remove(stringSource);
        method.instructions.remove(charsetSource);
        materializeArray(owner, method, call, new ConstExprArrayValue("[B", elements));
        FOLDED_CALLERS.add(key(owner.name, method.name, method.desc));
        return after;
    }

    private static AbstractInsnNode foldFreshInstanceCall(ClassNode owner, MethodNode caller, MethodInsnNode call) {
        if (call.owner.startsWith("java/")) return null;
        Type resultType = Type.getReturnType(call.desc);
        if (resultType.getSort() == Type.VOID || resultType.getSort() == Type.ARRAY
                || resultType.getSort() == Type.OBJECT) return null;
        AbstractInsnNode cursor = previousExecutable(call.getPrevious());
        List<ConstExprValue> arguments = new ArrayList<>();
        for (Type type : Type.getArgumentTypes(call.desc)) arguments.add(null);
        for (int index = arguments.size() - 1; index >= 0; index--) {
            ConstExprValue value = directScalar(cursor, Type.getArgumentTypes(call.desc)[index]);
            if (value == null) return null;
            arguments.set(index, value);
            cursor = previousExecutable(cursor.getPrevious());
        }
        VarInsnNode receiverLoad = null;
        VarInsnNode receiverStore = null;
        if (cursor instanceof VarInsnNode && cursor.getOpcode() == Opcodes.ALOAD) {
            receiverLoad = (VarInsnNode) cursor;
            AbstractInsnNode store = previousExecutable(cursor.getPrevious());
            if (!(store instanceof VarInsnNode) || store.getOpcode() != Opcodes.ASTORE
                    || ((VarInsnNode) store).var != receiverLoad.var) return null;
            receiverStore = (VarInsnNode) store;
            for (AbstractInsnNode instruction : caller.instructions) {
                if (instruction instanceof IincInsnNode && ((IincInsnNode) instruction).var == receiverLoad.var)
                    return null;
                if (instruction instanceof VarInsnNode && ((VarInsnNode) instruction).var == receiverLoad.var
                        && instruction != receiverLoad && instruction != receiverStore) return null;
            }
            cursor = previousExecutable(store.getPrevious());
        }
        if (!(cursor instanceof MethodInsnNode) || cursor.getOpcode() != Opcodes.INVOKESPECIAL) return null;
        MethodInsnNode constructorCall = (MethodInsnNode) cursor;
        if (!"<init>".equals(constructorCall.name) || !call.owner.equals(constructorCall.owner)) return null;
        List<ConstExprValue> constructorArguments = new ArrayList<>();
        Type[] constructorTypes = Type.getArgumentTypes(constructorCall.desc);
        for (int index = 0; index < constructorTypes.length; index++) constructorArguments.add(null);
        cursor = previousExecutable(cursor.getPrevious());
        for (int index = constructorTypes.length - 1; index >= 0; index--) {
            ConstExprValue value = directScalar(cursor, constructorTypes[index]);
            if (value == null) return null;
            constructorArguments.set(index, value);
            cursor = previousExecutable(cursor.getPrevious());
        }
        if (cursor == null || cursor.getOpcode() != Opcodes.DUP) return null;
        AbstractInsnNode allocation = previousExecutable(cursor.getPrevious());
        if (!(allocation instanceof TypeInsnNode) || allocation.getOpcode() != Opcodes.NEW
                || !call.owner.equals(((TypeInsnNode) allocation).desc)) return null;
        for (AbstractInsnNode instruction = allocation.getNext(); instruction != call; instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode) return null;
            if (instruction instanceof LabelNode && isControlFlowTarget(caller, (LabelNode) instruction)) return null;
        }
        ClassNode target = call.owner.equals(owner.name) ? owner : PROJECT_CLASSES.get(call.owner);
        if (target == null) target = CLASS_PATH.resolve(call.owner);
        if (target == null || !"java/lang/Object".equals(target.superName)
                || (target.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0
                || !target.interfaces.isEmpty()
                || target.methods.stream().anyMatch(method -> "<clinit>".equals(method.name)
                    || ("finalize".equals(method.name) && "()V".equals(method.desc)))) return null;
        MethodNode body = findMethod(target, call.name, call.desc);
        MethodNode constructor = findMethod(target, "<init>", constructorCall.desc);
        if (!pureInstanceBody(body, target.name, false)
                || !pureInstanceBody(constructor, target.name, true)) return null;

        ConstExprValue receiver = new ConstExprValue("L" + call.owner + ";", new ConstExprHeapObject(call.owner));
        ConstExprValue result;
        try {
            METHODS.invoke(constructorCall, receiver, constructorArguments);
            result = METHODS.invoke(call, receiver, arguments);
        } catch (RuntimeException error) {
            return null;
        }
        if (result == null || result.value == null) return null;
        AbstractInsnNode after = call.getNext();
        for (AbstractInsnNode instruction = allocation; instruction != call; ) {
            AbstractInsnNode following = instruction.getNext();
            if (instruction.getOpcode() >= 0) caller.instructions.remove(instruction);
            instruction = following;
        }
        replace(caller.instructions, call, result);
        FOLDED_CALLERS.add(key(owner.name, caller.name, caller.desc));
        FOLDED_INSTANCE_METHODS.add(key(call.owner, call.name, call.desc));
        FOLDED_INSTANCE_METHODS.add(key(constructorCall.owner, constructorCall.name, constructorCall.desc));
        return after;
    }

    private static boolean isControlFlowTarget(MethodNode method, LabelNode label) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode && ((JumpInsnNode) instruction).label == label) return true;
            if (instruction instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode branch = (TableSwitchInsnNode) instruction;
                if (branch.dflt == label || branch.labels.contains(label)) return true;
            }
            if (instruction instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode branch = (LookupSwitchInsnNode) instruction;
                if (branch.dflt == label || branch.labels.contains(label)) return true;
            }
        }
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode block : method.tryCatchBlocks)
                if (block.start == label || block.end == label || block.handler == label) return true;
        }
        return false;
    }

    private static MethodNode findMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods)
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        return null;
    }

    private static boolean pureInstanceBody(MethodNode method, String owner, boolean constructor) {
        if (method == null || (method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED)) != 0)
            return false;
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode invoked = (MethodInsnNode) instruction;
                if (!constructor || opcode != Opcodes.INVOKESPECIAL || !"java/lang/Object".equals(invoked.owner)
                        || !"<init>".equals(invoked.name) || !"()V".equals(invoked.desc)) return false;
            } else if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (!owner.equals(field.owner) || (opcode != Opcodes.GETFIELD
                        && !(constructor && opcode == Opcodes.PUTFIELD))) return false;
            } else if (instruction instanceof InvokeDynamicInsnNode || instruction instanceof TypeInsnNode
                    || opcode == Opcodes.PUTSTATIC || opcode == Opcodes.GETSTATIC
                    || opcode == Opcodes.ATHROW || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT
                    || opcode == Opcodes.NEWARRAY || opcode == Opcodes.MULTIANEWARRAY
                    || (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD)
                    || (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE)) return false;
        }
        return true;
    }

    private static ConstExprValue directScalar(AbstractInsnNode instruction, Type type) {
        if (instruction == null) return null;
        Object value = literal(instruction);
        if (value == null || value == NullLiteral.INSTANCE) return null;
        switch (type.getSort()) {
            case Type.INT: case Type.BYTE: case Type.SHORT: case Type.CHAR: case Type.BOOLEAN:
                return value instanceof Integer ? new ConstExprValue(type.getDescriptor(), value) : null;
            case Type.LONG: return value instanceof Long ? new ConstExprValue("J", value) : null;
            case Type.FLOAT: return value instanceof Float ? new ConstExprValue("F", value) : null;
            case Type.DOUBLE: return value instanceof Double ? new ConstExprValue("D", value) : null;
            case Type.OBJECT:
                return "java/lang/String".equals(type.getInternalName()) && value instanceof String
                        ? new ConstExprValue(type.getDescriptor(), value) : null;
            default: return null;
        }
    }

    private static String argumentError(String owner, String sourceFile, MethodNode method, MethodInsnNode call, int argumentIndex, String reason) {
        int line = sourceLine(call);
        String location = line < 0 ? owner : (sourceFile == null ? owner : sourceFile) + " line " + line + " (class " + owner + ")";
        return "@ConstExpr call argument " + reason + " at " + location + ", method " + owner + "." + method.name + method.desc
                + ", target " + call.owner + "." + call.name + call.desc + ", argument #" + argumentIndex;
    }

    private static int sourceLine(AbstractInsnNode node) {
        for (AbstractInsnNode current = node; current != null; current = current.getPrevious()) {
            if (current instanceof LineNumberNode) return ((LineNumberNode) current).line;
        }
        return -1;
    }

    private static Integer integer(AbstractInsnNode node) {
        if (node == null) return null;
        switch (node.getOpcode()) {
            case Opcodes.ICONST_M1:
                return -1;
            case Opcodes.ICONST_0:
                return 0;
            case Opcodes.ICONST_1:
                return 1;
            case Opcodes.ICONST_2:
                return 2;
            case Opcodes.ICONST_3:
                return 3;
            case Opcodes.ICONST_4:
                return 4;
            case Opcodes.ICONST_5:
                return 5;
            case Opcodes.BIPUSH:
            case Opcodes.SIPUSH:
                return ((IntInsnNode) node).operand;
            case Opcodes.LDC:
                Object c = ((LdcInsnNode) node).cst;
                return c instanceof Integer ? (Integer) c : null;
            default:
                return null;
        }
    }

    private static AbstractInsnNode executable(AbstractInsnNode node) {
        while (node instanceof LabelNode || node instanceof LineNumberNode || node instanceof FrameNode)
            node = node.getNext();
        return node;
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode node) {
        while (node instanceof LabelNode || node instanceof LineNumberNode || node instanceof FrameNode)
            node = node.getPrevious();
        return node;
    }

    private static Object literal(AbstractInsnNode node) {
        if (node == null) return null;
        Integer i = integer(node);
        if (i != null) return i;
        if (node.getOpcode() == Opcodes.ACONST_NULL) return NullLiteral.INSTANCE;
        if (node instanceof LdcInsnNode) {
            Object c = ((LdcInsnNode) node).cst;
            if (c instanceof String || c instanceof Long || c instanceof Float || c instanceof Double) return c;
        }
        return null;
    }

    static String key(String owner, String name, String descriptor) {
        return owner + ":" + name + ":" + descriptor;
    }

    static boolean isCheckLegit() {
        return CHECK_LEGIT;
    }

    private static boolean isSmartMode() {
        return MODE == ConstExprMode.SMART;
    }

    private static void requireStatic(String owner, String name, int access) {
        if ((access & Opcodes.ACC_STATIC) == 0) fail("@ConstExpr requires a static member: " + owner + "." + name);
    }

    private static void fail(String message) {
        throw new ConstExprException(message);
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        if (visible != null)
            for (AnnotationNode annotation : visible) if (DESC.equals(annotation.desc)) return annotation;
        if (invisible != null)
            for (AnnotationNode annotation : invisible) if (DESC.equals(annotation.desc)) return annotation;
        return null;
    }

    private static String string(AnnotationNode annotation, String name) {
        Object value = value(annotation, name);
        return value instanceof String ? (String) value : "";
    }

    private static ConstExprMode configuredMode() {
        String value = System.getProperty(MODE_PROPERTY);
        if (value == null || value.trim().isEmpty()) return ConstExprMode.SMART;
        try {
            return ConstExprMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new ConstExprException("System property " + MODE_PROPERTY + " must be SMART or TAG: " + value);
        }
    }

    private static boolean booleanValue(AnnotationNode annotation, String name) {
        Object value = value(annotation, name);
        return value instanceof Boolean && ((Boolean) value);
    }

    private static Type type(AnnotationNode annotation, String name) {
        Object value = value(annotation, name);
        return value instanceof Type ? (Type) value : null;
    }

    private static List<String> strings(AnnotationNode annotation, String name) {
        Object value = value(annotation, name);
        if (!(value instanceof List<?>)) return java.util.Collections.emptyList();
        List<?> values = (List<?>) value;
        List<String> result = new ArrayList<String>();
        for (Object item : values) {
            if (!(item instanceof String)) fail("Annotation array values must be strings");
            result.add((String) item);
        }
        return result;
    }

    private static int[] ints(AnnotationNode annotation, String name) {
        Object value = value(annotation, name);
        if (!(value instanceof List<?>)) return new int[0];
        List<?> values = (List<?>) value;
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object item = values.get(i);
            if (!(item instanceof Integer)) fail("Array shape dimensions must be positive integers");
            int n = (Integer) item;
            if (n <= 0) fail("Array shape dimensions must be positive integers");
            result[i] = n;
        }
        return result;
    }

    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values == null) return null;
        for (int index = 0; index < annotation.values.size(); index += 2)
            if (name.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        return null;
    }

    private static void removeAnnotations(ClassNode node) {
        remove(node.visibleAnnotations, node.invisibleAnnotations);
        removeTypes(node.visibleTypeAnnotations, node.invisibleTypeAnnotations);
        for (FieldNode field : node.fields) {
            remove(field.visibleAnnotations, field.invisibleAnnotations);
            removeTypes(field.visibleTypeAnnotations, field.invisibleTypeAnnotations);
        }
        for (MethodNode method : node.methods) {
            remove(method.visibleAnnotations, method.invisibleAnnotations);
            removeTypes(method.visibleTypeAnnotations, method.invisibleTypeAnnotations);
            if (method.visibleParameterAnnotations != null)
                for (List<AnnotationNode> annotations : method.visibleParameterAnnotations) remove(annotations, null);
            if (method.invisibleParameterAnnotations != null)
                for (List<AnnotationNode> annotations : method.invisibleParameterAnnotations) remove(null, annotations);
            for (TryCatchBlockNode block : method.tryCatchBlocks)
                removeTypes(block.visibleTypeAnnotations, block.invisibleTypeAnnotations);
            for (AbstractInsnNode instruction : method.instructions)
                removeTypes(instruction.visibleTypeAnnotations, instruction.invisibleTypeAnnotations);
        }
    }

    private static void removeTypes(List<TypeAnnotationNode> visible, List<TypeAnnotationNode> invisible) {
        if (visible != null) visible.removeIf(annotation -> DESC.equals(annotation.desc));
        if (invisible != null) invisible.removeIf(annotation -> DESC.equals(annotation.desc));
    }

    private static void remove(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        if (visible != null) visible.removeIf(annotation -> DESC.equals(annotation.desc));
        if (invisible != null) invisible.removeIf(annotation -> DESC.equals(annotation.desc));
    }

    public static void clear() {
        FIELDS.clear();
        PROJECT_CLASSES.clear();
        ANNOTATED_FIELDS.clear();
        ANNOTATED_METHODS.clear();
        CLASS_DISCOVERED_METHODS.clear();
        EVALUATED_METHODS.clear();
        FOLDED_CALLERS.clear();
        FOLDED_INSTANCE_METHODS.clear();
        ARRAY_SHAPES.clear();
        preserveReflectiveMembers = false;
        METHODS = new ConstExprMethodEvaluator(FIELDS);
        CLASS_PATH = new ConstExprClassPath(Collections.<Path>emptyList());
        UnsafeConstExprExpression.clear();
        CHECK_LEGIT = true;
        MODE = ConstExprMode.SMART;
    }

    private enum NullLiteral {INSTANCE}

    /**
     * Small recursive-descent evaluator for numeric annotation literals.
     */
    static final class NumericExpression {
        private final String source;
        private final boolean floating;
        private int position;

        private NumericExpression(String source, boolean floating) {
            this.source = source.replace("_", "");
            this.floating = floating;
        }

        static long integral(String source) {
            return new NumericExpression(source, false).parseIntegral();
        }

        static double floating(String source) {
            return new NumericExpression(source, true).parseFloating();
        }

        private long parseIntegral() {
            long result = parseIntegralSum();
            finish();
            return result;
        }

        private double parseFloating() {
            double result = parseFloatingSum();
            finish();
            return result;
        }

        private long parseIntegralSum() {
            long result = parseIntegralProduct();
            while (true) {
                if (consume('+')) result += parseIntegralProduct();
                else if (consume('-')) result -= parseIntegralProduct();
                else return result;
            }
        }

        private long parseIntegralProduct() {
            long result = parseIntegralUnary();
            while (true) {
                if (consume('*')) result *= parseIntegralUnary();
                else if (consume('/')) result /= parseIntegralUnary();
                else if (consume('%')) result %= parseIntegralUnary();
                else return result;
            }
        }

        private long parseIntegralUnary() {
            if (consume('+')) return parseIntegralUnary();
            if (consume('-')) return -parseIntegralUnary();
            if (consume('(')) {
                long result = parseIntegralSum();
                expect(')');
                return result;
            }
            return integralLiteral(numberToken());
        }

        private double parseFloatingSum() {
            double result = parseFloatingProduct();
            while (true) {
                if (consume('+')) result += parseFloatingProduct();
                else if (consume('-')) result -= parseFloatingProduct();
                else return result;
            }
        }

        private double parseFloatingProduct() {
            double result = parseFloatingUnary();
            while (true) {
                if (consume('*')) result *= parseFloatingUnary();
                else if (consume('/')) result /= parseFloatingUnary();
                else if (consume('%')) result %= parseFloatingUnary();
                else return result;
            }
        }

        private double parseFloatingUnary() {
            if (consume('+')) return parseFloatingUnary();
            if (consume('-')) return -parseFloatingUnary();
            if (consume('(')) {
                double result = parseFloatingSum();
                expect(')');
                return result;
            }
            return Double.parseDouble(numberToken().replaceAll("[fFdD]$", ""));
        }

        private String numberToken() {
            skipWhitespace();
            int start = position;
            while (position < source.length() && "0123456789abcdefABCDEFxXbBeE.+-lLfFdD".indexOf(source.charAt(position)) >= 0) {
                char current = source.charAt(position);
                if ((current == '+' || current == '-') && position > start && source.charAt(position - 1) != 'e' && source.charAt(position - 1) != 'E')
                    break;
                position++;
            }
            if (start == position) throw new NumberFormatException("Missing number at position " + position);
            return source.substring(start, position);
        }

        private static long integralLiteral(String token) {
            String value = token.replaceAll("[lL]$", "");
            if (value.startsWith("0b") || value.startsWith("0B")) return Long.parseLong(value.substring(2), 2);
            return Long.decode(value);
        }

        private boolean consume(char expected) {
            skipWhitespace();
            if (position < source.length() && source.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) throw new NumberFormatException("Expected '" + expected + "' at position " + position);
        }

        private void finish() {
            skipWhitespace();
            if (position != source.length()) throw new NumberFormatException("Invalid expression at position " + position);
        }

        private void skipWhitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
        }
    }
}
