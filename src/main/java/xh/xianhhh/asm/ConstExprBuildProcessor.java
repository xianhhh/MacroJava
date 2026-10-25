package xh.xianhhh.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;

/** Gradle JavaExec entry point. */
public final class ConstExprBuildProcessor {
    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException("Usage: ConstExprBuildProcessor <classesDir> <outputDir> [--dry-run] [--progress] [--report=path] [--workers=N] [classpathEntry...]");
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        if (!Files.exists(input)) {
            return;
        }
        List<Path> classPath = new ArrayList<>();
        boolean dryRun = false;
        boolean showProgress = false;
        Path report = null;
        int workers = 0;
        boolean explicitWorkers = false;
        Path selection = null, state = null, times = null;
        int workerId = -1;
        for (int index = 2; index < args.length; index++) {
            if ("--dry-run".equals(args[index])) dryRun = true;
            else if ("--progress".equals(args[index])) showProgress = true;
            else if (args[index].startsWith("--progress-worker=")) workerId = Integer.parseInt(args[index].substring(18));
            else if (args[index].startsWith("--report=")) report = Paths.get(args[index].substring(9));
            else if (args[index].startsWith("--workers=")) {
                workers = parseWorkers(args[index].substring(10));
                explicitWorkers = workers != 0;
            }
            else if (args[index].startsWith("--selection=")) selection = Paths.get(args[index].substring(12));
            else if (args[index].startsWith("--state=")) state = Paths.get(args[index].substring(8));
            else if (args[index].startsWith("--times=")) times = Paths.get(args[index].substring(8));
            else classPath.add(Paths.get(args[index]));
        }
        List<Path> files;
        Path normalizedInput = input.toAbsolutePath().normalize();
        Path normalizedOutput = output.toAbsolutePath().normalize();

        try (Stream<Path> stream = Files.walk(input)) {
            files = stream.filter(path -> path.toString().endsWith(".class")
                    && (normalizedInput.equals(normalizedOutput)
                    || !path.toAbsolutePath().normalize().startsWith(normalizedOutput)))
                    .sorted(Comparator.comparing(path -> input.relativize(path).toString()))
                    .collect(Collectors.toList());
        }

