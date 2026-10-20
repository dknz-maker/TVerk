package nz.home.tv;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches YouTube's media session and records a video once it has played for a while,
 * so autoplaying previews on YouTube's home screen don't end up in the history.
 */
public class MediaListener extends NotificationListenerService {
    private static final long SETTLE_MS = 10_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<MediaSession.Token> watched = new HashSet<>();
    private MediaSessionManager msm;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::onSessions;

    @Override
    public void onListenerConnected() {
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaListener.class);
        msm.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
        onSessions(msm.getActiveSessions(me));
        Log.i(History.TAG, "media listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        handler.removeCallbacksAndMessages(null);
        watched.clear();
    }

    private void onSessions(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController mc : controllers) {
            if (!History.YT.equals(mc.getPackageName())) continue;
            MediaSession.Token token = mc.getSessionToken();
            if (!watched.add(token)) continue;
            Log.i(History.TAG, "watching YouTube session");
            Runnable check = () -> {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    MediaMetadata md = mc.getMetadata();
                    io.execute(() -> History.record(this, md));
                }
            };
            mc.registerCallback(new MediaController.Callback() {
                private MediaMetadata current = mc.getMetadata();
                private PlaybackState last = mc.getPlaybackState();

                /** Saves where the current video got to (called when it pauses, stops or changes). */
                private void savePosition() {
                    if (current == null || last == null || last.getPosition() < 0) return;
                    long pos = last.getPosition();
                    if (last.getState() == PlaybackState.STATE_PLAYING) {
                        pos += (long) ((android.os.SystemClock.elapsedRealtime() - last.getLastPositionUpdateTime())
                                * last.getPlaybackSpeed());
                    }
                    CharSequence title = current.getText(MediaMetadata.METADATA_KEY_TITLE);
                    CharSequence artist = current.getText(MediaMetadata.METADATA_KEY_ARTIST);
                    if (title == null) return;
                    String t = title.toString(), a = artist == null ? "" : artist.toString();
                    long p = pos, dur = current.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    io.execute(() -> History.setPosition(MediaListener.this, t, a, p, dur));
                }

                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    savePosition();
                    current = metadata;
                    handler.removeCallbacks(check);
                    handler.postDelayed(check, SETTLE_MS);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    boolean wasPlaying = last != null && last.getState() == PlaybackState.STATE_PLAYING;
                    if (wasPlaying && (state == null || state.getState() != PlaybackState.STATE_PLAYING)) {
                        savePosition();
                    }
                    if (state != null && state.getPosition() >= 0) last = state;
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                        handler.removeCallbacks(check);
                        handler.postDelayed(check, SETTLE_MS);
                    }
                }

                @Override
                public void onSessionDestroyed() {
                    savePosition();
                    handler.removeCallbacks(check);
                    watched.remove(token);
                }
            }, handler);
            handler.postDelayed(check, SETTLE_MS);
        }
    }
}
