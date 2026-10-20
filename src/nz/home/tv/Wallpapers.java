package nz.home.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Landscape photos from Bing's daily image archive (no account or key needed), cached on disk. */
final class Wallpapers {
    private static final String TAG = History.TAG;
    private static final long LIST_TTL = 12 * 60 * 60 * 1000;
    private static final int KEEP_FILES = 20;

    static final class Photo {
        final String url, caption;

        Photo(String url, String caption) {
            this.url = url;
            this.caption = caption;
        }
    }

    private Wallpapers() {}

    /** Up to ~15 recent photos; refetched twice a day. Call off the main thread. */
    static List<Photo> list(Context c, String market) {
        File cache = new File(c.getCacheDir(), "bing_" + market + ".json");
        if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > LIST_TTL) {
            try {
                JSONArray all = new JSONArray();
                for (int idx : new int[]{0, 7}) {
                    String body = get("https://www.bing.com/HPImageArchive.aspx?format=js&n=8&mkt=" + market + "&idx=" + idx);
                    JSONArray imgs = new JSONObject(body).getJSONArray("images");
                    for (int i = 0; i < imgs.length(); i++) all.put(imgs.get(i));
                }
                Files.write(cache.toPath(), all.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "wallpaper list", e);
            }
        }
        List<Photo> out = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                String base = o.optString("urlbase");
                if (base.isEmpty() || seen.contains(base)) continue;
                seen.add(base);
                String caption = o.optString("copyright");
                int cut = caption.indexOf(" (©");
                if (cut > 0) caption = caption.substring(0, cut);
                out.add(new Photo("https://www.bing.com" + base + "_1920x1080.jpg", caption));
            }
        } catch (Exception e) {
            Log.w(TAG, "wallpaper parse", e);
        }
        return out;
    }

    /** Downloads (or reads from cache) one photo as a screen-sized RGB_565 bitmap. Off the main thread. */
    static Bitmap load(Context c, Photo p) {
        File dir = new File(c.getCacheDir(), "wall");
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString(p.url.hashCode()) + ".jpg");
        try {
            if (!f.exists()) {
                HttpURLConnection con = (HttpURLConnection) new URL(p.url).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(15000);
                File tmp = new File(dir, f.getName() + ".part");
                try (InputStream in = con.getInputStream()) {
                    Files.copy(in, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                tmp.renameTo(f);
                prune(dir);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            Log.w(TAG, "wallpaper load", e);
            f.delete();
            return null;
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP_FILES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - KEEP_FILES; i++) files[i].delete();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0 && buf.size() < 2_000_000; ) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        }
    }
}
