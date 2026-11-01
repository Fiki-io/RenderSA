package com.winlator.xserver;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import com.winlator.widget.InputControlsView;
import com.winlator.widget.XServerView;

public interface XSessionHost {
    Activity getHostActivity();
    ApplicationInfo getApplicationInfo();
    SharedPreferences getPreferences();
    XServer getXServer();
    XServerView getXServerView();
    InputControlsView getInputControlsView();
    void onDebugLog(String message);
    default void onFramePresented(int drawableId) {}
}
