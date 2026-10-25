package xh.xianhhh.macro;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for compile-time expression evaluation.
 *
 * <p>On fields, {@link #value()} is parsed as a constant of the field type;
 * arrays use {@link #values()}. Calls to annotated static methods are evaluated
 * and inlined. Parameterized methods must compute their result in the method body,
 * while methods with annotation values must have no parameters. Annotated fields
 * must be uninitialized {@code static} fields. Processing normally removes these
 * fields; when reflection is detected or member preservation is enabled, it keeps
 * and initializes them.</p>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})
public @interface ConstExpr {
    /**
     * Compile-time literal for a scalar field or parameterless method.
     * @return the literal, or an empty string when unspecified
     */
    String value() default "";

    /**
     * Element literals for a one-dimensional array field or parameterless method.
     * @return the element literals
     */
    String[] values() default {};

    /**
     * Array dimensions for multidimensional constants, e.g. {2, 2}.
     * @return the array dimensions
     */
    int[] shape() default {};

    /**
     * Build-time boolean expression; takes precedence over value.
     * @return the expression, or an empty string when unspecified
     */
    String build() default "";

    /**
     * Class constant for a field or method result; the default means unspecified.
     * @return the class value
     */
    Class<?> classValue() default Void.class;

    /**
     * Enum type for a constant whose name is specified by {@link #value()}.
     * @return the enum type
     */
    Class<?> enumType() default Enum.class;

    /**
     * Inline a reference field or method call as {@code null}.
     * @return whether to inline {@code null}
     */
    boolean nullValue() default false;
}
