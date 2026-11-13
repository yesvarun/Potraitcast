package portrait.cast;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1;
    private static final int ID_FIT = 100, ID_FILL = 101, ID_STRETCH = 102;

    private SharedPreferences prefs;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(MirrorService.PREFS, MODE_PRIVATE);

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Portrait Cast");
        title.setTextSize(26);
        box.addView(title);

        TextView help = new TextView(this);
        help.setText("Turn the TV on its side, connect the phone with the HDMI adaptor, "
                + "then press Start and choose \"Entire screen\".");
        help.setPadding(0, pad / 2, 0, pad / 2);
        box.addView(help);

        status = new TextView(this);
        status.setPadding(0, 0, 0, pad);
        box.addView(status);

        TextView l1 = new TextView(this);
        l1.setText("Picture size");
        box.addView(l1);

        RadioGroup modes = new RadioGroup(this);
        modes.addView(radio(ID_FIT, "Fit - whole phone screen, thin bars at the sides"));
        modes.addView(radio(ID_FILL, "Fill - no bars, top and bottom cropped"));
        modes.addView(radio(ID_STRETCH, "Stretch - no bars, slightly wider picture"));
        modes.check(ID_FIT + prefs.getInt(MirrorService.KEY_MODE, 0));
        modes.setOnCheckedChangeListener((g, id) -> {
            prefs.edit().putInt(MirrorService.KEY_MODE, id - ID_FIT).apply();
            notifyService();
        });
        box.addView(modes);

        CheckBox flip = new CheckBox(this);
        flip.setText("Flip 180 degrees (tick if the picture is upside down)");
        flip.setChecked(prefs.getBoolean(MirrorService.KEY_FLIP, false));
        flip.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(MirrorService.KEY_FLIP, on).apply();
            notifyService();
        });
        box.addView(flip);

        Button start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(v -> start());
        box.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> {
            if (MirrorService.running) {
                startService(new Intent(this, MirrorService.class)
                        .setAction(MirrorService.ACTION_STOP));
            }
            status.postDelayed(this::refreshStatus, 500);
        });
        box.addView(stop);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    private RadioButton radio(int id, String text) {
        RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(text);
        return r;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        Display[] ds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        String tv = ds.length > 0 ? "TV connected: " + ds[0].getName() : "No TV detected yet";
        status.setText(tv + "\n" + (MirrorService.running ? "Casting is ON" : "Casting is OFF"));
    }

    private void notifyService() {
        if (MirrorService.running) {
            startService(new Intent(this, MirrorService.class)
                    .setAction(MirrorService.ACTION_UPDATE));
        }
    }

    private void start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Appear on top\" for Portrait Cast, then press Start again",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (MirrorService.running) {
            Toast.makeText(this, "Already casting", Toast.LENGTH_SHORT).show();
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture was not allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        startForegroundService(new Intent(this, MirrorService.class)
                .setAction(MirrorService.ACTION_START)
                .putExtra(MirrorService.EXTRA_CODE, result)
                .putExtra(MirrorService.EXTRA_DATA, data));
        status.postDelayed(this::refreshStatus, 800);
    }
}
