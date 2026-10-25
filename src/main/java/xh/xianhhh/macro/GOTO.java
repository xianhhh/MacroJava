package xh.xianhhh.macro;

/** Checked, C-style label and jump markers rewritten after compilation. */
public final class GOTO {
    private GOTO() {}

    public static void label(String name) {
        throw new IllegalStateException("GOTO requires the macroJava bytecode transformation: " + name);
    }

    public static void goTo(String name) {
        throw new IllegalStateException("GOTO requires the macroJava bytecode transformation: " + name);
    }
}
