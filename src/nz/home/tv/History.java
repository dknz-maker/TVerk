package nz.home.tv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recently played YouTube videos, captured from YouTube's media session. */
final class History {
    static final String TAG = "HomeTV";
    static final String YT = "com.google.android.youtube.tv";
    static final int MAX = 20;
    private static final Pattern ID = Pattern.compile("(?:v=|/vi/|youtu\\.be/|^)([A-Za-z0-9_-]{11})(?=$|[/?&.#])");
    private static final Pattern VIDEO_ID = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"");
    private static final String[] ID_KEYS = {
            MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_MEDIA_URI,
            MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI};

    private History() {}

    static File file(Context c) {
        return new File(c.getFilesDir(), "history.json");
    }

    static synchronized JSONArray load(Context c) {
        try {
            File f = file(c);
            if (f.exists()) return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "history load failed", e);
        }
        return new JSONArray();
    }

    static synchronized void record(Context c, MediaMetadata md) {
        if (md == null) return;
        Log.i(TAG, "metadata keys=" + md.keySet());
        String title = first(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null) return;
        String artist = first(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_AUTHOR);
        if (artist == null) artist = "";
        String id = findId(md);
        if (id == null) id = resolveId(title, artist);
        String key = title + "\u0000" + artist;
        String thumb = "t_" + Integer.toHexString(key.hashCode()) + ".jpg";

        try {
            JSONArray old = load(c);
            JSONArray out = new JSONArray();
            JSONObject item = new JSONObject();
            for (int i = 0; i < old.length(); i++) {
                if (thumb.equals(old.getJSONObject(i).optString("thumb"))) {
                    item.put("pos", old.getJSONObject(i).optLong("pos"));
                }
            }
            item.put("title", title).put("artist", artist).put("id", id == null ? "" : id)
                    .put("thumb", thumb).put("ts", System.currentTimeMillis());
            out.put(item);
            for (int i = 0; i < old.length() && out.length() < MAX; i++) {
                JSONObject o = old.getJSONObject(i);
                if (thumb.equals(o.optString("thumb"))) continue;
                out.put(o);
            }
            for (int i = MAX; i < old.length(); i++) {
                new File(c.getFilesDir(), old.getJSONObject(i).optString("thumb", "x")).delete();
            }
            saveThumb(c, md, id, new File(c.getFilesDir(), thumb));
            Files.write(file(c).toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            // No titles in the log: logcat ends up in bug reports.
            Log.i(TAG, "recorded a video (id " + (id == null ? "not found" : "found") + ")");
        } catch (Exception e) {
            Log.w(TAG, "history record failed", e);
        }
    }

    /** Remembers where playback stopped; a finished video restarts from the beginning. */
    static synchronized void setPosition(Context c, String title, String artist, long posMs, long durationMs) {
        String thumb = "t_" + Integer.toHexString((title + "\u0000" + artist).hashCode()) + ".jpg";
        if (durationMs > 0 && posMs > durationMs * 95 / 100) posMs = 0;
        try {
            JSONArray arr = load(c);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!thumb.equals(o.optString("thumb"))) continue;
                o.put("pos", posMs);
                Files.write(file(c).toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
                Log.i(TAG, "position saved: " + posMs / 1000 + "s");
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "position save failed", e);
        }
    }

    /** YouTube doesn't expose the video id in its media session, so look it up by title and channel. */
    static String resolveId(String title, String artist) {
        try {
            String q = Uri.encode(title + " " + artist);
            HttpURLConnection con = (HttpURLConnection) new URL(
                    "https://www.youtube.com/results?search_query=" + q).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(6000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)");
            con.setRequestProperty("Accept-Language", "en");
            try (InputStream in = con.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                for (int n; (n = in.read(chunk)) > 0 && buf.size() < 2_000_000; ) buf.write(chunk, 0, n);
                Matcher m = VIDEO_ID.matcher(buf.toString("UTF-8"));
                if (m.find()) return m.group(1);
            }
        } catch (Exception e) {
            Log.w(TAG, "id lookup failed", e);
        }
        return null;
    }

    private static void saveThumb(Context c, MediaMetadata md, String id, File dst) {
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if ((art == null || art.getWidth() < 300) && id != null) {
            Bitmap web = download("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg");
            if (web != null) art = web;
        }
        if (art == null) return;
        if (art.getWidth() > 480) {
            art = Bitmap.createScaledBitmap(art, 480, art.getHeight() * 480 / art.getWidth(), true);
        }
        try (FileOutputStream os = new FileOutputStream(dst)) {
            art.compress(Bitmap.CompressFormat.JPEG, 85, os);
        } catch (Exception e) {
            Log.w(TAG, "thumb save failed", e);
        }
    }

    private static Bitmap download(String url) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            try (InputStream in = con.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String findId(MediaMetadata md) {
        for (String k : ID_KEYS) {
            String v = md.getString(k);
            if (v == null) continue;
            Matcher m = ID.matcher(v);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static String first(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    /** Opens the video at the remembered position, looking up its id first if needed. */
    static void open(Context c, JSONObject item) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            String id = item.optString("id");
            if (id.isEmpty()) id = resolveId(item.optString("title"), item.optString("artist"));
            long pos = item.optLong("pos") / 1000;
            Intent i = id == null
                    ? app.getPackageManager().getLeanbackLaunchIntentForPackage(YT)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id
                            + (pos > 15 ? "&t=" + pos + "s" : ""))).setPackage(YT);
            if (i == null) return;
            try {
                app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.w(TAG, "open failed", e);
            }
        }).start();
    }
}
