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
 *
 */

package io.fluxzero.common.serialization.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import lombok.NonNull;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.util.Native;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

import static net.jpountz.lz4.LZ4Factory.fastestJavaInstance;
import static net.jpountz.lz4.LZ4Factory.nativeInsecureInstance;

/**
 * Enumeration of supported compression algorithms used for serializing and deserializing byte data.
 *
 * <p>The available algorithms include:
 * <ul>
 *   <li>{@link #NONE} – No compression. The input is passed through unchanged.</li>
 *   <li>{@link #LZ4} – Fast compression using the LZ4 codec. Optimized for speed and suitable for large volumes of data.</li>
 *   <li>{@link #ZSTD} – Zstandard compression. Optimized for fast transport compression at high throughput.</li>
 *   <li>{@link #GZIP} – Standard GZIP compression. Compatible with most zip tools and libraries.</li>
 * </ul>
 */
public enum CompressionAlgorithm {
    /**
     * No compression. Data is stored and retrieved as-is.
     */
    NONE(0) {
        @Override
        public byte[] compress(byte[] uncompressed) {
            return uncompressed;
        }

        @Override
        public byte[] decompress(byte[] compressed) {
            return compressed;
        }

        @Override
        byte[] compressPayload(byte[] uncompressed) {
            return uncompressed;
        }

        @Override
        byte[] decompressPayload(byte[] compressed, int offset, int length, int originalSize) {
            return offset == 0 && length == compressed.length
                    ? compressed
                    : Arrays.copyOfRange(compressed, offset, offset + length);
        }
    },

    /**
     * Fast compression using the LZ4 codec. Includes original size prefix in output.
     */
    LZ4(1) {
        private final LZ4Compressor compressor = Lz4.factory.fastCompressor();
        private final LZ4FastDecompressor decompressor = Lz4.factory.fastDecompressor();

        @Override
        public byte[] compress(byte[] uncompressed) {
            byte[] compressedPayload = compressPayload(uncompressed);
            byte[] compressed = new byte[compressedPayload.length + Integer.BYTES];
            writeInt(compressed, 0, uncompressed.length);
            System.arraycopy(compressedPayload, 0, compressed, Integer.BYTES, compressedPayload.length);
            return compressed;
        }

        @Override
        public byte[] decompress(byte[] compressed) {
            if (hasFluxzeroCompressionHeader(compressed)) {
                return decompressFluxzeroCompressionHeader(compressed);
            }
            int uncompressedLength = readInt(compressed, 0);
            return decompressPayload(compressed, Integer.BYTES, compressed.length - Integer.BYTES,
                                     uncompressedLength);
        }

        @Override
        byte[] compressPayload(byte[] uncompressed) {
            return compressor.compress(uncompressed);
        }

        @Override
        byte[] decompressPayload(byte[] compressed, int offset, int length, int originalSize) {
            byte[] result = new byte[originalSize];
            decompressor.decompress(compressed, offset, result, 0, originalSize);
            return result;
        }
    },

    /**
     * Zstandard compression using the Fluxzero compression header format.
     */
    ZSTD(2) {
        private static final int COMPRESSION_LEVEL = 1;
        private static final int POOL_SIZE = 16;
        private static final ResourcePool<ZstdCompressCtx> COMPRESSORS =
                new ResourcePool<>(POOL_SIZE, () -> new ZstdCompressCtx().setLevel(COMPRESSION_LEVEL));
        private static final ResourcePool<ZstdDecompressCtx> DECOMPRESSORS =
                new ResourcePool<>(POOL_SIZE, ZstdDecompressCtx::new);

        @Override
        public byte[] compress(byte[] uncompressed) {
            return withFluxzeroCompressionHeader(uncompressed.length, this, compressPayload(uncompressed));
        }

        @Override
        public byte[] decompress(byte[] compressed) {
            if (hasFluxzeroCompressionHeader(compressed)) {
                return decompressFluxzeroCompressionHeader(compressed);
            }
            long uncompressedLength = Zstd.getFrameContentSize(compressed);
            if (uncompressedLength < 0 || uncompressedLength > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Unable to determine ZSTD uncompressed size: "
                                                   + uncompressedLength);
            }
            return decompressPayload(compressed, 0, compressed.length, (int) uncompressedLength);
        }

        @Override
        byte[] compressPayload(byte[] uncompressed) {
            return COMPRESSORS.apply(ctx -> ctx.compress(uncompressed));
        }

