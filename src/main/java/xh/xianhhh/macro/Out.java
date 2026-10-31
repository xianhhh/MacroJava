package xh.xianhhh.macro;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Requires assignment before use and on every normal return, then writes back to the caller local. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Out { }
