package xh.xianhhh.macro;

/** Direct jump markers rewritten after compilation. */
public final class JUMP {
    private JUMP() {}

    public static void label(String name) {
        throw new IllegalStateException("JUMP requires the macroJava bytecode transformation: " + name);
    }

    public static void goTo(String name) {
        throw new IllegalStateException("JUMP requires the macroJava bytecode transformation: " + name);
    }
}
