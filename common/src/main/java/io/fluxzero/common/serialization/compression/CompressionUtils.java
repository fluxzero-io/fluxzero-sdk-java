/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.serialization.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import lombok.NonNull;
import lombok.SneakyThrows;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.util.Native;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

import static net.jpountz.lz4.LZ4Factory.fastestJavaInstance;
import static net.jpountz.lz4.LZ4Factory.nativeInsecureInstance;

/**
 * Utility class for compressing and decompressing byte arrays using common compression algorithms.
 * <p>
 * Supports multiple algorithms including:
 * <ul>
 *   <li>{@link CompressionAlgorithm#LZ4} – fast compression optimized for speed</li>
 *   <li>{@link CompressionAlgorithm#ZSTD} – Zstandard compression using the Fluxzero runtime header format</li>
 *   <li>{@link CompressionAlgorithm#GZIP} – standard GZIP format for interoperability</li>
 *   <li>{@link CompressionAlgorithm#NONE} – pass-through mode (no compression)</li>
 * </ul>
 *
 * <h2>LZ4 Support</h2>
 * When compressing with LZ4, the output includes a 4-byte prefix that encodes the length of the original
 * (uncompressed) data. This prefix is required during decompression.
 *
 * <h2>GZIP Support</h2>
 * GZIP compression and decompression are compatible with standard ZIP tools. If a {@link ZipException}
 * is thrown during GZIP decompression (e.g. data is not compressed), the original input is returned as-is.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * byte[] input = ...;
 * byte[] compressed = CompressionUtils.compress(input, CompressionAlgorithm.GZIP);
 * byte[] restored = CompressionUtils.decompress(compressed, CompressionAlgorithm.GZIP);
 * }</pre>
 */
public class CompressionUtils {

    private static final byte[] FLUXZERO_COMPRESSION_MAGIC = {(byte) 0xFF, 0x00};
    private static final int FLUXZERO_COMPRESSION_HEADER_LENGTH = FLUXZERO_COMPRESSION_MAGIC.length + 1 + Integer.BYTES;
    private static final byte FLUXZERO_COMPRESSION_NONE_ID = 0;
    private static final byte FLUXZERO_COMPRESSION_LZ4_ID = 1;
    private static final byte FLUXZERO_COMPRESSION_ZSTD_ID = 2;
    private static final int ZSTD_COMPRESSION_LEVEL = 1;

    private static final LZ4Factory lz4Factory = fastestInstance();
    private static final LZ4Compressor lz4Compressor = lz4Factory.fastCompressor();
    private static final LZ4FastDecompressor lz4Decompressor = lz4Factory.fastDecompressor();
    private static final ThreadLocal<ZstdCompressCtx> zstdCompressors =
            ThreadLocal.withInitial(() -> new ZstdCompressCtx().setLevel(ZSTD_COMPRESSION_LEVEL));
    private static final ThreadLocal<ZstdDecompressCtx> zstdDecompressors =
            ThreadLocal.withInitial(ZstdDecompressCtx::new);

    /**
     * Returns the fastest available {@link LZ4Factory} instance.
     *
     * <p>Fluxzero deliberately uses the native insecure factory when possible. The non-deprecated
     * {@link LZ4Factory#fastestInstance()} selects the secure native factory, which keeps the fast decompressor on the
     * Java implementation in this LZ4 release.</p>
     *
     * @return the fastest available {@link LZ4Factory} instance
     */
    @SuppressWarnings("deprecation")
    private static LZ4Factory fastestInstance() {
        if (Native.isLoaded() || Native.class.getClassLoader() == ClassLoader.getSystemClassLoader()) {
            try {
                return nativeInsecureInstance();
            } catch (Throwable t) {
                return fastestJavaInstance();
            }
        } else {
            return fastestJavaInstance();
        }
    }

    /**
     * Compresses the given byte array using {@link CompressionAlgorithm#LZ4} by default.
     *
     * @param uncompressed the data to compress
     * @return the compressed byte array
     */
    public static byte[] compress(byte[] uncompressed) {
        return compress(uncompressed, CompressionAlgorithm.LZ4);
    }

