package xh.xianhhh.asm;

import org.objectweb.asm.Handle;
import java.util.ArrayList;
import java.util.List;

/** A lambda closure represented by its implementation method and captured values. */
final class ConstExprLambda {
    final Handle implementation;
    final List<ConstExprValue> captured;

    ConstExprLambda(Handle implementation, List<ConstExprValue> captured) {
        this.implementation = implementation;
        this.captured = new ArrayList<ConstExprValue>(captured);
    }
}