        List<byte[]> classes = new ArrayList<>();
        for (Path file : files) {
            classes.add(Files.readAllBytes(file));
        }
        if (selection != null) {
            runWorker(input, output, files, classes, classPath, selection, state, times, showProgress, workerId);
            return;
        }
        progress(showProgress, "scanning " + files.size() + " classes");
        long totalBytes = 0;
        for (byte[] bytes : classes) totalBytes += bytes.length;
        if (workers == 0) workers = Math.min(8, Math.min(Runtime.getRuntime().availableProcessors(),
                Math.max(1, files.size() / 8)));
        workers = Math.min(workers, files.size());
        boolean parallel = workers > 1 && (explicitWorkers || (files.size() >= 32 && totalBytes >= 262_144));
        long parallelStart = System.nanoTime();
        List<byte[]> transformed = new ArrayList<>();
        List<Long> nanos = new ArrayList<>();
        long scanNanos;
        if (parallel) {
            ConstExprTransformer.clear();
            ParallelResult result = runParallel(input, files, classes, classPath, workers, showProgress);
            transformed = result.classes;
            nanos = result.nanos;
            scanNanos = result.scanNanos;
        } else {
        long scanStart = System.nanoTime();
        ConstExprTransformer.preScan(classes, classPath, booleanProperty("xh.xianhhh.constexpr.checkLegit", true));
        scanNanos = System.nanoTime() - scanStart;
        }
        try {
            if (!parallel) {
                ConstExprTransformer transformer = new ConstExprTransformer(showProgress);
                for (int index = 0; index < files.size(); index++) {
                    if (showProgress) progress(true, "processing " + (index + 1) + "/" + files.size()
                            + " " + relativeName(input, files.get(index)));
                    long start = System.nanoTime();
                    byte[] result = transformer.transform(classes.get(index));
                    transformed.add(result);
                    nanos.add(System.nanoTime() - start);
                    printDetails(showProgress, "", transformer.progressDetails());
                    reportRemovedMembers(showProgress, classes.get(index), result);
                }
            }
            long pruneStart = System.nanoTime();
            progress(showProgress, "pruning unused methods");
            List<byte[]> beforePruning = transformed;
            transformed = ConstExprTransformer.pruneUnusedMethods(transformed);
            transformed = ConstExprTransformer.verifyAndRepair(transformed);
            List<byte[]> beforeCompaction = transformed;
            transformed = new ArrayList<byte[]>(beforeCompaction.size());
            for (int index = 0; index < beforeCompaction.size(); index++) {
                byte[] original = beforeCompaction.get(index);
                byte[] compacted = ConstExprConstantPool.shrink(original);
                transformed.add(compacted);
                if (compacted != original)
                    progress(showProgress, "shrink constant pool " + relativeName(input, files.get(index))
                            + " " + ConstExprConstantPool.count(original) + "->"
                            + ConstExprConstantPool.count(compacted) + " entries, "
                            + original.length + "->" + compacted.length + " bytes");
            }
            if (showProgress)
                for (int index = 0; index < transformed.size(); index++)
                    reportRemovedMembers(true, beforePruning.get(index), transformed.get(index));
            long pruneNanos = System.nanoTime() - pruneStart;
            if (report != null) {
                StringBuilder text = new StringBuilder("class\tinputBytes\toutputBytes\ttransformNanos\tbeforeMethods\tafterMethods\tbeforeCalls\tafterCalls\tchangedMethods\tpoolBefore\tpoolAfter\n");
                long inputBytes = 0, outputBytes = 0, transformNanos = 0;
                for (int index = 0; index < files.size(); index++) {
                    text.append(input.relativize(files.get(index)).toString().replace('\\', '/')).append('\t')
                            .append(classes.get(index).length).append('\t').append(transformed.get(index).length)
                            .append('\t').append(nanos.get(index));
                    int[] before = counts(classes.get(index));
                    int[] after = counts(transformed.get(index));
                    text.append('\t').append(before[0]).append('\t').append(after[0])
                            .append('\t').append(before[1]).append('\t').append(after[1])
                            .append('\t').append(methodChanges(classes.get(index), transformed.get(index)))
                            .append('\t').append(ConstExprConstantPool.count(classes.get(index)))
                            .append('\t').append(ConstExprConstantPool.count(transformed.get(index))).append('\n');
                    inputBytes += classes.get(index).length;
                    outputBytes += transformed.get(index).length;
                    transformNanos += nanos.get(index);
                }
                text.append("TOTAL\t").append(inputBytes).append('\t').append(outputBytes).append('\t')
                        .append(transformNanos).append("\nscanNanos\t").append(scanNanos)
                        .append("\npruneNanos\t").append(pruneNanos)
                        .append("\nworkers\t").append(parallel ? workers : 1)
                        .append("\nwallNanos\t").append(System.nanoTime() - parallelStart).append('\n');
                if (report.getParent() != null) Files.createDirectories(report.getParent());
                Files.write(report, text.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!dryRun) {
                progress(showProgress, "writing " + transformed.size() + " classes");
                if (!normalizedInput.equals(normalizedOutput) && Files.exists(output)) {
                    Set<Path> expected = new HashSet<>();
                    for (Path file : files) expected.add(input.relativize(file));
                    try (Stream<Path> existing = Files.walk(output)) {
                        for (Path file : existing.filter(path -> path.toString().endsWith(".class"))
                                .collect(Collectors.toList()))
                            if (!expected.contains(output.relativize(file))) Files.delete(file);
                    }
                }
                for (int index = 0; index < files.size(); index++) {
                    Path target = output.resolve(input.relativize(files.get(index)));
                    Files.createDirectories(target.getParent());
                    Files.write(target, transformed.get(index));
                }
            }
            progress(showProgress, dryRun ? "dry run complete" : "complete");
        } finally {
            ConstExprTransformer.clear();
        }
    }