        @Override
        byte[] decompressPayload(byte[] compressed, int offset, int length, int originalSize) {
            return DECOMPRESSORS.apply(ctx -> ctx.decompress(compressed, offset, length, originalSize));
        }
    },

    /**
     * GZIP compression using standard Java APIs. Produces .gz-compatible output.
     */
    GZIP(-1) {
        @Override
        public byte[] compress(byte[] uncompressed) {
            try {
                ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                try (GZIPOutputStream zipStream = new GZIPOutputStream(byteStream)) {
                    zipStream.write(uncompressed);
                }
                return byteStream.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to GZIP compress payload", e);
            }
        }

        @Override
        public byte[] decompress(byte[] compressed) {
            try (var gzipStream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                return gzipStream.readAllBytes();
            } catch (ZipException ignored) {
                return compressed;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to GZIP decompress payload", e);
            }
        }

        @Override
        byte[] compressPayload(byte[] uncompressed) {
            throw new UnsupportedOperationException("GZIP is not supported in Fluxzero compression headers");
        }

        @Override
        byte[] decompressPayload(byte[] compressed, int offset, int length, int originalSize) {
            throw new UnsupportedOperationException("GZIP is not supported in Fluxzero compression headers");
        }
    };

    private static final byte[] FLUXZERO_COMPRESSION_MAGIC = {(byte) 0xFF, 0x00};
    private static final int FLUXZERO_COMPRESSION_HEADER_LENGTH = FLUXZERO_COMPRESSION_MAGIC.length + 1 + Integer.BYTES;
    private final int fluxzeroCompressionId;

    CompressionAlgorithm(int fluxzeroCompressionId) {
        this.fluxzeroCompressionId = fluxzeroCompressionId;
    }

    /**
     * Compresses bytes using this algorithm's public SDK wire format.
     *
     * <p>LZ4 preserves the legacy 4-byte size prefix format. ZSTD uses the Fluxzero compression header so the decoder
     * can identify the payload algorithm. GZIP keeps the standard gzip stream format.</p>
     *
     * @param uncompressed bytes to compress
     * @return compressed bytes
     */
    public abstract byte[] compress(byte[] uncompressed);

    /**
     * Decompresses bytes using this algorithm's public SDK wire format.
     *
     * @param compressed bytes to decompress
     * @return decompressed bytes
     */
    public abstract byte[] decompress(byte[] compressed);

    abstract byte[] compressPayload(byte[] uncompressed);

    abstract byte[] decompressPayload(byte[] compressed, int offset, int length, int originalSize);

    private static byte[] withFluxzeroCompressionHeader(int uncompressedLength, CompressionAlgorithm algorithm,
                                                        byte[] payload) {
        if (algorithm.fluxzeroCompressionId < 0) {
            throw new IllegalArgumentException(algorithm + " is not supported in Fluxzero compression headers");
        }
        byte[] compressed = new byte[FLUXZERO_COMPRESSION_HEADER_LENGTH + payload.length];
        compressed[0] = FLUXZERO_COMPRESSION_MAGIC[0];
        compressed[1] = FLUXZERO_COMPRESSION_MAGIC[1];
        compressed[2] = (byte) algorithm.fluxzeroCompressionId;
        writeInt(compressed, 3, uncompressedLength);
        System.arraycopy(payload, 0, compressed, FLUXZERO_COMPRESSION_HEADER_LENGTH, payload.length);
        return compressed;
    }

    private static byte[] decompressFluxzeroCompressionHeader(byte[] compressed) {
        CompressionAlgorithm algorithm = fromFluxzeroCompressionId(compressed[2] & 0xff);
        int uncompressedLength = readInt(compressed, 3);
        int offset = FLUXZERO_COMPRESSION_HEADER_LENGTH;
        int length = compressed.length - offset;
        return algorithm.decompressPayload(compressed, offset, length, uncompressedLength);
    }

    private static CompressionAlgorithm fromFluxzeroCompressionId(int id) {
        for (CompressionAlgorithm algorithm : values()) {
            if (algorithm.fluxzeroCompressionId == id) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("Unknown Fluxzero compression algorithm id: " + id);
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

    /**
     * Returns the fastest available LZ4 factory.
     *
     * <p>Fluxzero deliberately uses the native insecure factory when possible. The non-deprecated
     * {@link LZ4Factory#fastestInstance()} selects the secure native factory, which keeps the fast decompressor on the
     * Java implementation in this LZ4 release.</p>
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

    private static final class Lz4 {
        private static final LZ4Factory factory = fastestInstance();
    }

    private static final class ResourcePool<T extends AutoCloseable> implements AutoCloseable {
        private final Semaphore semaphore;
        private final Queue<Resource<T>> resources;

        ResourcePool(int size, @NonNull Supplier<T> factory) {
            if (size <= 0) {
                throw new IllegalArgumentException("Pool size must be > 0");
            }
            this.semaphore = new Semaphore(size);
            this.resources = new ConcurrentLinkedQueue<>(
                    IntStream.range(0, size).mapToObj(ignored -> new Resource<>(factory)).toList());
        }

        <R> R apply(Function<T, R> function) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for compression resource", e);
            }
            Resource<T> resource = resources.poll();
            try {
                if (resource == null) {
                    throw new IllegalStateException("Compression resource pool is exhausted");
                }
                return function.apply(resource.get());
            } finally {
                if (resource != null) {
                    resources.offer(resource);
                }
                semaphore.release();
            }
        }

        @Override
        public void close() {
            Resource<T> resource;
            while ((resource = resources.poll()) != null) {
                resource.close();
            }
        }
    }

    private static final class Resource<T extends AutoCloseable> implements AutoCloseable {
        private final Supplier<T> factory;
        private volatile T value;

        Resource(Supplier<T> factory) {
            this.factory = factory;
        }

        T get() {
            T currentValue = value;
            if (currentValue == null) {
                currentValue = factory.get();
                value = currentValue;
            }
            return currentValue;
        }

        @Override
        public void close() {
            T currentValue = value;
            if (currentValue != null) {
                try {
                    currentValue.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