    /**
     * Compresses the given byte array using the specified compression algorithm.
     *
     * @param uncompressed the data to compress
     * @param algorithm the compression algorithm to use
     * @return the compressed byte array
     */
    @SneakyThrows
    public static byte[] compress(byte[] uncompressed, @NonNull CompressionAlgorithm algorithm) {
        return switch (algorithm) {
            case NONE -> uncompressed;
            case LZ4 -> {
                byte[] compressedPayload = lz4Compressor.compress(uncompressed);
                byte[] compressed = new byte[compressedPayload.length + Integer.BYTES];
                writeInt(compressed, 0, uncompressed.length);
                System.arraycopy(compressedPayload, 0, compressed, Integer.BYTES, compressedPayload.length);
                yield compressed;
            }
            case ZSTD -> withFluxzeroCompressionHeader(
                    uncompressed.length, FLUXZERO_COMPRESSION_ZSTD_ID, zstdCompressors.get().compress(uncompressed));
            case GZIP -> {
                ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                try (GZIPOutputStream zipStream = new GZIPOutputStream(byteStream)) {
                    zipStream.write(uncompressed);
                }
                yield byteStream.toByteArray();
            }
        };
    }

    /**
     * Decompresses the given byte array using {@link CompressionAlgorithm#LZ4} by default.
     *
     * @param compressed the compressed data
     * @return the original (decompressed) byte array
     */
    public static byte[] decompress(byte[] compressed) {
        return decompress(compressed, CompressionAlgorithm.LZ4);
    }

    /**
     * Decompresses the given byte array using the specified algorithm.
     *
     * @param compressed the compressed data
     * @param algorithm the compression algorithm to apply
     * @return the original (decompressed) byte array
     */
    @SneakyThrows
    public static byte[] decompress(byte[] compressed, @NonNull CompressionAlgorithm algorithm) {
        return switch (algorithm) {
            case NONE -> compressed;
            case LZ4 -> {
                if (hasFluxzeroCompressionHeader(compressed)) {
                    yield decompressFluxzeroCompressionHeader(compressed);
                }
                int uncompressedLength = readInt(compressed, 0);
                byte[] result = new byte[uncompressedLength];
                lz4Decompressor.decompress(compressed, Integer.BYTES, result, 0, uncompressedLength);
                yield result;
            }
            case ZSTD -> {
                if (hasFluxzeroCompressionHeader(compressed)) {
                    yield decompressFluxzeroCompressionHeader(compressed);
                }
                long uncompressedLength = Zstd.getFrameContentSize(compressed);
                if (uncompressedLength < 0 || uncompressedLength > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Unable to determine ZSTD uncompressed size: "
                                                       + uncompressedLength);
                }
                yield Zstd.decompress(compressed, (int) uncompressedLength);
            }
            case GZIP -> {
                try (var gzipStream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                    yield gzipStream.readAllBytes();
                } catch (ZipException ignored) {
                    yield compressed;
                }
            }
        };
    }

    private static byte[] withFluxzeroCompressionHeader(int uncompressedLength, byte algorithmId, byte[] payload) {
        byte[] compressed = new byte[FLUXZERO_COMPRESSION_HEADER_LENGTH + payload.length];
        compressed[0] = FLUXZERO_COMPRESSION_MAGIC[0];
        compressed[1] = FLUXZERO_COMPRESSION_MAGIC[1];
        compressed[2] = algorithmId;
        writeInt(compressed, 3, uncompressedLength);
        System.arraycopy(payload, 0, compressed, FLUXZERO_COMPRESSION_HEADER_LENGTH, payload.length);
        return compressed;
    }

    private static byte[] decompressFluxzeroCompressionHeader(byte[] compressed) {
        byte algorithmId = compressed[2];
        int uncompressedLength = readInt(compressed, 3);
        int offset = FLUXZERO_COMPRESSION_HEADER_LENGTH;
        int length = compressed.length - offset;
        return switch (algorithmId) {
            case FLUXZERO_COMPRESSION_NONE_ID -> Arrays.copyOfRange(compressed, offset, compressed.length);
            case FLUXZERO_COMPRESSION_LZ4_ID -> {
                byte[] result = new byte[uncompressedLength];
                lz4Decompressor.decompress(compressed, offset, result, 0, uncompressedLength);
                yield result;
            }
            case FLUXZERO_COMPRESSION_ZSTD_ID ->
                    zstdDecompressors.get().decompress(compressed, offset, length, uncompressedLength);
            default -> throw new IllegalArgumentException(
                    "Unknown Fluxzero compression algorithm id: " + (algorithmId & 0xff));
        };
    }

    private static boolean hasFluxzeroCompressionHeader(byte[] compressed) {
        return compressed.length >= FLUXZERO_COMPRESSION_HEADER_LENGTH
               && compressed[0] == FLUXZERO_COMPRESSION_MAGIC[0]
               && compressed[1] == FLUXZERO_COMPRESSION_MAGIC[1];
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

}
