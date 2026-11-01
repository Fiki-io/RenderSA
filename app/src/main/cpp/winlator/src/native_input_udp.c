#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/time.h>
#include <time.h>

static uint64_t monotonicMillis(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000ULL);
}

JNIEXPORT jint JNICALL
Java_com_winlator_winhandler_WinHandler_nativeCreateUdpSocket(JNIEnv* env, jclass clazz) {
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, IPPROTO_UDP);
    if (fd < 0) return -1;

    int reuse = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(0);

    if (bind(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_winhandler_WinHandler_nativeSendDirectPacket(
        JNIEnv* env,
        jclass clazz,
        jint sockFd,
        jobject directBuffer,
        jint length,
        jint destPort) {
    if (sockFd < 0 || !directBuffer || length <= 0) return JNI_FALSE;
    void* bufAddr = (*env)->GetDirectBufferAddress(env, directBuffer);
    if (!bufAddr) return JNI_FALSE;

    struct sockaddr_in destAddr;
    memset(&destAddr, 0, sizeof(destAddr));
    destAddr.sin_family = AF_INET;
    destAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    destAddr.sin_port = htons((uint16_t)destPort);

    ssize_t sent = sendto(
        sockFd,
        bufAddr,
        (size_t)length,
        MSG_DONTWAIT,
        (struct sockaddr*)&destAddr,
        sizeof(destAddr)
    );
    return sent == length ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_winlator_winhandler_WinHandler_nativeRecvBlockingPacket(
        JNIEnv* env,
        jclass clazz,
        jint sockFd,
        jobject directBuffer,
        jint maxLength) {
    if (sockFd < 0 || !directBuffer || maxLength <= 0) return -1;
    void* bufAddr = (*env)->GetDirectBufferAddress(env, directBuffer);
    if (!bufAddr) return -1;

    ssize_t received = recvfrom(sockFd, bufAddr, (size_t)maxLength, 0, NULL, NULL);
    return (jint)received;
}

JNIEXPORT void JNICALL
Java_com_winlator_winhandler_WinHandler_nativeCloseUdpSocket(JNIEnv* env, jclass clazz, jint sockFd) {
    if (sockFd >= 0) {
        close(sockFd);
    }
}

/*
 * Native C SA-MP UDP Query Protocol ('SAMP' header + 4-byte IPv4 + 2-byte port + opcode 'i' / 'p').
 * Returns a pipe-delimited string:
 *   "ONLINE|<pingMs>|<passwordFlag>|<players>|<maxPlayers>|<hostname>|<gamemode>|<language>"
 * or "OFFLINE|<reason>" if unreachable or invalid response.
 */
JNIEXPORT jstring JNICALL
Java_com_example_engine_NativeBridge_nativeQuerySampServer(
        JNIEnv* env,
        jclass clazz,
        jstring hostJStr,
        jint port,
        jint timeoutMs) {
    if (!hostJStr || port <= 0 || port > 65535) {
        return (*env)->NewStringUTF(env, "OFFLINE|Invalid host or port");
    }

    const char* hostStr = (*env)->GetStringUTFChars(env, hostJStr, NULL);
    if (!hostStr) {
        return (*env)->NewStringUTF(env, "OFFLINE|Memory error");
    }

    struct addrinfo hints;
    struct addrinfo* res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;

    int gaiErr = getaddrinfo(hostStr, NULL, &hints, &res);
    (*env)->ReleaseStringUTFChars(env, hostJStr, hostStr);

    if (gaiErr != 0 || !res) {
        return (*env)->NewStringUTF(env, "OFFLINE|DNS lookup failed");
    }

    struct sockaddr_in serverAddr;
    memset(&serverAddr, 0, sizeof(serverAddr));
    memcpy(&serverAddr, res->ai_addr, sizeof(struct sockaddr_in));
    serverAddr.sin_port = htons((uint16_t)port);
    freeaddrinfo(res);

    int sockFd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    if (sockFd < 0) {
        return (*env)->NewStringUTF(env, "OFFLINE|Socket creation failed");
    }

    int clampedTimeout = timeoutMs > 200 ? timeoutMs : 1500;
    struct timeval tv;
    tv.tv_sec = clampedTimeout / 1000;
    tv.tv_usec = (clampedTimeout % 1000) * 1000;
    setsockopt(sockFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(sockFd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    uint32_t ipNet = serverAddr.sin_addr.s_addr;
    uint8_t ipBytes[4];
    memcpy(ipBytes, &ipNet, 4);

    uint8_t packet[11];
    packet[0] = 'S';
    packet[1] = 'A';
    packet[2] = 'M';
    packet[3] = 'P';
    packet[4] = ipBytes[0];
    packet[5] = ipBytes[1];
    packet[6] = ipBytes[2];
    packet[7] = ipBytes[3];
    packet[8] = (uint8_t)(port & 0xFF);
    packet[9] = (uint8_t)((port >> 8) & 0xFF);
    packet[10] = 'i';

    uint64_t tStart = monotonicMillis();
    ssize_t sent = sendto(sockFd, packet, sizeof(packet), 0, (struct sockaddr*)&serverAddr, sizeof(serverAddr));
    if (sent != sizeof(packet)) {
        close(sockFd);
        return (*env)->NewStringUTF(env, "OFFLINE|Network unreachable");
    }

    uint8_t recvBuf[2048];
    ssize_t received = recvfrom(sockFd, recvBuf, sizeof(recvBuf) - 1, 0, NULL, NULL);
    uint64_t tEnd = monotonicMillis();
    close(sockFd);

    if (received < 11) {
        return (*env)->NewStringUTF(env, "OFFLINE|Timeout");
    }

    if (recvBuf[0] != 'S' || recvBuf[1] != 'A' || recvBuf[2] != 'M' || recvBuf[3] != 'P' || recvBuf[10] != 'i') {
        return (*env)->NewStringUTF(env, "OFFLINE|Invalid SAMP header");
    }

    int offset = 11;
    if (offset + 5 > received) {
        return (*env)->NewStringUTF(env, "OFFLINE|Truncated SAMP payload");
    }

    uint8_t passwordFlag = recvBuf[offset];
    offset += 1;

    uint16_t players = (uint16_t)recvBuf[offset] | ((uint16_t)recvBuf[offset + 1] << 8);
    offset += 2;

    uint16_t maxPlayers = (uint16_t)recvBuf[offset] | ((uint16_t)recvBuf[offset + 1] << 8);
    offset += 2;

    char hostname[256] = {0};
    char gamemode[128] = {0};
    char language[128] = {0};

    if (offset + 4 <= received) {
        uint32_t hostLen = (uint32_t)recvBuf[offset] |
                           ((uint32_t)recvBuf[offset + 1] << 8) |
                           ((uint32_t)recvBuf[offset + 2] << 16) |
                           ((uint32_t)recvBuf[offset + 3] << 24);
        offset += 4;
        if (hostLen < 512 && offset + (int)hostLen <= received) {
            uint32_t copyLen = hostLen < sizeof(hostname) - 1 ? hostLen : sizeof(hostname) - 1;
            memcpy(hostname, recvBuf + offset, copyLen);
            hostname[copyLen] = '\0';
            offset += (int)hostLen;
        }
    }

    if (offset + 4 <= received) {
        uint32_t gmLen = (uint32_t)recvBuf[offset] |
                         ((uint32_t)recvBuf[offset + 1] << 8) |
                         ((uint32_t)recvBuf[offset + 2] << 16) |
                         ((uint32_t)recvBuf[offset + 3] << 24);
        offset += 4;
        if (gmLen < 256 && offset + (int)gmLen <= received) {
            uint32_t copyLen = gmLen < sizeof(gamemode) - 1 ? gmLen : sizeof(gamemode) - 1;
            memcpy(gamemode, recvBuf + offset, copyLen);
            gamemode[copyLen] = '\0';
            offset += (int)gmLen;
        }
    }

    if (offset + 4 <= received) {
        uint32_t langLen = (uint32_t)recvBuf[offset] |
                           ((uint32_t)recvBuf[offset + 1] << 8) |
                           ((uint32_t)recvBuf[offset + 2] << 16) |
                           ((uint32_t)recvBuf[offset + 3] << 24);
        offset += 4;
        if (langLen < 256 && offset + (int)langLen <= received) {
            uint32_t copyLen = langLen < sizeof(language) - 1 ? langLen : sizeof(language) - 1;
            memcpy(language, recvBuf + offset, copyLen);
            language[copyLen] = '\0';
        }
    }

    // Sanitize pipe characters and non-ASCII bytes so UTF-8 JNI string creation never fails
    for (int i = 0; hostname[i] != '\0'; i++) {
        if (hostname[i] == '|' || (uint8_t)hostname[i] < 32 || (uint8_t)hostname[i] > 126) hostname[i] = ' ';
    }
    for (int i = 0; gamemode[i] != '\0'; i++) {
        if (gamemode[i] == '|' || (uint8_t)gamemode[i] < 32 || (uint8_t)gamemode[i] > 126) gamemode[i] = ' ';
    }
    for (int i = 0; language[i] != '\0'; i++) {
        if (language[i] == '|' || (uint8_t)language[i] < 32 || (uint8_t)language[i] > 126) language[i] = ' ';
    }

    uint32_t pingMs = (uint32_t)(tEnd >= tStart ? (tEnd - tStart) : 1);
    char resultBuf[768] = {0};
    snprintf(
        resultBuf,
        sizeof(resultBuf),
        "ONLINE|%u|%u|%u|%u|%s|%s|%s",
        pingMs,
        (unsigned int)passwordFlag,
        (unsigned int)players,
        (unsigned int)maxPlayers,
        hostname[0] ? hostname : "SA-MP Server",
        gamemode[0] ? gamemode : "Default",
        language[0] ? language : "Global"
    );
    return (*env)->NewStringUTF(env, resultBuf);
}
