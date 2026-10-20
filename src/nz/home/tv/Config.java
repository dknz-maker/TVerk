package nz.home.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User settings, stored in SharedPreferences and changed over adb via {@link ConfigReceiver}.
 * Defaults are for Auckland, New Zealand.
 */
final class Config {
    static final String PREFS = "config";
    static final String SELF = "nz.home.tv";

    /** Default app row order; anything not listed follows in system order. */
    static final String DEFAULT_ORDER = TextUtils.join(",", Arrays.asList(
            History.YT, "com.netflix.ninja", "com.disney.disneyplus", "com.apple.atve.androidtv.appletv",
            "com.amazon.amazonvideo.livingroom", "com.wbd.stream", "com.google.android.youtube.tvmusic",
            "com.spotify.tv.android", "tv.twitch.android.app", "com.plexapp.android", "org.xbmc.kodi",
            "com.android.vending", "com.android.tv.settings"));

    final String lang;
    final String city;
    final double lat, lon;
    final boolean fahrenheit;
    final String tideName;
    final double tideLat, tideLon;
    final boolean captions;
    final String market;
    final List<String> order;
    final Set<String> hide;

    private Config(SharedPreferences p) {
        lang = p.getString("lang", "");
        city = p.getString("city", "Auckland");
        lat = num(p.getString("lat", null), -36.8485);
        lon = num(p.getString("lon", null), 174.7633);
        fahrenheit = "fahrenheit".equalsIgnoreCase(p.getString("units", "celsius"));
        tideName = p.getString("tide", "Browns Bay");
        tideLat = num(p.getString("tide_lat", null), -36.7166);
        tideLon = num(p.getString("tide_lon", null), 174.7500);
        captions = !"false".equalsIgnoreCase(p.getString("captions", "true"));
        // Goes into a URL and a cache file name, so only accept the "xx-XX" form.
        String mkt = p.getString("market", "en-US");
        market = mkt.matches("[A-Za-z]{2}-[A-Za-z]{2}") ? mkt : "en-US";
        order = csv(p.getString("order", DEFAULT_ORDER));
        hide = new HashSet<>(csv(p.getString("hide", "")));
        hide.add(SELF);
    }

    /** Applies the configured language (if any) to a context; used by the home screen and the screensaver. */
    static Context localized(Context base) {
        String lang = load(base).lang;
        if (lang.isEmpty()) return base;
        java.util.Locale locale = java.util.Locale.forLanguageTag(lang);
        android.content.res.Configuration conf = new android.content.res.Configuration(base.getResources().getConfiguration());
        conf.setLocale(locale);
        java.util.Locale.setDefault(locale); // TextClock formats with the process default locale
        return base.createConfigurationContext(conf);
    }

    static Config load(Context c) {
        return new Config(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    boolean tideEnabled() {
        return !tideName.isEmpty() && !"off".equalsIgnoreCase(tideName);
    }

    /** Southern-hemisphere viewers see the moon's lit side mirrored relative to the emoji. */
    boolean southern() {
        return lat < 0;
    }

    String weatherUrl() {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=10" + (fahrenheit ? "&temperature_unit=fahrenheit" : "");
    }

    String tideUrl() {
        return "https://marine-api.open-meteo.com/v1/marine?latitude=" + tideLat + "&longitude=" + tideLon
                + "&hourly=sea_level_height_msl&timezone=auto&past_days=1&forecast_days=2";
    }

    /** Changes when anything that affects downloaded data changes, so caches can be keyed by it. */
    String dataKey() {
        return lat + "," + lon + "," + fahrenheit + "," + tideLat + "," + tideLon;
    }

    private static double num(String s, double fallback) {
        try {
            return s == null ? fallback : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) if (!part.trim().isEmpty()) out.add(part.trim());
        return out;
    }
}
