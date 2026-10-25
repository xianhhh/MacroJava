package xh.xianhhh.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** Lazily reads dependency bytecode without loading or executing dependency classes. */
final class ConstExprClassPath {
    private final List<Path> entries;
    private final Map<String, ClassNode> cache = new HashMap<>();

    ConstExprClassPath(List<Path> entries) {
        this.entries = java.util.Collections.unmodifiableList(new java.util.ArrayList<Path>(entries));
    }

    List<Path> entries() {
        return entries;
    }

    ClassNode resolve(String internalName) {
        if (cache.containsKey(internalName)) return cache.get(internalName);
        String name = internalName + ".class";
        for (Path entry : entries) {
            byte[] bytes = read(entry, name);
            if (bytes == null) continue;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
            cache.put(internalName, node);
            return node;
        }
        cache.put(internalName, null);
        return null;
    }

    private static byte[] read(Path entry, String name) {
        try {
            if (Files.isDirectory(entry)) {
                Path candidate = entry.resolve(name);
                return Files.exists(candidate) ? Files.readAllBytes(candidate) : null;
            }
            if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                try (JarFile archive = new JarFile(entry.toFile())) {
                    java.util.zip.ZipEntry candidate = null;
                    if (archive.getManifest() != null && "true".equalsIgnoreCase(
                            archive.getManifest().getMainAttributes().getValue("Multi-Release"))) {
                        for (int version = runtimeFeature(); version >= 9; version--) {
                            candidate = archive.getEntry("META-INF/versions/" + version + "/" + name);
                            if (candidate != null) break;
                        }
                    }
                    if (candidate == null) candidate = archive.getEntry(name);
                    if (candidate == null) return null;
                    InputStream input = archive.getInputStream(candidate);
                    try {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        return output.toByteArray();
                    } finally {
                        input.close();
                    }
                }
            }
        } catch (IOException error) {
            throw new ConstExprException("Cannot read dependency class: " + entry + " (" + name + ")", error);
        }
        return null;
    }

    private static int runtimeFeature() {
        String version = System.getProperty("java.specification.version", "8");
        if (version.startsWith("1.")) version = version.substring(2);
        int dot = version.indexOf('.');
        if (dot >= 0) version = version.substring(0, dot);
        try { return Integer.parseInt(version); }
        catch (NumberFormatException ignored) { return 8; }
    }
}
