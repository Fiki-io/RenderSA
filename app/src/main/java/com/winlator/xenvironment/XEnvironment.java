package com.winlator.xenvironment;

import android.content.Context;

import com.example.diagnostic.UniversalCrashLogger;
import com.winlator.core.FileUtils;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

public class XEnvironment implements Iterable<EnvironmentComponent> {
    private final Context context;
    private final RootFS rootFS;
    private final ArrayList<EnvironmentComponent> components = new ArrayList<>();

    public XEnvironment(Context context, RootFS rootFS) {
        this.context = context;
        this.rootFS = rootFS;
    }

    public Context getContext() {
        return context;
    }

    public RootFS getRootFS() {
        return rootFS;
    }

    public void addComponent(EnvironmentComponent environmentComponent) {
        environmentComponent.environment = this;
        components.add(environmentComponent);
    }

    public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
        for (EnvironmentComponent component : components) {
            if (component.getClass() == componentClass) return (T)component;
        }
        return null;
    }

    @Override
    public Iterator<EnvironmentComponent> iterator() {
        return components.iterator();
    }

    public File getTmpDir() {
        File tmpDir = new File(context.getFilesDir(), "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
            FileUtils.chmod(tmpDir, 0771);
        }
        return tmpDir;
    }

    public void startEnvironmentComponents() {
        FileUtils.clear(getTmpDir());
        for (EnvironmentComponent environmentComponent : this) {
            String compName = environmentComponent.getClass().getSimpleName();
            UniversalCrashLogger.logCheckpoint("XEnvironment", "Memulai komponen: " + compName);
            try {
                environmentComponent.start();
                UniversalCrashLogger.logCheckpoint("XEnvironment", "Komponen aktif: " + compName);
            } catch (Throwable t) {
                UniversalCrashLogger.recordComponentFatalError(compName, "start()", t);
                throw t;
            }
        }
    }

    public void stopEnvironmentComponents() {
        GuestProgramLauncherComponent launcher = getComponent(GuestProgramLauncherComponent.class);
        if (launcher != null) {
            try {
                launcher.stop();
            } catch (Throwable t) {
                UniversalCrashLogger.logCheckpoint(
                    "XEnvironment",
                    "Error saat menghentikan GuestProgramLauncherComponent: " + t.getMessage()
                );
            }
        }

        for (int i = components.size() - 1; i >= 0; i--) {
            EnvironmentComponent environmentComponent = components.get(i);
            if (environmentComponent == launcher) continue;
            String compName = environmentComponent.getClass().getSimpleName();
            try {
                environmentComponent.stop();
            } catch (Throwable t) {
                UniversalCrashLogger.logCheckpoint(
                    "XEnvironment",
                    "Error saat menghentikan " + compName + ": " + t.getMessage()
                );
            }
        }
    }

    public void onPause() {
        for (EnvironmentComponent environmentComponent : this) environmentComponent.onPause();
    }

    public void onResume() {
        for (EnvironmentComponent environmentComponent : this) environmentComponent.onResume();
    }
}
