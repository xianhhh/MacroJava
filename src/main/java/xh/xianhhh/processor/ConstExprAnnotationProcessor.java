package xh.xianhhh.processor;

import xh.xianhhh.macro.ConstExpr;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SupportedAnnotationTypes("xh.xianhhh.macro.ConstExpr")
@SupportedOptions("xh.xianhhh.constexpr.checkLegit")
public final class ConstExprAnnotationProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element element : round.getElementsAnnotatedWith(ConstExpr.class)) {
            if (element.getKind() == ElementKind.FIELD) {
                if (!element.getModifiers().contains(Modifier.STATIC)) error(element, "@ConstExpr fields must be static.");
                validateValue(element, element.asType(), true, element.getModifiers().contains(Modifier.FINAL));
            } else if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) element;
                if (!element.getModifiers().contains(Modifier.STATIC)) error(element, "@ConstExpr methods must be static.");
                validateValue(element, method.getReturnType(), false, false);
                if (strictChecks() && !method.getParameters().isEmpty() && hasAnnotationValue(element)) {
                    error(element, "@ConstExpr methods with annotation values must have no parameters; parameterized methods must compute their result in the method body.");
                }
            } else if (element.getKind() == ElementKind.CLASS
                    || element.getKind() == ElementKind.INTERFACE
                    || element.getKind() == ElementKind.ENUM) {
                // Class-level @ConstExpr is a method selection directive. Bytecode
                // transformation performs the actual eligibility and evaluation.
            } else if (element.getKind() == ElementKind.LOCAL_VARIABLE) {
                if (hasAnnotationValue(element)) error(element, "@ConstExpr on a local variable is only an optimization marker and cannot specify a value.");
            } else {
                error(element, "@ConstExpr can only annotate classes, fields, methods, or local variables.");
            }
        }
        return false;
    }

    private void validateValue(Element element, TypeMirror type, boolean field, boolean finalField) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values = annotationValues(element);
        boolean hasValue = !string(values, "value").isEmpty();
        boolean hasValues = !list(values, "values").isEmpty();
        List<? extends AnnotationValue> shape = list(values, "shape");
        boolean hasBuild = !string(values, "build").isEmpty();
        TypeMirror classValue = type(values, "classValue");
        TypeMirror enumType = type(values, "enumType");
        boolean hasClass = classValue != null && !"java.lang.Void".equals(classValue.toString());
        boolean hasEnum = enumType != null && !"java.lang.Enum".equals(enumType.toString());
        boolean hasNull = Boolean.TRUE.equals(value(values, "nullValue"));
        // An enum constant is described by both enumType and value; together they are one source.
        int sources = (hasValue && !hasEnum ? 1 : 0) + (hasValues ? 1 : 0) + (hasBuild ? 1 : 0)
                + (hasClass ? 1 : 0) + (hasEnum ? 1 : 0) + (hasNull ? 1 : 0);
        if (sources == 0 && field && shape.isEmpty() && !finalField) error(element, "@ConstExpr fields require value, values, build, classValue, enumType, or nullValue.");
        if (sources > 1) error(element, "@ConstExpr cannot specify multiple value sources.");
        if (hasEnum && !hasValue) error(element, "Enum constants require a name in value.");
        if (type.getKind() == TypeKind.VOID) {
            if (sources != 0) error(element, "void @ConstExpr methods cannot specify a return value source.");
            return;
        }

        if (type.getKind() == TypeKind.ARRAY) {
            TypeMirror component = ((ArrayType) type).getComponentType();
            int dimensions = 1;
            while (component.getKind() == TypeKind.ARRAY) {
                dimensions++;
                component = ((ArrayType) component).getComponentType();
            }
            if (field && dimensions > 1 && shape.isEmpty()) error(element, "Multidimensional @ConstExpr array fields require shape.");
            if (!shape.isEmpty() && shape.size() != dimensions) error(element, "The number of shape dimensions must match the array type.");
            for (AnnotationValue dimension : shape) {
                if (!(dimension.getValue() instanceof Integer) || ((Integer) dimension.getValue()) <= 0) error(element, "Each shape dimension must be a positive integer.");
            }
            if (component.getKind() == TypeKind.VOID) error(element, "Array elements cannot be void.");
            if (field && !hasValues && shape.isEmpty()) error(element, "@ConstExpr arrays require elements in values or shape for multidimensional arrays.");
            return;
        }

        if (strictChecks() && !hasNull && !supportedScalar(type)) error(element, "Unsupported @ConstExpr type: " + type + ".");
        if (hasValues) error(element, "values is only valid for @ConstExpr array fields or methods.");
        if (hasBuild && type.getKind() != TypeKind.BOOLEAN) error(element, "build currently supports only boolean types.");
        if (hasNull && type.getKind().isPrimitive()) error(element, "nullValue requires a reference type.");
        if (hasClass && !isDeclaredType(type, "java.lang.Class")) error(element, "classValue requires a Class type.");
        if (hasEnum && (!(processingEnv.getTypeUtils().asElement(enumType) instanceof TypeElement)
                || ((TypeElement) processingEnv.getTypeUtils().asElement(enumType)).getKind() != ElementKind.ENUM)) {
            error(element, "enumType must reference an enum type.");
        }
        if (hasEnum && !processingEnv.getTypeUtils().isSameType(type, enumType)) {
            error(element, "enumType must match the field or method return type.");
        }
    }

    private Map<? extends ExecutableElement, ? extends AnnotationValue> annotationValues(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName()
                    .contentEquals(ConstExpr.class.getName())) {
                return processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
            }
        }
        throw new IllegalStateException("Missing @ConstExpr annotation");
    }

    private boolean hasAnnotationValue(Element element) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values = annotationValues(element);
        return !string(values, "value").isEmpty() || !list(values, "values").isEmpty()
                || !list(values, "shape").isEmpty() || !string(values, "build").isEmpty()
                || (type(values, "classValue") != null && !"java.lang.Void".equals(type(values, "classValue").toString()))
                || (type(values, "enumType") != null && !"java.lang.Enum".equals(type(values, "enumType").toString()))
                || Boolean.TRUE.equals(value(values, "nullValue"));
    }

    private boolean strictChecks() {
        String value = processingEnv.getOptions().get("xh.xianhhh.constexpr.checkLegit");
        return value == null || Boolean.parseBoolean(value);
    }

    private static Object value(Map<? extends ExecutableElement, ? extends AnnotationValue> values, String name) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) if (entry.getKey().getSimpleName().contentEquals(name)) return entry.getValue().getValue();
        return null;
    }

    private static String string(Map<? extends ExecutableElement, ? extends AnnotationValue> values, String name) {
        Object value = value(values, name); return value instanceof String ? (String) value : "";
    }

    @SuppressWarnings("unchecked")
    private static List<? extends AnnotationValue> list(Map<? extends ExecutableElement, ? extends AnnotationValue> values, String name) {
        Object value = value(values, name); return value instanceof List<?> ? (List<? extends AnnotationValue>) value : java.util.Collections.<AnnotationValue>emptyList();
    }

    private static TypeMirror type(Map<? extends ExecutableElement, ? extends AnnotationValue> values, String name) {
        Object value = value(values, name); return value instanceof TypeMirror ? (TypeMirror) value : null;
    }

    private boolean supportedScalar(TypeMirror type) {
        if (type.getKind().isPrimitive()) return true;
        if (isDeclaredType(type, "java.lang.String") || isDeclaredType(type, "java.lang.Class")) return true;
        // Methods without an explicit literal may return arbitrary declared objects;
        // bytecode interpretation and materialization validate the concrete type later.
        if (type.getKind() == TypeKind.DECLARED) return true;
        return processingEnv.getTypeUtils().asElement(type) instanceof TypeElement
                && ((TypeElement) processingEnv.getTypeUtils().asElement(type)).getKind() == ElementKind.ENUM;
    }

    private boolean isDeclaredType(TypeMirror type, String qualifiedName) {
        return processingEnv.getTypeUtils().asElement(type) instanceof TypeElement
                && ((TypeElement) processingEnv.getTypeUtils().asElement(type)).getQualifiedName().contentEquals(qualifiedName);
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
