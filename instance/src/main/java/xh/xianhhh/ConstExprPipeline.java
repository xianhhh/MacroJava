package xh.xianhhh;

import xh.xianhhh.macro.ConstExpr;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@ConstExpr
public final class ConstExprPipeline {

    // ==================================================================
    // Constants
    // ==================================================================

    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME  = 0x100000001B3L;

    @ConstExpr("256")
    private static int tableSize;

    @ConstExpr("2")
    private static int profile;

    @ConstExpr(values = {"5", "11", "17", "23"})
    private static int[] biases;

    private final int factor;

    private ConstExprPipeline(int factor) {
        this.factor = factor;
    }

    private int apply(int value) {
        return factor * value;
    }

    // ==================================================================
    // Compile-time table generation with the original behavior
    // ==================================================================

    @ConstExpr
    private static int mix(int value, int depth) {
        if (depth == 0) return value;
        int next = Integer.rotateLeft(value ^ 0x6D2B79F5, 7) * 0x45D9F3B;
        return mix(next, depth - 1);
    }

    @ConstExpr
    private static int[][] buildTables(int salt) {
        int[] forward = new int[tableSize];
        int[] inverse = new int[tableSize];
        int[] crc = new int[tableSize];
        int[] roundKeys = new int[8];

        int multiplier = (mix(salt + biases[0], 3) | 1) & 255;
        int offset = mix(salt + biases[1], 3) & 255;
        switch (profile) {
            case 1 -> offset ^= biases[2];
            case 2 -> offset = (offset + biases[3]) & 255;
            default -> offset = (offset + salt) & 255;
        }

        for (int value = 0; value < forward.length; value++) {
            int substituted = (value * multiplier + offset) & 255;
            forward[value] = substituted;
            inverse[substituted] = value;

            int remainder = value;
            int bits = 8;
            while (bits-- > 0) {
                remainder = (remainder >>> 1) ^ ((remainder & 1) == 0 ? 0 : 0xEDB88320);
            }
            crc[value] = remainder;
        }
        for (int round = 0; round < roundKeys.length; round++) {
            roundKeys[round] = mix(salt + round * biases[round & 3], 3);
        }
        return new int[][]{forward, inverse, crc, roundKeys};
    }

    @ConstExpr
    private static long expectedSignature(int salt) {
        int[][] tables = buildTables(salt);
        long result = 0xCBF29CE484222325L;
        for (int[] table : tables) {
            result = (result ^ table.length) * 0x100000001B3L;
            for (int value : table) result = (result ^ value) * 0x100000001B3L;
        }
        return result;
    }

    // ==================================================================
    // Runtime table holder
    // ==================================================================

    private static TableBundle bundle(int[][] raw) {
        return new TableBundle(raw[0], raw[1], raw[2], raw[3]);
    }

    private static final class TableBundle {

        private final int[] forward;
        private final int[] inverse;
        private final int[] crc;
        private final int[] roundKeys;

        TableBundle(int[] forward, int[] inverse, int[] crc, int[] roundKeys) {
            this.forward = forward.clone();
            this.inverse = inverse.clone();
            this.crc = crc.clone();
            this.roundKeys = roundKeys.clone();
        }

        int substitute(int value)        { return forward[value & 0xFF]; }
        int invert(int value)            { return inverse[value & 0xFF]; }
        int crcAt(int index)             { return crc[index & 0xFF]; }
        int roundKey(int index)          { return roundKeys[index & 7]; }
        int[] crcTable()                 { return crc; }
        int[] roundKeys()                { return roundKeys; }
        int[] forwardTable()             { return forward; }
        int[] inverseTable()             { return inverse; }
        int size()                       { return forward.length; }

        long signature() {
            long r = FNV_OFFSET;

            r = (r ^ forward.length) * FNV_PRIME;
            for (int v : forward) r = (r ^ v) * FNV_PRIME;

            r = (r ^ inverse.length) * FNV_PRIME;
            for (int v : inverse) r = (r ^ v) * FNV_PRIME;

            r = (r ^ crc.length) * FNV_PRIME;
            for (int v : crc) r = (r ^ v) * FNV_PRIME;

            r = (r ^ roundKeys.length) * FNV_PRIME;
            for (int v : roundKeys) r = (r ^ v) * FNV_PRIME;

            return r;
        }
    }

    // ==================================================================
    // Random nonce factory
    // ==================================================================

    private static final class RandomNonce {
        private final SecureRandom random = new SecureRandom();
        int next() { return random.nextInt(); }
    }

    // ==================================================================
    // CRC32
    // ==================================================================

