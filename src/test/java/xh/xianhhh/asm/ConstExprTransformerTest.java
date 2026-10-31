package xh.xianhhh.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public final class ConstExprTransformerTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("constexpr-local-test");
        Path source = directory.resolve("LocalArguments.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class LocalArguments {\n"
                + "  @ConstExpr static int constant() { return 41; }\n"
                + "  @ConstExpr static int twice(int value) { return value * 2; }\n"
                + "  @ConstExpr static int combine(int first, long second) { return first + (int) second; }\n"
                + "  static int plusOne(int value) { return value + 1; }\n"
                + "  public static int immutable() { final int value = constant(); return plusOne(value); }\n"
                + "  public int instance() { final int value = constant(); return twice(value); }\n"
                + "  public static int changed() { int value = 41; value++; return plusOne(value); }\n"
                + "  public static int parameter(int value) { return plusOne(value); }\n"
                + "  public static int bitwise() { return twice(((constant() << 2) ^ 7) | 1); }\n"
                + "  public static int localBits() { final int value = constant(); return twice(value << 1); }\n"
                + "  public static int negative() { return twice(-constant()); }\n"
                + "  public static int overflow() { return twice(Integer.MAX_VALUE + 1); }\n"
                + "  public static long longBits() { return 0x12345678L << 37 | 3L; }\n"
                + "  public static int dynamicBits(int value) { return plusOne(value | 1); }\n"
                + "  public static int nested() { return combine((constant() << 1) | 3, (long) (constant() + 5)); }\n"
                + "  public static int sameBranches(boolean choose) { int value; if (choose) value = constant() + 1; else value = 42; return twice(value); }\n"
                + "  public static int differentBranches(boolean choose) { int value; if (choose) value = 40; else value = 42; return plusOne(value); }\n"
                + "  public static int divideAtRuntime() { return 1 / 0; }\n"
                + "  static final class Scale { final int factor; Scale(int factor) { this.factor = factor; } int apply(int n) { return factor * n; } int keyed(Scale key) { return factor ^ key.hashCode(); } }\n"
                + "  public static int effects;\n"
                + "  static final class Impure { Impure() { effects++; } int value() { return 3; } }\n"
                + "  public static int freshObject() { return new Scale(7).apply(3); }\n"
                + "  public static int localObject() { Scale scale = new Scale(6); return scale.apply(4); }\n"
                + "  public static int reusedObject() { Scale scale = new Scale(6); int result = scale.apply(4); return result + scale.factor; }\n"
                + "  public static int selfKey() { Scale scale = new Scale(7); return scale.keyed(scale); }\n"
                + "  public static int impureObject() { return new Impure().value(); }\n"
                + "  public static int identityHash() { return new Scale(7).hashCode(); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new AssertionError("JDK compiler unavailable");
        int status = compiler.run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("fixture compilation failed: " + status);

        byte[] input = Files.readAllBytes(directory.resolve("LocalArguments.class"));
        byte[] scale = Files.readAllBytes(directory.resolve("LocalArguments$Scale.class"));
        byte[] impure = Files.readAllBytes(directory.resolve("LocalArguments$Impure.class"));
        java.util.List<byte[]> projectClasses = java.util.Arrays.asList(input, scale, impure);
        ConstExprTransformer.preScan(projectClasses, Collections.<Path>emptyList(), true);
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertCalls(node, "immutable", 0);
        assertCalls(node, "instance", 0);
        assertCalls(node, "changed", 1);
        assertCalls(node, "parameter", 1);
        assertCalls(node, "bitwise", 0);
        assertCalls(node, "localBits", 0);
        assertCalls(node, "negative", 0);
        assertCalls(node, "overflow", 0);
        assertCalls(node, "dynamicBits", 1);
        assertCalls(node, "nested", 0);
        assertCalls(node, "sameBranches", 0);
        assertCalls(node, "differentBranches", 1);
        assertNamedCalls(node, "freshObject", "apply", 0);
        assertNamedCalls(node, "freshObject", "<init>", 0);
        assertNamedCalls(node, "localObject", "apply", 0);
        assertNamedCalls(node, "reusedObject", "apply", 1);
        assertNamedCalls(node, "selfKey", "keyed", 1);
        assertNamedCalls(node, "impureObject", "value", 1);
        assertNamedCalls(node, "identityHash", "hashCode", 1);
        assertOpcode(node, "divideAtRuntime", Opcodes.IDIV);
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("LocalArguments", output, 0, output.length); }
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.startsWith("LocalArguments$")) throw new ClassNotFoundException(name);
                try {
                    byte[] nested = Files.readAllBytes(directory.resolve(name + ".class"));
                    return defineClass(name, nested, 0, nested.length);
                } catch (java.io.IOException error) {
                    throw new ClassNotFoundException(name, error);
                }
            }
        }.define();
        if ((Integer) fixture.getMethod("immutable").invoke(null) != 42
                || (Integer) fixture.getMethod("instance").invoke(fixture.newInstance()) != 82
                || (Integer) fixture.getMethod("changed").invoke(null) != 43
                || (Integer) fixture.getMethod("parameter", int.class).invoke(null, 9) != 10
                || (Integer) fixture.getMethod("bitwise").invoke(null) != ((((41 << 2) ^ 7) | 1) * 2)
                || (Integer) fixture.getMethod("localBits").invoke(null) != 164
                || (Integer) fixture.getMethod("negative").invoke(null) != -82
                || (Integer) fixture.getMethod("overflow").invoke(null) != 0
                || (Long) fixture.getMethod("longBits").invoke(null) != (0x12345678L << 37 | 3L)
                || (Integer) fixture.getMethod("dynamicBits", int.class).invoke(null, 6) != 8) {
            throw new AssertionError("transformed behavior changed");
        }
        assertResult("nested", 129, fixture.getMethod("nested").invoke(null));
        assertResult("sameBranches(true)", 84, fixture.getMethod("sameBranches", boolean.class).invoke(null, true));
        assertResult("sameBranches(false)", 84, fixture.getMethod("sameBranches", boolean.class).invoke(null, false));
        assertResult("differentBranches(true)", 41, fixture.getMethod("differentBranches", boolean.class).invoke(null, true));
        assertResult("differentBranches(false)", 43, fixture.getMethod("differentBranches", boolean.class).invoke(null, false));
        assertResult("fresh instance", 21, fixture.getMethod("freshObject").invoke(null));
        assertResult("local instance", 24, fixture.getMethod("localObject").invoke(null));
        assertResult("reused instance", 30, fixture.getMethod("reusedObject").invoke(null));
        assertResult("impure instance", 3, fixture.getMethod("impureObject").invoke(null));
        if (fixture.getDeclaredField("effects").getInt(null) != 1)
            throw new AssertionError("impure constructor side effect was removed");
        String previousMode = System.getProperty("xh.xianhhh.constexpr.mode");
        System.setProperty("xh.xianhhh.constexpr.mode", "TAG");
        try {
            ConstExprTransformer.preScan(projectClasses, Collections.<Path>emptyList(), true);
            byte[] tagged;
            try {
                tagged = new ConstExprTransformer().transform(input);
            } finally {
                ConstExprTransformer.clear();
            }
            ClassNode taggedNode = new ClassNode();
            new ClassReader(tagged).accept(taggedNode, 0);
            assertCalls(taggedNode, "instance", 0);
            assertCalls(taggedNode, "bitwise", 0);
            assertCalls(taggedNode, "localBits", 0);
            assertCalls(taggedNode, "dynamicBits", 1);
            assertCalls(taggedNode, "nested", 0);
            assertCalls(taggedNode, "sameBranches", 0);
            assertCalls(taggedNode, "differentBranches", 1);
            assertNamedCalls(taggedNode, "freshObject", "apply", 1);
            assertNamedCalls(taggedNode, "localObject", "apply", 1);
            assertNamedCalls(taggedNode, "impureObject", "value", 1);
            assertOpcode(taggedNode, "divideAtRuntime", Opcodes.IDIV);
        } finally {
            if (previousMode == null) System.clearProperty("xh.xianhhh.constexpr.mode");
            else System.setProperty("xh.xianhhh.constexpr.mode", previousMode);
        }
        verifyMethodPruning();
        verifyInstancePruning();
        verifyPlacementMarkers();
        verifyAnalyzedBranches();
        verifyDependencyJarMethods();
        verifyInferredArrayShape();
        verifyStringEncodingFold();
        verifyDefaultArrayMaterialization();
        verifyDifferentialArraysAndDryRun();
        verifyParallelProcessor();
        verifyPureJdkCalls();
        verifyPureDependencyCalls();
        verifyJomlMath();
        verifyReferenceParameters();
        verifyJoinedOperands();
        verifyArrayComparisons();
        verifyLocalStackCompaction();
        verifyReflectiveMembers();
        verifyUnusedPrivateFields();
        verifyObjectResults();
        verifyProjectHierarchyFrames();
        verifyRedundantControlFlow();
        verifyLoopNormalization();
        verifyUnconditionalJump();
        verifyConstantPoolShrink();
        verifyCompilerPasses();
        System.out.println("ConstExpr transformer regression: OK");
    }

    private static void verifyCompilerPasses() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-passes-test");
        Path source = directory.resolve("CompilerPasses.java");
        Files.write(source, ("public class CompilerPasses {\n"
                + "  public static int calls;\n"
                + "  static int record(int value) { calls++; return value; }\n"
                + "  public static int reassoc(int value) { return (value + 4) + 5; }\n"
                + "  public static long reassocLong(long value) { return (value + 4L) + 5L; }\n"
                + "  public static int overflow(int value) { return (value + Integer.MAX_VALUE) + 1; }\n"
                + "  public static int dead(int value) { int unused = value * 7 + 3; return value; }\n"
                + "  public static int copy(int value) { int alias = value; return alias + 1; }\n"
                + "  public static int effect(int value) { int unused = record(value) + 3; return value; }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
                directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("compiler pass fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("CompilerPasses.class"));
        ConstExprTransformer.preScan(Collections.singletonList(input));
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
            output = ConstExprTransformer.verifyAndRepair(Collections.singletonList(output)).get(0);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertOpcodeCount(node, "reassoc", Opcodes.IADD, 1);
        assertOpcodeCount(node, "reassocLong", Opcodes.LADD, 1);
        assertOpcodeAbsent(node, "dead", Opcodes.IMUL);
        assertOpcodeAbsent(node, "dead", Opcodes.IADD);
        assertOpcodeAbsent(node, "copy", Opcodes.ISTORE);
        assertNamedCalls(node, "effect", "record", 1);
        final byte[] verified = output;
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("CompilerPasses", verified, 0, verified.length); }
        }.define();
        assertResult("integer reassociation", 16, fixture.getMethod("reassoc", int.class).invoke(null, 7));
        if ((Long) fixture.getMethod("reassocLong", long.class).invoke(null, Long.MAX_VALUE) != Long.MIN_VALUE + 8)
            throw new AssertionError("long reassociation changed overflow behavior");
        assertResult("integer overflow", Integer.MIN_VALUE,
                fixture.getMethod("overflow", int.class).invoke(null, 0));
        assertResult("dead pure expression", 11, fixture.getMethod("dead", int.class).invoke(null, 11));
        assertResult("local copy", 12, fixture.getMethod("copy", int.class).invoke(null, 11));
        assertResult("side effect result", 13, fixture.getMethod("effect", int.class).invoke(null, 13));
        assertResult("side effect count", 1, fixture.getField("calls").get(null));
    }

    private static void verifyConstantPoolShrink() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "PoolShrink", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor live = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()I", null, null);
        live.visitCode();
        live.visitIntInsn(Opcodes.BIPUSH, 17);
        live.visitInsn(Opcodes.IRETURN);
        live.visitMaxs(0, 0);
        live.visitEnd();
        org.objectweb.asm.MethodVisitor dead = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "unused", "()Ljava/lang/String;", null, null);
        dead.visitCode();
        dead.visitLdcInsn("discarded-constant-pool-entry");
        dead.visitInsn(Opcodes.ARETURN);
        dead.visitMaxs(0, 0);
        dead.visitEnd();
        writer.visitEnd();
        byte[] original = writer.toByteArray();
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        node.methods.removeIf(method -> "unused".equals(method.name));
        ClassWriter retaining = new ClassWriter(reader, 0);
        node.accept(retaining);
        byte[] withDeadPoolEntries = retaining.toByteArray();
        byte[] compacted = ConstExprConstantPool.shrink(withDeadPoolEntries);
        if (ConstExprConstantPool.count(compacted) >= ConstExprConstantPool.count(withDeadPoolEntries)
                || compacted.length >= withDeadPoolEntries.length)
            throw new AssertionError("unused constant pool entries were retained");
        if (!java.util.Arrays.equals(compacted, ConstExprConstantPool.shrink(compacted)))
            throw new AssertionError("constant pool shrink is not stable");
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("PoolShrink", compacted, 0, compacted.length); }
        }.define();
        assertResult("compacted class", 17, fixture.getMethod("value").invoke(null));
    }

    private static void verifyUnconditionalJump() throws Exception {
        Path directory = Files.createTempDirectory("jump-test");
        Path source = directory.resolve("JumpFixture.java");
        Files.write(source, ("import xh.xianhhh.macro.GOTO;\n"
                + "import xh.xianhhh.macro.JUMP;\n"
                + "public class JumpFixture {\n"
                + "  public static int forward() { int n=2; GOTO.goTo(\"end\"); n=99; GOTO.label(\"end\"); return n; }\n"
                + "  public static int backward() { int n=0; GOTO.label(\"again\"); n++; if (n<3) GOTO.goTo(\"again\"); return n; }\n"
                + "  public static int direct() { int n=0; try { JUMP.goTo(\"end\"); } finally { n++; } JUMP.label(\"end\"); return n; }\n"
                + "  public static int missing() { GOTO.goTo(\"absent\"); return 0; }\n"
                + "  public static int invalidLocal() { int n; GOTO.goTo(\"after\"); n=3; GOTO.label(\"after\"); return n; }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("jump fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("JumpFixture.class"));
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        node.methods.removeIf(method -> "missing".equals(method.name));
        node.methods.removeIf(method -> "invalidLocal".equals(method.name));
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        ConstExprTransformer.preScan(Collections.singletonList(writer.toByteArray()));
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(writer.toByteArray());
            output = ConstExprTransformer.pruneUnusedMethods(Collections.singletonList(output)).get(0);
            output = ConstExprTransformer.verifyAndRepair(Collections.singletonList(output)).get(0);
        } finally {
            ConstExprTransformer.clear();
        }
        final byte[] loadedOutput = output;
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("JumpFixture", loadedOutput, 0, loadedOutput.length); }
        }.define();
        assertResult("jump forward", 2, fixture.getMethod("forward").invoke(null));
        assertResult("jump backward", 3, fixture.getMethod("backward").invoke(null));
        assertResult("jump direct across finally", 0, fixture.getMethod("direct").invoke(null));
        ClassNode transformed = new ClassNode();
        new ClassReader(output).accept(transformed, 0);
        for (MethodNode method : transformed.methods)
            for (AbstractInsnNode insn : method.instructions)
                if (insn instanceof MethodInsnNode
                        && ((MethodInsnNode) insn).owner.startsWith("xh/xianhhh/macro/")
                        && (((MethodInsnNode) insn).owner.endsWith("/GOTO")
                        || ((MethodInsnNode) insn).owner.endsWith("/JUMP")))
                    throw new AssertionError("Jump marker remained in " + method.name);
        ConstExprTransformer.preScan(Collections.singletonList(input));
        try {
            new ConstExprTransformer().transform(input);
            throw new AssertionError("missing jump label accepted");
        } catch (ConstExprException expected) {
            if (!expected.getMessage().contains("Label not found")) throw expected;
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode invalidNode = new ClassNode();
        new ClassReader(input).accept(invalidNode, 0);
        invalidNode.methods.removeIf(method -> "missing".equals(method.name));
        ClassWriter invalidWriter = new ClassWriter(0);
        invalidNode.accept(invalidWriter);
        byte[] invalidInput = invalidWriter.toByteArray();
        ConstExprTransformer.preScan(Collections.singletonList(invalidInput));
        try {
            new ConstExprTransformer().transform(invalidInput);
            throw new AssertionError("jump over local initialization accepted");
        } catch (ConstExprException expected) {
            if (!expected.getMessage().contains("Invalid control flow after jump")) throw expected;
        } finally {
            ConstExprTransformer.clear();
        }
    }

    private static void verifyLoopNormalization() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-loops-test");
        Path source = directory.resolve("LoopNormalization.java");
        Files.write(source, ("public class LoopNormalization {\n"
                + "  public static int calls;\n"
                + "  static int start() { calls++; return 5; }\n"
                + "  public static int zero(int[] values) { int sum=11; for (int i=5; i<3; i++) sum+=values[i]; return sum; }\n"
                + "  public static int zeroArray(int[] values) { int[] table=new int[2]; int sum=4; for (int i=3; i<table.length; i++) sum+=values[i]; return sum; }\n"
                + "  public static int whileZero(int[] values) { int sum=6; int i=5; while (i<3) sum+=values[i++]; return sum; }\n"
                + "  public static int doOnce(int[] values) { int sum=0; int i=5; do { sum+=values[0]; } while (i++<3); return sum; }\n"
                + "  public static int reversed(int[] values) { int sum=0; for (int i=0; 3>i; i++) sum+=values[i]; return sum; }\n"
                + "  public static int dynamic(int[] values, int limit) { int sum=0; for (int i=0; i<limit; i++) sum+=values[i]; return sum; }\n"
                + "  public static int effect(int[] values) { int sum=7; for (int i=start(); i<3; i++) sum+=values[i]; return sum; }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
                directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("loop fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("LoopNormalization.class"));
        ConstExprTransformer.preScan(Collections.singletonList(input));
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
            output = ConstExprTransformer.verifyAndRepair(Collections.singletonList(output)).get(0);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertOpcodeAbsent(node, "zero", Opcodes.IALOAD);
        assertOpcodeAbsent(node, "zeroArray", Opcodes.IALOAD);
        assertOpcodeAbsent(node, "whileZero", Opcodes.IALOAD);
        assertOpcode(node, "doOnce", Opcodes.IALOAD);
        assertOpcode(node, "reversed", Opcodes.IF_ICMPGE);
        assertOpcode(node, "dynamic", Opcodes.IALOAD);
        final byte[] verified = output;
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("LoopNormalization", verified, 0, verified.length); }
        }.define();
        assertResult("zero iteration", 11, fixture.getMethod("zero", int[].class).invoke(null, (Object) null));
        assertResult("zero array bound", 4, fixture.getMethod("zeroArray", int[].class).invoke(null, (Object) null));
        assertResult("zero while", 6, fixture.getMethod("whileZero", int[].class).invoke(null, (Object) null));
        int[] values = {2, 3, 5, 7};
        assertResult("do-while first iteration", 2, fixture.getMethod("doOnce", int[].class).invoke(null, (Object) values));
        assertResult("reversed bound", 10, fixture.getMethod("reversed", int[].class).invoke(null, (Object) values));
        assertResult("dynamic bound", 5, fixture.getMethod("dynamic", int[].class, int.class).invoke(null, values, 2));
        assertResult("side effect", 7, fixture.getMethod("effect", int[].class).invoke(null, (Object) null));
        assertResult("initialization call", 1, fixture.getField("calls").get(null));
        try {
            fixture.getMethod("reversed", int[].class).invoke(null, (Object) new int[] {2});
            throw new AssertionError("nonzero loop lost its bounds exception");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof ArrayIndexOutOfBoundsException)) throw expected;
        }
    }

    private static void verifyProjectHierarchyFrames() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-frames-test");
        String[] names = {"HierarchyBase", "HierarchyWindow", "HierarchyLeft", "HierarchyRight", "HierarchyCall"};
        String[] sources = {
                "public class HierarchyBase { public int value() { return 0; } }",
                "public class HierarchyWindow extends HierarchyBase { }",
                "public class HierarchyLeft extends HierarchyWindow { public int value() { return 1; } }",
                "public class HierarchyRight extends HierarchyWindow { public int value() { return 2; } }",
                "public class HierarchyCall {"
                        + " static int consume(HierarchyBase value) { return value.value(); }"
                        + " public static int run(boolean left) { return consume(left ? new HierarchyLeft() : new HierarchyRight()); }"
                        + " }"
        };
        String[] arguments = new String[names.length + 3];
        arguments[0] = "-proc:none";
        arguments[1] = "-d";
        arguments[2] = directory.toString();
        for (int index = 0; index < names.length; index++) {
            Path source = directory.resolve(names[index] + ".java");
            Files.write(source, sources[index].getBytes(StandardCharsets.UTF_8));
            arguments[index + 3] = source.toString();
        }
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments);
        if (status != 0) throw new AssertionError("frame fixture compilation failed: " + status);
        java.util.List<byte[]> project = new java.util.ArrayList<byte[]>();
        for (int index = 0; index < names.length; index++)
            project.add(Files.readAllBytes(directory.resolve(names[index] + ".class")));
        ConstExprTransformer.preScan(project, Collections.<Path>emptyList(), true);
        java.util.List<byte[]> transformed = new java.util.ArrayList<byte[]>();
        try {
            ConstExprTransformer transformer = new ConstExprTransformer();
            for (byte[] bytes : project) transformed.add(transformer.transform(bytes));
            transformed = ConstExprTransformer.pruneUnusedMethods(transformed);
            ClassNode damaged = new ClassNode();
            new ClassReader(transformed.get(transformed.size() - 1)).accept(damaged, ClassReader.EXPAND_FRAMES);
            for (MethodNode method : damaged.methods) {
                if (!"run".equals(method.name)) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof org.objectweb.asm.tree.FrameNode)) continue;
                    org.objectweb.asm.tree.FrameNode frame = (org.objectweb.asm.tree.FrameNode) instruction;
                    if (frame.stack != null) java.util.Collections.replaceAll(frame.stack,
                            "HierarchyWindow", "java/lang/Object");
                }
            }
            ClassWriter damagedWriter = new ClassWriter(0);
            damaged.accept(damagedWriter);
            transformed.set(transformed.size() - 1, damagedWriter.toByteArray());
            transformed = ConstExprTransformer.verifyAndRepair(transformed);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode call = new ClassNode();
        new ClassReader(transformed.get(transformed.size() - 1)).accept(call, 0);
        boolean mergedAsWindow = false;
        for (MethodNode method : call.methods) {
            if (!"run".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof org.objectweb.asm.tree.FrameNode)) continue;
                org.objectweb.asm.tree.FrameNode frame = (org.objectweb.asm.tree.FrameNode) instruction;
                if (frame.stack != null && frame.stack.contains("HierarchyWindow")) mergedAsWindow = true;
            }
        }
        if (!mergedAsWindow) throw new AssertionError("project hierarchy was lost from the merged stack frame");
        java.util.Map<String, byte[]> output = new java.util.HashMap<String, byte[]>();
        for (int index = 0; index < names.length; index++) output.put(names[index], transformed.get(index));
        ClassLoader loader = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = output.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> fixture = loader.loadClass("HierarchyCall");
        assertResult("project hierarchy left", 1, fixture.getMethod("run", boolean.class).invoke(null, true));
        assertResult("project hierarchy right", 2, fixture.getMethod("run", boolean.class).invoke(null, false));
    }

    private static void verifyRedundantControlFlow() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "RedundantControlFlow", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC,
                "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        org.objectweb.asm.MethodVisitor conditional = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "conditional", "(I)I", null, null);
        org.objectweb.asm.Label join = new org.objectweb.asm.Label();
        conditional.visitCode();
        conditional.visitVarInsn(Opcodes.ILOAD, 0);
        conditional.visitJumpInsn(Opcodes.IFEQ, join);
        conditional.visitLabel(join);
        conditional.visitIntInsn(Opcodes.BIPUSH, 7);
        conditional.visitInsn(Opcodes.IRETURN);
        conditional.visitMaxs(0, 0);
        conditional.visitEnd();
        org.objectweb.asm.MethodVisitor selected = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "selected", "(I)I", null, null);
        org.objectweb.asm.Label common = new org.objectweb.asm.Label();
        selected.visitCode();
        selected.visitVarInsn(Opcodes.ILOAD, 0);
        selected.visitTableSwitchInsn(0, 2, common, common, common, common);
        selected.visitLabel(common);
        selected.visitIntInsn(Opcodes.BIPUSH, 9);
        selected.visitInsn(Opcodes.IRETURN);
        selected.visitMaxs(0, 0);
        selected.visitEnd();
        org.objectweb.asm.MethodVisitor threaded = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "threaded", "(Z)I", null, null);
        org.objectweb.asm.Label bridge = new org.objectweb.asm.Label();
        org.objectweb.asm.Label destination = new org.objectweb.asm.Label();
        threaded.visitCode();
        threaded.visitVarInsn(Opcodes.ILOAD, 0);
        threaded.visitJumpInsn(Opcodes.IFEQ, bridge);
        threaded.visitInsn(Opcodes.ICONST_1);
        threaded.visitInsn(Opcodes.IRETURN);
        threaded.visitLabel(bridge);
        threaded.visitJumpInsn(Opcodes.GOTO, destination);
        threaded.visitLabel(destination);
        threaded.visitInsn(Opcodes.ICONST_2);
        threaded.visitInsn(Opcodes.IRETURN);
        threaded.visitMaxs(0, 0);
        threaded.visitEnd();
        writer.visitEnd();
        byte[] input = writer.toByteArray();
        ConstExprTransformer.preScan(java.util.Collections.singletonList(input));
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
            output = ConstExprTransformer.verifyAndRepair(java.util.Collections.singletonList(output)).get(0);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertOpcodeAbsent(node, "conditional", Opcodes.IFEQ);
        assertOpcodeAbsent(node, "selected", Opcodes.TABLESWITCH);
        assertOpcodeAbsent(node, "threaded", Opcodes.GOTO);
        final byte[] verified = output;
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("RedundantControlFlow", verified, 0, verified.length); }
        }.define();
        assertResult("redundant conditional true", 7, fixture.getMethod("conditional", int.class).invoke(null, 0));
        assertResult("redundant conditional false", 7, fixture.getMethod("conditional", int.class).invoke(null, 1));
        assertResult("uniform switch case", 9, fixture.getMethod("selected", int.class).invoke(null, 1));
        assertResult("uniform switch default", 9, fixture.getMethod("selected", int.class).invoke(null, 5));
        assertResult("threaded branch true", 1, fixture.getMethod("threaded", boolean.class).invoke(null, true));
        assertResult("threaded branch false", 2, fixture.getMethod("threaded", boolean.class).invoke(null, false));
        ClassWriter invalidWriter = new ClassWriter(0);
        invalidWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "InvalidStack", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor invalid = invalidWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()I", null, null);
        invalid.visitCode();
        invalid.visitInsn(Opcodes.IRETURN);
        invalid.visitMaxs(1, 0);
        invalid.visitEnd();
        invalidWriter.visitEnd();
        boolean rejected = false;
        try {
            ConstExprTransformer.verifyAndRepair(java.util.Collections.singletonList(invalidWriter.toByteArray()));
        } catch (ConstExprException expected) {
            rejected = expected.getMessage().contains("InvalidStack");
        }
        if (!rejected) throw new AssertionError("unrepairable bytecode was accepted");
    }

    private static void verifyJoinedOperands() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "JoinedOperands", null, "java/lang/Object", null);
        for (String name : new String[] {"arithmetic", "branch"}) {
            org.objectweb.asm.MethodVisitor code = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    name, "(Z)I", null, null);
            org.objectweb.asm.Label alternate = new org.objectweb.asm.Label();
            org.objectweb.asm.Label join = new org.objectweb.asm.Label();
            code.visitCode();
            code.visitVarInsn(Opcodes.ILOAD, 0);
            code.visitJumpInsn(Opcodes.IFEQ, alternate);
            code.visitInsn(Opcodes.ICONST_2);
            code.visitJumpInsn(Opcodes.GOTO, join);
            code.visitLabel(alternate);
            code.visitInsn(Opcodes.ICONST_3);
            code.visitLabel(join);
            if ("arithmetic".equals(name)) {
                code.visitInsn(Opcodes.ICONST_4);
                code.visitInsn(Opcodes.IADD);
                code.visitInsn(Opcodes.IRETURN);
            } else {
                org.objectweb.asm.Label equal = new org.objectweb.asm.Label();
                code.visitInsn(Opcodes.ICONST_2);
                code.visitJumpInsn(Opcodes.IF_ICMPEQ, equal);
                code.visitInsn(Opcodes.ICONST_0);
                code.visitInsn(Opcodes.IRETURN);
                code.visitLabel(equal);
                code.visitInsn(Opcodes.ICONST_1);
                code.visitInsn(Opcodes.IRETURN);
            }
            code.visitMaxs(0, 0);
            code.visitEnd();
        }
        org.objectweb.asm.MethodVisitor local = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "localJoin", "(Z)I", null, null);
        org.objectweb.asm.Label alternate = new org.objectweb.asm.Label();
        org.objectweb.asm.Label join = new org.objectweb.asm.Label();
        local.visitCode();
        local.visitVarInsn(Opcodes.ILOAD, 0);
        local.visitJumpInsn(Opcodes.IFEQ, alternate);
        local.visitInsn(Opcodes.ICONST_1);
        local.visitJumpInsn(Opcodes.GOTO, join);
        local.visitLabel(alternate);
        local.visitInsn(Opcodes.ICONST_0);
        local.visitLabel(join);
        local.visitVarInsn(Opcodes.ISTORE, 1);
        local.visitVarInsn(Opcodes.ILOAD, 1);
        local.visitInsn(Opcodes.IRETURN);
        local.visitMaxs(0, 0);
        local.visitEnd();
        writer.visitEnd();
        byte[] input = writer.toByteArray();
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
        } finally {
            ConstExprTransformer.clear();
        }
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("JoinedOperands", output, 0, output.length); }
        }.define();
        assertResult("joined arithmetic true", 6, fixture.getMethod("arithmetic", boolean.class).invoke(null, true));
        assertResult("joined arithmetic false", 7, fixture.getMethod("arithmetic", boolean.class).invoke(null, false));
        assertResult("joined branch true", 1, fixture.getMethod("branch", boolean.class).invoke(null, true));
        assertResult("joined branch false", 0, fixture.getMethod("branch", boolean.class).invoke(null, false));
        assertResult("joined local true", 1, fixture.getMethod("localJoin", boolean.class).invoke(null, true));
        assertResult("joined local false", 0, fixture.getMethod("localJoin", boolean.class).invoke(null, false));
    }

    private static void verifyPureJdkCalls() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-jdk-calls-test");
        Path source = directory.resolve("PureJdkCalls.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class PureJdkCalls {\n"
                + "  public static int arithmetic() { int base = 21; return Math.addExact(base, 21) + Integer.rotateLeft(1, 3); }\n"
                + "  public static long bits() { return Long.rotateRight(0x123456789ABCDEFL, 8) ^ Long.reverseBytes(0x123456789ABCDEFL); }\n"
                + "  public static int unsigned() { return Integer.divideUnsigned(-1, 3); }\n"
                + "  public static int dynamic(int value) { return Math.max(value, 14); }\n"
                + "  public static int overflow() { return Math.addExact(Integer.MAX_VALUE, 1); }\n"
                + "  public static int tagged() { @ConstExpr int marker = 0; return Math.floorMod(-17, 5); }\n"
                + "  public static double precise() { return Math.fma(1.0e16, 1.0000000000000002, -1.0e16); }\n"
                + "  public static double negativeZero() { return Math.copySign(0.0, -1.0); }\n"
                + "  public static double sine() { return StrictMath.sin(Math.PI / 3.0); }\n"
                + "  public static double random() { return Math.random(); }\n"
                + "  @ConstExpr private static int arrayElement() { return 17; }\n"
                + "  public static String arrayText() { return java.util.Arrays.toString(new int[]{5, 11, 17, 23}); }\n"
                + "  public static String nestedArrayText() { return java.util.Arrays.toString(new int[]{5, 11, arrayElement(), 23}); }\n"
                + "  public static String dynamicArrayText(int value) { return java.util.Arrays.toString(new int[]{5, value}); }\n"
                + "  @ConstExpr public static String taggedArrayText() { return java.util.Arrays.toString(new long[]{5L, 11L}); }\n"
                + "  public static String byteArrayText() { return java.util.Arrays.toString(new byte[]{-1, 2}); }\n"
                + "  public static String charArrayText() { return java.util.Arrays.toString(new char[]{'A', 'Z'}); }\n"
                + "  public static String booleanArrayText() { return java.util.Arrays.toString(new boolean[]{true, false}); }\n"
                + "  public static String zeroArrayText() { return java.util.Arrays.toString(new int[3]); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("JDK call fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("PureJdkCalls.class"));
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertNamedCalls(node, "arithmetic", "addExact", 0);
        assertNamedCalls(node, "arithmetic", "rotateLeft", 0);
        assertNamedCalls(node, "bits", "rotateRight", 0);
        assertNamedCalls(node, "bits", "reverseBytes", 0);
        assertNamedCalls(node, "unsigned", "divideUnsigned", 0);
        assertNamedCalls(node, "dynamic", "max", 1);
        assertNamedCalls(node, "overflow", "addExact", 1);
        assertNamedCalls(node, "tagged", "floorMod", 0);
        assertNamedCalls(node, "precise", "fma", 0);
        assertNamedCalls(node, "negativeZero", "copySign", 0);
        assertNamedCalls(node, "sine", "sin", 0);
        assertNamedCalls(node, "random", "random", 1);
        assertNamedCalls(node, "arrayText", "toString", 0);
        assertNamedCalls(node, "nestedArrayText", "toString", 0);
        assertNamedCalls(node, "nestedArrayText", "arrayElement", 0);
        assertNamedCalls(node, "dynamicArrayText", "toString", 1);
        assertNamedCalls(node, "taggedArrayText", "toString", 0);
        assertNamedCalls(node, "byteArrayText", "toString", 0);
        assertNamedCalls(node, "charArrayText", "toString", 0);
        assertNamedCalls(node, "booleanArrayText", "toString", 0);
        assertNamedCalls(node, "zeroArrayText", "toString", 0);
        assertOpcodeAbsent(node, "arrayText", Opcodes.NEWARRAY);
        assertOpcodeAbsent(node, "nestedArrayText", Opcodes.NEWARRAY);
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("PureJdkCalls", output, 0, output.length); }
        }.define();
        assertResult("JDK arithmetic", 50, fixture.getMethod("arithmetic").invoke(null));
        long expectedBits = Long.rotateRight(0x123456789ABCDEFL, 8)
                ^ Long.reverseBytes(0x123456789ABCDEFL);
        if (!Long.valueOf(expectedBits).equals(fixture.getMethod("bits").invoke(null)))
            throw new AssertionError("JDK bits changed");
        assertResult("JDK unsigned", Integer.divideUnsigned(-1, 3), fixture.getMethod("unsigned").invoke(null));
        assertResult("JDK dynamic", 20, fixture.getMethod("dynamic", int.class).invoke(null, 20));
        assertResult("JDK tagged", Math.floorMod(-17, 5), fixture.getMethod("tagged").invoke(null));
        assertDoubleBits("JDK fma", Math.fma(1.0e16, 1.0000000000000002, -1.0e16),
                (Double) fixture.getMethod("precise").invoke(null));
        assertDoubleBits("JDK signed zero", -0.0, (Double) fixture.getMethod("negativeZero").invoke(null));
        assertDoubleBits("JDK sine", StrictMath.sin(Math.PI / 3.0),
                (Double) fixture.getMethod("sine").invoke(null));
        assertObjectResult("literal array text", "[5, 11, 17, 23]", fixture.getMethod("arrayText").invoke(null));
        assertObjectResult("nested array text", "[5, 11, 17, 23]", fixture.getMethod("nestedArrayText").invoke(null));
        assertObjectResult("dynamic array text", "[5, 7]", fixture.getMethod("dynamicArrayText", int.class).invoke(null, 7));
        assertObjectResult("tagged array text", "[5, 11]", fixture.getMethod("taggedArrayText").invoke(null));
        assertObjectResult("byte array text", "[-1, 2]", fixture.getMethod("byteArrayText").invoke(null));
        assertObjectResult("char array text", "[A, Z]", fixture.getMethod("charArrayText").invoke(null));
        assertObjectResult("boolean array text", "[true, false]", fixture.getMethod("booleanArrayText").invoke(null));
        assertObjectResult("zero array text", "[0, 0, 0]", fixture.getMethod("zeroArrayText").invoke(null));
        try {
            fixture.getMethod("overflow").invoke(null);
            throw new AssertionError("overflow no longer throws");
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (!(error.getCause() instanceof ArithmeticException)) throw error;
        }
        String previousMode = System.getProperty("xh.xianhhh.constexpr.mode");
        System.setProperty("xh.xianhhh.constexpr.mode", "TAG");
        try {
            ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
            byte[] tagged;
            try {
                tagged = new ConstExprTransformer().transform(input);
            } finally {
                ConstExprTransformer.clear();
            }
            ClassNode taggedNode = new ClassNode();
            new ClassReader(tagged).accept(taggedNode, 0);
            assertNamedCalls(taggedNode, "arithmetic", "addExact", 1);
            assertNamedCalls(taggedNode, "tagged", "floorMod", 0);
            assertNamedCalls(taggedNode, "arrayText", "toString", 1);
            assertNamedCalls(taggedNode, "taggedArrayText", "toString", 0);
        } finally {
            if (previousMode == null) System.clearProperty("xh.xianhhh.constexpr.mode");
            else System.setProperty("xh.xianhhh.constexpr.mode", previousMode);
        }
    }

    private static void verifyPureDependencyCalls() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-pure-dependency-test");
        Path dependency = directory.resolve("thirdparty/Numeric.java");
        Files.createDirectories(dependency.getParent());
        Files.write(dependency, ("package thirdparty; public final class Numeric {\n"
                + "  public static double curve(double x) { return Math.fma(x, 0.5, -0.0); }\n"
                + "  public static int effect(int x) { System.setProperty(\"macrojava.test.effect\", \"yes\"); return x; }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        Path caller = directory.resolve("DependencyNumericCalls.java");
        Files.write(caller, ("public class DependencyNumericCalls {\n"
                + "  public static double folded() { return thirdparty.Numeric.curve(0.1); }\n"
                + "  public static int preserved() { return thirdparty.Numeric.effect(3); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
                directory.toString(), dependency.toString(), caller.toString());
        if (status != 0) throw new AssertionError("numeric dependency fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("DependencyNumericCalls.class"));
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.singletonList(directory), true);
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertNamedCalls(node, "folded", "curve", 0);
        assertNamedCalls(node, "preserved", "effect", 1);
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("DependencyNumericCalls", output, 0, output.length); }
        }.define();
        assertDoubleBits("dependency double", Math.fma(0.1, 0.5, -0.0),
                (Double) fixture.getMethod("folded").invoke(null));
    }

    private static void verifyJomlMath() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-joml-test");
        Path source = directory.resolve("JomlMathCalls.java");
        Files.write(source, ("public class JomlMathCalls {\n"
                + "  public static double squareRoot() { return org.joml.Math.sqrt(2.0); }\n"
                + "  public static double inverseRoot() { return org.joml.Math.invsqrt(4.0); }\n"
                + "  public static double configurable() { return org.joml.Math.sin(0.5); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("JOML fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("JomlMathCalls.class"));
        Path joml = java.nio.file.Paths.get(org.joml.Math.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.singletonList(joml), true);
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertNamedCalls(node, "squareRoot", "sqrt", 0);
        assertNamedCalls(node, "inverseRoot", "invsqrt", 0);
        assertNamedCalls(node, "configurable", "sin", 1);
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("JomlMathCalls", output, 0, output.length); }
        }.define();
        assertDoubleBits("JOML sqrt", org.joml.Math.sqrt(2.0),
                (Double) fixture.getMethod("squareRoot").invoke(null));
        assertDoubleBits("JOML invsqrt", org.joml.Math.invsqrt(4.0),
                (Double) fixture.getMethod("inverseRoot").invoke(null));
    }

    private static void verifyReferenceParameters() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-reference-parameters-test");
        Path source = directory.resolve("ReferenceParameters.java");
        Files.write(source, ("import xh.xianhhh.macro.*;\n"
                + "public class ReferenceParameters {\n"
                + "  static void add(@Ref int value) { value += 7; }\n"
                + "  static void assign(@Out int value) { value = 42; }\n"
                + "  static void sum(@Out int value) { value = 1; for (int i = 0; i < 4; i++) value += i; }\n"
                + "  static boolean outBoolean(@Out int value) { value = 21; return true; }\n"
                + "  static long refLong(@Ref long value) { value += 3; return value * 2; }\n"
                + "  static double outDouble(@Out double value) { value = -0.0; return Math.PI; }\n"
                + "  static String refString(@Ref int value) { value += 2; return \"done\"; }\n"
                + "  static int[] refArray(@Ref int value) { value += 5; return new int[]{value}; }\n"
                + "  static int read(@In int value) { return value + 1; }\n"
                + "  public static int ref() { int value = 5; add(value); return value; }\n"
                + "  public static int out() { int value = 0; assign(value); return value; }\n"
                + "  public static int outLoop() { int value = 99; sum(value); return value; }\n"
                + "  public static int outReturn() { int value = 99; return outBoolean(value) && value == 21 ? value : -1; }\n"
                + "  public static long refReturn() { long value = 7; long result = refLong(value); return result + value; }\n"
                + "  public static double doubleReturn() { double value = 99; double result = outDouble(value); return result + value; }\n"
                + "  public static double doubleWriteback() { double value = 99; outDouble(value); return value; }\n"
                + "  public static String objectReturn() { int value = 4; String result = refString(value); return result + value; }\n"
                + "  public static int arrayReturn() { int value = 4; int[] result = refArray(value); return result[0] + value; }\n"
                + "  public static int in() { return read(5); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-processor",
                "xh.xianhhh.processor.ConstExprAnnotationProcessor", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("reference parameter fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("ReferenceParameters.class"));
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
        } finally {
            ConstExprTransformer.clear();
        }
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("ReferenceParameters", output, 0, output.length); }
        }.define();
        assertResult("@Ref writeback", 12, fixture.getMethod("ref").invoke(null));
        assertResult("@Out writeback", 42, fixture.getMethod("out").invoke(null));
        assertResult("@Out loop writeback", 7, fixture.getMethod("outLoop").invoke(null));
        assertResult("@Out boolean return", 21, fixture.getMethod("outReturn").invoke(null));
        if (!Long.valueOf(30).equals(fixture.getMethod("refReturn").invoke(null)))
            throw new AssertionError("@Ref long return changed");
        assertDoubleBits("@Out double return", Math.PI, (Double) fixture.getMethod("doubleReturn").invoke(null));
        assertDoubleBits("@Out double writeback", -0.0,
                (Double) fixture.getMethod("doubleWriteback").invoke(null));
        assertObjectResult("@Ref object return", "done6", fixture.getMethod("objectReturn").invoke(null));
        assertResult("@Ref array return", 18, fixture.getMethod("arrayReturn").invoke(null));
        assertResult("@In read", 6, fixture.getMethod("in").invoke(null));
        ClassNode referenceNode = new ClassNode();
        new ClassReader(output).accept(referenceNode, 0);
        MethodNode outCaller = null;
        for (MethodNode candidate : referenceNode.methods)
            if ("out".equals(candidate.name)) outCaller = candidate;
        if (outCaller == null) throw new AssertionError("@Out caller missing");
        for (AbstractInsnNode instruction : outCaller.instructions) {
            if (!(instruction instanceof MethodInsnNode)
                    || !"$macrojava$byref$assign".equals(((MethodInsnNode) instruction).name)) continue;
            AbstractInsnNode argument = instruction.getPrevious();
            while (argument != null && argument.getOpcode() < 0) argument = argument.getPrevious();
            boolean defaultInput = false;
            AbstractInsnNode cursor = argument;
            for (int count = 0; count < 5 && cursor != null; ) {
                cursor = cursor.getPrevious();
                if (cursor == null || cursor.getOpcode() < 0) continue;
                count++;
                if (cursor.getOpcode() == Opcodes.ILOAD) break;
                if (cursor.getOpcode() == Opcodes.ICONST_0) defaultInput = true;
            }
            if (argument == null || argument.getOpcode() != Opcodes.ALOAD || !defaultInput)
                throw new AssertionError("@Out still passes the caller's initial value");
        }

        Path callee = directory.resolve("ReferenceCallee.java");
        Path caller = directory.resolve("ReferenceCaller.java");
        Files.write(callee, ("import xh.xianhhh.macro.Ref;\n"
                + "public class ReferenceCallee { public static void add(@Ref long value) { value += 9; } }\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(caller, ("public class ReferenceCaller {\n"
                + "  public static long run() { long value = 13; ReferenceCallee.add(value); return value; }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), callee.toString(), caller.toString());
        if (status != 0) throw new AssertionError("cross-class reference fixture compilation failed: " + status);
        byte[] calleeInput = Files.readAllBytes(directory.resolve("ReferenceCallee.class"));
        byte[] callerInput = Files.readAllBytes(directory.resolve("ReferenceCaller.class"));
        ConstExprTransformer.preScan(java.util.Arrays.asList(calleeInput, callerInput), Collections.<Path>emptyList(), true);
        byte[] calleeOutput, callerOutput;
        try {
            ConstExprTransformer transformer = new ConstExprTransformer();
            calleeOutput = transformer.transform(calleeInput);
            callerOutput = transformer.transform(callerInput);
        } finally {
            ConstExprTransformer.clear();
        }

        for (String invalidBody : new String[] {
                "int previous = value; value = previous + 1;",
                "if (System.nanoTime() > 0) value = 1;"}) {
            Path badOut = directory.resolve("InvalidOutBody.java");
            Files.write(badOut, ("import xh.xianhhh.macro.Out;\n"
                    + "public class InvalidOutBody { static void assign(@Out int value) { "
                    + invalidBody + " } }\n").getBytes(StandardCharsets.UTF_8));
            status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                    System.getProperty("java.class.path"), "-d", directory.toString(), badOut.toString());
            if (status != 0) throw new AssertionError("invalid @Out fixture compilation failed: " + status);
            byte[] badOutInput = Files.readAllBytes(directory.resolve("InvalidOutBody.class"));
            ConstExprTransformer.preScan(Collections.singletonList(badOutInput), Collections.<Path>emptyList(), true);
            try {
                new ConstExprTransformer().transform(badOutInput);
                throw new AssertionError("invalid @Out body was accepted: " + invalidBody);
            } catch (ConstExprException expected) {
                if (!expected.getMessage().startsWith("@Out ")) throw expected;
            } finally {
                ConstExprTransformer.clear();
            }
        }
        Path invalidReturn = directory.resolve("InvalidOutReturn.java");
        Files.write(invalidReturn, ("import xh.xianhhh.macro.Out;\n"
                + "public class InvalidOutReturn { static boolean assign(@Out int value) { "
                + "if (System.nanoTime() > 0) return true; value = 1; return false; } }\n")
                .getBytes(StandardCharsets.UTF_8));
        status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), invalidReturn.toString());
        if (status != 0) throw new AssertionError("invalid returning @Out fixture compilation failed: " + status);
        byte[] invalidReturnInput = Files.readAllBytes(directory.resolve("InvalidOutReturn.class"));
        ConstExprTransformer.preScan(Collections.singletonList(invalidReturnInput), Collections.<Path>emptyList(), true);
        try {
            new ConstExprTransformer().transform(invalidReturnInput);
            throw new AssertionError("returning @Out without assignment was accepted");
        } catch (ConstExprException expected) {
            if (!expected.getMessage().contains("can return without assigning")) throw expected;
        } finally {
            ConstExprTransformer.clear();
        }
        Class<?> crossClass = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("ReferenceCaller", callerOutput, 0, callerOutput.length); }
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if ("ReferenceCallee".equals(name)) return defineClass(name, calleeOutput, 0, calleeOutput.length);
                throw new ClassNotFoundException(name);
            }
        }.define();
        if (!Long.valueOf(22L).equals(crossClass.getMethod("run").invoke(null)))
            throw new AssertionError("cross-class @Ref writeback changed");

        Path invalid = directory.resolve("InvalidReferenceCall.java");
        Files.write(invalid, ("import xh.xianhhh.macro.Out;\n"
                + "public class InvalidReferenceCall {\n"
                + "  static void assign(@Out int value) { value = 1; }\n"
                + "  public static void run() { assign(0); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), invalid.toString());
        if (status != 0) throw new AssertionError("invalid reference fixture compilation failed: " + status);
        byte[] invalidInput = Files.readAllBytes(directory.resolve("InvalidReferenceCall.class"));
        ConstExprTransformer.preScan(Collections.singletonList(invalidInput), Collections.<Path>emptyList(), true);
        try {
            new ConstExprTransformer().transform(invalidInput);
            throw new AssertionError("@Out literal argument was accepted");
        } catch (ConstExprException expected) {
            if (!expected.getMessage().contains("local variable argument")) throw expected;
        } finally {
            ConstExprTransformer.clear();
        }

        Path invalidIn = directory.resolve("InvalidInParameter.java");
        Files.write(invalidIn, ("import xh.xianhhh.macro.In;\n"
                + "public class InvalidInParameter { static int run(@In int value) { value++; return value; } }\n")
                .getBytes(StandardCharsets.UTF_8));
        status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), invalidIn.toString());
        if (status != 0) throw new AssertionError("invalid @In fixture compilation failed: " + status);
        byte[] invalidInInput = Files.readAllBytes(directory.resolve("InvalidInParameter.class"));
        ConstExprTransformer.preScan(Collections.singletonList(invalidInInput), Collections.<Path>emptyList(), true);
        try {
            new ConstExprTransformer().transform(invalidInInput);
            throw new AssertionError("@In reassignment was accepted");
        } catch (ConstExprException expected) {
            if (!expected.getMessage().contains("cannot be reassigned")) throw expected;
        } finally {
            ConstExprTransformer.clear();
        }
    }

    private static void assertDoubleBits(String label, double expected, double actual) {
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual))
            throw new AssertionError(label + " changed bits: " + expected + " -> " + actual);
    }

    private static void verifyArrayComparisons() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-array-comparison-test");
        Path source = directory.resolve("ArrayComparisons.java");
        Files.write(source, ("public class ArrayComparisons {\n"
                + "  public static int distinct() { int[] left = new int[3]; int[] right = new int[3]; return left != right && left.length == right.length ? left.length + right.length : -1; }\n"
                + "  public static int alias() { int[] array = new int[2]; return array == array ? array.length : -1; }\n"
                + "  public static int nonNull() { int[] array = new int[4]; return array != null ? array.length : -1; }\n"
                + "  public static int dynamic(int[] left, int[] right) { return left == right ? 1 : 0; }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
                directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("array comparison fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("ArrayComparisons.class"));
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (!"distinct".equals(method.name) && !"alias".equals(method.name)
                    && !"nonNull".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction.getOpcode() == Opcodes.IF_ACMPEQ || instruction.getOpcode() == Opcodes.IF_ACMPNE
                        || instruction.getOpcode() == Opcodes.IFNULL || instruction.getOpcode() == Opcodes.IFNONNULL
                        || instruction.getOpcode() == Opcodes.ARRAYLENGTH)
                    throw new AssertionError(method.name + " retained a constant array comparison or length");
        }
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("ArrayComparisons", output, 0, output.length); }
        }.define();
        assertResult("distinct arrays", 6, fixture.getMethod("distinct").invoke(null));
        assertResult("alias array", 2, fixture.getMethod("alias").invoke(null));
        assertResult("non-null array", 4, fixture.getMethod("nonNull").invoke(null));
        Object[] same = {new int[1], new int[1]};
        assertResult("dynamic equal", 1, fixture.getMethod("dynamic", int[].class, int[].class)
                .invoke(null, same[0], same[0]));
        assertResult("dynamic distinct", 0, fixture.getMethod("dynamic", int[].class, int[].class)
                .invoke(null, same[0], same[1]));
    }

    private static void verifyLocalStackCompaction() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "LocalStackCompaction", null,
                "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor code = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()I", null, null);
        code.visitCode();
        code.visitInsn(Opcodes.ICONST_5);
        code.visitVarInsn(Opcodes.ISTORE, 0);
        code.visitVarInsn(Opcodes.ILOAD, 0);
        code.visitInsn(Opcodes.POP);
        code.visitVarInsn(Opcodes.ILOAD, 0);
        code.visitVarInsn(Opcodes.ISTORE, 0);
        code.visitInsn(Opcodes.ICONST_3);
        code.visitInsn(Opcodes.POP);
        code.visitLdcInsn(9L);
        code.visitInsn(Opcodes.POP2);
        code.visitVarInsn(Opcodes.ILOAD, 0);
        code.visitInsn(Opcodes.IRETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        writer.visitEnd();
        byte[] input = writer.toByteArray();
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        byte[] output;
        try {
            output = new ConstExprTransformer().transform(input);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (!"value".equals(method.name)) continue;
            int loads = 0, stores = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                int opcode = instruction.getOpcode();
                if (opcode == Opcodes.POP || opcode == Opcodes.POP2)
                    throw new AssertionError("redundant stack discard remains");
                if (opcode == Opcodes.ILOAD) loads++;
                if (opcode == Opcodes.ISTORE) stores++;
            }
            if (loads != stores || loads > 1)
                throw new AssertionError("redundant local transfers remain: " + loads + "/" + stores);
        }
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("LocalStackCompaction", output, 0, output.length); }
        }.define();
        assertResult("compacted locals", 5, fixture.getMethod("value").invoke(null));
    }

    private static void verifyReflectiveMembers() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-reflection-test");
        Path source = directory.resolve("ReflectiveMembers.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class ReflectiveMembers {\n"
                + "  @ConstExpr(\"42\") private static int token;\n"
                + "  @ConstExpr(values={\"2\",\"3\"}) private static int[] table;\n"
                + "  private static int unused = 7;\n"
                + "  public static int fast = 11;\n"
                + "  public static String text = \"ready\";\n"
                + "  @ConstExpr private static int helper() { return 9; }\n"
                + "  public static int direct() { return token + helper(); }\n"
                + "  public static int fastRead() throws Exception { return ReflectiveMembers.class.getDeclaredField(\"fast\").getInt(null); }\n"
                + "  public static String fastText() throws Exception { return (String) ReflectiveMembers.class.getDeclaredField(\"text\").get(null); }\n"
                + "  public static Object field(String name) throws Exception { java.lang.reflect.Field value = ReflectiveMembers.class.getDeclaredField(name); value.setAccessible(true); return value.get(null); }\n"
                + "  public static int method(String name) throws Exception { java.lang.reflect.Method value = ReflectiveMembers.class.getDeclaredMethod(name); value.setAccessible(true); return (Integer)value.invoke(null); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("reflection fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("ReflectiveMembers.class"));
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        byte[] output;
        try {
            output = ConstExprTransformer.pruneUnusedMethods(Collections.singletonList(
                    new ConstExprTransformer().transform(input))).get(0);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertMethod(node, "helper", true);
        assertNamedCalls(node, "fastRead", "getDeclaredField", 0);
        assertNamedCalls(node, "fastRead", "getInt", 0);
        assertNamedCalls(node, "fastText", "getDeclaredField", 0);
        assertNamedCalls(node, "fastText", "get", 0);
        assertNamedCalls(node, "field", "getDeclaredField", 1);
        boolean dispatchedToken = false;
        for (MethodNode method : node.methods) {
            if (!"field".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode) {
                    org.objectweb.asm.tree.FieldInsnNode access = (org.objectweb.asm.tree.FieldInsnNode) instruction;
                    if (access.getOpcode() == Opcodes.GETSTATIC && "token".equals(access.name))
                        dispatchedToken = true;
                }
        }
        if (!dispatchedToken) throw new AssertionError("dynamic reflection did not emit direct field access");
        for (String fieldName : new String[] {"token", "table", "unused"}) {
            boolean present = false;
            for (org.objectweb.asm.tree.FieldNode field : node.fields)
                if (fieldName.equals(field.name)) present = true;
            if (!present) throw new AssertionError("reflected field was pruned: " + fieldName);
        }
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("ReflectiveMembers", output, 0, output.length); }
        }.define();
        assertResult("direct constant", 51, fixture.getMethod("direct").invoke(null));
        assertResult("reflected token", 42, fixture.getMethod("field", String.class).invoke(null, "token"));
        assertResult("reflected unused", 7, fixture.getMethod("field", String.class).invoke(null, "unused"));
        assertResult("reflected helper", 9, fixture.getMethod("method", String.class).invoke(null, "helper"));
        assertResult("direct field opcode", 11, fixture.getMethod("fastRead").invoke(null));
        if (!"ready".equals(fixture.getMethod("fastText").invoke(null)))
            throw new AssertionError("direct reference field read changed");
        fixture.getField("fast").setInt(null, 19);
        assertResult("direct field observes runtime value", 19, fixture.getMethod("fastRead").invoke(null));
        assertResult("dynamic dispatch observes runtime value", 19,
                fixture.getMethod("field", String.class).invoke(null, "fast"));
        try {
            fixture.getMethod("field", String.class).invoke(null, "missing");
            throw new AssertionError("unknown reflective name no longer fails");
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (!(error.getCause() instanceof NoSuchFieldException)) throw error;
        }
        if (!java.util.Arrays.equals(new int[] {2, 3},
                (int[]) fixture.getMethod("field", String.class).invoke(null, "table")))
            throw new AssertionError("reflected constexpr array changed");

        String previous = System.getProperty("xh.xianhhh.constexpr.preserveReflectiveMembers");
        System.setProperty("xh.xianhhh.constexpr.preserveReflectiveMembers", "true");
        try {
            Path externalSource = directory.resolve("ExternalReflection.java");
            Files.write(externalSource, ("import xh.xianhhh.macro.ConstExpr;\n"
                    + "public class ExternalReflection {\n"
                    + "  @ConstExpr(\"27\") private static int token;\n"
                    + "  @ConstExpr private static int helper() { return 3; }\n"
                    + "  public static int direct() { return token + helper(); }\n"
                    + "}\n").getBytes(StandardCharsets.UTF_8));
            status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                    System.getProperty("java.class.path"), "-d", directory.toString(), externalSource.toString());
            if (status != 0) throw new AssertionError("external reflection fixture compilation failed: " + status);
            byte[] externalInput = Files.readAllBytes(directory.resolve("ExternalReflection.class"));
            ConstExprTransformer.preScan(Collections.singletonList(externalInput), Collections.<Path>emptyList(), true);
            byte[] externalOutput;
            try {
                externalOutput = ConstExprTransformer.pruneUnusedMethods(Collections.singletonList(
                        new ConstExprTransformer().transform(externalInput))).get(0);
            } finally {
                ConstExprTransformer.clear();
            }
            ClassNode externalNode = new ClassNode();
            new ClassReader(externalOutput).accept(externalNode, 0);
            assertMethod(externalNode, "helper", true);
            Class<?> external = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
                Class<?> define() { return defineClass("ExternalReflection", externalOutput, 0, externalOutput.length); }
            }.define();
            java.lang.reflect.Field token = external.getDeclaredField("token");
            token.setAccessible(true);
            assertResult("externally reflected token", 27, token.get(null));
            assertResult("external direct constant", 30, external.getMethod("direct").invoke(null));

            Path dummySource = directory.resolve("ExternalDummy.java");
            Files.write(dummySource, "public class ExternalDummy { public static int value() { return 1; } }\n"
                    .getBytes(StandardCharsets.UTF_8));
            status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
                    directory.toString(), dummySource.toString());
            if (status != 0) throw new AssertionError("parallel reflection fixture compilation failed: " + status);
            Path raw = directory.resolve("raw"), parallel = directory.resolve("parallel");
            Files.createDirectories(raw);
            Files.copy(directory.resolve("ExternalReflection.class"), raw.resolve("ExternalReflection.class"));
            Files.copy(directory.resolve("ExternalDummy.class"), raw.resolve("ExternalDummy.class"));
            ConstExprBuildProcessor.main(new String[] {raw.toString(), parallel.toString(), "--workers=2"});
            byte[] parallelBytes = Files.readAllBytes(parallel.resolve("ExternalReflection.class"));
            Class<?> parallelFixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
                Class<?> define() { return defineClass("ExternalReflection", parallelBytes, 0, parallelBytes.length); }
            }.define();
            java.lang.reflect.Field parallelToken = parallelFixture.getDeclaredField("token");
            parallelToken.setAccessible(true);
            assertResult("parallel reflected token", 27, parallelToken.get(null));
        } finally {
            if (previous == null) System.clearProperty("xh.xianhhh.constexpr.preserveReflectiveMembers");
            else System.setProperty("xh.xianhhh.constexpr.preserveReflectiveMembers", previous);
        }
    }

    private static void verifyParallelProcessor() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-parallel-test");
        Path raw = directory.resolve("raw");
        Files.createDirectories(raw);
        Path source = directory.resolve("ParallelBase.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class ParallelBase { @ConstExpr(\"3\") public static int offset; @ConstExpr private static int helper() { return 5; } @ConstExpr public static int value(int input) { return input * 7 + helper() - 5 + offset; } }\n")
                .getBytes(StandardCharsets.UTF_8));
        java.util.List<String> names = java.util.Arrays.asList("ParallelA", "ParallelB", "ParallelC", "ParallelD");
        java.util.List<String> arguments = new java.util.ArrayList<>();
        arguments.add("-proc:none");
        arguments.add("-classpath");
        arguments.add(System.getProperty("java.class.path"));
        arguments.add("-d");
        arguments.add(raw.toString());
        arguments.add(source.toString());
        for (int index = 0; index < names.size(); index++) {
            Path caller = directory.resolve(names.get(index) + ".java");
            Files.write(caller, ("public class " + names.get(index) + " { public static int run() { return ParallelBase.value("
                    + index + "); } }\n").getBytes(StandardCharsets.UTF_8));
            arguments.add(caller.toString());
        }
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                arguments.toArray(new String[arguments.size()]));
        if (status != 0) throw new AssertionError("parallel fixture compilation failed: " + status);
        Path serial = directory.resolve("serial"), parallel = directory.resolve("parallel");
        java.io.PrintStream originalOutput = System.out;
        java.io.ByteArrayOutputStream quietOutput = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(quietOutput, true, "UTF-8"));
            ConstExprBuildProcessor.main(new String[] {raw.toString(), serial.toString(), "--workers=1",
                    "--report=" + directory.resolve("serial-report.tsv")});
        } finally {
            System.setOut(originalOutput);
        }
        if (quietOutput.size() != 0) throw new AssertionError("progress output is enabled by default");
        String report = new String(Files.readAllBytes(directory.resolve("serial-report.tsv")), StandardCharsets.UTF_8);
        if (!report.startsWith("class\tinputBytes\toutputBytes\ttransformNanos\tbeforeMethods\tafterMethods\tbeforeCalls\tafterCalls\tchangedMethods\tpoolBefore\tpoolAfter\n"))
            throw new AssertionError("constant pool counts missing from report");
        ConstExprBuildProcessor.main(new String[] {raw.toString(), parallel.toString(), "--workers=3", "--progress"});
        java.io.ByteArrayOutputStream progressOutput = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(progressOutput, true, "UTF-8"));
            ConstExprBuildProcessor.main(new String[] {raw.toString(), directory.resolve("progress").toString(),
                    "--workers=1", "--progress"});
        } finally {
            System.setOut(originalOutput);
        }
        String progressText = new String(progressOutput.toByteArray(), StandardCharsets.UTF_8);
        if (!progressText.contains("processing 1/5 ") || !progressText.contains("pruning unused methods")
                || !progressText.contains("inline call ParallelBase.value(I)I")
                || !progressText.contains("inline field ParallelBase.offsetI into value(I)I")
                || !progressText.contains("optimize run()I:")
                || !progressText.contains("remove method ParallelBase.helper()I")
                || !progressText.contains("remove field ParallelBase.offsetI")
                || !progressText.contains("[ConstExpr] complete"))
            throw new AssertionError("progress output omitted the current class or build stage: " + progressText);
        for (String name : names) {
            Path relative = java.nio.file.Paths.get(name + ".class");
            if (!java.util.Arrays.equals(Files.readAllBytes(serial.resolve(relative)),
                    Files.readAllBytes(parallel.resolve(relative))))
                throw new AssertionError("parallel output differs for " + name);
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(parallel.resolve(relative))).accept(node, 0);
            assertNamedCalls(node, "run", "value", 0);
        }
        if (!java.util.Arrays.equals(Files.readAllBytes(serial.resolve("ParallelBase.class")),
                Files.readAllBytes(parallel.resolve("ParallelBase.class"))))
            throw new AssertionError("parallel declaration output differs");
    }

    private static void verifyObjectResults() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-object-test");
        Path source = directory.resolve("ObjectResults.java");
        Files.write(source, ("import java.util.*;\n"
                + "import java.math.*;\n"
                + "import xh.xianhhh.macro.ConstExpr;\n"
                + "public class ObjectResults {\n"
                + "  public static final class Box { private final int value; public Box(int value) { this.value = value; } public int value() { return value; } }\n"
                + "  public static final class Link { Link next; public Link() { next = this; } public boolean self() { return next == this; } }\n"
                + "  public static final class Node { public Node next; public Node() { } public boolean cycle() { return next.next == this; } }\n"
                + "  public static final class Mutable { public int value; public Mutable() { } }\n"
                + "  @ConstExpr static Box makeBox() { return new Box(17); }\n"
                + "  @ConstExpr static Link makeLink() { return new Link(); }\n"
                + "  @ConstExpr static Node makeCycle() { Node first = new Node(); Node second = new Node(); first.next = second; second.next = first; return first; }\n"
                + "  @ConstExpr static Mutable makeMutable() { Mutable value = new Mutable(); value.value = 9; return value; }\n"
                + "  @ConstExpr static Class<?> makeType() { return new Box(17).getClass(); }\n"
                + "  @ConstExpr static Box[] makeBoxes() { return new Box[] {new Box(3), new Box(5)}; }\n"
                + "  @ConstExpr static Box[] makeLargeBoxes() { Box[] boxes = new Box[2200]; boxes[10] = new Box(10); boxes[2199] = new Box(99); return boxes; }\n"
                + "  @ConstExpr static Box[] makeManyBoxes() { Box[] boxes = new Box[2200]; for (int i=0;i<600;i++) boxes[i] = new Box(i); return boxes; }\n"
                + "  @ConstExpr static String[] makeLargeStrings() { String[] names = new String[3000]; for (int i=0;i<names.length;i++) names[i] = \"item-\" + i; return names; }\n"
                + "  @ConstExpr static int[][] makeLargeRows() { int[][] rows = new int[2200][]; rows[0] = new int[] {3,5}; rows[2199] = new int[] {8}; return rows; }\n"
                + "  @ConstExpr static Box[] makeSharedBoxes() { Box item = new Box(7); return new Box[] {item, item}; }\n"
                + "  @ConstExpr static Map<String,Integer> makeMap() { HashMap<String,Integer> map = new HashMap<>(); map.put(\"a\", 7); map.put(\"b\", 9); return map; }\n"
                + "  @ConstExpr static Map<String,Integer> makeImmutable() { return Map.of(\"x\", 4, \"y\", 8); }\n"
                + "  @ConstExpr static Map<Integer,Integer> makeLargeMap() { HashMap<Integer,Integer> map = new HashMap<>(); for (int i=0;i<5000;i++) map.put(i, i*3); return map; }\n"
                + "  @ConstExpr static List<String> makeList() { return Arrays.asList(\"first\", \"second\"); }\n"
                + "  @ConstExpr static Optional<String> makeOptional() { return Optional.of(\"ready\"); }\n"
                + "  @ConstExpr static String makeText() { return new StringBuilder(\"item-\").append(42).append('!').toString(); }\n"
                + "  @ConstExpr static StringBuilder makeBuilder() { return new StringBuilder(\"item-\").append(42); }\n"
                + "  @ConstExpr static BigInteger makeBigInteger() { return new BigInteger(\"12345678901234567890\").pow(3).add(BigInteger.valueOf(7)); }\n"
                + "  @ConstExpr static BigDecimal makeBigDecimal() { return new BigDecimal(\"19.125\").multiply(new BigDecimal(\"8\")); }\n"
                + "  public static Map<String,Integer> map() { return makeMap(); }\n"
                + "  public static Map<String,Integer> immutable() { return makeImmutable(); }\n"
                + "  public static Map<Integer,Integer> largeMap() { return makeLargeMap(); }\n"
                + "  public static Box box() { return makeBox(); }\n"
                + "  public static Link link() { return makeLink(); }\n"
                + "  public static Node cycle() { return makeCycle(); }\n"
                + "  public static Mutable mutable() { return makeMutable(); }\n"
                + "  public static Class<?> type() { return makeType(); }\n"
                + "  public static Box[] boxes() { return makeBoxes(); }\n"
                + "  public static Box[] largeBoxes() { return makeLargeBoxes(); }\n"
                + "  public static Box[] manyBoxes() { return makeManyBoxes(); }\n"
                + "  public static String[] largeStrings() { return makeLargeStrings(); }\n"
                + "  public static int[][] largeRows() { return makeLargeRows(); }\n"
                + "  public static Box[] sharedBoxes() { return makeSharedBoxes(); }\n"
                + "  public static List<String> list() { return makeList(); }\n"
                + "  public static Optional<String> optional() { return makeOptional(); }\n"
                + "  public static String text() { return makeText(); }\n"
                + "  public static StringBuilder builder() { return makeBuilder(); }\n"
                + "  public static BigInteger bigInteger() { return makeBigInteger(); }\n"
                + "  public static BigDecimal bigDecimal() { return makeBigDecimal(); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("object fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("ObjectResults.class"));
        byte[] box = Files.readAllBytes(directory.resolve("ObjectResults$Box.class"));
        byte[] link = Files.readAllBytes(directory.resolve("ObjectResults$Link.class"));
        byte[] cycle = Files.readAllBytes(directory.resolve("ObjectResults$Node.class"));
        byte[] mutable = Files.readAllBytes(directory.resolve("ObjectResults$Mutable.class"));
        ConstExprTransformer.preScan(java.util.Arrays.asList(input, box, link, cycle, mutable), Collections.<Path>emptyList(), true);
        byte[] output;
        try { output = ConstExprTransformer.pruneUnusedMethods(Collections.singletonList(
                new ConstExprTransformer().transform(input))).get(0); }
        finally { ConstExprTransformer.clear(); }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertNamedCalls(node, "map", "makeMap", 0);
        assertNamedCalls(node, "immutable", "makeImmutable", 0);
        assertNamedCalls(node, "largeMap", "makeLargeMap", 0);
        assertNamedCalls(node, "box", "makeBox", 0);
        assertNamedCalls(node, "link", "makeLink", 0);
        assertNamedCalls(node, "cycle", "makeCycle", 0);
        assertNamedCalls(node, "mutable", "makeMutable", 0);
        boolean foundMutableFactory = false;
        for (MethodNode method : node.methods) {
            if (!"()LObjectResults$Mutable;".equals(method.desc)) continue;
            if (!method.name.startsWith("$constexpr$object$")) continue;
            foundMutableFactory = true;
            int duplicates = 0, localTransfers = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() == Opcodes.DUP) duplicates++;
                if (instruction.getOpcode() == Opcodes.ALOAD || instruction.getOpcode() == Opcodes.ASTORE)
                    localTransfers++;
            }
            if (duplicates != 2 || localTransfers != 0)
                throw new AssertionError("direct object factory did not retain receiver on stack");
        }
        if (!foundMutableFactory) throw new AssertionError("mutable object factory missing");
        assertNamedCalls(node, "type", "makeType", 0);
        assertNamedCalls(node, "boxes", "makeBoxes", 0);
        assertNamedCalls(node, "largeBoxes", "makeLargeBoxes", 0);
        assertNamedCalls(node, "manyBoxes", "makeManyBoxes", 1);
        assertNamedCalls(node, "largeStrings", "makeLargeStrings", 0);
        assertNamedCalls(node, "largeRows", "makeLargeRows", 0);
        assertNamedCalls(node, "sharedBoxes", "makeSharedBoxes", 1);
        assertNamedCalls(node, "list", "makeList", 0);
        assertNamedCalls(node, "optional", "makeOptional", 0);
        assertNamedCalls(node, "text", "makeText", 0);
        assertNamedCalls(node, "builder", "makeBuilder", 0);
        assertNamedCalls(node, "bigInteger", "makeBigInteger", 0);
        assertNamedCalls(node, "bigDecimal", "makeBigDecimal", 0);
        boolean serializedMap = false;
        boolean chunkedSnapshot = false;
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("$constexpr$object$")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("put".equals(call.name)) throw new AssertionError("HashMap construction was replayed");
                if ("readObject".equals(call.name)) serializedMap = true;
                if ("java/lang/StringBuilder".equals(call.owner) && "append".equals(call.name)) chunkedSnapshot = true;
            }
        }
        if (!serializedMap) throw new AssertionError("HashMap result was not snapshotted");
        if (!chunkedSnapshot) throw new AssertionError("large object snapshot was not chunked");
        Class<?> baseline = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("ObjectResults", input, 0, input.length); }
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if ("ObjectResults$Box".equals(name)) return defineClass(name, box, 0, box.length);
                if ("ObjectResults$Link".equals(name)) return defineClass(name, link, 0, link.length);
                if ("ObjectResults$Node".equals(name)) return defineClass(name, cycle, 0, cycle.length);
                if ("ObjectResults$Mutable".equals(name)) return defineClass(name, mutable, 0, mutable.length);
                throw new ClassNotFoundException(name);
            }
        }.define();
        Class<?> modified = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("ObjectResults", output, 0, output.length); }
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if ("ObjectResults$Box".equals(name)) return defineClass(name, box, 0, box.length);
                if ("ObjectResults$Link".equals(name)) return defineClass(name, link, 0, link.length);
                if ("ObjectResults$Node".equals(name)) return defineClass(name, cycle, 0, cycle.length);
                if ("ObjectResults$Mutable".equals(name)) return defineClass(name, mutable, 0, mutable.length);
                throw new ClassNotFoundException(name);
            }
        }.define();
        for (String method : new String[] {"map", "immutable", "list", "optional", "text",
                "bigInteger", "bigDecimal"}) {
            Object expected = baseline.getMethod(method).invoke(null);
            Object actual = modified.getMethod(method).invoke(null);
            if (!expected.equals(actual)) throw new AssertionError("object result changed: " + method);
        }
        Object first = modified.getMethod("map").invoke(null);
        Object second = modified.getMethod("map").invoke(null);
        if (first == second) throw new AssertionError("mutable map results unexpectedly share an instance");
        StringBuilder firstBuilder = (StringBuilder) modified.getMethod("builder").invoke(null);
        StringBuilder secondBuilder = (StringBuilder) modified.getMethod("builder").invoke(null);
        if (firstBuilder == secondBuilder || !"item-42".contentEquals(firstBuilder)
                || !"item-42".contentEquals(secondBuilder))
            throw new AssertionError("builder snapshot or identity changed");
        @SuppressWarnings("unchecked")
        java.util.Map<String,Integer> immutable = (java.util.Map<String,Integer>) modified.getMethod("immutable").invoke(null);
        try { immutable.put("z", 1); throw new AssertionError("immutable map became mutable"); }
        catch (UnsupportedOperationException expected) { }
        @SuppressWarnings("unchecked")
        java.util.Map<Integer,Integer> largeMap = (java.util.Map<Integer,Integer>) modified.getMethod("largeMap").invoke(null);
        if (largeMap.size() != 5000 || !Integer.valueOf(14997).equals(largeMap.get(4999)))
            throw new AssertionError("large object snapshot changed");
        Object originalBox = baseline.getMethod("box").invoke(null);
        Object foldedBox = modified.getMethod("box").invoke(null);
        if (!baseline.getMethod("type").invoke(null).toString()
                .equals(modified.getMethod("type").invoke(null).toString()))
            throw new AssertionError("Class object result changed");
        if (!Integer.valueOf(17).equals(originalBox.getClass().getMethod("value").invoke(originalBox))
                || !Integer.valueOf(17).equals(foldedBox.getClass().getMethod("value").invoke(foldedBox)))
            throw new AssertionError("constructor-initialized object changed");
        Object runtimeLink = modified.getMethod("link").invoke(null);
        if (!Boolean.TRUE.equals(runtimeLink.getClass().getMethod("self").invoke(runtimeLink)))
            throw new AssertionError("self-referential object changed");
        Object firstCycle = modified.getMethod("cycle").invoke(null);
        Object secondCycle = modified.getMethod("cycle").invoke(null);
        if (firstCycle == secondCycle || !Boolean.TRUE.equals(firstCycle.getClass().getMethod("cycle").invoke(firstCycle))
                || firstCycle.getClass().getField("next").get(firstCycle) == firstCycle
                || firstCycle.getClass().getField("next").get(firstCycle) == secondCycle)
            throw new AssertionError("cyclic object graph or identity changed");
        Object firstMutable = modified.getMethod("mutable").invoke(null);
        Object secondMutable = modified.getMethod("mutable").invoke(null);
        if (firstMutable == secondMutable || firstMutable.getClass().getField("value").getInt(firstMutable) != 9)
            throw new AssertionError("compacted object factory changed result or identity");
        Object[] foldedBoxes = (Object[]) modified.getMethod("boxes").invoke(null);
        Object[] secondBoxes = (Object[]) modified.getMethod("boxes").invoke(null);
        if (foldedBoxes == secondBoxes || foldedBoxes[0] == secondBoxes[0]
                || !Integer.valueOf(3).equals(foldedBoxes[0].getClass().getMethod("value").invoke(foldedBoxes[0]))
                || !Integer.valueOf(5).equals(foldedBoxes[1].getClass().getMethod("value").invoke(foldedBoxes[1])))
            throw new AssertionError("object array values or identity changed");
        Object[] sharedBoxes = (Object[]) modified.getMethod("sharedBoxes").invoke(null);
        if (sharedBoxes[0] != sharedBoxes[1])
            throw new AssertionError("shared object references were split");
        Object[] largeBoxes = (Object[]) modified.getMethod("largeBoxes").invoke(null);
        Object[] freshLargeBoxes = (Object[]) modified.getMethod("largeBoxes").invoke(null);
        if (largeBoxes.length != 2200 || largeBoxes[0] != null || largeBoxes == freshLargeBoxes
                || largeBoxes[10] == freshLargeBoxes[10]
                || !Integer.valueOf(99).equals(largeBoxes[2199].getClass().getMethod("value").invoke(largeBoxes[2199])))
            throw new AssertionError("large object array values or identity changed");
        Object[] manyBoxes = (Object[]) modified.getMethod("manyBoxes").invoke(null);
        if (manyBoxes.length != 2200 || manyBoxes[600] != null
                || !Integer.valueOf(599).equals(manyBoxes[599].getClass().getMethod("value").invoke(manyBoxes[599])))
            throw new AssertionError("large object array budget fallback changed");
        String[] largeStrings = (String[]) modified.getMethod("largeStrings").invoke(null);
        if (largeStrings.length != 3000 || !"item-0".equals(largeStrings[0])
                || !"item-2999".equals(largeStrings[2999]))
            throw new AssertionError("large reference array changed");
        int[][] largeRows = (int[][]) modified.getMethod("largeRows").invoke(null);
        int[][] freshRows = (int[][]) modified.getMethod("largeRows").invoke(null);
        if (largeRows.length != 2200 || largeRows[1] != null || largeRows[0][1] != 5
                || largeRows[2199][0] != 8 || largeRows == freshRows || largeRows[0] == freshRows[0])
            throw new AssertionError("large multidimensional array changed");
    }

    private static void verifyUnusedPrivateFields() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-field-prune-test");
        Path source = directory.resolve("PrivateFields.java");
        Files.write(source, ("public class PrivateFields {\n"
                + "  private static final int unused = 7;\n"
                + "  private static final long serialVersionUID = 1L;\n"
                + "  private static int used = 9;\n"
                + "  public static int result() { return used; }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
                directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("field fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("PrivateFields.class"));
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        byte[] output;
        try {
            output = ConstExprTransformer.pruneUnusedMethods(Collections.singletonList(
                    new ConstExprTransformer().transform(input))).get(0);
        } finally { ConstExprTransformer.clear(); }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (org.objectweb.asm.tree.FieldNode field : node.fields) names.add(field.name);
        if (names.contains("unused") || !names.contains("used") || !names.contains("serialVersionUID"))
            throw new AssertionError("private field pruning changed live or serialization fields");
    }

    private static void verifyDifferentialArraysAndDryRun() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-differential-test");
        Path source = directory.resolve("DifferentialArrays.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class DifferentialArrays {\n"
                + "  @ConstExpr static byte[] bytes() { byte[] a = new byte[3000]; for (int i=0;i<a.length;i++) a[i]=(byte)(i*7); return a; }\n"
                + "  @ConstExpr static short[] shorts() { short[] a = new short[3000]; for (int i=0;i<a.length;i++) a[i]=(short)(i*11); return a; }\n"
                + "  @ConstExpr static char[] chars() { char[] a = new char[3000]; for (int i=0;i<a.length;i++) a[i]=(char)(i*13); return a; }\n"
                + "  @ConstExpr static int[] ints() { int[] a = new int[3000]; for (int i=0;i<a.length;i++) a[i]=i*17; return a; }\n"
                + "  @ConstExpr static long[] longs() { long[] a = new long[3000]; for (int i=0;i<a.length;i++) a[i]=i*19L; return a; }\n"
                + "  @ConstExpr static float[] floats() { float[] a = new float[3000]; for (int i=0;i<a.length;i++) a[i]=i*0.25f; return a; }\n"
                + "  @ConstExpr static double[] doubles() { double[] a = new double[3000]; for (int i=0;i<a.length;i++) a[i]=i*0.5; return a; }\n"
                + "  @ConstExpr static boolean[] booleans() { boolean[] a = new boolean[3000]; for (int i=0;i<a.length;i++) a[i]=(i&1)==0; return a; }\n"
                + "  @ConstExpr static int[] sparse() { int[] a = new int[3000]; a[9]=17; a[2900]=-5; return a; }\n"
                + "  public static Object get(int kind) { switch(kind) { case 0:return bytes(); case 1:return shorts(); case 2:return chars(); case 3:return ints(); case 4:return longs(); case 5:return floats(); case 6:return doubles(); case 7:return booleans(); default:return sparse(); } }\n"
                + "  public static boolean independent() { Object a=get(3), b=get(3); return a!=b; }\n"
                + "  public static int failure() { throw new IllegalArgumentException(\"same failure\"); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("differential fixture compilation failed: " + status);
        Path inputFile = directory.resolve("DifferentialArrays.class");
        byte[] input = Files.readAllBytes(inputFile);
        Path outputDir = directory.resolve("transformed");
        Path report = directory.resolve("dry-run.tsv");
        ConstExprBuildProcessor.main(new String[] {directory.toString(), outputDir.toString(), "--dry-run",
                "--report=" + report});
        if (!java.util.Arrays.equals(input, Files.readAllBytes(inputFile))
                || Files.exists(outputDir.resolve("DifferentialArrays.class"))
                || !new String(Files.readAllBytes(report), StandardCharsets.UTF_8).contains("TOTAL\t"))
            throw new AssertionError("dry run wrote classes or omitted its report");
        Files.createDirectories(outputDir);
        Path stale = outputDir.resolve("RemovedClass.class");
        Files.write(stale, input);
        ConstExprBuildProcessor.main(new String[] {directory.toString(), outputDir.toString()});
        if (Files.exists(stale)) throw new AssertionError("stale transformed class remained");
        byte[] output = Files.readAllBytes(outputDir.resolve("DifferentialArrays.class"));
        Class<?> baseline = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("DifferentialArrays", input, 0, input.length); }
        }.define();
        Class<?> modified = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("DifferentialArrays", output, 0, output.length); }
        }.define();
        for (int kind = 0; kind < 9; kind++) {
            Object before = baseline.getMethod("get", int.class).invoke(null, kind);
            Object after = modified.getMethod("get", int.class).invoke(null, kind);
            if (!java.util.Objects.deepEquals(before, after))
                throw new AssertionError("array mismatch for kind " + kind);
        }
        if (!Boolean.TRUE.equals(baseline.getMethod("independent").invoke(null))
                || !Boolean.TRUE.equals(modified.getMethod("independent").invoke(null)))
            throw new AssertionError("array identity changed");
        for (Class<?> fixture : new Class<?>[] {baseline, modified}) {
            try { fixture.getMethod("failure").invoke(null); throw new AssertionError("exception disappeared"); }
            catch (java.lang.reflect.InvocationTargetException error) {
                if (!(error.getCause() instanceof IllegalArgumentException)
                        || !"same failure".equals(error.getCause().getMessage()))
                    throw new AssertionError("exception changed", error);
            }
        }
    }

    private static void verifyDefaultArrayMaterialization() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-default-array-test");
        Path source = directory.resolve("DefaultArrays.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class DefaultArrays {\n"
                + "  @ConstExpr static int[] zeros() { return new int[4096]; }\n"
                + "  @ConstExpr static int[] moreZeros() { return new int[4097]; }\n"
                + "  @ConstExpr static float[] signed() { return new float[] {0.0f, -0.0f}; }\n"
                + "  public static int[] large() { return zeros(); }\n"
                + "  public static int[] larger() { return moreZeros(); }\n"
                + "  public static float[] small() { return signed(); }\n"
                + "  public static int[][] repeated() { int[] a = zeros(); int[] b = zeros(); return new int[][] {a, b}; }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("default array fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("DefaultArrays.class"));
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        byte[] output;
        try { output = new ConstExprTransformer().transform(input); }
        finally { ConstExprTransformer.clear(); }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        for (MethodNode method : node.methods)
            if (method.name.startsWith("$constexpr$fill"))
                throw new AssertionError("all-zero array generated a payload decoder");
        final byte[] verified = output;
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("DefaultArrays", verified, 0, verified.length); }
        }.define();
        int[] large = (int[]) fixture.getMethod("large").invoke(null);
        int[][] repeated = (int[][]) fixture.getMethod("repeated").invoke(null);
        float[] signed = (float[]) fixture.getMethod("small").invoke(null);
        if (large.length != 4096 || large[4095] != 0
                || ((int[]) fixture.getMethod("larger").invoke(null)).length != 4097
                || repeated[0] == repeated[1]
                || Float.floatToRawIntBits(signed[0]) != 0
                || Float.floatToRawIntBits(signed[1]) != 0x80000000)
            throw new AssertionError("default array values or independent references changed");
        repeated[0][0] = 7;
        if (repeated[1][0] != 0) throw new AssertionError("default arrays share mutable storage");
        String previousSharing = System.getProperty("xh.xianhhh.constexpr.shareTemplateArray");
        System.setProperty("xh.xianhhh.constexpr.shareTemplateArray", "true");
        try {
            ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
            byte[] shared = new ConstExprTransformer().transform(input);
            Class<?> sharedFixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
                Class<?> define() { return defineClass("DefaultArrays", shared, 0, shared.length); }
            }.define();
            int[][] sharedRepeated = (int[][]) sharedFixture.getMethod("repeated").invoke(null);
            if (sharedRepeated[0] != sharedRepeated[1]
                    || ((int[]) sharedFixture.getMethod("larger").invoke(null)).length != 4097)
                throw new AssertionError("shared default array templates changed");
        } finally {
            ConstExprTransformer.clear();
            if (previousSharing == null) System.clearProperty("xh.xianhhh.constexpr.shareTemplateArray");
            else System.setProperty("xh.xianhhh.constexpr.shareTemplateArray", previousSharing);
        }
    }

    private static void verifyStringEncodingFold() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-encoding-test");
        Path source = directory.resolve("StringEncoding.java");
        StringBuilder large = new StringBuilder();
        for (int index = 0; index < 4096; index++) large.append((char) ('a' + index % 26));
        Files.write(source, ("import java.nio.charset.StandardCharsets;\n"
                + "@xh.xianhhh.macro.ConstExpr public class StringEncoding {\n"
                + "  public static byte[] small() { return \"Hello\".getBytes(StandardCharsets.UTF_8); }\n"
                + "  public static byte[] large() { return \"" + large + "\".getBytes(StandardCharsets.UTF_8); }\n"
                + "  public static byte[][] repeatedLarge() { byte[] first = \"" + large
                + "\".getBytes(StandardCharsets.UTF_8); byte[] second = \"" + large
                + "\".getBytes(StandardCharsets.UTF_8); return new byte[][] {first, second}; }\n"
                + "  public static byte[] dynamic(String text) { return text.getBytes(StandardCharsets.UTF_8); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "--release", "17", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("encoding fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("StringEncoding.class"));
        byte[] output;
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        try {
            output = new ConstExprTransformer().transform(input);
            output = ConstExprTransformer.pruneUnusedMethods(Collections.singletonList(output)).get(0);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertNamedCalls(node, "small", "getBytes", 0);
        assertNamedCalls(node, "large", "getBytes", 0);
        assertNamedCalls(node, "dynamic", "getBytes", 1);
        assertNamedCalls(node, "repeatedLarge", "getBytes", 0);
        int copiedLargeArrays = 0;
        for (MethodNode method : node.methods) {
            if (!"repeatedLarge".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction instanceof MethodInsnNode
                        && "clone".equals(((MethodInsnNode) instruction).name)) copiedLargeArrays++;
        }
        if (copiedLargeArrays != 1) throw new AssertionError("large repeated array was not copied from a local");
        String previousMode = System.getProperty("xh.xianhhh.constexpr.mode");
        System.setProperty("xh.xianhhh.constexpr.mode", "TAG");
        try {
            ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
            ClassNode tagged = new ClassNode();
            new ClassReader(new ConstExprTransformer().transform(input)).accept(tagged, 0);
            assertNamedCalls(tagged, "small", "getBytes", 0);
            assertNamedCalls(tagged, "dynamic", "getBytes", 1);
        } finally {
            ConstExprTransformer.clear();
            if (previousMode == null) System.clearProperty("xh.xianhhh.constexpr.mode");
            else System.setProperty("xh.xianhhh.constexpr.mode", previousMode);
        }
        final byte[] verified = output;
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("StringEncoding", verified, 0, verified.length); }
        }.define();
        byte[] small = (byte[]) fixture.getMethod("small").invoke(null);
        byte[] second = (byte[]) fixture.getMethod("small").invoke(null);
        byte[] encodedLarge = (byte[]) fixture.getMethod("large").invoke(null);
        byte[] secondLarge = (byte[]) fixture.getMethod("large").invoke(null);
        if (!java.util.Arrays.equals(small, "Hello".getBytes(StandardCharsets.UTF_8))
                || small == second || encodedLarge.length != large.length()
                || !java.util.Arrays.equals(encodedLarge, large.toString().getBytes(StandardCharsets.UTF_8))
                || encodedLarge == secondLarge || !java.util.Arrays.equals(encodedLarge, secondLarge))
            throw new AssertionError("folded UTF-8 bytes or fresh-array semantics changed");
        byte[] dynamic = (byte[]) fixture.getMethod("dynamic", String.class).invoke(null, "runtime");
        if (!java.util.Arrays.equals(dynamic, "runtime".getBytes(StandardCharsets.UTF_8)))
            throw new AssertionError("dynamic encoding changed");
        byte[][] repeated = (byte[][]) fixture.getMethod("repeatedLarge").invoke(null);
        if (repeated[0] == repeated[1] || !java.util.Arrays.equals(repeated[0], repeated[1]))
            throw new AssertionError("repeated large arrays lost independent contents");
        repeated[0][0] = 0;
        if (repeated[1][0] == 0) throw new AssertionError("repeated large arrays share storage");
    }

    private static void verifyInstancePruning() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-instance-prune-test");
        Path source = directory.resolve("InstancePrune.java");
        Files.write(source, ("public class InstancePrune {\n"
                + "  private static final class FoldOnly { private final int n; private FoldOnly(int n) { this.n = n; } private int apply(int x) { return n * x; } }\n"
                + "  private static final class Shared { private final int n; private Shared(int n) { this.n = n; } private int apply(int x) { return n * x; } }\n"
                + "  public static int folded() { return new FoldOnly(7).apply(3); }\n"
                + "  public static int sharedFolded() { return new Shared(7).apply(3); }\n"
                + "  public static int runtime(int n) { return new Shared(n).apply(3); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "--release", "17", "-g", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("instance pruning fixture compilation failed: " + status);
        byte[] root = Files.readAllBytes(directory.resolve("InstancePrune.class"));
        byte[] folded = Files.readAllBytes(directory.resolve("InstancePrune$FoldOnly.class"));
        byte[] shared = Files.readAllBytes(directory.resolve("InstancePrune$Shared.class"));
        java.util.List<byte[]> inputs = java.util.Arrays.asList(root, folded, shared);
        java.util.List<byte[]> outputs;
        ConstExprTransformer.preScan(inputs, Collections.<Path>emptyList(), true);
        try {
            ConstExprTransformer transformer = new ConstExprTransformer();
            java.util.List<byte[]> transformed = new java.util.ArrayList<byte[]>();
            for (byte[] input : inputs) transformed.add(transformer.transform(input));
            outputs = ConstExprTransformer.pruneUnusedMethods(transformed);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode foldedNode = new ClassNode();
        new ClassReader(outputs.get(1)).accept(foldedNode, 0);
        assertMethod(foldedNode, "<init>", false);
        assertMethod(foldedNode, "apply", false);
        ClassNode sharedNode = new ClassNode();
        new ClassReader(outputs.get(2)).accept(sharedNode, 0);
        assertMethod(sharedNode, "<init>", true);
        assertMethod(sharedNode, "apply", true);
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.startsWith("InstancePrune")) throw new ClassNotFoundException(name);
                int index = name.endsWith("$FoldOnly") ? 1 : name.endsWith("$Shared") ? 2 : 0;
                byte[] bytes = outputs.get(index);
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.loadClass("InstancePrune");
        assertResult("folded private instance", 21, fixture.getMethod("folded").invoke(null));
        assertResult("shared folded instance", 21, fixture.getMethod("sharedFolded").invoke(null));
        assertResult("shared runtime instance", 15, fixture.getMethod("runtime", int.class).invoke(null, 5));
    }

    private static void verifyInferredArrayShape() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-inferred-shape-test");
        Path source = directory.resolve("InferredShape.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class InferredShape {\n"
                + "  private static int[] $constexpr$template$0 = {42};\n"
                + "  @ConstExpr private static int[][] rows(int seed) { return new int[][] { {seed}, {seed + 1, seed + 2} }; }\n"
                + "  @ConstExpr public static int localLength(int seed) { int[] values = {seed, seed + 1}; return values.length + seed; }\n"
                + "  @ConstExpr public static int deadLength(int seed) { int[] values = {seed}; int length = values.length; return seed; }\n"
                + "  public static int[][] result() { return rows(7); }\n"
                + "  public static int[][][] repeated() { int[][] first = rows(7); int[][] second = rows(7); return new int[][][] {first, second}; }\n"
                + "  public static int[][][] changed() { int[][] first = rows(7); first[0][0] = 99; int[][] second = rows(7); return new int[][][] {first, second}; }\n"
                + "  public static int[][] dynamic(int seed) { return rows(seed); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "-g", "-processor",
                "xh.xianhhh.processor.ConstExprAnnotationProcessor", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("inferred shape fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("InferredShape.class"));
        byte[] output;
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        try {
            output = new ConstExprTransformer().transform(input);
            output = ConstExprTransformer.pruneUnusedMethods(Collections.singletonList(output)).get(0);
        } finally {
            ConstExprTransformer.clear();
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertNamedCalls(node, "result", "rows", 0);
        assertNamedCalls(node, "dynamic", "rows", 1);
        assertNamedCalls(node, "repeated", "rows", 0);
        assertNamedCalls(node, "changed", "rows", 0);
        int cloneCalls = 0;
        for (MethodNode method : node.methods) {
            if (!"repeated".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction instanceof MethodInsnNode
                        && ((MethodInsnNode) instruction).name.startsWith("$constexpr$clone$")) cloneCalls++;
        }
        if (cloneCalls != 1) throw new AssertionError("repeated array was not copied from the first local");
        for (MethodNode method : node.methods) {
            if (!"localLength".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction.getOpcode() == Opcodes.ARRAYLENGTH)
                    throw new AssertionError("known local array length remained at runtime");
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction.getOpcode() == Opcodes.POP)
                    throw new AssertionError("local array length left an unnecessary POP");
        }
        for (MethodNode method : node.methods) {
            if (!"deadLength".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction instanceof org.objectweb.asm.tree.VarInsnNode
                        && instruction.getOpcode() == Opcodes.ISTORE)
                    throw new AssertionError("dead constant local store remained");
        }
        for (MethodNode method : node.methods) {
            if (method.visibleAnnotations != null && method.visibleAnnotations.stream()
                    .anyMatch(annotation -> ConstExprTransformer.DESC.equals(annotation.desc)))
                throw new AssertionError("ConstExpr method annotation remained: " + method.name);
            if (method.invisibleAnnotations != null && method.invisibleAnnotations.stream()
                    .anyMatch(annotation -> ConstExprTransformer.DESC.equals(annotation.desc)))
                throw new AssertionError("ConstExpr method annotation remained: " + method.name);
            if (method.visibleTypeAnnotations != null && method.visibleTypeAnnotations.stream()
                    .anyMatch(annotation -> ConstExprTransformer.DESC.equals(annotation.desc)))
                throw new AssertionError("ConstExpr return type annotation remained: " + method.name);
            if (method.invisibleTypeAnnotations != null && method.invisibleTypeAnnotations.stream()
                    .anyMatch(annotation -> ConstExprTransformer.DESC.equals(annotation.desc)))
                throw new AssertionError("ConstExpr return type annotation remained: " + method.name);
        }
        final byte[] verifiedOutput = output;
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("InferredShape", verifiedOutput, 0, verifiedOutput.length); }
        }.define();
        int[][] first = (int[][]) fixture.getMethod("result").invoke(null);
        int[][] second = (int[][]) fixture.getMethod("result").invoke(null);
        int[][] dynamic = (int[][]) fixture.getMethod("dynamic", int.class).invoke(null, 11);
        int[][][] repeated = (int[][][]) fixture.getMethod("repeated").invoke(null);
        int[][][] changed = (int[][][]) fixture.getMethod("changed").invoke(null);
        if (repeated[0] == repeated[1] || repeated[0][0] == repeated[1][0]
                || !java.util.Arrays.deepEquals(repeated[0], repeated[1])
                || changed[0][0][0] != 99 || changed[1][0][0] != 7)
            throw new AssertionError("repeated array cloning or mutation guard changed");
        String previousSharing = System.getProperty("xh.xianhhh.constexpr.shareTemplateArray");
        System.setProperty("xh.xianhhh.constexpr.shareTemplateArray", "true");
        try {
            ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
            byte[] sharedOutput = new ConstExprTransformer().transform(input);
            ConstExprTransformer.preScan(Collections.singletonList(sharedOutput), Collections.<Path>emptyList(), true);
            byte[] repeatedOutput = new ConstExprTransformer().transform(sharedOutput);
            if (!java.util.Arrays.equals(sharedOutput, repeatedOutput))
                throw new AssertionError("repeated transformation changed generated arrays");
            Class<?> sharedFixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
                Class<?> define() { return defineClass("InferredShape", sharedOutput, 0, sharedOutput.length); }
            }.define();
            int[][][] shared = (int[][][]) sharedFixture.getMethod("repeated").invoke(null);
            if (shared[0] == shared[1] || shared[0][0] != shared[1][0])
                throw new AssertionError("shared templates changed outer/inner array identity");
        } finally {
            ConstExprTransformer.clear();
            if (previousSharing == null) System.clearProperty("xh.xianhhh.constexpr.shareTemplateArray");
            else System.setProperty("xh.xianhhh.constexpr.shareTemplateArray", previousSharing);
        }
        assertResult("local array length", 13, fixture.getMethod("localLength", int.class).invoke(null, 11));
        if (first.length != 2 || first[0].length != 1 || first[1].length != 2
                || first[0][0] != 7 || first[1][0] != 8 || first[1][1] != 9
                || first == second || first[0] == second[0]
                || dynamic[0][0] != 11 || dynamic[1][1] != 13)
            throw new AssertionError("inferred array shape or fresh-array semantics changed");
    }

    private static void verifyDependencyJarMethods() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-dependency-jar-test");
        Path base = directory.resolve("base"), versioned = directory.resolve("versioned");
        Files.createDirectories(base);
        Files.createDirectories(versioned);
        Path baseSource = directory.resolve("LibraryBase.java");
        Path childSource = directory.resolve("LibraryChild.java");
        Files.write(baseSource, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class LibraryBase { @ConstExpr public static int compute(int n) { return n * 3 + 2; } @ConstExpr(value=\"91\") public static int fixed() { return 0; } public int instance(int n) { return n * 3 + 2; } }\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(childSource, "public class LibraryChild extends LibraryBase {}\n".getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", base.toString(),
                baseSource.toString(), childSource.toString());
        if (status != 0) throw new AssertionError("dependency base compilation failed: " + status);
        Files.write(baseSource, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class LibraryBase { @ConstExpr public static int compute(int n) { return n * 5 + 1; } @ConstExpr(value=\"91\") public static int fixed() { return 0; } public int instance(int n) { return n * 5 + 1; } }\n")
                .getBytes(StandardCharsets.UTF_8));
        status = compiler.run(null, null, null, "--release", "17", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", versioned.toString(), baseSource.toString());
        if (status != 0) throw new AssertionError("dependency versioned compilation failed: " + status);
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        Path jar = directory.resolve("library.jar");
        try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String name : new String[] {"LibraryBase.class", "LibraryChild.class"}) {
                output.putNextEntry(new java.util.jar.JarEntry(name));
                output.write(Files.readAllBytes(base.resolve(name)));
                output.closeEntry();
            }
            output.putNextEntry(new java.util.jar.JarEntry("META-INF/versions/17/LibraryBase.class"));
            output.write(Files.readAllBytes(versioned.resolve("LibraryBase.class")));
            output.closeEntry();
        }
        Path caller = directory.resolve("JarCaller.java");
        Files.write(caller, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class JarCaller {\n"
                + "  public static int folded() { return LibraryChild.compute(7); }\n"
                + "  public static int fixed() { return LibraryChild.fixed(); }\n"
                + "  public static int dynamic(int n) { return LibraryChild.compute(n); }\n"
                + "  @ConstExpr static int inherited() { return new LibraryChild().instance(7); }\n"
                + "  public static int foldedInherited() { return inherited(); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        status = compiler.run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path") + java.io.File.pathSeparator + jar,
                "-d", directory.toString(), caller.toString());
        if (status != 0) throw new AssertionError("dependency caller compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("JarCaller.class"));
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.singletonList(jar), true);
        byte[] transformed;
        try { transformed = new ConstExprTransformer().transform(input); }
        finally { ConstExprTransformer.clear(); }
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        assertNamedCalls(node, "folded", "compute", 0);
        assertNamedCalls(node, "fixed", "fixed", 0);
        assertNamedCalls(node, "foldedInherited", "inherited", 0);
        assertNamedCalls(node, "dynamic", "compute", 1);
        try (java.net.URLClassLoader dependencies = new java.net.URLClassLoader(
                new java.net.URL[] {jar.toUri().toURL()}, ConstExprTransformerTest.class.getClassLoader())) {
            Class<?> fixture = new ClassLoader(dependencies) {
                Class<?> define() { return defineClass("JarCaller", transformed, 0, transformed.length); }
            }.define();
            assertResult("dependency versioned constant", 36, fixture.getMethod("folded").invoke(null));
            assertResult("dependency annotation value", 91, fixture.getMethod("fixed").invoke(null));
            assertResult("dependency inherited instance", 36, fixture.getMethod("foldedInherited").invoke(null));
            assertResult("dependency versioned dynamic", 36, fixture.getMethod("dynamic", int.class).invoke(null, 7));
        }
    }

    private static void verifyAnalyzedBranches() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-branches-test");
        Path source = directory.resolve("BranchShapes.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "@ConstExpr public class BranchShapes {\n"
                + "  public static int table(boolean flag) { int value; if (flag) value=3; else value=3; switch(value) { case 1:return 1; case 2:return 2; case 3:return 33; default:return -1; } }\n"
                + "  public static int lookup(boolean flag) { int value; if (flag) value=100; else value=100; switch(value) { case 1:return 1; case 100:return 88; default:return -1; } }\n"
                + "  public static int branch(boolean flag) { int value; if (flag) value=7; else value=7; if (value==7) return 21; return -1; }\n"
                + "  public static int nullCheck(boolean flag) { Object value; if (flag) value=null; else value=null; return value==null ? 42 : -1; }\n"
                + "  public static int cascade(boolean flag) { int first; if (flag) first=5; else first=5; int second; if (first==5) second=100; else second=1; switch(second) { case 1:return -1; case 100:return 73; default:return -2; } }\n"
                + "  public static int dynamic(int value) { switch(value) { case 1:return 11; case 2:return 22; default:return -1; } }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("branch fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("BranchShapes.class"));
        String previousMode = System.getProperty("xh.xianhhh.constexpr.mode");
        System.setProperty("xh.xianhhh.constexpr.mode", "TAG");
        byte[] output;
        try {
            ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
            output = new ConstExprTransformer().transform(input);
        } finally {
            ConstExprTransformer.clear();
            if (previousMode == null) System.clearProperty("xh.xianhhh.constexpr.mode");
            else System.setProperty("xh.xianhhh.constexpr.mode", previousMode);
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (!java.util.Arrays.asList("table", "lookup", "branch", "nullCheck", "cascade").contains(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if ("table".equals(method.name) || "lookup".equals(method.name) || "cascade".equals(method.name)) {
                    if (instruction.getOpcode() == Opcodes.TABLESWITCH || instruction.getOpcode() == Opcodes.LOOKUPSWITCH)
                        throw new AssertionError(method.name + " retained a known switch");
                }
            }
        }
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("BranchShapes", output, 0, output.length); }
        }.define();
        for (boolean flag : new boolean[] {false, true}) {
            if (!Integer.valueOf(33).equals(fixture.getMethod("table", boolean.class).invoke(null, flag))
                    || !Integer.valueOf(88).equals(fixture.getMethod("lookup", boolean.class).invoke(null, flag))
                    || !Integer.valueOf(21).equals(fixture.getMethod("branch", boolean.class).invoke(null, flag))
                    || !Integer.valueOf(73).equals(fixture.getMethod("cascade", boolean.class).invoke(null, flag))
                    || !Integer.valueOf(42).equals(fixture.getMethod("nullCheck", boolean.class).invoke(null, flag)))
                throw new AssertionError("known branch result changed");
        }
        assertResult("dynamic branch", 22, fixture.getMethod("dynamic", int.class).invoke(null, 2));
    }

    private static void verifyPlacementMarkers() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-placement-test");
        Path source = directory.resolve("PlacementMarkers.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "@ConstExpr public class PlacementMarkers {\n"
                + "  public static int byClass() { int selector = 2; if (selector == 2) return 7; return 0; }\n"
                + "  @ConstExpr public static int byMethod() { int selector = 2; switch (selector) { case 2: return 11; default: return 0; } }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        Path localSource = directory.resolve("LocalMarkerOnly.java");
        Files.write(localSource, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class LocalMarkerOnly {\n"
                + "  public static int byLocal() { @ConstExpr int selector = 2; int result = 13; while (selector < 0) result++; for (; selector < 0;) result++; return result; }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "-g", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString(), localSource.toString());
        if (status != 0) throw new AssertionError("placement fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("PlacementMarkers.class"));
        byte[] localInput = Files.readAllBytes(directory.resolve("LocalMarkerOnly.class"));
        String previousMode = System.getProperty("xh.xianhhh.constexpr.mode");
        System.setProperty("xh.xianhhh.constexpr.mode", "TAG");
        byte[] output;
        byte[] localOutput;
        try {
            ConstExprTransformer.preScan(java.util.Arrays.asList(input, localInput), Collections.<Path>emptyList(), true);
            ConstExprTransformer transformer = new ConstExprTransformer();
            output = transformer.transform(input);
            localOutput = transformer.transform(localInput);
            java.util.List<byte[]> pruned = ConstExprTransformer.pruneUnusedMethods(
                    java.util.Arrays.asList(output, localOutput));
            if (!java.util.Arrays.equals(output, pruned.get(0)))
                throw new AssertionError("unexpected class pruning in placement fixture");
            if (!java.util.Arrays.equals(localOutput, pruned.get(1)))
                throw new AssertionError("unexpected local class pruning in placement fixture");
        } finally {
            ConstExprTransformer.clear();
            if (previousMode == null) System.clearProperty("xh.xianhhh.constexpr.mode");
            else System.setProperty("xh.xianhhh.constexpr.mode", previousMode);
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("by")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() == Opcodes.TABLESWITCH || instruction.getOpcode() == Opcodes.LOOKUPSWITCH)
                    throw new AssertionError(method.name + " kept a constant switch");
            }
        }
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("PlacementMarkers", output, 0, output.length); }
        }.define();
        ClassNode localNode = new ClassNode();
        new ClassReader(localOutput).accept(localNode, 0);
        for (MethodNode method : localNode.methods) {
            if (!"byLocal".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                int opcode = instruction.getOpcode();
                if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE)
                    throw new AssertionError("local marker kept a constant loop condition");
            }
        }
        assertResult("class marker", 7, fixture.getMethod("byClass").invoke(null));
        assertResult("method marker", 11, fixture.getMethod("byMethod").invoke(null));
        Class<?> localFixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("LocalMarkerOnly", localOutput, 0, localOutput.length); }
        }.define();
        assertResult("local marker", 13, localFixture.getMethod("byLocal").invoke(null));
    }

    private static void verifyMethodPruning() throws Exception {
        Path directory = Files.createTempDirectory("constexpr-prune-test");
        Path source = directory.resolve("PruneChain.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class PruneChain {\n"
                + "  private static int leaf(int n) { return n + 1; }\n"
                + "  private static int middle(int n) { return leaf(n) * 2; }\n"
                + "  @ConstExpr private static int root() { return middle(20); }\n"
                + "  public static int folded() { return root(); }\n"
                + "  private static int orphanParent() { return root() + 1; }\n"
                + "  private static int orphanGrandparent() { return orphanParent() + 1; }\n"
                + "  private static int retainedParent(int n) { return n + root(); }\n"
                + "  public static int retainedRuntime(int n) { return retainedParent(n); }\n"
                + "  private static int recurse(int n) { return n == 0 ? 1 : n * recurse(n - 1); }\n"
                + "  @ConstExpr private static int recursiveRoot() { return recurse(5); }\n"
                + "  public static int foldedRecursion() { return recursiveRoot(); }\n"
                + "  private static int runtimeHelper(int n) { return n + 1; }\n"
                + "  @ConstExpr private static int shared() { return runtimeHelper(3); }\n"
                + "  public static int foldedShared() { return shared(); }\n"
                + "  public static int runtimeShared(int n) { return runtimeHelper(n); }\n"
                + "  @ConstExpr private static int handleTarget() { return 9; }\n"
                + "  public static int foldedHandle() { return handleTarget(); }\n"
                + "  public static java.util.function.IntSupplier supplier() { return PruneChain::handleTarget; }\n"
                + "  @ConstExpr public static int api() { return 11; }\n"
                + "  public static int foldedApi() { return api(); }\n"
                + "  @ConstExpr private static int chainLeaf() { return 3; }\n"
                + "  @ConstExpr private static int chainMiddle(int n) { return n * chainLeaf(); }\n"
                + "  private static int dynamicHelper(int n) { return n + 2; }\n"
                + "  @ConstExpr public static int liveChain(int n) { return dynamicHelper(n) + chainMiddle(4); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("pruning fixture compilation failed: " + status);
        byte[] input = Files.readAllBytes(directory.resolve("PruneChain.class"));
        String previousMode = System.getProperty("xh.xianhhh.constexpr.mode");
        System.setProperty("xh.xianhhh.constexpr.mode", "TAG");
        ConstExprTransformer.preScan(Collections.singletonList(input), Collections.<Path>emptyList(), true);
        byte[] output;
        try {
            byte[] transformed = new ConstExprTransformer().transform(input);
            ClassNode beforePruning = new ClassNode();
            new ClassReader(transformed).accept(beforePruning, 0);
            assertNamedCalls(beforePruning, "chainMiddle", "chainLeaf", 0);
            assertNamedCalls(beforePruning, "liveChain", "chainMiddle", 0);
            assertNamedCalls(beforePruning, "liveChain", "dynamicHelper", 1);
            output = ConstExprTransformer.pruneUnusedMethods(Collections.singletonList(transformed)).get(0);
        } finally {
            ConstExprTransformer.clear();
            if (previousMode == null) System.clearProperty("xh.xianhhh.constexpr.mode");
            else System.setProperty("xh.xianhhh.constexpr.mode", previousMode);
        }
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        assertMethod(node, "leaf", false);
        assertMethod(node, "middle", false);
        assertMethod(node, "root", false);
        assertMethod(node, "orphanParent", false);
        assertMethod(node, "orphanGrandparent", false);
        assertMethod(node, "retainedParent", true);
        assertMethod(node, "recurse", false);
        assertMethod(node, "recursiveRoot", false);
        assertMethod(node, "shared", false);
        assertMethod(node, "runtimeHelper", true);
        assertMethod(node, "handleTarget", true);
        assertMethod(node, "api", true);
        assertMethod(node, "chainLeaf", false);
        assertMethod(node, "chainMiddle", false);
        assertMethod(node, "dynamicHelper", true);
        assertMethod(node, "liveChain", true);
        Class<?> fixture = new ClassLoader(ConstExprTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("PruneChain", output, 0, output.length); }
        }.define();
        assertResult("folded chain", 42, fixture.getMethod("folded").invoke(null));
        assertResult("retained runtime caller", 47, fixture.getMethod("retainedRuntime", int.class).invoke(null, 5));
        assertResult("folded recursion", 120, fixture.getMethod("foldedRecursion").invoke(null));
        assertResult("shared helper", 4, fixture.getMethod("foldedShared").invoke(null));
        assertResult("runtime helper", 6, fixture.getMethod("runtimeShared", int.class).invoke(null, 5));
        assertResult("method reference", 9, ((java.util.function.IntSupplier) fixture.getMethod("supplier").invoke(null)).getAsInt());
        assertResult("public API", 11, fixture.getMethod("api").invoke(null));
        assertResult("live recursive chain", 19, fixture.getMethod("liveChain", int.class).invoke(null, 5));
    }

    private static void assertMethod(ClassNode node, String name, boolean expected) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name)) {
                if (!expected) throw new AssertionError(name + " should have been removed");
                return;
            }
        }
        if (expected) throw new AssertionError(name + " should have been retained");
    }

    private static void assertNamedCalls(ClassNode node, String methodName, String callName, int expected) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(methodName)) continue;
            int count = 0;
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction instanceof MethodInsnNode
                        && ((MethodInsnNode) instruction).name.equals(callName)) count++;
            if (count != expected)
                throw new AssertionError(methodName + " still calls " + callName + " " + count + " times");
            return;
        }
        throw new AssertionError("missing method: " + methodName);
    }

    private static void assertCalls(ClassNode node, String name, int expected) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name)) continue;
            int calls = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode
                        && (((MethodInsnNode) instruction).name.equals("plusOne")
                        || ((MethodInsnNode) instruction).name.equals("twice")
                        || ((MethodInsnNode) instruction).name.equals("combine"))) calls++;
            }
            if (calls != expected) {
                StringBuilder instructions = new StringBuilder();
                for (AbstractInsnNode instruction : method.instructions) {
                    instructions.append(instruction.getOpcode()).append(':')
                            .append(instruction.getClass().getSimpleName()).append(' ');
                }
                throw new AssertionError(name + " calls: " + calls + ", expected " + expected + "; " + instructions);
            }
            return;
        }
        throw new AssertionError("missing method: " + name);
    }

    private static void assertOpcode(ClassNode node, String name, int opcode) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() == opcode) return;
            }
            throw new AssertionError(name + " lost opcode " + opcode);
        }
        throw new AssertionError("missing method: " + name);
    }

    private static void assertOpcodeCount(ClassNode node, String name, int opcode, int expected) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name)) continue;
            int count = 0;
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction.getOpcode() == opcode) count++;
            if (count != expected)
                throw new AssertionError(name + " opcode " + opcode + ": expected " + expected + ", got " + count);
            return;
        }
        throw new AssertionError("missing method: " + name);
    }

    private static void assertOpcodeAbsent(ClassNode node, String name, int opcode) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name)) continue;
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction.getOpcode() == opcode)
                    throw new AssertionError(name + " retained opcode " + opcode);
            return;
        }
        throw new AssertionError("missing method: " + name);
    }

    private static void assertResult(String name, int expected, Object actual) {
        if (!Integer.valueOf(expected).equals(actual))
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
    }

    private static void assertObjectResult(String name, Object expected, Object actual) {
        if (!expected.equals(actual))
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
    }
}
