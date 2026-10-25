package xh.xianhhh.asm;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evaluates simple, side-effect-screened JDK static calls embedded in annotation expressions. */
final class ConstExprStaticExpression {
    private static final Pattern CALL = Pattern.compile("([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)\\(([^()]*)\\)");

    private ConstExprStaticExpression() { }

    static Object evaluate(String source) {
        Matcher matcher = CALL.matcher(source.trim());
        return matcher.matches() ? invoke(matcher.group(1), matcher.group(2)) : null;
    }

    static String replaceCalls(String source) {
        Matcher matcher = CALL.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = invoke(matcher.group(1), matcher.group(2));
            if (!(value instanceof Number || value instanceof Boolean || value instanceof String)) {
                throw new ConstExprException("Unsupported static expression return type: " + value.getClass().getName());
            }
            String replacement = value instanceof String ? '"' + (String) value + '"' : String.valueOf(value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Object invoke(String target, String rawArguments) {
        int separator = target.lastIndexOf('.');
        String className = target.substring(0, separator);
        String methodName = target.substring(separator + 1);
        try {
            Class<?> type = resolveClass(className);
            if (!type.getName().startsWith("java.")) {
                throw new ConstExprException("Static expressions only allow JDK classes: " + type.getName());
            }
            if (dangerousName(methodName)) throw new ConstExprException("Static expression call is not allowed: " + target);
            List<String> arguments = splitArguments(rawArguments);
            for (Method method : type.getMethods()) {
                if (!method.getName().equals(methodName) || !Modifier.isStatic(method.getModifiers())
                        || !Modifier.isPublic(method.getModifiers()) || method.getParameterCount() != arguments.size()
                        || !supported(method.getReturnType())) continue;
                Object[] values = convert(arguments, method.getParameterTypes());
                if (values == null) continue;
                return method.invoke(null, values);
            }
            throw new ConstExprException("No applicable method found for static expression: " + target);
        } catch (ReflectiveOperationException error) {
            throw new ConstExprException("Cannot execute static expression: " + target, error);
        }
    }

    private static Class<?> resolveClass(String name) throws ClassNotFoundException {
        if (!name.contains(".")) return Class.forName("java.lang." + name);
        return Class.forName(name);
    }

    private static Object[] convert(List<String> values, Class<?>[] types) {
        Object[] result = new Object[values.size()];
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index).trim();
            Class<?> type = types[index];
            try {
                if (type == String.class && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    result[index] = value.substring(1, value.length() - 1);
                } else if (type == boolean.class && ("true".equals(value) || "false".equals(value))) {
                    result[index] = Boolean.parseBoolean(value);
                } else if (type == int.class) result[index] = Math.toIntExact(ConstExprTransformer.NumericExpression.integral(value));
                else if (type == long.class) result[index] = ConstExprTransformer.NumericExpression.integral(value);
                else if (type == float.class) result[index] = (float) ConstExprTransformer.NumericExpression.floating(value);
                else if (type == double.class) result[index] = ConstExprTransformer.NumericExpression.floating(value);
                else return null;
            } catch (RuntimeException error) {
                return null;
            }
        }
        return result;
    }

    private static List<String> splitArguments(String value) {
        if (value.trim().isEmpty()) return java.util.Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String argument : value.split(",", -1)) result.add(argument);
        return result;
    }

    private static boolean supported(Class<?> type) {
        return type == String.class || type == boolean.class || type == int.class || type == long.class
                || type == float.class || type == double.class;
    }

    private static boolean dangerousName(String name) {
        return name.equals("exit") || name.equals("gc") || name.equals("runFinalization")
                || name.startsWith("set") || name.startsWith("clear") || name.startsWith("load");
    }
}
