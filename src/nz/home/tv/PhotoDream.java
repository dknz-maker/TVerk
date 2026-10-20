package nz.home.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: the same landscape photos as the home screen, full screen with a slow drift,
 * a large clock and dimmed brightness. Fades in from black so the switch from any app looks intended.
 */
public class PhotoDream extends DreamService {
    private static final long PHOTO_MS = 60_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ImageView front, back;
    private TextView caption, date;
    private int index;
    private Config cfg;
    private final Runnable next = this::nextPhoto;
    private final Runnable dateTick = this::updateDate;

    private void updateDate() {
        java.util.Locale loc = cfg.lang.isEmpty() ? java.util.Locale.getDefault() : java.util.Locale.forLanguageTag(cfg.lang);
        String d = new java.text.SimpleDateFormat("EEEE, d MMMM", loc).format(new java.util.Date());
        date.setText(d);
        ui.postDelayed(dateTick, 60_000);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Config.localized(base));
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        cfg = Config.load(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        float d = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = new ImageView(this);
        front = new ImageView(this);
        for (ImageView iv : new ImageView[]{back, front}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, 0x99000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        boolean force24 = getResources().getBoolean(R.bool.force_24h);
        time.setFormat12Hour(force24 ? "H:mm" : "h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextSize(64);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        // A plain TextView: TextClock in a dream window ignores the configured language.
        date = new TextView(this);
        date.setTextSize(18);
        updateDate();
        TextView weather = new TextView(this);
        weather.setTextSize(18);
        weather.setText(currentWeather());
        caption = new TextView(this);
        caption.setTextSize(12);
        caption.setPadding(0, Math.round(10 * d), 0, 0);
        for (TextView t : new TextView[]{time, date, weather, caption}) {
            t.setTextColor(t == caption ? 0xB3FFFFFF : 0xF2FFFFFF);
            t.setShadowLayer(8f, 0f, 1f, 0xAA000000);
            t.setIncludeFontPadding(false);
            info.addView(t);
        }
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        ip.setMargins(Math.round(56 * d), 0, 0, Math.round(44 * d));
        root.addView(info, ip);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        nextPhoto();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        ui.removeCallbacks(next);
    }

    @Override
    public void onDetachedFromWindow() {
        ui.removeCallbacks(next);
        ui.removeCallbacks(dateTick);
        io.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void nextPhoto() {
        ui.removeCallbacks(next);
        ui.postDelayed(next, PHOTO_MS);
        int i = index++;
        io.execute(() -> {
            List<Wallpapers.Photo> list = Wallpapers.list(this, cfg.market);
            if (list.isEmpty()) return;
            Wallpapers.Photo p = list.get(Math.abs(i + (int) (System.currentTimeMillis() / PHOTO_MS)) % list.size());
            Bitmap bmp = Wallpapers.load(this, p);
            if (bmp != null) ui.post(() -> show(p, bmp));
        });
    }

    /** Crossfade to the new photo while it slowly zooms and pans (Ken Burns), so nothing stays static. */
    private void show(Wallpapers.Photo p, Bitmap bmp) {
        ImageView old = front;
        front = back;
        back = old;
        front.setImageBitmap(bmp);
        front.setAlpha(0f);
        front.setScaleX(1.04f);
        front.setScaleY(1.04f);
        front.setTranslationX(0);
        front.bringToFront();
        ((View) caption.getParent()).bringToFront();
        float drift = getResources().getDisplayMetrics().widthPixels * 0.03f * (index % 2 == 0 ? 1 : -1);
        front.animate().alpha(1f).setDuration(2000).start();
        front.animate().scaleX(1.14f).scaleY(1.14f).translationX(drift).setDuration(PHOTO_MS + 4000)
                .setStartDelay(0).start();
        back.animate().alpha(0f).setDuration(2000).withEndAction(() -> back.setImageDrawable(null)).start();
        caption.setText(cfg.captions ? p.caption : "");
    }

    /** Current temperature and conditions from the home screen's cached forecast, if any. */
    private String currentWeather() {
        try {
            File f = new File(getCacheDir(), "weather.json");
            JSONObject cur = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    .getJSONObject("current");
            return cfg.city + " · " + Math.round(cur.getDouble("temperature_2m")) + "°";
        } catch (Exception e) {
            return "";
        }
    }
}
