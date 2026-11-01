package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.TarCompressorUtils;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private static int pid = -1;
    private EnvVars envVars;
    private String box64Preset = Box64Preset.PERFORMANCE;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            extractBox64File();
            copyDefaultBox64RCFile();
            com.example.engine.RootFsPathRelocator.INSTANCE.relocateRootFsIfNeeded(
                environment.getContext(),
                environment.getRootFS().getRootDir(),
                false
            );
            pid = execGuestProgram();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public static int getCurrentPid() {
        synchronized (lock) {
            return pid;
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    private String lastFullCommand = "";
    private String lastEnvSummary = "";
    private long lastStartTimestampMs = 0L;

    public String getLastFullCommand() {
        return lastFullCommand;
    }

    public String getLastEnvSummary() {
        return lastEnvSummary;
    }

    public long getLastStartTimestampMs() {
        return lastStartTimestampMs;
    }

    private int execGuestProgram() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();

        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars);
        envVars.put("LC_ALL", "en_US.utf8");
        envVars.put("LANG", "en_US.utf8");

        envVars.put("HOME", rootDir + RootFS.HOME_PATH);
        envVars.put("WINEPREFIX", rootDir + RootFS.HOME_PATH + "/.wine");
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir + "/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir + rootFS.getWinePath() + "/bin:" + rootDir + "/usr/local/bin:" + rootDir + "/usr/bin:" + rootDir + "/bin");
        envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath() + ":" + rootDir + "/usr/lib:" + rootDir + "/usr/lib/aarch64-linux-gnu");
        envVars.put(
            "BOX64_LD_LIBRARY_PATH",
            rootDir + "/usr/lib/x86_64-linux-gnu:" +
                rootDir + "/lib/x86_64-linux-gnu:" +
                rootDir + rootFS.getWinePath() + "/lib/wine/x86_64-unix:" +
                rootDir + rootFS.getWinePath() + "/lib/wine/i386-unix"
        );
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("ANDROID_ALSA_SERVER", rootDir + UnixSocketConfig.ALSA_SERVER_PATH);
        envVars.put("ANDROID_ASERVER_USE_SHM", "true");
        envVars.put("ALSA_CONFIG_PATH", rootDir + "/usr/share/alsa/alsa.conf");
        envVars.put("ALSA_CONFIG_DIR", rootDir + "/usr/share/alsa");
        envVars.put("ALSA_PLUGIN_DIR", rootDir + "/usr/lib/alsa-lib");
        envVars.put("LIBGL_DRIVERS_PATH", rootDir + "/usr/lib/dri:" + rootDir + "/usr/lib/aarch64-linux-gnu/dri");
        envVars.put("FONTCONFIG_PATH", rootDir + "/etc/fonts");
        envVars.put("FONTCONFIG_FILE", rootDir + "/etc/fonts/fonts.conf");
        envVars.put("XKB_CONFIG_ROOT", rootDir + "/usr/share/X11/xkb");
        envVars.put("XLOCALEDIR", rootDir + "/usr/share/X11/locale");
        envVars.put("WINEDEBUG", "+err,+warn,+loaddll");
        envVars.put("WINEESYNC", "1");

        if (this.envVars != null) envVars.putAll(this.envVars);

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        File box64Bin = new File(rootDir, "/usr/local/bin/box64");
        if (!box64Bin.isFile()) {
            File box64Alt = new File(rootDir, "/usr/bin/box64");
            if (box64Alt.isFile()) {
                box64Bin = box64Alt;
            }
        }
        if (box64Bin.exists()) {
            FileUtils.chmod(box64Bin, 0755);
        }

        String command = box64Bin.getAbsolutePath() + " " + guestExecutable;
        lastFullCommand = command;
        lastEnvSummary = envVars.toString().replace(" ", "\n  ");
        lastStartTimestampMs = System.currentTimeMillis();

        return ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    private void extractBox64File() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String box64Version = preferences.getString("box64_version", DefaultVersion.BOX64);
        String currentBox64Version = preferences.getString("current_box64_version", "");
        File box64Bin = new File(rootFS.getRootDir(), "/usr/local/bin/box64");

        if (!box64Version.equals(currentBox64Version) || !box64Bin.isFile()) {
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "box64/box64-" + DefaultVersion.BOX64 + ".tzst",
                rootFS.getRootDir()
            );
            preferences.edit().putString("current_box64_version", box64Version).apply();
        }
        if (box64Bin.exists()) {
            FileUtils.chmod(box64Bin, 0755);
        }
    }

    private void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        FileUtils.copy(context, "box64/default.box64rc", new File(rootFS.getRootDir(), "/etc/config.box64rc"));
    }

    private void addBox64EnvVars(EnvVars envVars) {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();

        File dynacacheDir = new File(rootFS.getRootDir(), "/tmp/box64_cache");
        if (!dynacacheDir.isDirectory()) dynacacheDir.mkdirs();

        envVars.put("BOX64_NOBANNER", "0");
        envVars.put("BOX64_LOG", "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_UNITYPLAYER", "0");
        envVars.put("BOX64_DYNACACHE", "1");
        envVars.put("BOX64_DYNACACHE_FOLDER", dynacacheDir.getAbsolutePath());

        envVars.putAll(Box64PresetManager.getEnvVars(context, box64Preset));

        File box64RCFile = new File(rootFS.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size() - 1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}