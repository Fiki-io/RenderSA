#define _GNU_SOURCE
#include <jni.h>
#include <signal.h>
#include <ucontext.h>
#include <unwind.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <elf.h>
#include <android/log.h>

#define TAG "RenderSANativeCrash"
#define ALT_STACK_SIZE (64 * 1024)
#define MAX_BACKTRACE_FRAMES 32

static char g_alt_stack_mem[ALT_STACK_SIZE];
static char g_crash_file_path[512] = {0};
static char g_session_log_path[512] = {0};
static char g_last_component[128] = "INIT";
static char g_last_checkpoint[384] = "Library libwinlator.so dimuat";
static volatile sig_atomic_t g_handlers_installed = 0;
static volatile sig_atomic_t g_handling_signal = 0;

static const int HANDLED_SIGNALS[] = {
    SIGSEGV,
    SIGABRT,
    SIGBUS,
    SIGILL,
    SIGFPE,
    SIGSYS,
    SIGTRAP
};
#define NUM_HANDLED_SIGNALS (sizeof(HANDLED_SIGNALS) / sizeof(HANDLED_SIGNALS[0]))
static struct sigaction g_old_actions[NUM_HANDLED_SIGNALS];

static void safe_write_str(int fd, const char* s) {
    if (fd < 0 || !s) return;
    size_t len = 0;
    while (s[len] != '\0') len++;
    if (len > 0) {
        ssize_t unused = write(fd, s, len);
        (void)unused;
    }
}

static void safe_write_hex64(int fd, uint64_t val) {
    char buf[19];
    buf[0] = '0';
    buf[1] = 'x';
    const char* hex = "0123456789abcdef";
    for (int i = 15; i >= 0; i--) {
        buf[2 + (15 - i)] = hex[(val >> (i * 4)) & 0xF];
    }
    buf[18] = '\0';
    safe_write_str(fd, buf);
}

static void safe_write_dec(int fd, long val) {
    char buf[32];
    int pos = 31;
    buf[pos] = '\0';
    int neg = 0;
    unsigned long uval;
    if (val < 0) {
        neg = 1;
        uval = (unsigned long)(-val);
    } else {
        uval = (unsigned long)val;
    }
    if (uval == 0) {
        buf[--pos] = '0';
    } else {
        while (uval > 0 && pos > 1) {
            buf[--pos] = (char)('0' + (uval % 10));
            uval /= 10;
        }
    }
    if (neg && pos > 0) {
        buf[--pos] = '-';
    }
    safe_write_str(fd, &buf[pos]);
}

static const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV (Invalid Memory Reference / Segmentation Fault)";
        case SIGABRT: return "SIGABRT (Abort Signal / Native Assertion)";
        case SIGBUS:  return "SIGBUS (Bus Error / Misaligned Memory or Bad mmap)";
        case SIGILL:  return "SIGILL (Illegal CPU Instruction)";
        case SIGFPE:  return "SIGFPE (Floating-Point / Arithmetic Exception)";
        case SIGSYS:  return "SIGSYS (Bad System Call / Seccomp Blocked Syscall)";
        case SIGTRAP: return "SIGTRAP (Trace / Breakpoint Trap)";
        default:      return "UNKNOWN_SIGNAL";
    }
}

static const char* sigcode_description(int sig, int code) {
    if (sig == SIGSEGV) {
        if (code == SEGV_MAPERR) return "SEGV_MAPERR (Address not mapped to object)";
        if (code == SEGV_ACCERR) return "SEGV_ACCERR (Invalid permissions for mapped object)";
    } else if (sig == SIGBUS) {
        if (code == BUS_ADRALN) return "BUS_ADRALN (Invalid address alignment)";
        if (code == BUS_ADRERR) return "BUS_ADRERR (Nonexistent physical address)";
        if (code == BUS_OBJERR) return "BUS_OBJERR (Object-specific hardware error)";
    } else if (sig == SIGILL) {
        if (code == ILL_ILLOPC) return "ILL_ILLOPC (Illegal opcode)";
        if (code == ILL_ILLOPN) return "ILL_ILLOPN (Illegal operand)";
        if (code == ILL_PRVOPC) return "ILL_PRVOPC (Privileged opcode)";
    } else if (sig == SIGSYS) {
        return "SYS_SECCOMP (Blocked by Android seccomp-bpf policy)";
    }
    if (code == SI_USER) return "SI_USER (Sent by kill/raise)";
    if (code == SI_TKILL) return "SI_TKILL (Sent by tgkill/abort)";
    return "KERNEL_SIGNAL_CODE";
}