    private static int parseWorkers(String raw) {
        try {
            int workers = Integer.parseInt(raw);
            if (workers >= 0 && workers <= 256) return workers;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("--workers must be between 0 and 256: " + raw);
    }

    private static void progress(boolean enabled, String message) {
        if (enabled) System.out.println("[ConstExpr] " + message);
    }

    private static String relativeName(Path input, Path file) {
        return input.relativize(file).toString().replace('\\', '/');
    }

    private static void printDetails(boolean enabled, String prefix, List<String> details) {
        if (enabled) for (String detail : details) progress(true, prefix + detail);
    }

    private static void reportRemovedMembers(boolean enabled, byte[] before, byte[] after) {
        if (!enabled) return;
        ClassNode original = new ClassNode(), result = new ClassNode();
        new ClassReader(before).accept(original, ClassReader.SKIP_FRAMES);
        new ClassReader(after).accept(result, ClassReader.SKIP_FRAMES);
        Set<String> methods = new HashSet<String>();
        Set<String> fields = new HashSet<String>();
        for (MethodNode method : result.methods) methods.add(method.name + method.desc);
        for (FieldNode field : result.fields) fields.add(field.name + field.desc);
        for (MethodNode method : original.methods)
            if (!methods.contains(method.name + method.desc))
                progress(true, "remove method " + original.name + "." + method.name + method.desc);
        for (FieldNode field : original.fields)
            if (!fields.contains(field.name + field.desc))
                progress(true, "remove field " + original.name + "." + field.name + field.desc);
    }

    private static void runWorker(Path input, Path output, List<Path> files, List<byte[]> classes,
                                  List<Path> classPath, Path selection, Path state, Path times,
                                  boolean showProgress, int workerId) throws Exception {
        if (state == null || times == null) throw new IllegalArgumentException("Worker metadata paths are required");
        Set<String> selected = new HashSet<>(Files.readAllLines(selection, StandardCharsets.UTF_8));
        progress(showProgress, "worker-" + workerId + " scanning " + files.size() + " classes");
        long scanStart = System.nanoTime();
        ConstExprTransformer.preScan(classes, classPath, booleanProperty("xh.xianhhh.constexpr.checkLegit", true));
        long scanNanos = System.nanoTime() - scanStart;
        try {
            ConstExprTransformer transformer = new ConstExprTransformer(showProgress);
            StringBuilder timings = new StringBuilder().append(scanNanos).append('\n');
            for (int index = 0; index < files.size(); index++) {
                String relative = input.relativize(files.get(index)).toString().replace('\\', '/');
                if (!selected.contains(relative)) continue;
                if (showProgress) progress(true, "worker-" + workerId + " processing " + relative);
                long start = System.nanoTime();
                byte[] transformed = transformer.transform(classes.get(index));
                long nanos = System.nanoTime() - start;
                Path destination = output.resolve(input.relativize(files.get(index)));
                Files.createDirectories(destination.getParent());
                Files.write(destination, transformed);
                printDetails(showProgress, "worker-" + workerId + " ", transformer.progressDetails());
                reportRemovedMembers(showProgress, classes.get(index), transformed);
                timings.append(relative).append('\t').append(nanos).append('\n');
            }
            ConstExprTransformer.writePruningState(state);
            Files.write(times, timings.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            ConstExprTransformer.clear();
        }
    }

    private static ParallelResult runParallel(Path input, List<Path> files, List<byte[]> classes,
                                              List<Path> classPath, int workers, boolean showProgress) throws Exception {
        Path temporary = Files.createTempDirectory("constexpr-workers-");
        List<Process> processes = new ArrayList<>();
        try {
            return runParallelInDirectory(input, files, classes, classPath, workers, temporary, processes, showProgress);
        } finally {
            for (Process process : processes) {
                if (process.isAlive()) process.destroyForcibly();
            }
            boolean interrupted = false;
            for (Process process : processes) {
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            try (Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(path);
            }
        }
    }

    private static ParallelResult runParallelInDirectory(Path input, List<Path> files, List<byte[]> classes,
                                                         List<Path> classPath, int workers, Path temporary,
                                                         List<Process> processes, boolean showProgress) throws Exception {
        List<List<Integer>> assignments = new ArrayList<>();
        long[] sizes = new long[workers];
        for (int worker = 0; worker < workers; worker++) assignments.add(new ArrayList<Integer>());
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) order.add(index);
        order.sort((left, right) -> Integer.compare(classes.get(right).length, classes.get(left).length));
        for (int index : order) {
            int smallest = 0;
            for (int worker = 1; worker < workers; worker++)
                if (sizes[worker] < sizes[smallest]) smallest = worker;
            assignments.get(smallest).add(index);
            sizes[smallest] += classes.get(index).length;
        }
        List<Path> logs = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            Path directory = temporary.resolve("worker-" + worker);
            Files.createDirectories(directory);
            List<String> names = new ArrayList<>();
            for (int index : assignments.get(worker))
                names.add(input.relativize(files.get(index)).toString().replace('\\', '/'));
            Path selection = directory.resolve("selection.txt");
            Files.write(selection, names, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>();
            command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            for (String property : new String[] {"checkLegit", "mode", "maxArrayElements",
                    "stepsPerArrayElement", "maxStepLimit", "baseStepLimit", "maxCallDepth", "shareTemplateArray",
                    "preserveReflectiveMembers"}) {
                String value = System.getProperty("xh.xianhhh.constexpr." + property);
                if (value != null) command.add("-Dxh.xianhhh.constexpr." + property + "=" + value);
            }
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(ConstExprBuildProcessor.class.getName());
            command.add(input.toString());
            command.add(directory.resolve("classes").toString());
            command.add("--selection=" + selection);
            command.add("--state=" + directory.resolve("state.bin"));
            command.add("--times=" + directory.resolve("times.tsv"));
            if (showProgress) {
                command.add("--progress");
                command.add("--progress-worker=" + worker);
            }
            for (Path entry : classPath) command.add(entry.toString());
            Path log = directory.resolve("worker.log");
            logs.add(log);
            ProcessBuilder builder = new ProcessBuilder(command);
            if (showProgress) builder.redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(log.toFile());
            else builder.redirectErrorStream(true).redirectOutput(log.toFile());
            processes.add(builder.start());
        }
        StringBuilder failures = new StringBuilder();
        for (int worker = 0; worker < processes.size(); worker++) {
            int exit = processes.get(worker).waitFor();
            if (exit != 0) failures.append("worker ").append(worker).append(" exited ").append(exit)
                    .append(':').append(System.lineSeparator())
                    .append(new String(Files.readAllBytes(logs.get(worker)), StandardCharsets.UTF_8));
        }
        if (failures.length() != 0) throw new ConstExprException(failures.toString());
        List<byte[]> transformed = new ArrayList<>(Collections.nCopies(files.size(), null));
        List<Long> nanos = new ArrayList<>(Collections.nCopies(files.size(), 0L));
        long scanNanos = 0;
        for (int worker = 0; worker < workers; worker++) {
            Path directory = temporary.resolve("worker-" + worker);
            List<String> timings = Files.readAllLines(directory.resolve("times.tsv"), StandardCharsets.UTF_8);
            scanNanos += Long.parseLong(timings.get(0));
            Map<String, Long> byName = new HashMap<>();
            for (int line = 1; line < timings.size(); line++) {
                String[] columns = timings.get(line).split("\t", 2);
                byName.put(columns[0], Long.parseLong(columns[1]));
            }
            for (int index : assignments.get(worker)) {
                Path relative = input.relativize(files.get(index));
                transformed.set(index, Files.readAllBytes(directory.resolve("classes").resolve(relative)));
                nanos.set(index, byName.get(relative.toString().replace('\\', '/')));
            }
            ConstExprTransformer.readPruningState(directory.resolve("state.bin"));
        }
        return new ParallelResult(transformed, nanos, scanNanos);
    }

    private static final class ParallelResult {
        final List<byte[]> classes;
        final List<Long> nanos;
        final long scanNanos;
        ParallelResult(List<byte[]> classes, List<Long> nanos, long scanNanos) {
            this.classes = classes;
            this.nanos = nanos;
            this.scanNanos = scanNanos;
        }
    }

    private static boolean booleanProperty(String name, boolean defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) return defaultValue;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new ConstExprException("System property " + name + " must be true or false: " + value);
    }

    private static int[] counts(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        int methods = 0, calls = 0;
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("$constexpr$")) continue;
            methods++;
            for (AbstractInsnNode instruction : method.instructions)
                if (instruction instanceof MethodInsnNode) calls++;
        }
        return new int[] {methods, calls};
    }

    private static String methodChanges(byte[] before, byte[] after) {
        ClassNode original = new ClassNode(), transformed = new ClassNode();
        new ClassReader(before).accept(original, ClassReader.SKIP_FRAMES);
        new ClassReader(after).accept(transformed, ClassReader.SKIP_FRAMES);
        Map<String, Integer> resultingCalls = new HashMap<>();
        for (MethodNode method : transformed.methods)
            if (!method.name.startsWith("$constexpr$"))
                resultingCalls.put(method.name + method.desc, countCalls(method));
        StringJoiner changes = new StringJoiner(",");
        for (MethodNode method : original.methods) {
            if (method.name.startsWith("$constexpr$")) continue;
            Integer current = resultingCalls.get(method.name + method.desc);
            int previous = countCalls(method);
            if (current == null) changes.add(method.name + method.desc + ":removed");
            else if (previous != current) changes.add(method.name + method.desc + ":" + previous + "->" + current);
        }
        return changes.toString();
    }

    private static int countCalls(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof MethodInsnNode) count++;
        return count;
    }
}
