package xh.xianhhh.asm;

public final class ConstExprException extends RuntimeException {
    private final java.util.List<String> frames = new java.util.ArrayList<String>();
    private static final ThreadLocal<Integer> CURRENT_LINE = new ThreadLocal<Integer>();
    public ConstExprException(String message) {
        super(message);
    }

    public ConstExprException(String message, Throwable cause) {
        super(message, cause);
    }

    static void setCurrentLine(int line) { CURRENT_LINE.set(line); }

    ConstExprException withFrame(String owner, String method, String descriptor) {
        Integer line = CURRENT_LINE.get();
        String frame = owner.replace('/', '.') + "." + method + descriptor + (line == null || line.intValue() < 0 ? "" : ":" + line);
        if (frames.isEmpty() || !frame.equals(frames.get(0))) frames.add(0, frame);
        return this;
    }

    @Override
    public String getMessage() {
        String message = super.getMessage();
        if (frames.isEmpty()) return message;
        StringBuilder result = new StringBuilder(message == null ? "" : message);
        result.append("\nConstExpr stack trace:");
        for (String frame : frames) result.append("\n\tat ").append(frame);
        return result.toString();
    }
}