typedef struct BacktraceState {
    int fd;
    int frame_count;
} BacktraceState;

static _Unwind_Reason_Code unwind_callback(struct _Unwind_Context* context, void* arg) {
    BacktraceState* state = (BacktraceState*)arg;
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc) {
        safe_write_str(state->fd, "  #");
        if (state->frame_count < 10) safe_write_str(state->fd, "0");
        safe_write_dec(state->fd, state->frame_count);
        safe_write_str(state->fd, " pc ");
        safe_write_hex64(state->fd, (uint64_t)pc);

        Dl_info info;
        if (dladdr((void*)pc, &info) && info.dli_fname) {
            uintptr_t rel_pc = pc - (uintptr_t)info.dli_fbase;
            safe_write_str(state->fd, "  ");
            safe_write_str(state->fd, info.dli_fname);
            safe_write_str(state->fd, " (offset ");
            safe_write_hex64(state->fd, (uint64_t)rel_pc);
            if (info.dli_sname) {
                uintptr_t sym_off = pc - (uintptr_t)info.dli_saddr;
                safe_write_str(state->fd, ", symbol: ");
                safe_write_str(state->fd, info.dli_sname);
                safe_write_str(state->fd, "+");
                safe_write_hex64(state->fd, (uint64_t)sym_off);
            }
            safe_write_str(state->fd, ")");
        } else {
            safe_write_str(state->fd, "  [anon/jit/unknown]");
        }
        safe_write_str(state->fd, "\n");
        state->frame_count++;
    }
    if (state->frame_count >= MAX_BACKTRACE_FRAMES) {
        return _URC_END_OF_STACK;
    }
    return _URC_NO_REASON;
}

