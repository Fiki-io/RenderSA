#define _GNU_SOURCE
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <unistd.h>
#include <dirent.h>
#include <sched.h>
#include <ctype.h>
#include <sys/system_properties.h>

#define MAX_CORES 16

typedef struct CpuTopology {
    int totalCores;
    uint32_t freqKhz[MAX_CORES];
    uint32_t maxFreqKhz;
    uint32_t minFreqKhz;
    uint32_t perfCoreMask;
    uint32_t effCoreMask;
    uint32_t allCoreMask;
    char socModel[128];
} CpuTopology;

static uint32_t readCoreMaxFreqKhz(int coreIdx) {
    char path[128];
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", coreIdx);
    FILE* f = fopen(path, "r");
    if (!f) {
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq", coreIdx);
        f = fopen(path, "r");
    }
    if (!f) return 0;
    unsigned int khz = 0;
    if (fscanf(f, "%u", &khz) != 1) {
        khz = 0;
    }
    fclose(f);
    return (uint32_t)khz;
}

static void detectCpuTopology(CpuTopology* topo) {
    memset(topo, 0, sizeof(CpuTopology));

    char propVal[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.soc.model", propVal) > 0 && propVal[0] != '\0') {
        strncpy(topo->socModel, propVal, sizeof(topo->socModel) - 1);
    } else if (__system_property_get("ro.board.platform", propVal) > 0 && propVal[0] != '\0') {
        strncpy(topo->socModel, propVal, sizeof(topo->socModel) - 1);
    } else {
        strncpy(topo->socModel, "ARM64 SoC", sizeof(topo->socModel) - 1);
    }

    long configured = sysconf(_SC_NPROCESSORS_CONF);
    if (configured <= 0) configured = 8;
    if (configured > MAX_CORES) configured = MAX_CORES;
    topo->totalCores = (int)configured;

    topo->maxFreqKhz = 0;
    topo->minFreqKhz = UINT32_MAX;

    for (int i = 0; i < topo->totalCores; i++) {
        uint32_t f = readCoreMaxFreqKhz(i);
        topo->freqKhz[i] = f;
        topo->allCoreMask |= (1u << i);
        if (f > topo->maxFreqKhz) topo->maxFreqKhz = f;
        if (f > 0 && f < topo->minFreqKhz) topo->minFreqKhz = f;
    }

    if (topo->minFreqKhz == UINT32_MAX) topo->minFreqKhz = 0;

    if (topo->maxFreqKhz > 0 && topo->maxFreqKhz > topo->minFreqKhz) {
        uint32_t threshold = topo->minFreqKhz + ((topo->maxFreqKhz - topo->minFreqKhz) / 3);
        for (int i = 0; i < topo->totalCores; i++) {
            if (topo->freqKhz[i] > threshold) {
                topo->perfCoreMask |= (1u << i);
            } else {
                topo->effCoreMask |= (1u << i);
            }
        }
    }

    // Fallback if frequencies could not be read due to SELinux or uniform clock cores:
    // on 8-core ARM64 SoCs (like Dimensity 7400 / Snapdragon), cores 4..7 are typically P-Cores
    if (topo->perfCoreMask == 0) {
        if (topo->totalCores >= 8) {
            topo->perfCoreMask = 0xF0u; // cores 4,5,6,7
            topo->effCoreMask = 0x0Fu;  // cores 0,1,2,3
        } else {
            topo->perfCoreMask = topo->allCoreMask;
            topo->effCoreMask = topo->allCoreMask;
        }
    }
    if (topo->effCoreMask == 0) {
        topo->effCoreMask = topo->allCoreMask;
    }
}

static void applyAffinityMaskToPidAndTasks(int pid, uint32_t mask) {
    if (mask == 0 || pid <= 0) return;

    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int i = 0; i < MAX_CORES; i++) {
        if (mask & (1u << i)) {
            CPU_SET(i, &cpuset);
        }
    }

    sched_setaffinity((pid_t)pid, sizeof(cpu_set_t), &cpuset);

    char taskDirPath[64];
    snprintf(taskDirPath, sizeof(taskDirPath), "/proc/%d/task", pid);
    DIR* taskDir = opendir(taskDirPath);
    if (!taskDir) return;

    struct dirent* entry;
    while ((entry = readdir(taskDir)) != NULL) {
        if (!isdigit((unsigned char)entry->d_name[0])) continue;
        int tid = atoi(entry->d_name);
        if (tid > 0) {
            sched_setaffinity((pid_t)tid, sizeof(cpu_set_t), &cpuset);
        }
    }
    closedir(taskDir);
}

