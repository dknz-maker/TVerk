package nz.home.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tide at Browns Bay from Open-Meteo's marine model (hourly sea level vs mean sea level).
 * It's a model, not the official LINZ tables: times are good, absolute heights understate the range,
 * so the UI shows rising/falling, next high/low and the shape of the curve.
 */
final class Tide {
    private static final long HOUR = 3600_000L;

    final long[] t;
    final double[] h;

    static final class Turn {
        final long time;
        final double height;
        final boolean high;

        Turn(long time, double height, boolean high) {
            this.time = time;
            this.height = height;
            this.high = high;
        }
    }

    private Tide(long[] t, double[] h) {
        this.t = t;
        this.h = h;
    }

    static Tide parse(String json) throws Exception {
        JSONObject hourly = new JSONObject(json).getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time");
        JSONArray vals = hourly.getJSONArray("sea_level_height_msl");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Pacific/Auckland"));
        List<long[]> ok = new ArrayList<>();
        List<Double> hv = new ArrayList<>();
        for (int i = 0; i < times.length(); i++) {
            if (vals.isNull(i)) continue;
            ok.add(new long[]{f.parse(times.getString(i)).getTime()});
            hv.add(vals.getDouble(i));
        }
        long[] t = new long[ok.size()];
        double[] h = new double[ok.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = ok.get(i)[0];
            h[i] = hv.get(i);
        }
        return new Tide(t, h);
    }

    double at(long time) {
        if (time <= t[0]) return h[0];
        for (int i = 1; i < t.length; i++) {
            if (time <= t[i]) {
                double k = (time - t[i - 1]) / (double) (t[i] - t[i - 1]);
                return h[i - 1] + (h[i] - h[i - 1]) * k;
            }
        }
        return h[h.length - 1];
    }

    /** Highs and lows, with the exact time refined by fitting a parabola through each hourly peak. */
    List<Turn> turns() {
        List<Turn> out = new ArrayList<>();
        for (int i = 1; i < h.length - 1; i++) {
            boolean high = h[i] >= h[i - 1] && h[i] > h[i + 1];
            boolean low = h[i] <= h[i - 1] && h[i] < h[i + 1];
            if (!high && !low) continue;
            double denom = h[i - 1] - 2 * h[i] + h[i + 1];
            double off = denom == 0 ? 0 : (h[i - 1] - h[i + 1]) / (2 * denom);
            double peak = h[i] - (h[i - 1] - h[i + 1]) * off / 4;
            out.add(new Turn(t[i] + (long) (off * HOUR), peak, high));
        }
        return out;
    }

    Turn next(long now) {
        for (Turn turn : turns()) if (turn.time > now) return turn;
        return null;
    }

    Turn previous(long now) {
        Turn prev = null;
        for (Turn turn : turns()) if (turn.time <= now) prev = turn;
        return prev;
    }

    /** Small tide curve from 6h ago to 18h ahead, with a dot at "now". */
    static final class CurveView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nowLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Tide tide;

        CurveView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2 * d);
            line.setColor(0xFF8FD3FF);
            dot.setColor(0xFFFFFFFF);
            dot.setShadowLayer(4 * d, 0, 0, 0xFF8FD3FF);
            nowLine.setColor(0x66FFFFFF);
            nowLine.setStrokeWidth(d);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        void setTide(Tide t) {
            tide = t;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (tide == null) return;
            long now = System.currentTimeMillis();
            long from = now - 6 * HOUR, to = now + 18 * HOUR;
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (long x = from; x <= to; x += HOUR / 4) {
                double v = tide.at(x);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            float w = getWidth(), hgt = getHeight(), pad = hgt * 0.12f;
            double range = Math.max(0.01, max - min);
            Path p = new Path();
            for (long x = from; x <= to; x += HOUR / 4) {
                float px = (float) ((x - from) / (double) (to - from) * w);
                float py = (float) (pad + (max - tide.at(x)) / range * (hgt - 2 * pad));
                if (x == from) p.moveTo(px, py);
                else p.lineTo(px, py);
            }
            Path area = new Path(p);
            area.lineTo(w, hgt);
            area.lineTo(0, hgt);
            area.close();
            fill.setShader(new LinearGradient(0, 0, 0, hgt, 0x668FD3FF, 0x008FD3FF, Shader.TileMode.CLAMP));
            canvas.drawPath(area, fill);
            canvas.drawPath(p, line);
            float nx = w * 6 / 24f;
            float ny = (float) (pad + (max - tide.at(now)) / range * (hgt - 2 * pad));
            canvas.drawLine(nx, 0, nx, hgt, nowLine);
            canvas.drawCircle(nx, ny, line.getStrokeWidth() * 2.2f, dot);
        }
    }
}