static void native_signal_handler(int sig, siginfo_t* info, void* ucontext_raw) {
    if (g_handling_signal) {
        _exit(128 + sig);
    }
    g_handling_signal = 1;

    char thread_name[32] = {0};
    if (prctl(PR_GET_NAME, (unsigned long)thread_name, 0, 0, 0) != 0) {
        strcpy(thread_name, "unknown_thread");
    }

    int fd = -1;
    if (g_crash_file_path[0] != '\0') {
        fd = open(g_crash_file_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    }

    if (fd >= 0) {
        safe_write_str(fd, "=== CRASH NATIVE C/C++ TERDETEKSI (POSIX SIGNAL) ===\n");
        safe_write_str(fd, "Sinyal       : ");
        safe_write_str(fd, signal_name(sig));
        safe_write_str(fd, " (");
        safe_write_dec(fd, sig);
        safe_write_str(fd, ")\n");

        if (info != NULL) {
            safe_write_str(fd, "Kode Sinyal  : ");
            safe_write_str(fd, sigcode_description(sig, info->si_code));
            safe_write_str(fd, " (si_code=");
            safe_write_dec(fd, info->si_code);
            safe_write_str(fd, ", si_errno=");
            safe_write_dec(fd, info->si_errno);
            safe_write_str(fd, ")\n");

            safe_write_str(fd, "Fault Addr   : ");
            safe_write_hex64(fd, (uint64_t)(uintptr_t)info->si_addr);
            safe_write_str(fd, "\n");
        }

        safe_write_str(fd, "PID / TID    : ");
        safe_write_dec(fd, (long)getpid());
        safe_write_str(fd, " / ");
        safe_write_dec(fd, (long)syscall(SYS_gettid));
        safe_write_str(fd, " (Thread: ");
        safe_write_str(fd, thread_name);
        safe_write_str(fd, ")\n");

        safe_write_str(fd, "Komponen Aktif: ");
        safe_write_str(fd, g_last_component);
        safe_write_str(fd, "\n");
        safe_write_str(fd, "Checkpoint   : ");
        safe_write_str(fd, g_last_checkpoint);
        safe_write_str(fd, "\n\n");

#if defined(__aarch64__)
        if (ucontext_raw != NULL) {
            ucontext_t* uc = (ucontext_t*)ucontext_raw;
            safe_write_str(fd, "--- REGISTER CPU ARM64 (AArch64) ---\n");
            safe_write_str(fd, "  PC (Program Counter) : ");
            safe_write_hex64(fd, (uint64_t)uc->uc_mcontext.pc);
            safe_write_str(fd, "\n  LR (x30 Link Reg)    : ");
            safe_write_hex64(fd, (uint64_t)uc->uc_mcontext.regs[30]);
            safe_write_str(fd, "\n  SP (Stack Pointer)   : ");
            safe_write_hex64(fd, (uint64_t)uc->uc_mcontext.sp);
            safe_write_str(fd, "\n  FP (x29 Frame Ptr)   : ");
            safe_write_hex64(fd, (uint64_t)uc->uc_mcontext.regs[29]);
            safe_write_str(fd, "\n  Fault Address (FAR)  : ");
            safe_write_hex64(fd, (uint64_t)uc->uc_mcontext.fault_address);
            safe_write_str(fd, "\n  PSTATE               : ");
            safe_write_hex64(fd, (uint64_t)uc->uc_mcontext.pstate);
            safe_write_str(fd, "\n  X0=");
            safe_write_hex64(fd, (uint64_t)uc->uc_mcontext.regs[0]);
            safe_write_str(fd, " X1=");
            safe_write_hex64(fd, (uint64_t)uc->uc_mcontext.regs[1]);
            safe_write_str(fd, " X2=");
            safe_write_hex64(fd, (uint64_t)uc->uc_mcontext.regs[2]);
            safe_write_str(fd, " X3=");
            safe_write_hex64(fd, (uint64_t)uc->uc_mcontext.regs[3]);
            safe_write_str(fd, "\n\n");
        }
#endif

        safe_write_str(fd, "--- BACKTRACE NATIVE STACK ---\n");
        BacktraceState bt_state = { .fd = fd, .frame_count = 0 };
        _Unwind_Backtrace(unwind_callback, &bt_state);
        fsync(fd);
        close(fd);
    }

    if (g_session_log_path[0] != '\0') {
        int sfd = open(g_session_log_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (sfd >= 0) {
            safe_write_str(sfd, "\n[FATAL-NATIVE-SIGNAL] ");
            safe_write_str(sfd, signal_name(sig));
            safe_write_str(sfd, " pada thread ");
            safe_write_str(sfd, thread_name);
            safe_write_str(sfd, " | Checkpoint: ");
            safe_write_str(sfd, g_last_checkpoint);
            safe_write_str(sfd, "\n");
            fsync(sfd);
            close(sfd);
        }
    }

    __android_log_print(
        ANDROID_LOG_FATAL,
        TAG,
        "Fatal native signal %d (%s) in thread %s at checkpoint [%s: %s]",
        sig,
        signal_name(sig),
        thread_name,
        g_last_component,
        g_last_checkpoint
    );

    // Chain to previous handler or default termination
    for (size_t i = 0; i < NUM_HANDLED_SIGNALS; i++) {
        if (HANDLED_SIGNALS[i] == sig) {
            sigaction(sig, &g_old_actions[i], NULL);
            break;
        }
    }
    raise(sig);
}

JNIEXPORT void JNICALL
Java_com_example_diagnostic_UniversalCrashLogger_nativeInstallSignalHandlers(
    JNIEnv* env,
    jclass clazz,
    jstring crashFilePath,
    jstring sessionLogPath
) {
    (void)clazz;
    if (crashFilePath != NULL) {
        const char* cpath = (*env)->GetStringUTFChars(env, crashFilePath, NULL);
        if (cpath) {
            strncpy(g_crash_file_path, cpath, sizeof(g_crash_file_path) - 1);
            g_crash_file_path[sizeof(g_crash_file_path) - 1] = '\0';
            (*env)->ReleaseStringUTFChars(env, crashFilePath, cpath);
        }
    }
    if (sessionLogPath != NULL) {
        const char* spath = (*env)->GetStringUTFChars(env, sessionLogPath, NULL);
        if (spath) {
            strncpy(g_session_log_path, spath, sizeof(g_session_log_path) - 1);
            g_session_log_path[sizeof(g_session_log_path) - 1] = '\0';
            (*env)->ReleaseStringUTFChars(env, sessionLogPath, spath);
        }
    }

    if (g_handlers_installed) return;

    stack_t ss;
    ss.ss_sp = g_alt_stack_mem;
    ss.ss_size = sizeof(g_alt_stack_mem);
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = native_signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;

    for (size_t i = 0; i < NUM_HANDLED_SIGNALS; i++) {
        sigaction(HANDLED_SIGNALS[i], &sa, &g_old_actions[i]);
    }
    g_handlers_installed = 1;
}

JNIEXPORT void JNICALL
Java_com_example_diagnostic_UniversalCrashLogger_nativeSetCheckpoint(
    JNIEnv* env,
    jclass clazz,
    jstring component,
    jstring message
) {
    (void)clazz;
    if (component != NULL) {
        const char* cstr = (*env)->GetStringUTFChars(env, component, NULL);
        if (cstr) {
            strncpy(g_last_component, cstr, sizeof(g_last_component) - 1);
            g_last_component[sizeof(g_last_component) - 1] = '\0';
            (*env)->ReleaseStringUTFChars(env, component, cstr);
        }
    }
    if (message != NULL) {
        const char* mstr = (*env)->GetStringUTFChars(env, message, NULL);
        if (mstr) {
            strncpy(g_last_checkpoint, mstr, sizeof(g_last_checkpoint) - 1);
            g_last_checkpoint[sizeof(g_last_checkpoint) - 1] = '\0';
            (*env)->ReleaseStringUTFChars(env, message, mstr);
        }
    }
}

JNIEXPORT jstring JNICALL
Java_com_example_diagnostic_UniversalCrashLogger_nativeInspectBinaryPath(
    JNIEnv* env,
    jclass clazz,
    jstring pathJ
) {
    (void)clazz;
    if (pathJ == NULL) return (*env)->NewStringUTF(env, "Path kosong");

    const char* path = (*env)->GetStringUTFChars(env, pathJ, NULL);
    if (!path) return (*env)->NewStringUTF(env, "Gagal membaca string path");

    char report[2048];
    memset(report, 0, sizeof(report));
    int offset = 0;

    struct stat lst;
    int lstat_res = lstat(path, &lst);
    if (lstat_res != 0) {
        snprintf(
            report,
            sizeof(report),
            "Path: %s\n  Status: TIDAK DITEMUKAN (lstat errno=%d: %s)",
            path,
            errno,
            strerror(errno)
        );
        (*env)->ReleaseStringUTFChars(env, pathJ, path);
        return (*env)->NewStringUTF(env, report);
    }

    offset += snprintf(
        report + offset,
        sizeof(report) - offset,
        "Path: %s\n  Mode Octal: 0%o | Size: %lld bytes",
        path,
        (unsigned int)(lst.st_mode & 07777),
        (long long)lst.st_size
    );

    if (S_ISLNK(lst.st_mode)) {
        char link_target[512] = {0};
        ssize_t rlen = readlink(path, link_target, sizeof(link_target) - 1);
        if (rlen >= 0) {
            link_target[rlen] = '\0';
            offset += snprintf(
                report + offset,
                sizeof(report) - offset,
                "\n  Symlink -> %s",
                link_target
            );
        }
    }

    struct stat st;
    if (stat(path, &st) != 0) {
        offset += snprintf(
            report + offset,
            sizeof(report) - offset,
            "\n  Target Symlink RUSAK (stat errno=%d: %s)",
            errno,
            strerror(errno)
        );
        (*env)->ReleaseStringUTFChars(env, pathJ, path);
        return (*env)->NewStringUTF(env, report);
    }

    errno = 0;
    int r_ok = access(path, R_OK);
    int r_err = errno;
    errno = 0;
    int x_ok = access(path, X_OK);
    int x_err = errno;

    offset += snprintf(
        report + offset,
        sizeof(report) - offset,
        "\n  Akses Baca (R_OK): %s (errno=%d: %s)\n  Akses Eksekusi (X_OK): %s (errno=%d: %s)",
        (r_ok == 0) ? "YA" : "TIDAK",
        r_ok == 0 ? 0 : r_err,
        r_ok == 0 ? "OK" : strerror(r_err),
        (x_ok == 0) ? "YA" : "TIDIBLOKIR/TIDAK",
        x_ok == 0 ? 0 : x_err,
        x_ok == 0 ? "OK" : strerror(x_err)
    );

    int fd = open(path, O_RDONLY);
    if (fd >= 0) {
        unsigned char buf[4096];
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n >= 4 && buf[0] == 0x7F && buf[1] == 'E' && buf[2] == 'L' && buf[3] == 'F') {
            int elf_class = buf[4];
            if (elf_class == ELFCLASS64 && n >= (ssize_t)sizeof(Elf64_Ehdr)) {
                Elf64_Ehdr* ehdr = (Elf64_Ehdr*)buf;
                const char* arch = (ehdr->e_machine == EM_AARCH64) ? "ARM64 (AArch64)" :
                                   (ehdr->e_machine == EM_X86_64) ? "x86_64" : "Lainnya";
                offset += snprintf(
                    report + offset,
                    sizeof(report) - offset,
                    "\n  Format: ELF 64-bit | Arsitektur: %s (machine=%d, type=%d)",
                    arch,
                    ehdr->e_machine,
                    ehdr->e_type
                );

                if (ehdr->e_phoff > 0 && ehdr->e_phnum > 0 && ehdr->e_phnum < 64) {
                    for (int i = 0; i < ehdr->e_phnum; i++) {
                        size_t ph_pos = (size_t)ehdr->e_phoff + (size_t)i * sizeof(Elf64_Phdr);
                        if (ph_pos + sizeof(Elf64_Phdr) <= (size_t)n) {
                            Elf64_Phdr* phdr = (Elf64_Phdr*)(buf + ph_pos);
                            if (phdr->p_type == PT_INTERP && phdr->p_filesz > 0 && phdr->p_filesz < 256) {
                                char interp[256] = {0};
                                if (pread(fd, interp, phdr->p_filesz, (off_t)phdr->p_offset) > 0) {
                                    interp[255] = '\0';
                                    int interp_exists = (access(interp, F_OK) == 0);
                                    offset += snprintf(
                                        report + offset,
                                        sizeof(report) - offset,
                                        "\n  ELF PT_INTERP (Dynamic Linker): %s [Ada di Host: %s]",
                                        interp,
                                        interp_exists ? "YA" : "TIDAK ADA di root Android (/)"
                                    );
                                }
                            }
                        }
                    }
                }
            } else if (elf_class == ELFCLASS32 && n >= (ssize_t)sizeof(Elf32_Ehdr)) {
                Elf32_Ehdr* ehdr = (Elf32_Ehdr*)buf;
                offset += snprintf(
                    report + offset,
                    sizeof(report) - offset,
                    "\n  Format: ELF 32-bit (machine=%d, type=%d)",
                    ehdr->e_machine,
                    ehdr->e_type
                );
            }
        } else if (n >= 2 && buf[0] == '#' && buf[1] == '!') {
            char shebang[128] = {0};
            int s_idx = 0;
            while (s_idx < n && s_idx < 127 && buf[s_idx] != '\n' && buf[s_idx] != '\r') {
                shebang[s_idx] = (char)buf[s_idx];
                s_idx++;
            }
            shebang[s_idx] = '\0';
            offset += snprintf(
                report + offset,
                sizeof(report) - offset,
                "\n  Format: Script Shebang (%s)",
                shebang
            );
        }
        close(fd);
    }

    (*env)->ReleaseStringUTFChars(env, pathJ, path);
    return (*env)->NewStringUTF(env, report);
}

JNIEXPORT jstring JNICALL
Java_com_example_diagnostic_UniversalCrashLogger_nativeInspectMountAndSelinux(
    JNIEnv* env,
    jclass clazz
) {
    (void)clazz;
    char report[2048];
    memset(report, 0, sizeof(report));
    int offset = 0;

    char selinux_ctx[256] = {0};
    int sfd = open("/proc/self/attr/current", O_RDONLY);
    if (sfd >= 0) {
        ssize_t n = read(sfd, selinux_ctx, sizeof(selinux_ctx) - 1);
        if (n > 0) selinux_ctx[n] = '\0';
        close(sfd);
    }
    offset += snprintf(
        report + offset,
        sizeof(report) - offset,
        "SELinux Process Context: %s\n",
        selinux_ctx[0] ? selinux_ctx : "Tidak dapat dibaca"
    );

    FILE* fp = fopen("/proc/self/mountinfo", "r");
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, " /data ") != NULL) {
                offset += snprintf(
                    report + offset,
                    sizeof(report) - offset,
                    "Mount /data: %s",
                    line
                );
                break;
            }
        }
        fclose(fp);
    }

    return (*env)->NewStringUTF(env, report);
}
