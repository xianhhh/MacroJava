package xh.xianhhh.asm;

import java.io.Closeable;
import java.lang.reflect.Modifier;
import java.net.Socket;
import java.nio.channels.Channel;

/** Centralized policy for values that must not be materialized as compile-time objects. */
final class ConstExprBlackList {
    private ConstExprBlackList() { }

    static boolean isUnsafe(Object value) {
        if (value == null) return false;
        if (value instanceof Thread) return true;
        if (value instanceof Closeable) return true;
        if (value instanceof Channel) return true;
        if (value instanceof Socket) return true;
        if (value instanceof Class<?>) return true;
        Class<?> type = value.getClass();
        String name = type.getName();
        return isUnsafeClassName(name) || Modifier.isNative(type.getModifiers());
    }

    static boolean isUnsafeClassName(String name) {
        if (name == null) return false;
        if (name.startsWith("java.lang.reflect.")) return true;
        if (name.contains("Socket") || name.contains("Channel")) return true;
        if (name.equals("java.lang.Thread") || name.startsWith("java.lang.Thread$")
                || name.equals("java.lang.Class") || name.startsWith("java.io.FileInputStream")
                || name.startsWith("java.io.FileOutputStream")) return true;
        return false;
    }
}