JNIEXPORT jintArray JNICALL
Java_com_example_engine_NativeBridge_nativeGetCpuTopologyInfo(JNIEnv* env, jclass clazz) {
    CpuTopology topo;
    detectCpuTopology(&topo);

    int pCoreCount = 0;
    for (int i = 0; i < topo.totalCores; i++) {
        if (topo.perfCoreMask & (1u << i)) {
            pCoreCount++;
        }
    }
    if (pCoreCount <= 0) {
        pCoreCount = topo.totalCores;
    }

    jint values[6];
    values[0] = (jint)topo.totalCores;
    values[1] = (jint)topo.perfCoreMask;
    values[2] = (jint)topo.effCoreMask;
    values[3] = (jint)topo.maxFreqKhz;
    values[4] = (jint)topo.minFreqKhz;
    values[5] = (jint)pCoreCount;

    jintArray result = (*env)->NewIntArray(env, 6);
    if (!result) return NULL;
    (*env)->SetIntArrayRegion(env, result, 0, 6, values);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_example_engine_NativeBridge_nativeSetCurrentThreadAffinity(JNIEnv* env, jclass clazz, jint coreMask) {
    if (coreMask <= 0) return JNI_FALSE;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int i = 0; i < MAX_CORES; i++) {
        if (((uint32_t)coreMask) & (1u << i)) {
            CPU_SET(i, &cpuset);
        }
    }
    return sched_setaffinity(0, sizeof(cpu_set_t), &cpuset) == 0 ? JNI_TRUE : JNI_FALSE;
}

/*
 * Native C /proc process scanner and CPU core affinity scheduler.
 * Pins gta_sa.exe / box64 game threads to perfCoreMask and background wineserver/services.exe to effCoreMask.
 * Returns the number of matching game processes tuned.
 */
JNIEXPORT jint JNICALL
Java_com_example_engine_NativeBridge_nativeOptimizeRunningGameProcesses(
        JNIEnv* env,
        jclass clazz,
        jint perfCoreMask,
        jint effCoreMask) {
    DIR* procDir = opendir("/proc");
    if (!procDir) return 0;

    uid_t myUid = getuid();
    int tunedCount = 0;
    struct dirent* entry;

    while ((entry = readdir(procDir)) != NULL) {
        if (!isdigit((unsigned char)entry->d_name[0])) continue;
        int pid = atoi(entry->d_name);
        if (pid <= 1) continue;

        char cmdlinePath[64];
        snprintf(cmdlinePath, sizeof(cmdlinePath), "/proc/%d/cmdline", pid);
        FILE* f = fopen(cmdlinePath, "rb");
        if (!f) continue;

        char cmdBuf[512] = {0};
        size_t n = fread(cmdBuf, 1, sizeof(cmdBuf) - 1, f);
        fclose(f);
        if (n == 0) continue;

        for (size_t i = 0; i < n; i++) {
            if (cmdBuf[i] == '\0') cmdBuf[i] = ' ';
        }
        cmdBuf[n] = '\0';

        if (strstr(cmdBuf, "gta_sa.exe") != NULL ||
            strstr(cmdBuf, "samp.dll") != NULL ||
            strstr(cmdBuf, "winhandler.exe") != NULL) {
            applyAffinityMaskToPidAndTasks(pid, (uint32_t)perfCoreMask);
            tunedCount++;
        } else if (strstr(cmdBuf, "wineserver") != NULL ||
                   strstr(cmdBuf, "services.exe") != NULL ||
                   strstr(cmdBuf, "plugplay.exe") != NULL ||
                   strstr(cmdBuf, "rpcss.exe") != NULL) {
            applyAffinityMaskToPidAndTasks(pid, (uint32_t)effCoreMask);
        }
    }

    closedir(procDir);
    return tunedCount;
}
