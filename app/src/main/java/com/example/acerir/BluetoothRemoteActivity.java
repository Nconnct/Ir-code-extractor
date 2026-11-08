package com.example.acerir;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.ConsumerIrManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Makes the phone pose as a Bluetooth keyboard / media remote that the Google TV accepts,
 * and adds the IR Power button (the TV's real remote only uses IR for power).
 */
public class BluetoothRemoteActivity extends Activity {

    private static final int REQ_BT = 41;

    // HID usages (keyboard page 0x07)
    private static final int K_ENTER = 0x28;
    private static final int K_RIGHT = 0x4F;
    private static final int K_LEFT = 0x50;
    private static final int K_DOWN = 0x51;
    private static final int K_UP = 0x52;
    // HID usages (consumer page 0x0C)
    private static final int C_MENU = 0x0040;
    private static final int C_HOME = 0x0223;
    private static final int C_BACK = 0x0224;
    private static final int C_VOL_UP = 0x00E9;
    private static final int C_VOL_DOWN = 0x00EA;
    private static final int C_MUTE = 0x00E2;
    private static final int C_PLAY_PAUSE = 0x00CD;
    private static final int C_REWIND = 0x00B4;
    private static final int C_FORWARD = 0x00B3;
    private static final int C_CH_UP = 0x009C;
    private static final int C_CH_DOWN = 0x009D;

    /** Report 1 = keyboard (8 bytes), report 2 = consumer control (one 16-bit usage). */
    private static final byte[] HID_DESCRIPTOR = bytes(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
            0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0);

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice connected;
    private boolean registered;

    private ConsumerIrManager ir;
    private SharedPreferences prefs;
    private TextView statusTv;

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            updateStatus();
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connected = device;
            } else if (device != null && device.equals(connected)) {
                connected = null;
            }
            updateStatus();
        }
    };

    private final BluetoothProfile.ServiceListener serviceListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile != BluetoothProfile.HID_DEVICE) return;
                    hid = (BluetoothHidDevice) proxy;
                    BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                            "Phone TV Remote", "TV remote", "Acer IR Sweep",
                            BluetoothHidDevice.SUBCLASS1_COMBO, HID_DESCRIPTOR);
                    boolean ok = hid.registerApp(sdp, null, null, getMainExecutor(), callback);
                    if (!ok) {
                        statusTv.setText("Bluetooth remote mode could not start on this phone.");
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile != BluetoothProfile.HID_DEVICE) return;
                    hid = null;
                    connected = null;
                    registered = false;
                    updateStatus();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        prefs = getSharedPreferences("codes", MODE_PRIVATE);
        buildUi();

        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        } else {
            startHid();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startHid();
            } else {
                statusTv.setText("Bluetooth permission is needed for the Bluetooth remote.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hid != null) {
            try {
                hid.unregisterApp();
            } catch (RuntimeException ignored) {
                // already unregistered
            }
            if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
        }
    }

    // ---------------------------------------------------------------- Bluetooth

    private void startHid() {
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            statusTv.setText("Turn Bluetooth on, then reopen this screen.");
            return;
        }
        boolean ok = adapter.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE);
        if (!ok) {
            statusTv.setText("This phone does not offer Bluetooth remote (HID device) mode.");
        } else {
            statusTv.setText("Starting Bluetooth remote...");
        }
    }

    private void updateStatus() {
        if (connected != null) {
            String name = connected.getName();
            statusTv.setText("Connected to " + (name == null ? "the TV" : name));
        } else if (registered) {
            statusTv.setText("Ready. Pair from the TV (steps above), "
                    + "or tap \"Connect to a paired device\".");
        } else {
            statusTv.setText("Starting Bluetooth remote...");
        }
    }

    private void makeDiscoverable() {
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivity(i);
    }

    private void chooseDeviceToConnect() {
        if (hid == null || adapter == null) {
            statusTv.setText("Bluetooth remote is not ready yet.");
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        final List<BluetoothDevice> devices = new ArrayList<>(bonded);
        if (devices.isEmpty()) {
            statusTv.setText("No paired devices yet. Pair from the TV first.");
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) {
            String n = devices.get(i).getName();
            names[i] = n == null ? devices.get(i).getAddress() : n;
        }
        new AlertDialog.Builder(this)
                .setTitle("Connect to")
                .setItems(names, (d, which) -> {
                    boolean ok = hid.connect(devices.get(which));
                    statusTv.setText(ok ? "Connecting..." : "Could not start the connection.");
                })
                .show();
    }

    private void press(boolean consumer, int usage) {
        if (hid == null || connected == null) {
            statusTv.setText("Not connected to the TV yet.");
            return;
        }
        if (consumer) {
            hid.sendReport(connected, 2, new byte[]{(byte) (usage & 0xFF), (byte) (usage >> 8)});
        } else {
            hid.sendReport(connected, 1, new byte[]{0, 0, (byte) usage, 0, 0, 0, 0, 0});
        }
    }

    private void release(boolean consumer) {
        if (hid == null || connected == null) return;
        hid.sendReport(connected, consumer ? 2 : 1, new byte[consumer ? 2 : 8]);
    }

    // ---------------------------------------------------------------- IR power

    private void sendIrPower() {
        if (ir == null || !ir.hasIrEmitter()) {
            statusTv.setText("This phone has no IR blaster.");
            return;
        }
        // Falls back to the code found for this TV: standard NEC, address 04, command 5F.
        String saved = prefs.getString("slot0", "S:4:5f");
        String[] p = saved.split(":");
        boolean ext = "E".equals(p[0]);
        int addr = Integer.parseInt(p[1], 16);
        int cmd = Integer.parseInt(p[2], 16);
        try {
            ir.transmit(38000, NecEncoder.encode(addr, cmd, ext));
            statusTv.setText("Sent IR power.");
        } catch (RuntimeException e) {
            statusTv.setText("IR send failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setText(
                "First time pairing:\n"
                + "1. On the TV: Settings > Remotes & Accessories > Pair remote or accessory.\n"
                + "2. Here: tap Make phone discoverable.\n"
                + "3. Choose \"Phone TV Remote\" (this phone) on the TV and confirm.\n"
                + "Later, just tap Connect to a paired device.");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(help);

        statusTv = new TextView(this);
        statusTv.setPadding(0, dp(12), 0, dp(12));
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusTv.setText("Starting Bluetooth remote...");
        root.addView(statusTv);

        Button discover = textButton("Make phone discoverable");
        discover.setOnClickListener(v -> makeDiscoverable());
        root.addView(discover);
        Button connect = textButton("Connect to a paired device");
        connect.setOnClickListener(v -> chooseDeviceToConnect());
        root.addView(connect);

        Button power = textButton("Power (IR)");
        power.setOnClickListener(v -> sendIrPower());
        root.addView(power);

        // D-pad
        root.addView(keyRow(spacer(), key("▲", false, K_UP), spacer()));
        root.addView(keyRow(key("◀", false, K_LEFT), key("OK", false, K_ENTER),
                key("▶", false, K_RIGHT)));
        root.addView(keyRow(spacer(), key("▼", false, K_DOWN), spacer()));

        root.addView(keyRow(key("Back", true, C_BACK), key("Home", true, C_HOME),
                key("Menu", true, C_MENU)));
        root.addView(keyRow(key("Vol -", true, C_VOL_DOWN), key("Mute", true, C_MUTE),
                key("Vol +", true, C_VOL_UP)));
        root.addView(keyRow(key("⏪", true, C_REWIND), key("Play/Pause", true, C_PLAY_PAUSE),
                key("⏩", true, C_FORWARD)));
        root.addView(keyRow(key("Ch -", true, C_CH_DOWN), spacer(), key("Ch +", true, C_CH_UP)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout keyRow(View... views) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private View spacer() {
        return new View(this);
    }

    private Button textButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTransformationMethod(null);
        return b;
    }

    /** A button that sends the key down when touched and key up when released. */
    private Button key(String label, final boolean consumer, final int usage) {
        Button b = textButton(label);
        b.setMinHeight(dp(64));
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    press(consumer, usage);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    release(consumer);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static byte[] bytes(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }
}
