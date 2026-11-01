#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <jni.h>
#include <string.h>
#include <stdbool.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <unistd.h>
#include <dlfcn.h>

#include "native_handle.h"
#include "winlator.h"

#define HAL_PIXEL_FORMAT_BGRA_8888 5

extern const native_handle_t* _Nullable AHardwareBuffer_getNativeHandle(const AHardwareBuffer* _Nonnull buffer);

typedef int (*PFN_AHardwareBuffer_sendHandleToUnixSocket)(const AHardwareBuffer*, int);

static int getFdViaSocketPair(AHardwareBuffer* hardwareBuffer) {
    void* handle = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) return -1;

    PFN_AHardwareBuffer_sendHandleToUnixSocket sendFn =
        (PFN_AHardwareBuffer_sendHandleToUnixSocket)dlsym(handle, "AHardwareBuffer_sendHandleToUnixSocket");
    if (!sendFn) {
        dlclose(handle);
        return -1;
    }

    int fds[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, fds) != 0) {
        dlclose(handle);
        return -1;
    }

    int receivedFd = -1;
    if (sendFn(hardwareBuffer, fds[0]) == 0) {
        char dummy[1];
        struct iovec iov = { .iov_base = dummy, .iov_len = sizeof(dummy) };
        char cmsgBuf[CMSG_SPACE(sizeof(int))];
        struct msghdr msg = {
            .msg_iov = &iov,
            .msg_iovlen = 1,
            .msg_control = cmsgBuf,
            .msg_controllen = sizeof(cmsgBuf)
        };

        if (recvmsg(fds[1], &msg, 0) > 0) {
            struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
            if (cmsg && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
                receivedFd = *((int*)CMSG_DATA(cmsg));
            }
        }
    }

    close(fds[0]);
    close(fds[1]);
    dlclose(handle);
    return receivedFd;
}

int AHardwareBuffer_getFd(AHardwareBuffer* hardwareBuffer) {
    if (!hardwareBuffer) return -1;
    const native_handle_t* nativeHandle = AHardwareBuffer_getNativeHandle(hardwareBuffer);
    if (nativeHandle && nativeHandle->numFds > 0 && nativeHandle->data[0] >= 0) {
        return nativeHandle->data[0];
    }
    return getFdViaSocketPair(hardwareBuffer);
}

EGLImageKHR createImageKHR(AHardwareBuffer* hardwareBuffer, int textureId) {
    if (!hardwareBuffer) return NULL;
    const EGLint attribList[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};

    AHardwareBuffer_acquire(hardwareBuffer);

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (!clientBuffer) {
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLImageKHR imageKHR = eglCreateImageKHR(eglDisplay, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribList);
    if (!imageKHR) {
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    glBindTexture(GL_TEXTURE_2D, textureId);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, imageKHR);
    glBindTexture(GL_TEXTURE_2D, 0);

    return imageKHR;
}

AHardwareBuffer* createHardwareBuffer(int width, int height, bool cpuAccess, bool useHALPixelFormatBGRA8888) {
    if (width <= 0 || height <= 0) return NULL;

    AHardwareBuffer_Desc buffDesc = {0};
    buffDesc.width = width;
    buffDesc.height = height;
    buffDesc.layers = 1;
    buffDesc.usage = cpuAccess ? AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN : AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    buffDesc.format = useHALPixelFormatBGRA8888 ? HAL_PIXEL_FORMAT_BGRA_8888 : AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;

    AHardwareBuffer* hardwareBuffer = NULL;
    AHardwareBuffer_allocate(&buffDesc, &hardwareBuffer);

    return hardwareBuffer;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_createHardwareBuffer(JNIEnv *env, jobject obj, jshort width,
                                                         jshort height, jboolean cpuAccess, jboolean useHALPixelFormatBGRA8888) {
    if (width <= 0 || height <= 0) return 0;
    AHardwareBuffer* hardwareBuffer = createHardwareBuffer(width, height, cpuAccess, useHALPixelFormatBGRA8888);
    if (hardwareBuffer) {
        jclass cls = (*env)->GetObjectClass(env, obj);
        if (cls) {
            AHardwareBuffer_Desc buffDesc = {0};
            AHardwareBuffer_describe(hardwareBuffer, &buffDesc);

            jmethodID setStride = (*env)->GetMethodID(env, cls, "setStride", "(S)V");
            if (setStride) {
                (*env)->CallVoidMethod(env, obj, setStride, (jshort)buffDesc.stride);
            }

            int fd = AHardwareBuffer_getFd(hardwareBuffer);
            if (fd != -1) {
                jmethodID setNativeHandle = (*env)->GetMethodID(env, cls, "setNativeHandle", "(I)V");
                if (setNativeHandle) {
                    (*env)->CallVoidMethod(env, obj, setNativeHandle, fd);
                }
            }
            (*env)->DeleteLocalRef(env, cls);
        }
    }
    return (jlong)hardwareBuffer;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_createImageKHR(JNIEnv *env, jobject obj,
                                                   jlong hardwareBufferPtr, jint textureId) {
    return (jlong)createImageKHR((AHardwareBuffer*)hardwareBufferPtr, textureId);
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_destroyHardwareBuffer(JNIEnv *env, jobject obj,
                                                          jlong hardwareBufferPtr, jboolean locked) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (hardwareBuffer) {
        if (locked) {
            AHardwareBuffer_unlock(hardwareBuffer, NULL);
        }
        AHardwareBuffer_release(hardwareBuffer);
    }
}

JNIEXPORT jobject JNICALL
Java_com_winlator_renderer_GPUImage_lockHardwareBuffer(JNIEnv *env, jobject obj,
                                                       jlong hardwareBufferPtr) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (!hardwareBuffer) return NULL;
    void *virtualAddr = NULL;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &virtualAddr) != 0 || !virtualAddr) {
        return NULL;
    }

    AHardwareBuffer_Desc buffDesc = {0};
    AHardwareBuffer_describe(hardwareBuffer, &buffDesc);

    jlong size = (jlong)buffDesc.stride * (jlong)buffDesc.height * 4;
    if (size <= 0) return NULL;
    return (*env)->NewDirectByteBuffer(env, virtualAddr, size);
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_destroyImageKHR(JNIEnv *env, jobject obj, jlong imageKHRPtr) {
    EGLImageKHR imageKHR = (EGLImageKHR)imageKHRPtr;
    if (imageKHR) {
        EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglDestroyImageKHR(eglDisplay, imageKHR);
    }
}