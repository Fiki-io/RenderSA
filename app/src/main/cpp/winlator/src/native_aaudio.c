#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <aaudio/AAudio.h>
#include <android/log.h>

typedef struct NativeAAudioHandle {
    AAudioStream* stream;
    int32_t channels;
    int32_t sampleRate;
    int32_t dataType;
    int32_t frameBytes;
    int32_t bufferSizeFrames;
    float volume;
    int16_t* u8ConversionBuffer;
    int32_t u8ConversionCapacityFrames;
} NativeAAudioHandle;

static void applyVolumeInPlaceI16(int16_t* samples, int32_t count, float volume) {
    if (volume >= 0.999f) return;
    int32_t scale = (int32_t)(volume * 32768.0f);
    for (int32_t i = 0; i < count; i++) {
        samples[i] = (int16_t)((samples[i] * scale) >> 15);
    }
}

static void applyVolumeInPlaceFloat(float* samples, int32_t count, float volume) {
    if (volume >= 0.999f) return;
    for (int32_t i = 0; i < count; i++) {
        samples[i] *= volume;
    }
}

JNIEXPORT jlong JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeCreateAAudio(
        JNIEnv* env,
        jclass clazz,
        jint sampleRate,
        jint channels,
        jint formatCode,
        jint bufferCapacityFrames,
        jboolean lowLatency) {
    if (sampleRate <= 0 || channels <= 0 || bufferCapacityFrames <= 0) return 0;

    AAudioStreamBuilder* builder = NULL;
    aaudio_result_t res = AAudio_createStreamBuilder(&builder);
    if (res != AAUDIO_OK || !builder) return 0;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);

    aaudio_format_t format = (formatCode == 1)
            ? AAUDIO_FORMAT_PCM_FLOAT
            : AAUDIO_FORMAT_PCM_I16;
    AAudioStreamBuilder_setFormat(builder, format);

    aaudio_performance_mode_t aaudioPerf = lowLatency
            ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
            : AAUDIO_PERFORMANCE_MODE_NONE;
    AAudioStreamBuilder_setPerformanceMode(builder, aaudioPerf);
    AAudioStreamBuilder_setBufferCapacityInFrames(builder, bufferCapacityFrames * 2);

    AAudioStream* stream = NULL;
    res = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);

    if (res != AAUDIO_OK || !stream) {
        return 0;
    }

    AAudioStream_setBufferSizeInFrames(stream, bufferCapacityFrames);
    AAudioStream_requestStart(stream);

    NativeAAudioHandle* handle = (NativeAAudioHandle*)calloc(1, sizeof(NativeAAudioHandle));
    if (!handle) {
        AAudioStream_close(stream);
        return 0;
    }
    handle->stream = stream;
    handle->channels = channels;
    handle->sampleRate = sampleRate;
    handle->dataType = (formatCode == 1) ? 3 : 1;
    int32_t bytesPerSample = (formatCode == 1) ? 4 : 2;
    handle->frameBytes = channels * bytesPerSample;
    handle->bufferSizeFrames = bufferCapacityFrames;
    handle->volume = 1.0f;
    return (jlong)handle;
}

JNIEXPORT jint JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeWriteDirect(
        JNIEnv* env,
        jclass clazz,
        jlong handlePtr,
        jobject directBuffer,
        jint offsetBytes,
        jint lengthBytes,
        jint frameBytesParam) {
    NativeAAudioHandle* handle = (NativeAAudioHandle*)handlePtr;
    if (!handle || !handle->stream || !directBuffer || lengthBytes <= 0) {
        return -1;
    }

    int32_t effFrameBytes = frameBytesParam > 0 ? frameBytesParam : handle->frameBytes;
    if (effFrameBytes <= 0) return -1;

    uint8_t* baseAddr = (uint8_t*)(*env)->GetDirectBufferAddress(env, directBuffer);
    if (!baseAddr) return -1;

    uint8_t* srcPtr = baseAddr + offsetBytes;
    int32_t numFrames = lengthBytes / effFrameBytes;
    if (numFrames <= 0) return 0;

    aaudio_stream_state_t state = AAudioStream_getState(handle->stream);
    if (state != AAUDIO_STREAM_STATE_STARTED && state != AAUDIO_STREAM_STATE_STARTING) {
        AAudioStream_requestStart(handle->stream);
    }

    const int64_t timeoutNs = 15000000L; // 15ms bounded write timeout
    aaudio_result_t framesWritten = AAudioStream_write(handle->stream, srcPtr, numFrames, timeoutNs);
    if (framesWritten == AAUDIO_ERROR_DISCONNECTED) {
        return -1;
    }
    return framesWritten > 0 ? (framesWritten * effFrameBytes) : 0;
}

JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeStartAAudio(JNIEnv* env, jclass clazz, jlong handlePtr) {
    NativeAAudioHandle* handle = (NativeAAudioHandle*)handlePtr;
    if (handle && handle->stream) {
        AAudioStream_requestStart(handle->stream);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativePauseAAudio(JNIEnv* env, jclass clazz, jlong handlePtr) {
    NativeAAudioHandle* handle = (NativeAAudioHandle*)handlePtr;
    if (handle && handle->stream) {
        AAudioStream_requestPause(handle->stream);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeStopAAudio(JNIEnv* env, jclass clazz, jlong handlePtr) {
    NativeAAudioHandle* handle = (NativeAAudioHandle*)handlePtr;
    if (handle && handle->stream) {
        AAudioStream_requestPause(handle->stream);
        AAudioStream_requestFlush(handle->stream);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeFlushAAudio(JNIEnv* env, jclass clazz, jlong handlePtr) {
    NativeAAudioHandle* handle = (NativeAAudioHandle*)handlePtr;
    if (handle && handle->stream) {
        AAudioStream_requestFlush(handle->stream);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeReleaseAAudio(JNIEnv* env, jclass clazz, jlong handlePtr) {
    NativeAAudioHandle* handle = (NativeAAudioHandle*)handlePtr;
    if (!handle) return;
    if (handle->stream) {
        AAudioStream_requestStop(handle->stream);
        AAudioStream_close(handle->stream);
        handle->stream = NULL;
    }
    if (handle->u8ConversionBuffer) {
        free(handle->u8ConversionBuffer);
        handle->u8ConversionBuffer = NULL;
    }
    free(handle);
}
