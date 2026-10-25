package xh.xianhhh.asm;

import java.util.ArrayList;
import java.util.List;

/** Compile-time array whose elements may themselves be compile-time arrays. */
final class ConstExprArrayValue extends ConstExprValue {
    private final List<ConstExprValue> elements;
    private final boolean mutable;

    ConstExprArrayValue(String descriptor, List<ConstExprValue> elements) {
        this(descriptor, elements, false);
    }

    private ConstExprArrayValue(String descriptor, List<ConstExprValue> elements, boolean mutable) {
        super(descriptor, null);
        this.elements = new ArrayList<>(elements);
        this.mutable = mutable;
    }

    static ConstExprArrayValue mutable(String descriptor, List<ConstExprValue> elements) {
        return new ConstExprArrayValue(descriptor, elements, true);
    }

    @Override boolean isArray() { return true; }
    int length() { return elements.size(); }
    ConstExprValue element(int index) { return elements.get(index); }
    void set(int index, ConstExprValue value) {
        if (!mutable) throw new ConstExprException("Cannot modify a compile-time array defined by an annotation");
        elements.set(index, value);
    }
}
