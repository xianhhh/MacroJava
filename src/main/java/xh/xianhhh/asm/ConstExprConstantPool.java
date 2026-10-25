package xh.xianhhh.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.Collections;

final class ConstExprConstantPool {
    private ConstExprConstantPool() { }

    static byte[] shrink(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] compacted = writer.toByteArray();
        if (compacted.length >= original.length || count(compacted) >= count(original)) return original;
        byte[] verified = ConstExprTransformer.verifyAndRepair(Collections.singletonList(compacted)).get(0);
        return verified.length < original.length && count(verified) < count(original) ? verified : original;
    }

    static int count(byte[] bytes) {
        return new ClassReader(bytes).getItemCount() - 1;
    }
}
