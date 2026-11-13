package portrait.cast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Captures the phone screen and draws it, turned 90 degrees and scaled,
 * on the HDMI display. A TV standing on its side then shows an upright,
 * full-height phone screen instead of a small picture with huge black bars.
 */
public class MirrorService extends Service
        implements DisplayManager.DisplayListener, TextureView.SurfaceTextureListener {

    static final String PREFS = "settings";
    static final String KEY_MODE = "mode";   // 0 fit, 1 fill, 2 stretch
    static final String KEY_FLIP = "flip";
    static final String ACTION_START = "portrait.cast.START";
    static final String ACTION_STOP = "portrait.cast.STOP";
    static final String ACTION_UPDATE = "portrait.cast.UPDATE";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "cast";

    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private DisplayManager dm;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private Surface surface;
    private Display tv;
    private WindowManager tvWm;
    private View tvRoot;
    private TextureView texture;
    private int capW, capH;
    private boolean closing;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            shutdown();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        dm = getSystemService(DisplayManager.class);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_UPDATE.equals(action)) {
            applyTransform();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        startForeground(1, buildNotification("Starting"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (projection != null) return START_NOT_STICKY;

        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        try {
            projection = getSystemService(MediaProjectionManager.class)
                    .getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            shutdown();
            return START_NOT_STICKY;
        }
        projection.registerCallback(projectionCallback, main);
        dm.registerDisplayListener(this, main);
        running = true;
        attachToTv();
        return START_NOT_STICKY;
    }

    // ---------- TV window ----------

    private void attachToTv() {
        if (closing || projection == null || tvRoot != null) return;
        Display[] ds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (ds.length == 0) {
            notifyText("Waiting for TV - connect the HDMI adaptor");
            return;
        }
        tv = ds[0];
        try {
            Context wc = createDisplayContext(tv).createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            tvWm = wc.getSystemService(WindowManager.class);

            texture = new TextureView(wc);
            texture.setSurfaceTextureListener(this);
            FrameLayout root = new FrameLayout(wc);
            root.setBackgroundColor(Color.BLACK);
            root.addView(texture, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            tvWm.addView(root, lp);
            tvRoot = root;
            notifyText("Casting to " + tv.getName());
        } catch (Exception e) {
            tvRoot = null;
            texture = null;
            tv = null;
            notifyText("Could not open TV window: " + e.getMessage());
        }
    }

    private void detachFromTv() {
        if (virtualDisplay != null) virtualDisplay.setSurface(null);
        if (tvRoot != null) {
            try {
                tvWm.removeViewImmediate(tvRoot);
            } catch (Exception ignored) {
            }
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        tvRoot = null;
        texture = null;
        tv = null;
        tvWm = null;
    }

    // ---------- capture ----------

    @SuppressWarnings("deprecation")
    private boolean measurePhone() {
        Point p = new Point();
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p);
        // 1080 px on the short side is all a Full HD TV can show
        float s = Math.min(1f, 1080f / Math.min(p.x, p.y));
        int w = Math.round(p.x * s) & ~1;
        int h = Math.round(p.y * s) & ~1;
        boolean changed = w != capW || h != capH;
        capW = w;
        capH = h;
        return changed;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        if (closing || projection == null) return;
        measurePhone();
        st.setDefaultBufferSize(capW, capH);
        surface = new Surface(st);
        int dpi = getResources().getDisplayMetrics().densityDpi;
        try {
            if (virtualDisplay == null) {
                virtualDisplay = projection.createVirtualDisplay("PortraitCast",
                        capW, capH, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, main);
            } else {
                virtualDisplay.resize(capW, capH, dpi);
                virtualDisplay.setSurface(surface);
            }
        } catch (Exception e) {
            shutdown();
            return;
        }
        applyTransform();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        st.setDefaultBufferSize(capW, capH);
        applyTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (virtualDisplay != null) virtualDisplay.setSurface(null);
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    /** Turns the picture 90 degrees and scales it to the sideways TV. */
    private void applyTransform() {
        if (texture == null || capW == 0 || capH == 0) return;
        float w = texture.getWidth(), h = texture.getHeight();
        if (w == 0 || h == 0) return;

        int mode = prefs.getInt(KEY_MODE, 0);
        boolean phonePortrait = capH > capW;
        // Size of the picture on the TV panel after turning it
        float onTvW, onTvH;
        if (mode == 2 && phonePortrait) {
            onTvW = w;
            onTvH = h;
        } else {
            float fit = Math.min(w / capH, h / capW);
            float fill = Math.max(w / capH, h / capW);
            float s = (mode == 1 && phonePortrait) ? fill : fit;
            onTvW = capH * s;
            onTvH = capW * s;
        }
        Matrix m = new Matrix();
        m.setScale(onTvH / w, onTvW / h, w / 2f, h / 2f);
        m.postRotate(prefs.getBoolean(KEY_FLIP, false) ? -90 : 90, w / 2f, h / 2f);
        texture.setTransform(m);
        texture.invalidate();
    }

    // ---------- display changes ----------

    @Override
    public void onDisplayAdded(int id) {
        attachToTv();
    }

    @Override
    public void onDisplayRemoved(int id) {
        if (tv != null && tv.getDisplayId() == id) {
            detachFromTv();
            notifyText("TV disconnected - waiting");
        }
    }

    @Override
    public void onDisplayChanged(int id) {
        if (id == Display.DEFAULT_DISPLAY) {
            // phone was rotated or its resolution changed
            if (measurePhone() && virtualDisplay != null && texture != null) {
                SurfaceTexture st = texture.getSurfaceTexture();
                if (st != null) st.setDefaultBufferSize(capW, capH);
                virtualDisplay.resize(capW, capH,
                        getResources().getDisplayMetrics().densityDpi);
            }
            applyTransform();
        } else if (tv != null && tv.getDisplayId() == id) {
            applyTransform();
        }
    }

    // ---------- lifecycle ----------

    private void shutdown() {
        if (closing) return;
        closing = true;
        running = false;
        try {
            dm.unregisterDisplayListener(this);
        } catch (Exception ignored) {
        }
        detachFromTv();
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback);
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, MirrorService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setContentTitle("Portrait Cast")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void notifyText(String text) {
        if (closing) return;
        getSystemService(NotificationManager.class).notify(1, buildNotification(text));
    }
}