    private static final class Crc32 {
        private final int[] table;
        Crc32(int[] table) { this.table = table; }
        int compute(byte[] data) {
            int crc = -1;
            for (byte value : data) crc = (crc >>> 8) ^ table[(crc ^ value) & 0xFF];
            return ~crc;
        }
    }

    // ==================================================================
    // Frame container
    // ==================================================================

    private static final class Frame {

        final int nonce;
        final byte[] payload;
        final int checksum;

        Frame(int nonce, byte[] payload, int checksum) {
            this.nonce = nonce;
            this.payload = payload;
            this.checksum = checksum;
        }

        static Frame parse(byte[] raw) {
            if (raw.length < 8) throw new IllegalArgumentException("frame too short");
            int nonce = readInt(raw, 0);
            byte[] payload = Arrays.copyOfRange(raw, 4, raw.length - 4);
            int checksum = readInt(raw, raw.length - 4);
            return new Frame(nonce, payload, checksum);
        }
    }

    // ==================================================================
    // Frame codec
    // ==================================================================

    private static final class FrameCodec {

        private final TableBundle tables;
        private final Crc32 crc;

        FrameCodec(TableBundle tables) {
            this.tables = tables;
            this.crc = new Crc32(tables.crcTable());
        }

        byte[] encode(byte[] plain, int nonce) {
            byte[] frame = new byte[plain.length + 8];
            writeInt(frame, 0, nonce);
            int state = nonce;
            for (int index = 0; index < plain.length; index++) {
                int mask = (state ^ tables.roundKey(index & 7)) & 0xFF;
                int cipher = tables.substitute((plain[index] ^ mask) & 0xFF);
                frame[4 + index] = (byte) cipher;
                state = Integer.rotateLeft(state, 5)
                        ^ cipher
                        ^ tables.roundKey((index + 3) & 7);
            }
            writeInt(frame, frame.length - 4, crc.compute(plain));
            return frame;
        }

        byte[] decode(byte[] raw) {
            Frame parsed = Frame.parse(raw);
            byte[] plain = new byte[parsed.payload.length];
            int state = parsed.nonce;
            for (int index = 0; index < plain.length; index++) {
                int cipher = parsed.payload[index] & 0xFF;
                int mask = (state ^ tables.roundKey(index & 7)) & 0xFF;
                plain[index] = (byte) (tables.invert(cipher) ^ mask);
                state = Integer.rotateLeft(state, 5)
                        ^ cipher
                        ^ tables.roundKey((index + 3) & 7);
            }
            if (crc.compute(plain) != parsed.checksum) {
                throw new IllegalArgumentException("frame checksum mismatch");
            }
            return plain;
        }
    }

    // ==================================================================
    // Stages and stage chain
    // ==================================================================

    private interface Stage {
        String name();
        byte[] apply(byte[] input);
    }

    private static final class StageChain {
        private final List<Stage> stages = new ArrayList<>();
        StageChain add(Stage stage) { stages.add(stage); return this; }
        byte[] run(byte[] input) {
            byte[] current = input;
            for (Stage stage : stages) current = stage.apply(current);
            return current;
        }
        List<String> names() {
            List<String> out = new ArrayList<>(stages.size());
            for (Stage s : stages) out.add(s.name());
            return out;
        }
    }

    private static final class EncodeStage implements Stage {
        private final FrameCodec codec;
        private final RandomNonce nonces;
        EncodeStage(FrameCodec codec, RandomNonce nonces) {
            this.codec = codec;
            this.nonces = nonces;
        }
        public String name() { return "encode"; }
        public byte[] apply(byte[] input) { return codec.encode(input, nonces.next()); }
    }

    private static final class DecodeStage implements Stage {
        private final FrameCodec codec;
        DecodeStage(FrameCodec codec) { this.codec = codec; }
        public String name() { return "decode"; }
        public byte[] apply(byte[] input) { return codec.decode(input); }
    }

    private static final class XorStage implements Stage {
        private final int key;
        XorStage(int key) { this.key = key; }
        public String name() { return "xor(0x" + Integer.toHexString(key) + ")"; }
        public byte[] apply(byte[] input) {
            byte[] out = new byte[input.length];
            for (int i = 0; i < input.length; i++) out[i] = (byte) (input[i] ^ key);
            return out;
        }
    }

    private static final class ReverseStage implements Stage {
        public String name() { return "reverse"; }
        public byte[] apply(byte[] input) {
            byte[] out = new byte[input.length];
            for (int i = 0; i < input.length; i++) out[i] = input[input.length - 1 - i];
            return out;
        }
    }

    // ==================================================================
    // Diagnostics and hexadecimal output
    // ==================================================================

