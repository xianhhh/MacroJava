package xh.xianhhh.asm;

import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Compiles and executes an annotation expression when strict validation is explicitly disabled. */
final class UnsafeConstExprExpression {
    private static final Pattern NEW_SIMPLE_TYPE = Pattern.compile("\\bnew\\s+([A-Z][A-Za-z0-9_$]*)\\b");
    private static Context context;
    private static int expressionIndex;
    private UnsafeConstExprExpression() { }

    static ConstExprValue evaluate(String descriptor, String source, List<ConstExprValue> arguments,
                                   MethodNode method, ConstExprClassPath classPath) {
        Type returnType = Type.getType(descriptor);
        Type[] argumentTypes = method == null ? new Type[0] : Type.getArgumentTypes(method.desc);
        if (argumentTypes.length != arguments.size()) throw new ConstExprException("@ConstExpr expression argument count does not match");
        Path directory = null;
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new ConstExprException("checkLegit=false requires a JDK compiler");
            Context active = context(classPath);
            directory = active.directory;
            String className = "ConstExprUnsafeExpression" + expressionIndex++;
            Path sourceFile = directory.resolve(className + ".java");
            List<Binding> bindings = bindings();
            Files.write(sourceFile, source(className, returnType, argumentTypes, method, source,
                    importsForSimpleNewTypes(source, classPath), bindings).getBytes("UTF-8"));
            List<String> options = new ArrayList<String>(java.util.Arrays.asList("-d", directory.toString()));
            if (classPath != null && !classPath.entries().isEmpty()) {
                options.add("-classpath");
                options.add(classPath.entries().stream().map(Path::toString)
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)));
            }
            if (!Boolean.TRUE.equals(compiler.getTask(null, null, null, options, null,
                    compiler.getStandardFileManager(null, null, null).getJavaFileObjects(sourceFile.toFile())).call())) {
                throw new ConstExprException("checkLegit=false expression cannot be compiled as Java: " + source);
            }
            {
                Class<?> expression = Class.forName(className, true, active.loader);
                Class<?>[] types = new Class<?>[argumentTypes.length];
                for (int index = 0; index < types.length; index++) types[index] = typeClass(argumentTypes[index], active.loader);
                Class<?>[] evaluatorTypes = new Class<?>[types.length + 1]; evaluatorTypes[0] = Object[].class;
                System.arraycopy(types, 0, evaluatorTypes, 1, types.length);
                Method evaluator = expression.getMethod("evaluate", evaluatorTypes);
                Object[] values = arguments.stream().map(value -> value.value).toArray();
                Object[] invocation = new Object[values.length + 1];
                invocation[0] = bindings.stream().map(binding -> runtimeValue(binding.value.value)).toArray();
                System.arraycopy(values, 0, invocation, 1, values.length);
                return value(descriptor, evaluator.invoke(null, invocation));
            }
        } catch (ConstExprException error) {
            throw error;
        } catch (Exception error) {
            throw new ConstExprException("checkLegit=false expression execution failed: " + source, error);
        } finally { }
    }

    private static String source(String className, Type result, Type[] arguments, MethodNode method, String expression,
                                 String imports, List<Binding> bindings) {
        StringBuilder parameters = new StringBuilder();
        for (int index = 0; index < arguments.length; index++) {
            if (index > 0) parameters.append(", ");
            String name = parameterName(method, arguments, index);
            parameters.append(arguments[index].getClassName()).append(' ').append(name);
        }
        StringBuilder locals = new StringBuilder();
        for (int index = 0; index < bindings.size(); index++) locals.append(bindings.get(index).type.getClassName())
                .append(' ').append(bindings.get(index).name).append(" = (").append(bindings.get(index).type.getClassName())
                .append(") bindings[").append(index).append("]; ");
        return imports + "public final class " + className + " { public static " + result.getClassName()
                + " evaluate(Object[] bindings" + (parameters.length() == 0 ? "" : ", " + parameters)
                + ") { " + locals + "return " + expression + "; } }";
    }

    static void setFields(java.util.Map<String, ConstExprValue> fields) { FIELDS = fields; }
    private static java.util.Map<String, ConstExprValue> FIELDS = java.util.Collections.emptyMap();
    private static List<Binding> bindings() {
        java.util.Map<String, Binding> result = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, ConstExprValue> entry : FIELDS.entrySet()) {
            String key = entry.getKey(); int first = key.indexOf(':'); int second = key.indexOf(':', first + 1);
            if (first < 0 || second < 0 || entry.getValue() instanceof ConstExprArrayValue) continue;
            String name = key.substring(first + 1, second);
            if (name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) result.putIfAbsent(name, new Binding(name, Type.getType(entry.getValue().descriptor), entry.getValue()));
        }
        return new ArrayList<Binding>(result.values());
    }
    private static Context context(ConstExprClassPath classPath) throws Exception {
        if (context != null) return context;
        Path root = Files.createTempDirectory("constexpr-expression-"); List<URL> urls = new ArrayList<>(); urls.add(root.toUri().toURL());
        if (classPath != null) for (Path entry : classPath.entries()) urls.add(entry.toUri().toURL());
        return context = new Context(root, new URLClassLoader(urls.toArray(new URL[urls.size()]), ClassLoader.getSystemClassLoader()));
    }
    static void clear() { if (context != null) try { context.loader.close(); } catch (Exception ignored) { } context = null; expressionIndex = 0; FIELDS = java.util.Collections.emptyMap(); }
    private static final class Binding { final String name; final Type type; final ConstExprValue value; Binding(String n,Type t,ConstExprValue v){name=n;type=t;value=v;} String name(){return name;} Type type(){return type;} ConstExprValue value(){return value;} }
    private static final class Context { final Path directory; final URLClassLoader loader; Context(Path d,URLClassLoader l){directory=d;loader=l;} Path directory(){return directory;} URLClassLoader loader(){return loader;} }

    private static String importsForSimpleNewTypes(String expression, ConstExprClassPath classPath) {
        if (classPath == null) return "";
        StringBuilder imports = new StringBuilder();
        Matcher matcher = NEW_SIMPLE_TYPE.matcher(expression);
        while (matcher.find()) {
            String simpleName = matcher.group(1);
            try {
                Class.forName("java.util." + simpleName);
                imports.append("import java.util.").append(simpleName).append(";\n");
                continue;
            } catch (ClassNotFoundException ignored) { }
            List<String> matches = new ArrayList<>();
            for (Path entry : classPath.entries()) collectClasses(entry, simpleName, matches);
            if (matches.size() == 1) imports.append("import ").append(matches.get(0)).append(";\n");
            if (matches.size() > 1) throw new ConstExprException("checkLegit=false class name is ambiguous; use a fully qualified name: " + simpleName);
        }
        return imports.toString();
    }

    private static void collectClasses(Path entry, String simpleName, List<String> matches) {
        String suffix = "/" + simpleName + ".class";
        try {
            if (Files.isDirectory(entry)) {
                try (java.util.stream.Stream<Path> paths = Files.walk(entry)) {
                    paths.filter(path -> path.toString().endsWith(simpleName + ".class")).forEach(path -> {
                        String relative = entry.relativize(path).toString().replace('\\', '/');
                        if (relative.endsWith(suffix)) matches.add(relative.substring(0, relative.length() - 6).replace('/', '.'));
                    });
                }
            } else if (entry.toString().endsWith(".jar")) {
                try (ZipFile archive = new ZipFile(entry.toFile())) {
                    archive.stream().map(candidate -> candidate.getName()).filter(name -> name.endsWith(suffix))
                            .forEach(name -> matches.add(name.substring(0, name.length() - 6).replace('/', '.')));
                }
            }
        } catch (Exception error) {
            throw new ConstExprException("Cannot scan the classpath for checkLegit=false: " + entry, error);
        }
    }

    private static Class<?> typeClass(Type type, ClassLoader loader) throws ClassNotFoundException {
        switch (type.getSort()) {
            case Type.BOOLEAN: return boolean.class; case Type.BYTE: return byte.class; case Type.CHAR: return char.class;
            case Type.SHORT: return short.class; case Type.INT: return int.class; case Type.LONG: return long.class;
            case Type.FLOAT: return float.class; case Type.DOUBLE: return double.class;
            case Type.ARRAY: return Class.forName(type.getDescriptor().replace('/', '.'), true, loader);
            default: return Class.forName(type.getClassName(), true, loader);
        }
    }

    private static String parameterName(MethodNode method, Type[] types, int requested) {
        if (method != null && method.parameters != null && requested < method.parameters.size()) {
            String name = method.parameters.get(requested).name;
            if (name != null && !name.trim().isEmpty()) return name;
        }
        if (method != null && method.localVariables != null) {
            int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
            for (int index = 0; index < types.length; index++) {
                if (index == requested) {
                    for (LocalVariableNode variable : method.localVariables) {
                        if (variable.index == slot && variable.name != null && !"this".equals(variable.name)) return variable.name;
                    }
                    break;
                }
                slot += types[index].getSize();
            }
        }
        return "arg" + requested;
    }

    private static ConstExprValue value(String descriptor, Object result) {
        if (descriptor.startsWith("[")) {
            int length = Array.getLength(result);
            List<ConstExprValue> values = new ArrayList<>(length);
            String component = descriptor.substring(1);
            for (int index = 0; index < length; index++) values.add(value(component, Array.get(result, index)));
            return new ConstExprArrayValue(descriptor, values);
        }
        return new ConstExprValue(descriptor, result);
    }

    private static Object runtimeValue(Object value) {
        return value instanceof ConstExprHeapObject && ((ConstExprHeapObject) value).runtimeValue != null
                ? ((ConstExprHeapObject) value).runtimeValue : value;
    }
}
