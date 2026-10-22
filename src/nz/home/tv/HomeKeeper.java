package nz.home.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps TVerk as the home screen on TVs where the stock launcher can't be removed.
 *
 * On Google TV the stock home (launcherx) and the setup app always outrank a third-party home screen,
 * and some vendor apps depend on them: on Skyworth-made TVs, Live TV (HDMI switching, CEC one-touch
 * play) crashes without the setup app. So instead of disabling them, this service catches the Home key
 * and brings TVerk back whenever a stock home screen comes to the front (after boot, or when leaving
 * an app).
 *
 * Turned on by install.sh --replace-stock; it reads no screen content.
 */
public class HomeKeeper extends AccessibilityService {
    /** Stock home screens to replace (full class names, as reported in window events). */
    private static final Set<String> STOCK_HOMES = new HashSet<>(Arrays.asList(
            "com.google.android.apps.tv.launcherx.home.HomeActivity",
            "com.google.android.tungsten.setupwraith.MainActivity",
            "com.google.android.tungsten.setupwraith.RecoveryActivity",
            "com.google.android.tvlauncher.MainActivity"));

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) goHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = event.getClassName();
        if (cls != null && STOCK_HOMES.contains(cls.toString())) {
            Log.i(History.TAG, "stock home shown, switching to TVerk");
            goHome();
        }
    }

    @Override
    public void onInterrupt() {}

    private void goHome() {
        Intent i = new Intent(this, MainActivity.class)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(History.TAG, "home", e);
        }
    }
}
