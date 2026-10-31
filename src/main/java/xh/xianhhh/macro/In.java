package xh.xianhhh.macro;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a primitive parameter that the method must not reassign. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface In { }
