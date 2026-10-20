package nz.home.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String TAG = History.TAG;

    static final long WEATHER_TTL = 30 * 60 * 1000;

    static final int TEXT = 0xFFF2F4F8;
    static final int DIM = 0xB3F2F4F8;
    /** Frosted-glass cards over the photo. */
    static final int CARD = 0x8010141E;
    static final int CARD_HI = 0xA6344466;
    static final long PHOTO_MS = 15 * 60 * 1000;

    private Config cfg;
    private float density;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ScrollView scroll;
    private LinearLayout appsRow, continueRow, weatherRow, inputsRow;
    private TextView nowIcon, nowTemp, nowLine;
    private FrameLayout tideCard;
    private TextView tideState, tideNext;
    private Tide.CurveView tideCurve;
    private Tide tide;
    private String appsKey = "", inputsKey = "", weatherShown = "";
    private long historyStamp = -1;
    private long weatherFetched;
    private boolean focusedOnce;

    private ImageView wallFront, wallBack;
    private TextView photoCaption;
    private String photoShown = "";
    private final Runnable photoTick = this::refreshWallpaper;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        cfg = Config.load(base);
        // Home screen language can differ from the system's (configured via adb).
        super.attachBaseContext(Config.localized(base));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }

    private static java.lang.ref.WeakReference<MainActivity> current = new java.lang.ref.WeakReference<>(null);

    /** Called by {@link ConfigReceiver} after settings change; re-reads config and language. */
    static void recreateCurrent() {
        MainActivity a = current.get();
        if (a != null && !a.isDestroyed()) a.recreate();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new java.lang.ref.WeakReference<>(this);
        density = getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B2133, 0xFF0E1119, 0xFF07080C});
        getWindow().setBackgroundDrawable(bg);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshApps();
        refreshContinue();
        refreshWeather();
        refreshInputs();
        refreshWallpaper();
        updateTide();
        if (!focusedOnce) {
            focusedOnce = true;
            focusTop();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(photoTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the top.
        focusTop();
    }

    private void focusTop() {
        if (continueRow.getChildCount() > 0) continueRow.getChildAt(0).requestFocus();
        // after the focus change has scrolled its row into view, bring the clock back
        scroll.postDelayed(() -> scroll.smoothScrollTo(0, 0), 250);
    }

    @Override
    public void onBackPressed() {
        // The home screen has nowhere to go back to.
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        wallBack = new ImageView(this);
        wallFront = new ImageView(this);
        for (ImageView iv : new ImageView[]{wallBack, wallFront}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        // Darken the top (clock) and bottom (rows) so text stays readable on bright photos.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x26000000, 0x59000000, 0xA6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setSmoothScrollingEnabled(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(px(44), px(28), px(44), px(48));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        scroll.addView(column);

        column.addView(header());
        continueRow = row(column, getString(R.string.continue_watching));
        weatherRow = row(column, getString(R.string.forecast_title, cfg.city));
        appsRow = row(column, getString(R.string.apps));
        inputsRow = row(column, getString(R.string.inputs));
        if (cfg.tideEnabled()) buildTideCard();
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        return root;
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(px(8), 0, px(8), px(14));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour(getResources().getBoolean(R.bool.force_24h) ? "H:mm" : "h:mm");
        time.setFormat24Hour("H:mm");
        style(time, 46, TEXT);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        style(date, 16, DIM);
        left.addView(time);
        left.addView(date);
        h.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        nowIcon = text("", 22, TEXT);
        nowTemp = text("", 22, TEXT);
        nowTemp.setPadding(px(10), 0, 0, 0);
        top.addView(nowIcon);
        top.addView(nowTemp);
        nowLine = text("", 14, DIM);
        nowLine.setGravity(Gravity.END);
        nowLine.setPadding(0, px(4), 0, 0);
        photoCaption = text("", 11, DIM);
        photoCaption.setGravity(Gravity.END);
        photoCaption.setPadding(0, px(8), 0, 0);
        photoCaption.setMaxWidth(px(380));
        right.addView(top, new LinearLayout.LayoutParams(-2, -2));
        right.addView(nowLine, new LinearLayout.LayoutParams(-2, -2));
        right.addView(photoCaption, new LinearLayout.LayoutParams(-2, -2));
        h.addView(right);
        return h;
    }

    private LinearLayout row(LinearLayout column, String title) {
        if (title != null) {
            TextView t = text(title, 15, 0xE6F2F4F8);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setPadding(px(8), px(10), 0, 0);
            column.addView(t);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(px(10), px(14), px(80), px(14));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        hs.addView(row);
        column.addView(hs);
        return row;
    }

    /** A rounded card that lifts and gets a soft ring when focused, tvOS style. */
    private FrameLayout card(int wDp, int hDp, int color) {
        FrameLayout f = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(wDp), px(hDp));
        lp.rightMargin = px(18);
        f.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(px(14));
        if (Color.alpha(color) < 255) bg.setStroke(Math.max(1, px(0.5f)), 0x33FFFFFF);
        f.setBackground(bg);
        f.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        f.setClipToOutline(true);
        f.setFocusable(true);
        f.setClickable(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(14));
        ring.setStroke(px(2), 0xCCFFFFFF);
        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                    .translationZ(has ? px(16) : 0).setDuration(140).start();
            v.setForeground(has ? ring : null);
        });
        return f;
    }

    // ---------------------------------------------------------------- apps

    private void refreshApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        LinkedHashMap<String, ResolveInfo> byPkg = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (!cfg.hide.contains(p) && !byPkg.containsKey(p)) byPkg.put(p, ri);
        }
        List<String> pkgs = new ArrayList<>(byPkg.keySet());
        pkgs.sort((a, b) -> rank(a) - rank(b));
        String key = TextUtils.join(",", pkgs);
        if (key.equals(appsKey)) return;
        appsKey = key;

        appsRow.removeAllViews();
        for (String p : pkgs) {
            ResolveInfo ri = byPkg.get(p);
            FrameLayout c = card(160, 90, CARD);
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (banner != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageDrawable(banner);
                c.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            } else {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(ri.loadIcon(pm));
                box.addView(iv, new LinearLayout.LayoutParams(px(40), px(40)));
                TextView t = text(ri.loadLabel(pm).toString(), 12, TEXT);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(px(8), px(6), px(8), 0);
                box.addView(t);
                c.addView(box, new FrameLayout.LayoutParams(-1, -1));
            }
            c.setOnClickListener(v -> launch(p));
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setClipChildren(false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.rightMargin = px(18);
            tile.setLayoutParams(tlp);
            c.setLayoutParams(new LinearLayout.LayoutParams(px(160), px(90)));
            TextView name = text(ri.loadLabel(pm).toString(), 13, TEXT);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, px(12), 0, 0);
            name.setAlpha(0f);
            tile.addView(c);
            tile.addView(name, new LinearLayout.LayoutParams(px(160), -2));
            View.OnFocusChangeListener lift = c.getOnFocusChangeListener();
            c.setOnFocusChangeListener((v, has) -> {
                lift.onFocusChange(v, has);
                name.animate().alpha(has ? 1f : 0f).setDuration(140).start();
            });
            appsRow.addView(tile);
        }
    }

    private int rank(String pkg) {
        int i = cfg.order.indexOf(pkg);
        return i < 0 ? 1000 : i;
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "launch " + pkg, e);
        }
    }

    // ---------------------------------------------------------------- continue watching

    private void refreshContinue() {
        long stamp = History.file(this).lastModified();
        if (stamp == historyStamp) return;
        historyStamp = stamp;
        boolean hadFocus = continueRow.hasFocus();
        continueRow.removeAllViews();

        JSONArray items = History.load(this);
        if (items.length() == 0) {
            FrameLayout c = card(300, 117, CARD);
            TextView t = text(getString(R.string.continue_empty), 14, DIM);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(20), 0, px(20), 0);
            c.addView(t, new FrameLayout.LayoutParams(-1, -1));
            c.setOnClickListener(v -> launch(History.YT));
            continueRow.addView(c);
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            FrameLayout c = card(208, 117, 0xFF000000);
            File thumb = new File(getFilesDir(), item.optString("thumb", "x"));
            if (thumb.exists()) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                iv.setImageBitmap(BitmapFactory.decodeFile(thumb.getPath(), o));
                c.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            }
            LinearLayout caption = new LinearLayout(this);
            caption.setOrientation(LinearLayout.VERTICAL);
            caption.setPadding(px(10), px(18), px(10), px(8));
            caption.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xE6000000, 0x00000000}));
            TextView title = text(item.optString("title"), 13, TEXT);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            TextView artist = text(item.optString("artist"), 11, DIM);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            caption.addView(title);
            caption.addView(artist);
            c.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            c.setOnClickListener(v -> History.open(this, item));
            continueRow.addView(c);
        }
        if (hadFocus) continueRow.getChildAt(0).requestFocus();
    }

    // ---------------------------------------------------------------- weather + calendar

    private void refreshWeather() {
        File cache = new File(getCacheDir(), "weather.json");
        if (weatherShown.isEmpty() && cache.exists()) {
            try {
                renderWeather(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "weather cache", e);
            }
        }
        if (weatherShown.isEmpty()) renderWeather(null);
        long now = System.currentTimeMillis();
        boolean tideMissing = cfg.tideEnabled() && tide == null;
        if (!tideMissing && now - Math.max(weatherFetched, cache.lastModified()) < WEATHER_TTL) return;
        weatherFetched = now;
        io.execute(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(cfg.weatherUrl()).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                String body;
                try (InputStream in = con.getInputStream()) {
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                    body = buf.toString("UTF-8");
                }
                new JSONObject(body); // validate before caching
                Files.write(cache.toPath(), body.getBytes(StandardCharsets.UTF_8));
                ui.post(() -> renderWeather(body));
                if (!cfg.tideEnabled()) return;
                String tideBody = httpGet(cfg.tideUrl());
                Tide parsed = Tide.parse(tideBody);
                Files.write(new File(getCacheDir(), "tide.json").toPath(), tideBody.getBytes(StandardCharsets.UTF_8));
                ui.post(() -> {
                    tide = parsed;
                    updateTide();
                });
            } catch (Exception e) {
                Log.w(TAG, "weather fetch", e);
            }
        });
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        try (InputStream in = con.getInputStream()) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        }
    }

    private View buildTideCard() {
        tideCard = card(236, 129, CARD);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(14), px(8), px(14), px(8));
        TextView title = text(getString(R.string.tide_title, cfg.tideName), 13, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tideState = text("", 17, TEXT);
        tideState.setSingleLine(true);
        tideState.setEllipsize(TextUtils.TruncateAt.END);
        tideState.setPadding(0, px(4), 0, 0);
        tideCurve = new Tide.CurveView(this);
        tideNext = text("", 11, DIM);
        box.addView(title);
        box.addView(tideState);
        box.addView(tideCurve, new LinearLayout.LayoutParams(-1, 0, 1));
        box.addView(tideNext);
        tideCard.addView(box, new FrameLayout.LayoutParams(-1, -1));
        File cache = new File(getCacheDir(), "tide.json");
        if (cache.exists()) {
            try {
                tide = Tide.parse(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "tide cache", e);
            }
        }
        updateTide();
        return tideCard;
    }

    /** Rising/falling, the next turn and the curve; cheap, so it runs on every resume and photo tick. */
    private void updateTide() {
        if (tideCard == null) return;
        if (tide == null) {
            tideState.setText(R.string.loading);
            return;
        }
        long now = System.currentTimeMillis();
        Tide.Turn next = tide.next(now), prev = tide.previous(now);
        boolean h24 = getResources().getBoolean(R.bool.force_24h) || android.text.format.DateFormat.is24HourFormat(this);
        SimpleDateFormat tf = new SimpleDateFormat(h24 ? "H:mm" : "h:mm a", Locale.getDefault());
        if (next == null) {
            tideState.setText("—");
            tideNext.setText("");
        } else {
            long mins = Math.max(0, (next.time - now) / 60_000);
            String left = mins >= 60
                    ? getString(R.string.duration_hm, (int) (mins / 60), (int) (mins % 60))
                    : getString(R.string.duration_m, (int) mins);
            // "↑ 4 h 27 min until high tide": arrow and time large, the rest small
            tideState.setText((next.high ? "↑ " : "↓ ") + left);
            tideState.append(span("  " + getString(next.high ? R.string.tide_until_high : R.string.tide_until_low), 11));
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Tide.Turn t : tide.turns()) {
                if (t.time <= now || shown == 2) continue;
                if (shown++ > 0) sb.append("  ·  ");
                sb.append(getString(t.high ? R.string.tide_next_high : R.string.tide_next_low,
                        tf.format(new java.util.Date(t.time))));
            }
            tideNext.setText(sb);
        }
        tideCurve.setTide(tide);
    }

    static final double SYNODIC = 29.530588853;

    /** {emoji, phase name with % lit} for the moon right now (reference new moon 2000-01-06 18:14 UTC). */
    private String[] moon(long ms) {
        double age = ((ms - 947182440000L) / 86400000.0) % SYNODIC;
        if (age < 0) age += SYNODIC;
        int lit = (int) Math.round((1 - Math.cos(2 * Math.PI * age / SYNODIC)) / 2 * 100);
        boolean waxing = age < SYNODIC / 2;
        int phase; // index into R.array.moon
        if (lit < 3) phase = 0;
        else if (lit > 97) phase = 4;
        else if (lit < 40) phase = waxing ? 1 : 7;
        else if (lit <= 60) phase = waxing ? 2 : 6;
        else phase = waxing ? 3 : 5;
        String[] emoji = {"🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘"};
        return new String[]{emoji[phase], getResources().getStringArray(R.array.moon)[phase] + " " + lit + "%"};
    }

    private void renderWeather(String json) {
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        String key = day + (json == null ? "" : json);
        if (key.equals(weatherShown)) return;
        weatherShown = key;
        boolean hadFocus = weatherRow.hasFocus();
        weatherRow.removeAllViews();
        weatherRow.addView(calendarCard());
        if (tideCard != null) {
            if (tideCard.getParent() != null) ((ViewGroup) tideCard.getParent()).removeView(tideCard);
            weatherRow.addView(tideCard);
        }
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cur = o.getJSONObject("current");
            JSONObject d = o.getJSONObject("daily");
            JSONArray dates = d.getJSONArray("time");
            JSONArray codes = d.getJSONArray("weather_code");
            JSONArray max = d.getJSONArray("temperature_2m_max");
            JSONArray min = d.getJSONArray("temperature_2m_min");
            JSONArray rain = d.getJSONArray("precipitation_probability_max");

            int code = cur.getInt("weather_code");
            boolean night = cur.optInt("is_day", 1) == 0;
            String detail = cfg.city + " · " + label(code);
            if (night && code <= 2) {
                // Clear-ish night: show the real moon phase (mirrored in the southern hemisphere).
                String[] moon = moon(System.currentTimeMillis());
                nowIcon.setText(moon[0]);
                nowIcon.setScaleX(cfg.southern() ? -1f : 1f);
                detail += " · " + moon[1];
            } else {
                nowIcon.setText(icon(code));
                nowIcon.setScaleX(1f);
            }
            nowTemp.setText(Math.round(cur.getDouble("temperature_2m")) + "°");
            nowLine.setText(detail + " · " + getString(R.string.hi_lo, Math.round(max.getDouble(0)), Math.round(min.getDouble(0))));

            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat dow = new SimpleDateFormat("EEE", Locale.getDefault());
            for (int i = 0; i < dates.length(); i++) {
                java.util.Date date = in.parse(dates.getString(i));
                Calendar cal = Calendar.getInstance();
                cal.setTime(date);
                FrameLayout c = card(84, 129, i == 0 ? CARD_HI : CARD);
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                box.setPadding(0, px(10), 0, px(8));
                TextView name = text(i == 0 ? getString(R.string.today) : cap(dow.format(date)), 13, TEXT);
                name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                if (i > 0) name.append(span("  " + cal.get(Calendar.DAY_OF_MONTH), 11));
                TextView ic = text(icon(codes.getInt(i)), 28, TEXT);
                ic.setPadding(0, px(5), 0, px(3));
                TextView temps = text(Math.round(max.getDouble(i)) + "°", 17, TEXT);
                temps.append(span("  " + Math.round(min.getDouble(i)) + "°", 13));
                int r = rain.isNull(i) ? 0 : rain.getInt(i);
                int dc = codes.getInt(i);
                TextView rr = text(r >= 20 ? "💧" + r + "%" : (dc == 45 || dc == 48) ? label(dc) : " ", 11, 0xFFA8D4FF);
                rr.setPadding(0, px(4), 0, 0);
                for (TextView t : new TextView[]{name, ic, temps, rr}) {
                    t.setGravity(Gravity.CENTER);
                    box.addView(t);
                }
                c.addView(box, new FrameLayout.LayoutParams(-1, -1));
                weatherRow.addView(c);
            }
        } catch (Exception e) {
            Log.w(TAG, "weather render", e);
        }
        if (hadFocus && weatherRow.getChildCount() > 0) weatherRow.getChildAt(0).requestFocus();
    }

    private View calendarCard() {
        FrameLayout c = card(236, 129, CARD);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(14), px(8), px(14), px(6));
        Calendar now = Calendar.getInstance();
        TextView month = text(cap(new SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(now.getTime())), 13, TEXT);
        month.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        month.setPadding(px(2), 0, 0, px(2));
        box.addView(month);

        // Week starts on the locale's first day (Monday in NZ/RU, Sunday in the US).
        int firstDay = Calendar.getInstance(Locale.getDefault()).getFirstDayOfWeek();
        SimpleDateFormat narrow = new SimpleDateFormat("EEEEE", Locale.getDefault());
        String[] heads = new String[7];
        Calendar h = Calendar.getInstance();
        for (int k = 0; k < 7; k++) {
            h.set(Calendar.DAY_OF_WEEK, (firstDay - 1 + k) % 7 + 1);
            heads[k] = cap(narrow.format(h.getTime()));
        }
        box.addView(weekRow(heads, -1, true));
        Calendar cal = (Calendar) now.clone();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int offset = (cal.get(Calendar.DAY_OF_WEEK) - firstDay + 7) % 7;
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        int today = now.get(Calendar.DAY_OF_MONTH);
        for (int start = 1 - offset; start <= days; start += 7) {
            String[] cells = new String[7];
            int mark = -1;
            for (int k = 0; k < 7; k++) {
                int dnum = start + k;
                cells[k] = dnum >= 1 && dnum <= days ? String.valueOf(dnum) : "";
                if (dnum == today) mark = k;
            }
            box.addView(weekRow(cells, mark, false));
        }
        c.addView(box, new FrameLayout.LayoutParams(-1, -1));
        return c;
    }

    private View weekRow(String[] cells, int mark, boolean head) {
        int firstDay = Calendar.getInstance(Locale.getDefault()).getFirstDayOfWeek();
        boolean[] weekend = new boolean[7];
        for (int k = 0; k < 7; k++) {
            int dow = (firstDay - 1 + k) % 7 + 1;
            weekend[k] = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY;
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int k = 0; k < 7; k++) {
            TextView t = text(cells[k], 10, head ? DIM : (weekend[k] ? 0xFFC9D2FF : TEXT));
            t.setGravity(Gravity.CENTER);
            FrameLayout cell = new FrameLayout(this);
            if (k == mark) {
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(0xFFE8504A);
                t.setBackground(dot);
                t.setTextColor(Color.WHITE);
                t.setShadowLayer(0, 0, 0, 0);
            }
            cell.addView(t, new FrameLayout.LayoutParams(px(15), px(15), Gravity.CENTER));
            r.addView(cell, new LinearLayout.LayoutParams(0, px(14.5f), 1));
        }
        return r;
    }

    private static String icon(int code) {
        if (code == 0) return "☀️";
        if (code == 1) return "🌤️";
        if (code == 2) return "⛅";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "☁️"; // the fog emoji renders as a grey square on some TVs
        if (code >= 51 && code <= 57) return "🌦️";
        if (code >= 61 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "🌨️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "🌡️";
    }

    private String label(int code) {
        int i; // index into R.array.weather
        if (code == 0) i = 0;
        else if (code == 1) i = 1;
        else if (code == 2) i = 2;
        else if (code == 3) i = 3;
        else if (code == 45 || code == 48) i = 4;
        else if (code >= 51 && code <= 57) i = 5;
        else if (code >= 61 && code <= 67) i = 6;
        else if (code >= 71 && code <= 77) i = 7;
        else if (code >= 80 && code <= 82) i = 8;
        else if (code == 85 || code == 86) i = 9;
        else if (code >= 95) i = 10;
        else return "";
        return getResources().getStringArray(R.array.weather)[i];
    }

    private CharSequence span(String s, int sp_) {
        android.text.SpannableString sp = new android.text.SpannableString(s);
        sp.setSpan(new android.text.style.AbsoluteSizeSpan(sp_, true), 0, s.length(), 0);
        sp.setSpan(new android.text.style.ForegroundColorSpan(DIM), 0, s.length(), 0);
        return sp;
    }

    // ---------------------------------------------------------------- wallpaper

    /** Shows the photo for the current 15-minute slot, fading it in, then schedules the next slot. */
    private void refreshWallpaper() {
        ui.removeCallbacks(photoTick);
        if (tideCurve != null) updateTide();
        long now = System.currentTimeMillis();
        ui.postDelayed(photoTick, PHOTO_MS - now % PHOTO_MS + 1000);
        io.execute(() -> {
            List<Wallpapers.Photo> list = Wallpapers.list(this, cfg.market);
            if (list.isEmpty()) return;
            Wallpapers.Photo p = list.get((int) ((now / PHOTO_MS) % list.size()));
            if (p.url.equals(photoShown)) return;
            Bitmap bmp = Wallpapers.load(this, p);
            if (bmp == null) return;
            ui.post(() -> showWallpaper(p, bmp));
        });
    }

    private void showWallpaper(Wallpapers.Photo p, Bitmap bmp) {
        photoShown = p.url;
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        wallFront.setImageBitmap(bmp);
        wallFront.setAlpha(0f);
        wallFront.bringToFront();
        // keep the scrim and content above the photos
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1);
        wallFront.animate().alpha(1f).setDuration(1500).withEndAction(() -> wallBack.setImageDrawable(null)).start();
        photoCaption.setText(cfg.captions ? p.caption : "");
    }

    // ---------------------------------------------------------------- inputs

    private void refreshInputs() {
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim == null) return;
        List<TvInputInfo> inputs = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (TvInputInfo in : tim.getTvInputList()) {
            if (!in.isPassthroughInput() || in.isHidden(this)) continue;
            inputs.add(in);
        }
        // Named devices (e.g. "PlayStation 4" over HDMI-CEC) first, then ports in natural order.
        inputs.sort((a, b) -> {
            String na = inputName(a), nb = inputName(b);
            boolean pa = isPort(na), pb = isPort(nb);
            if (pa != pb) return pa ? 1 : -1;
            if (na.equals("AV") != nb.equals("AV")) return na.equals("AV") ? 1 : -1;
            return na.compareToIgnoreCase(nb);
        });
        for (TvInputInfo in : inputs) key.append(in.getId()).append(inputName(in)).append(';');
        if (key.toString().equals(inputsKey)) return;
        inputsKey = key.toString();
        inputsRow.removeAllViews();
        for (TvInputInfo in : inputs) {
            FrameLayout c = card(140, 70, CARD);
            TextView t = text(inputName(in), 15, TEXT);
            t.setGravity(Gravity.CENTER);
            c.addView(t, new FrameLayout.LayoutParams(-1, -1));
            String id = in.getId();
            c.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    Log.w(TAG, "input " + id, e);
                }
            });
            inputsRow.addView(c);
        }
    }

    private static boolean isPort(String name) {
        return name.startsWith("HDMI") || name.equals("AV") || name.startsWith("Component") || name.startsWith("VGA");
    }

    private String inputName(TvInputInfo in) {
        CharSequence custom = in.loadCustomLabel(this);
        return (custom != null && custom.length() > 0 ? custom : in.loadLabel(this)).toString();
    }

    // ---------------------------------------------------------------- helpers

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        style(t, sp, color);
        return t;
    }

    private static void style(TextView t, int sp, int color) {
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(6f, 0f, 1f, 0x99000000);
    }

    private int px(float dp) {
        return Math.round(dp * density);
    }
}
