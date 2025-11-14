#include <jni.h>
#include "io_fluxzero_common_serialization_compression_LZ4Codec.h"
#include "lz4.h"

/*
 * Helper to avoid unused parameter warnings.
 */
#define UNUSED(x) (void)(x)

/*
 * JNI wrapper for: int LZ4_compressBound(int inputSize);
 */
JNIEXPORT jint JNICALL
Java_io_fluxzero_common_serialization_compression_LZ4Codec_nCompressBound(
        JNIEnv *env, jclass clazz, jint inputSize) {
    UNUSED(env);
    UNUSED(clazz);

    if (inputSize < 0) {
        return -1;
    }
    return (jint)LZ4_compressBound(inputSize);
}

/*
 * JNI wrapper for:
 *   int LZ4_compress_default(const char* src, char* dst,
 *                            int srcSize, int dstCapacity);
 *
 * Java signature:
 *   private static native int nCompress(
 *       byte[] src, int srcOff, int srcLen,
 *       byte[] dest, int destOff, int maxDestLen
 *   );
 */
JNIEXPORT jint JNICALL
Java_io_fluxzero_common_serialization_compression_LZ4Codec_nCompress(
        JNIEnv *env, jclass clazz,
        jbyteArray srcArray, jint srcOff, jint srcLen,
        jbyteArray dstArray, jint dstOff, jint maxDestLen) {
    UNUSED(clazz);

    if (srcArray == NULL || dstArray == NULL) {
        return -1;
    }
    if (srcLen < 0 || maxDestLen < 0 || srcOff < 0 || dstOff < 0) {
        return -1;
    }

    jsize srcSize = (*env)->GetArrayLength(env, srcArray);
    jsize dstSize = (*env)->GetArrayLength(env, dstArray);

    if (srcOff + srcLen > srcSize || dstOff + maxDestLen > dstSize) {
        return -1;
    }

    jboolean isCopySrc = JNI_FALSE;
    jboolean isCopyDst = JNI_FALSE;

    jbyte *src = (*env)->GetByteArrayElements(env, srcArray, &isCopySrc);
    if (src == NULL) {
        return -1;
    }

    jbyte *dst = (*env)->GetByteArrayElements(env, dstArray, &isCopyDst);
    if (dst == NULL) {
        (*env)->ReleaseByteArrayElements(env, srcArray, src, JNI_ABORT);
        return -1;
    }

    int written = LZ4_compress_default(
            (const char *)src + srcOff,
            (char *)dst + dstOff,
            (int)srcLen,
            (int)maxDestLen);

    /*
     * Commit changes to destination array (mode = 0).
     * For source array we can use JNI_ABORT since we didn't modify it.
     */
    (*env)->ReleaseByteArrayElements(env, srcArray, src, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, dstArray, dst, 0);

    return (jint)written;
}

/*
 * JNI wrapper for:
 *   int LZ4_decompress_safe(const char* src, char* dst,
 *                           int compressedSize, int dstCapacity);
 *
 * Java signature:
 *   private static native int nDecompress(
 *       byte[] src, int srcOff, int srcLen,
 *       byte[] dest, int destOff, int maxDestLen
 *   );
 */
JNIEXPORT jint JNICALL
Java_io_fluxzero_common_serialization_compression_LZ4Codec_nDecompress(
        JNIEnv *env, jclass clazz,
        jbyteArray srcArray, jint srcOff, jint srcLen,
        jbyteArray dstArray, jint dstOff, jint dstCap) {
    UNUSED(clazz);

    if (srcArray == NULL || dstArray == NULL) {
        return -1;
    }
    if (srcLen < 0 || dstCap < 0 || srcOff < 0 || dstOff < 0) {
        return -1;
    }

    jsize srcSize = (*env)->GetArrayLength(env, srcArray);
    jsize dstSize = (*env)->GetArrayLength(env, dstArray);

    if (srcOff + srcLen > srcSize || dstOff + dstCap > dstSize) {
        return -1;
    }

    jboolean isCopySrc = JNI_FALSE;
    jboolean isCopyDst = JNI_FALSE;

    jbyte *src = (*env)->GetByteArrayElements(env, srcArray, &isCopySrc);
    if (src == NULL) {
        return -1;
    }

    jbyte *dst = (*env)->GetByteArrayElements(env, dstArray, &isCopyDst);
    if (dst == NULL) {
        (*env)->ReleaseByteArrayElements(env, srcArray, src, JNI_ABORT);
        return -1;
    }

    int read = LZ4_decompress_safe(
            (const char *)src + srcOff,
            (char *)dst + dstOff,
            (int)srcLen,
            (int)dstCap);

    /*
     * Commit changes to destination array (mode = 0).
     * Source is read-only → JNI_ABORT.
     */
    (*env)->ReleaseByteArrayElements(env, srcArray, src, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, dstArray, dst, 0);

    return (jint)read;
}
