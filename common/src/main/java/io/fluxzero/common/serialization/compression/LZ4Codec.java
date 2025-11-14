package io.fluxzero.common.serialization.compression;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * JNI-based LZ4 codec with a 4-byte big-endian length prefix.
 * <p>
 * Data format:
 * [0..3]   = original uncompressed length (int, big-endian)
 * [4..end] = raw LZ4-compressed bytes
 * <p>
 * The JNI library must expose:
 * - nCompressBound
 * - nCompress
 * - nDecompress
 */
public final class LZ4Codec {

    static {
        loadNativeLibrary();
    }

    private LZ4Codec() {
    }

    private static native int nCompressBound(int inputSize);

    private static native int nCompress(
            byte[] src, int srcOff, int srcLen,
            byte[] dest, int destOff, int maxDestLen
    );

    private static native int nDecompress(
            byte[] src, int srcOff, int srcLen,
            byte[] dest, int destOff, int maxDestLen
    );

    // ------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------

    /**
     * Compresses the given data using LZ4 and prefixes it with a 4-byte
     * big-endian integer containing the original size.
     * <p>
     * Output format:
     * [0 to 3] = uncompressed length
     * [4 to n] = raw LZ4 block
     */
    public static byte[] compress(byte[] input) {
        if (input == null) {
            throw new NullPointerException("input");
        }

        int originalSize = input.length;
        int maxCompressedSize = nCompressBound(originalSize);

        if (maxCompressedSize <= 0) {
            throw new IllegalStateException("LZ4_compressBound returned " + maxCompressedSize);
        }

        byte[] out = new byte[4 + maxCompressedSize];
        ByteBuffer.wrap(out, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(originalSize);
        int actualCompressedSize = nCompress(input, 0, originalSize, out, 4, maxCompressedSize);

        if (actualCompressedSize <= 0) {
            throw new RuntimeException("LZ4_compress_default failed, code=" + actualCompressedSize);
        }

        int total = 4 + actualCompressedSize;
        byte[] trimmed = new byte[total];
        System.arraycopy(out, 0, trimmed, 0, total);
        return trimmed;
    }

    /**
     * Decompresses LZ4 data produced by {@link #compress(byte[])},
     * using the 4-byte original size prefix.
     */
    public static byte[] decompress(byte[] compressed) {
        if (compressed == null) {
            throw new NullPointerException("compressed");
        }
        if (compressed.length < 4) {
            throw new IllegalArgumentException("Compressed block too small to contain header");
        }

        int originalSize = ByteBuffer.wrap(compressed, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();

        if (originalSize < 0) {
            throw new IllegalArgumentException("Invalid original size: " + originalSize);
        }

        byte[] out = new byte[originalSize];
        int compressedSize = compressed.length - 4;
        int written = nDecompress(compressed, 4, compressedSize, out, 0, originalSize);

        if (written < 0) {
            throw new RuntimeException("LZ4_decompress_safe failed, code=" + written);
        }

        if (written == originalSize) {
            return out;
        }

        byte[] trimmed = new byte[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        return trimmed;
    }

    private static void loadNativeLibrary() {
        String os = detectOS();
        String arch = detectArch();

        String file = switch (os) {
            case "linux" -> "lz4jni.so";
            case "macos" -> "lz4jni.dylib";
            case "windows" -> "lz4jni.dll";
            default -> throw new IllegalStateException("Unsupported OS: " + os);
        };

        String resourcePath = "/native/" + os + "-" + arch + "/" + file;

        try {
            Path extracted = extractResource(resourcePath);
            System.load(extracted.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load native library from " + resourcePath, e);
        }
    }

    private static String detectOS() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) return "linux";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("win")) return "windows";
        throw new IllegalStateException("Unsupported OS: " + os);
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.contains("x86_64") || arch.contains("amd64")) return "x86_64";
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        throw new IllegalStateException("Unsupported architecture: " + arch);
    }

    private static Path extractResource(String resource) throws IOException {
        try (InputStream in = LZ4Codec.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new FileNotFoundException("Native library not found on classpath: " + resource);
            }

            Path tmp = Files.createTempFile("lz4jni-", null);
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }
}
