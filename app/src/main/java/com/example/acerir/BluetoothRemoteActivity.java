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
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
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
 * Makes the phone pose as a Bluetooth keyboard / media remote and adds an IR Power button.
 *
 * Bluetooth HID Device is a system-hosted profile. The phone must support the profile,
 * and the TV must support pairing with an external HID device.
 */
public class BluetoothRemoteActivity extends Activity {

    private static final int REQ_BT = 41;
    private static final String TAG = "AcerIR-HID";
    private final Handler diagnosticHandler = new Handler(Looper.getMainLooper());
    private Runnable profileTimeout;

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
    private boolean registrationPending;

    private ConsumerIrManager ir;
    private SharedPreferences prefs;
    private TextView statusTv;

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            Log.i(TAG, "onAppStatusChanged registered=" + isRegistered + " pluggedDevice=" + safeName(pluggedDevice));
            registered = isRegistered;
            registrationPending = false;
            cancelProfileTimeout();
            updateStatus();
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            Log.i(TAG, "onConnectionStateChanged state=" + state + " device=" + safeName(device));
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
                    Log.i(TAG, "HID_DEVICE service connected; proxy=" + proxy.getClass().getName());
                    cancelProfileTimeout();
                    hid = (BluetoothHidDevice) proxy;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                        statusTv.setText("Bluetooth Connect permission is missing. Grant it and reopen this screen.");
                        return;
                    }
                    BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                            "Phone TV Remote", "TV remote", "Acer IR Sweep",
                            BluetoothHidDevice.SUBCLASS1_COMBO, HID_DESCRIPTOR);
                    registrationPending = true;
                    statusTv.setText("HID service connected. Calling registerApp()...");
                    Log.i(TAG, "Calling registerApp; descriptorBytes=" + HID_DESCRIPTOR.length);
                    boolean started;
                    try {
                        started = hid.registerApp(sdp, null, null, getMainExecutor(), callback);
                        Log.i(TAG, "registerApp returned " + started);
                    } catch (SecurityException e) {
                        Log.e(TAG, "registerApp SecurityException", e);
                        registrationPending = false;
                        statusTv.setText("HID registerApp permission failure: " + e.getMessage());
                        return;
                    }
                    if (!started) {
                        registrationPending = false;
                        statusTv.setText("HID DIAGNOSTIC: registerApp() returned FALSE. Phone Bluetooth stack refused HID registration.");
                    } else {
                        statusTv.setText("HID DIAGNOSTIC: registerApp() returned TRUE; waiting for onAppStatusChanged(true)...");
                        startRegistrationTimeout();
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile != BluetoothProfile.HID_DEVICE) return;
                    Log.w(TAG, "HID_DEVICE service disconnected");
                    cancelProfileTimeout();
                    hid = null;
                    connected = null;
                    registered = false;
                    registrationPending = false;
                    updateStatus();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        prefs = getSharedPreferences("codes", MODE_PRIVATE);
        buildUi();
        requestBluetoothPermissions();
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> missing = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), REQ_BT);
                return;
            }
        }
        startHid();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BT) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            statusTv.setText("Bluetooth Connect permission was denied. Allow it in app settings, then reopen this screen.");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            statusTv.setText("Bluetooth Advertise permission was denied. Allow it in app settings, then reopen this screen.");
            return;
        }
        startHid();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hid != null) {
            try {
                hid.unregisterApp();
            } catch (RuntimeException ignored) {
                // The profile may already be disconnected.
            }
            if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            hid = null;
        }
    }

    // ---------------------------------------------------------------- Bluetooth

    private void startHid() {
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            statusTv.setText("Bluetooth is unavailable on this phone.");
            return;
        }
        if (!adapter.isEnabled()) {
            statusTv.setText("Turn Bluetooth on, then reopen this screen.");
            return;
        }
        statusTv.setText("HID DIAGNOSTIC: requesting HID_DEVICE profile proxy from Android...");
        Log.i(TAG, "getProfileProxy HID_DEVICE; sdk=" + Build.VERSION.SDK_INT + " adapter=" + adapter.getName());
        startProfileTimeout();
        boolean ok;
        try {
            ok = adapter.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE);
        } catch (SecurityException e) {
            statusTv.setText("Cannot access Bluetooth profile: " + e.getMessage());
            return;
        }
        if (!ok) {
            cancelProfileTimeout();
            Log.e(TAG, "getProfileProxy returned FALSE for HID_DEVICE");
            statusTv.setText("HID DIAGNOSTIC: getProfileProxy() returned FALSE. Android does not expose HID_DEVICE profile on this phone.");
        } else {
            Log.i(TAG, "getProfileProxy returned TRUE; waiting for ServiceListener callback");
        }
    }

    private void startProfileTimeout() {\n        cancelProfileTimeout();\n        profileTimeout = () -> {\n            if (hid == null) {\n                Log.e(TAG, "TIMEOUT: HID profile proxy callback did not arrive within 12 seconds");\n                statusTv.setText("HID DIAGNOSTIC: Android accepted getProfileProxy(), but HID service callback never arrived (12s timeout). This suggests the phone's Bluetooth stack does not expose HID Device profile.");\n            }\n        };\n        diagnosticHandler.postDelayed(profileTimeout, 12000);\n    }\n\n    private void startRegistrationTimeout() {\n        cancelProfileTimeout();\n        profileTimeout = () -> {\n            if (!registered && registrationPending) {\n                registrationPending = false;\n                Log.e(TAG, "TIMEOUT: registerApp accepted but onAppStatusChanged callback did not arrive");\n                statusTv.setText("HID DIAGNOSTIC: registerApp() returned TRUE, but onAppStatusChanged() did not arrive within 12 seconds. Registration is stuck or rejected by Bluetooth stack.");\n            }\n        };\n        diagnosticHandler.postDelayed(profileTimeout, 12000);\n    }\n\n    private void cancelProfileTimeout() {\n        if (profileTimeout != null) {\n            diagnosticHandler.removeCallbacks(profileTimeout);\n            profileTimeout = null;\n        }\n    }\n\n    private String safeName(BluetoothDevice device) {\n        if (device == null) return "null";\n        try { return device.getName() + " (" + device.getAddress() + ")"; }\n        catch (SecurityException e) { return "name/address permission denied"; }\n    }\n\n    private void updateStatus() {
        if (statusTv == null) return;
        if (connected != null) {
            String name = connected.getName();
            statusTv.setText("Connected to " + (name == null ? "the TV" : name));
        } else if (registered) {
            statusTv.setText("HID registered. Make the phone discoverable, then scan for Phone TV Remote on the TV.");
        } else if (registrationPending) {
            statusTv.setText("Registering Bluetooth remote...");
        } else if (hid != null) {
            statusTv.setText("Bluetooth profile available; waiting for HID registration.");
        } else {
            statusTv.setText("Bluetooth remote is not registered.");
        }
    }

    private void makeDiscoverable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermissions();
            return;
        }
        if (!registered) {
            statusTv.setText("The HID remote is not registered yet. Wait for registration or check phone HID support.");
            return;
        }
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        try {
            startActivityForResult(i, REQ_BT + 1);
        } catch (SecurityException e) {
            statusTv.setText("Cannot enable discoverability: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BT + 1) {
            if (resultCode == RESULT_CANCELED) {
                statusTv.setText("Discoverability was cancelled or denied by Android.");
            } else {
                statusTv.setText("Phone is discoverable. On the TV, scan for Phone TV Remote.");
            }
        }
    }

    private void chooseDeviceToConnect() {
        if (hid == null || adapter == null || !registered) {
            statusTv.setText("Bluetooth HID remote is not ready yet.");
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            statusTv.setText("Bluetooth Connect permission is missing.");
            return;
        }
        final List<BluetoothDevice> devices = new ArrayList<>(bonded);
        if (devices.isEmpty()) {
            statusTv.setText("No paired devices yet. Start pairing from the TV while this phone is discoverable.");
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
                    try {
                        boolean ok = hid.connect(devices.get(which));
                        statusTv.setText(ok ? "Connecting..." : "Could not start the connection.");
                    } catch (SecurityException e) {
                        statusTv.setText("Bluetooth Connect permission is missing.");
                    }
                })
                .show();
    }

    private void press(boolean consumer, int usage) {
        if (hid == null || connected == null || !registered) {
            statusTv.setText("Not connected to the TV yet.");
            return;
        }
        try {
            if (consumer) {
                hid.sendReport(connected, 2, new byte[]{(byte) (usage & 0xFF), (byte) (usage >> 8)});
            } else {
                hid.sendReport(connected, 1, new byte[]{0, 0, (byte) usage, 0, 0, 0, 0, 0});
            }
        } catch (SecurityException e) {
            statusTv.setText("Bluetooth permission error: " + e.getMessage());
        }
    }

    private void release(boolean consumer) {
        if (hid == null || connected == null || !registered) return;
        try {
            hid.sendReport(connected, consumer ? 2 : 1, new byte[consumer ? 2 : 8]);
        } catch (SecurityException e) {
            statusTv.setText("Bluetooth permission error: " + e.getMessage());
        }
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
                + "3. Choose \"Phone TV Remote\" on the TV and confirm.\n"
                + "Later, use Connect to a paired device.");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(help);

        statusTv = new TextView(this);
        statusTv.setPadding(0, dp(12), 0, dp(12));
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusTv.setText("Checking Bluetooth permissions...");
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
