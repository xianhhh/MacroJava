package xh.xianhhh.asm;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

/** Repeatable local probe for array size, build time, initialization and calls. */
public final class ConstExprArrayBenchmark {
    private static volatile int blackhole;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("constexpr-array-benchmark");
        Path source = directory.resolve("ArrayProbe.java");
        Files.write(source, ("import xh.xianhhh.macro.ConstExpr;\n"
                + "public class ArrayProbe {\n"
                + "  @ConstExpr private static int[] generate() { int[] a = new int[4096];"
                + " for (int i=0;i<a.length;i++) a[i]=i*17; return a; }\n"
                + "  public static int[] run() { return generate(); }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString(), source.toString());
        if (status != 0) throw new AssertionError("benchmark compilation failed: " + status);
        byte[] original = Files.readAllBytes(directory.resolve("ArrayProbe.class"));
        long start = System.nanoTime();
        ConstExprTransformer.preScan(Collections.singletonList(original), Collections.<Path>emptyList(), true);
        byte[] modified;
        try { modified = new ConstExprTransformer().transform(original); }
        finally { ConstExprTransformer.clear(); }
        long transformNanos = System.nanoTime() - start;
        long[] originalInit = new long[7], modifiedInit = new long[7];
        for (int index = 0; index < 7; index++) {
            originalInit[index] = initialize(original);
            modifiedInit[index] = initialize(modified);
        }
        Method baseline = load(original).getMethod("run");
        Method optimized = load(modified).getMethod("run");
        for (int index = 0; index < 2000; index++) {
            consume((int[]) baseline.invoke(null));
            consume((int[]) optimized.invoke(null));
        }
        long[] baselineCalls = new long[7], optimizedCalls = new long[7];
        for (int index = 0; index < 7; index++) {
            baselineCalls[index] = calls(baseline, 2000);
            optimizedCalls[index] = calls(optimized, 2000);
        }
        System.out.println("classBytes=" + original.length + "/" + modified.length
                + ", transformMs=" + transformNanos / 1_000_000.0);
        System.out.println("initMedianMs=" + median(originalInit) / 1_000_000.0 + "/"
                + median(modifiedInit) / 1_000_000.0);
        System.out.println("callsMedianMs=" + median(baselineCalls) / 1_000_000.0 + "/"
                + median(optimizedCalls) / 1_000_000.0 + ", calls/sample=2000, blackhole=" + blackhole);
    }

    private static Class<?> load(byte[] bytes) {
        return new ClassLoader(ConstExprArrayBenchmark.class.getClassLoader()) {
            Class<?> define() { return defineClass("ArrayProbe", bytes, 0, bytes.length); }
        }.define();
    }

    private static long initialize(byte[] bytes) throws Exception {
        Class<?> fixture = load(bytes);
        long start = System.nanoTime();
        fixture.getMethod("run").invoke(null);
        return System.nanoTime() - start;
    }

    private static long calls(Method method, int rounds) throws Exception {
        long start = System.nanoTime();
        for (int index = 0; index < rounds; index++) consume((int[]) method.invoke(null));
        return System.nanoTime() - start;
    }

    private static void consume(int[] values) { blackhole += values[values.length - 1]; }

    private static long median(long[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }
}
