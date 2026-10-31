package xh.xianhhh.macro;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Passes the caller's primitive value into a method and writes its final value back. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Ref { }