    private static final class Diagnostics {
        private final StringBuilder buffer = new StringBuilder();
        Diagnostics section(String name) {
            buffer.append('[').append(name).append(']');
            return this;
        }
        Diagnostics value(String key, Object value) {
            buffer.append(' ').append(key).append('=').append(value);
            return this;
        }
        Diagnostics end() { buffer.append('\n'); return this; }
        void flush() {
            System.out.print(buffer);
            buffer.setLength(0);
        }
    }

    private static final class HexUtil {
        private HexUtil() {}
        static String dump(byte[] data, int limit) {
            int n = Math.min(data.length, limit);
            StringBuilder sb = new StringBuilder(n * 2 + 8);
            for (int i = 0; i < n; i++) sb.append(String.format("%02x", data[i]));
            if (data.length > limit) sb.append("...(").append(data.length).append(')');
            return sb.toString();
        }
    }

    // ==================================================================
    // Static utilities
    // ==================================================================

    private static void writeInt(byte[] target, int index, int value) {
        target[index]     = (byte) (value >>> 24);
        target[index + 1] = (byte) (value >>> 16);
        target[index + 2] = (byte) (value >>> 8);
        target[index + 3] = (byte) value;
    }

    private static int readInt(byte[] source, int index) {
        return (source[index]     & 0xFF) << 24
                | (source[index + 1] & 0xFF) << 16
                | (source[index + 2] & 0xFF) << 8
                | (source[index + 3] & 0xFF);
    }

    // ==================================================================
    // main
    // ==================================================================

    public static void main(String[] args) {

        // ---------- Compile-time table stability ----------
        final int salt = new ConstExprPipeline(7).apply(9);
        int[][] rawTables  = buildTables(salt);
        int[][] freshTables = buildTables(salt);
        long expected = expectedSignature(salt);

        TableBundle tables = bundle(rawTables);

        if (!Arrays.deepEquals(rawTables, freshTables) || tables.signature() != expected) {
            throw new AssertionError("compile-time table generation changed");
        }

        Diagnostics diag = new Diagnostics();
        diag.section("tables")
                .value("profile",   profile)
                .value("size",      tableSize)
                .value("biases",    Arrays.toString(biases))
                .value("signature", Long.toUnsignedString(expected))
                .value("stable",    true)
                .end();

        // ---------- Encoding and decoding vectors ----------
        byte[] emptyInput   = new byte[0];
        byte[] shortInput   = "A".getBytes(StandardCharsets.UTF_8);
        byte[] messageInput = "ConstExpr: deterministic tables, live packets, reversible data"
                .getBytes(StandardCharsets.UTF_8);
        byte[] randomInput  = new byte[97];
        new SecureRandom().nextBytes(randomInput);

        byte[][] vectors = { emptyInput, shortInput, messageInput, randomInput };

        FrameCodec codec  = new FrameCodec(tables);
        RandomNonce nonces = new RandomNonce();

        for (int i = 0; i < vectors.length; i++) {
            byte[] input = vectors[i];
            byte[] frame = codec.encode(input, nonces.next());
            byte[] restored = codec.decode(frame);
            boolean ok = Arrays.equals(input, restored);

            diag.section("vector#" + i)
                    .value("len",       input.length)
                    .value("frameLen",  frame.length)
                    .value("roundtrip", ok)
                    .value("head",      HexUtil.dump(frame, 12))
                    .end();

            if (!ok) throw new AssertionError("round-trip failed for vector " + i);
            if (input.length == 0) continue;

            byte[] tampered = frame.clone();
            tampered[4] ^= 1;
            boolean rejected = false;
            try { codec.decode(tampered); }
            catch (IllegalArgumentException expectedFailure) { rejected = true; }

            diag.section("tamper#" + i).value("rejected", rejected).end();
            if (!rejected) throw new AssertionError("tampered frame accepted for vector " + i);
        }

        // ---------- Stage chain ----------
        StageChain chain = new StageChain()
                .add(new EncodeStage(codec, nonces))
                .add(new XorStage(0xA5))
                .add(new XorStage(0xA5))
                .add(new DecodeStage(codec))
                .add(new ReverseStage())
                .add(new ReverseStage());

        byte[] chainInput  = "stage chain round-trip probe".getBytes(StandardCharsets.UTF_8);
        byte[] chainOutput = chain.run(chainInput);
        boolean chainOk = Arrays.equals(chainInput, chainOutput);

        diag.section("stagechain")
                .value("stages", chain.names())
                .value("ok",     chainOk)
                .end();

        if (!chainOk) throw new AssertionError("stage chain broke round-trip");

        // ---------- Summary ----------
        diag.flush();
        System.out.println("profile=" + profile
                + ", tables=4"
                + ", signature=" + Long.toUnsignedString(expected)
                + ", bytes=" + chainInput.length
                + ", restored=" + chainOk
                + ", tamperRejected=true");
    }
}
