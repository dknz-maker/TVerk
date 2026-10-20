package nz.home.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;

/**
 * Settings over adb. Only the shell (or system) can send this broadcast: the receiver requires
 * android.permission.DUMP, which regular apps can't hold.
 *
 * adb shell am broadcast -a nz.home.tv.CONFIG -n nz.home.tv/.ConfigReceiver \
 *     --es city "London" --es lat 51.5072 --es lon -0.1276 --es tide off
 *
 * Keys: lang, city, lat, lon, units, tide, tide_lat, tide_lon, captions, market, order, hide.
 * --ez reset true clears everything back to defaults.
 */
public class ConfigReceiver extends BroadcastReceiver {
    static final String[] KEYS = {
            "lang", "city", "lat", "lon", "units", "tide", "tide_lat", "tide_lon", "captions", "market", "order", "hide"};

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences.Editor e = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit();
        if (intent.getBooleanExtra("reset", false)) e.clear();
        StringBuilder changed = new StringBuilder();
        for (String k : KEYS) {
            if (!intent.hasExtra(k)) continue;
            Object v = intent.getExtras().get(k);
            e.putString(k, String.valueOf(v));
            changed.append(k).append('=').append(v).append(' ');
        }
        e.commit();
        // Drop downloaded data so a new location takes effect immediately.
        for (String f : new String[]{"weather.json", "tide.json"}) new File(context.getCacheDir(), f).delete();
        Log.i(History.TAG, "config updated: " + changed);
        setResultData("ok " + changed);
        // Rebuild the home screen in place so every setting (including language) applies. Restarting the
        // process instead would briefly expose whatever is behind it (e.g. a "No Signal" Live TV screen).
        new Handler(Looper.getMainLooper()).post(MainActivity::recreateCurrent);
    }
}
