package com.google.android.libraries.launcherclient;

import android.os.Bundle;
import android.view.WindowManager.LayoutParams;
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback;

// Method order defines the binder transaction codes and must match the launcher's copy exactly.
interface ILauncherOverlay {

    oneway void startScroll();

    oneway void onScroll(float progress);

    oneway void endScroll();

    oneway void windowAttached(in LayoutParams lp, ILauncherOverlayCallback cb, int flags);

    oneway void windowDetached(boolean isChangingConfigurations);

    oneway void closeOverlay(int flags);

    oneway void onPause();

    oneway void onResume();

    oneway void openOverlay(int flags);

    oneway void requestVoiceDetection(boolean start);

    String getVoiceSearchLanguage();

    boolean isVoiceDetectionRunning();

    boolean hasOverlayContent();

    oneway void windowAttached2(in Bundle bundle, ILauncherOverlayCallback cb);

    oneway void unusedMethod();

    oneway void setActivityState(int flags);

    boolean startSearch(in byte[] data, in Bundle bundle);

}
