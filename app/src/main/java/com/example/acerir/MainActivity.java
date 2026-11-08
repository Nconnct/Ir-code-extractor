package com.example.acerir;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Finds the NEC code a TV answers to by sending codes one after another from the
 * phone's IR blaster, then lets you save the working ones to a tiny remote.
 */
public class MainActivity extends Activity {

    private static final int CARRIER_HZ = 38000;
    private static final long SWEEP_DELAY_MS = 1500;
    private static final String PREFS = "codes";
    private static final String[] SLOT_NAMES = {"Power", "Vol+", "Vol-", "Mute"};

    /** Addresses tried first when sweeping in standard NEC mode, then all the rest. */
    private static final int[] ADDRESS_ORDER = buildAddressOrder();

    private ConsumerIrManager ir;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText addrEt;
    private EditText cmdEt;
    private CheckBox extendedCb;
    private CheckBox lockCb;
    private TextView statusTv;
    private TextView historyTv;
    private Button sweepBtn;
    private final Button[] remoteBtns = new Button[SLOT_NAMES.length];

    private final ArrayDeque<String> history = new ArrayDeque<>();
    private boolean sweeping;
    private int sweepAddrIdx;
    private int sweepCmd;

    private final Runnable sweepStep = new Runnable() {
        @Override
        public void run() {
            if (!sweeping) return;
            boolean ext = extendedCb.isChecked();
            boolean lock = lockCb.isChecked() || ext;
            int addr = lock ? parseHex(addrEt) : ADDRESS_ORDER[sweepAddrIdx];
            if (!send(addr, sweepCmd, ext)) {
                endSweep(null); // send() already showed the reason
                return;
            }
            showFields(addr, sweepCmd);
            sweepCmd++;
            if (sweepCmd > 0xFF) {
                sweepCmd = 0;
                if (lock || sweepAddrIdx + 1 >= ADDRESS_ORDER.length) {
                    endSweep("Sweep finished with no reaction. Try Extended NEC or another address.");
                    return;
                }
                sweepAddrIdx++;
            }
            handler.postDelayed(this, SWEEP_DELAY_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        if (ir == null || !ir.hasIrEmitter()) {
            statusTv.setText("This phone has no IR blaster, so nothing can be sent.");
        }
        refreshRemoteLabels();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSweep();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setText(
                "1. Turn the TV ON.\n"
                + "2. Point the top edge of the phone at the TV, 1-2 m away.\n"
                + "3. Tap Auto sweep. One code is sent every 1.5 s.\n"
                + "4. The moment the TV reacts to ANYTHING (volume bar, mute, menu, power), tap Stop. "
                + "The address is then right.\n"
                + "5. Tick \"Keep this address\", step back with Prev, then Auto sweep again to find "
                + "which command is Power. Save it with the buttons below.");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(help);

        LinearLayout fields = row();
        fields.addView(label("Address (hex)"), weight(1f));
        fields.addView(label("Command (hex)"), weight(1f));
        root.addView(fields);

        LinearLayout inputs = row();
        addrEt = hexField("00");
        cmdEt = hexField("00");
        inputs.addView(addrEt, weight(1f));
        inputs.addView(cmdEt, weight(1f));
        root.addView(inputs);

        extendedCb = new CheckBox(this);
        extendedCb.setText("Extended NEC (16-bit address, e.g. 1308)");
        root.addView(extendedCb);

        lockCb = new CheckBox(this);
        lockCb.setText("Keep this address (sweep commands 00-FF only)");
        root.addView(lockCb);

        LinearLayout stepRow = row();
        Button prev = button("Prev");
        Button send = button("Send");
        Button next = button("Next");
        prev.setOnClickListener(v -> step(-1));
        send.setOnClickListener(v -> sendFromFields());
        next.setOnClickListener(v -> step(1));
        stepRow.addView(prev, weight(1f));
        stepRow.addView(send, weight(1f));
        stepRow.addView(next, weight(1f));
        root.addView(stepRow);

        sweepBtn = button("Auto sweep");
        sweepBtn.setOnClickListener(v -> {
            if (sweeping) {
                stopSweep();
            } else {
                startSweep();
            }
        });
        root.addView(sweepBtn);

        statusTv = new TextView(this);
        statusTv.setPadding(0, dp(8), 0, 0);
        root.addView(statusTv);

        historyTv = new TextView(this);
        historyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(historyTv);

        root.addView(heading("Save the code in the fields above as:"));
        LinearLayout saveRow = row();
        for (int i = 0; i < SLOT_NAMES.length; i++) {
            final int slot = i;
            Button b = button(SLOT_NAMES[i]);
            b.setOnClickListener(v -> saveSlot(slot));
            saveRow.addView(b, weight(1f));
        }
        root.addView(saveRow);

        root.addView(heading("Remote (sends the saved codes)"));
        LinearLayout remoteRow = row();
        for (int i = 0; i < SLOT_NAMES.length; i++) {
            final int slot = i;
            Button b = button(SLOT_NAMES[i]);
            b.setOnClickListener(v -> sendSlot(slot));
            remoteBtns[i] = b;
            remoteRow.addView(b, weight(1f));
        }
        root.addView(remoteRow);

        root.addView(heading("Bluetooth remote (D-pad, volume, home...)"));
        Button btRemote = button("Open Bluetooth remote");
        btRemote.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, BluetoothRemoteActivity.class)));
        root.addView(btRemote);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(dp(4), dp(12), dp(4), 0);
        return t;
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTransformationMethod(null); // keep the text as written (no forced caps)
        return b;
    }

    private EditText hexField(String initial) {
        EditText e = new EditText(this);
        e.setText(initial);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ---------------------------------------------------------------- actions

    private void startSweep() {
        int startAddr = parseHex(addrEt);
        sweepCmd = parseHex(cmdEt) & 0xFF;
        sweepAddrIdx = Math.max(0, indexOfAddress(startAddr & 0xFF));
        sweeping = true;
        sweepBtn.setText("Stop");
        statusTv.setText("Sweeping... tap Stop as soon as the TV reacts.");
        handler.post(sweepStep);
    }

    private void stopSweep() {
        if (!sweeping) return;
        endSweep("Stopped. The TV probably reacted to one of the last codes below. "
                + "Use Prev and Send to find exactly which.");
    }

    private void endSweep(String message) {
        sweeping = false;
        handler.removeCallbacks(sweepStep);
        sweepBtn.setText("Auto sweep");
        if (message != null) statusTv.setText(message);
    }

    private void sendFromFields() {
        int addr = parseHex(addrEt);
        int cmd = parseHex(cmdEt) & 0xFF;
        if (send(addr, cmd, extendedCb.isChecked())) {
            statusTv.setText("Sent " + format(addr, cmd, extendedCb.isChecked()));
        }
    }

    /** Move the command field by delta and send it. */
    private void step(int delta) {
        int addr = parseHex(addrEt);
        int cmd = (parseHex(cmdEt) + delta) & 0xFF;
        showFields(addr, cmd);
        sendFromFields();
    }

    private void saveSlot(int slot) {
        boolean ext = extendedCb.isChecked();
        int addr = parseHex(addrEt);
        int cmd = parseHex(cmdEt) & 0xFF;
        prefs.edit()
                .putString("slot" + slot, (ext ? "E" : "S") + ":"
                        + Integer.toHexString(addr) + ":" + Integer.toHexString(cmd))
                .apply();
        refreshRemoteLabels();
        statusTv.setText("Saved " + format(addr, cmd, ext) + " as " + SLOT_NAMES[slot]);
    }

    private void sendSlot(int slot) {
        String saved = prefs.getString("slot" + slot, null);
        if (saved == null) {
            statusTv.setText(SLOT_NAMES[slot] + " has no saved code yet.");
            return;
        }
        String[] p = saved.split(":");
        boolean ext = "E".equals(p[0]);
        int addr = Integer.parseInt(p[1], 16);
        int cmd = Integer.parseInt(p[2], 16);
        if (send(addr, cmd, ext)) {
            statusTv.setText("Sent " + SLOT_NAMES[slot] + " (" + format(addr, cmd, ext) + ")");
        }
    }

    private void refreshRemoteLabels() {
        for (int i = 0; i < SLOT_NAMES.length; i++) {
            boolean has = prefs.getString("slot" + i, null) != null;
            remoteBtns[i].setText(has ? SLOT_NAMES[i] : SLOT_NAMES[i] + " (empty)");
        }
    }

    // ---------------------------------------------------------------- IR

    private boolean send(int address, int command, boolean extended) {
        if (ir == null || !ir.hasIrEmitter()) {
            statusTv.setText("This phone has no IR blaster, so nothing can be sent.");
            return false;
        }
        try {
            ir.transmit(CARRIER_HZ, NecEncoder.encode(address, command, extended));
        } catch (RuntimeException e) {
            statusTv.setText("IR send failed: " + e.getMessage());
            return false;
        }
        history.addFirst(format(address, command, extended));
        while (history.size() > 4) history.removeLast();
        StringBuilder sb = new StringBuilder("Last sent (newest first):");
        for (String h : history) sb.append("\n  ").append(h);
        historyTv.setText(sb.toString());
        return true;
    }

    // ---------------------------------------------------------------- helpers

    private void showFields(int addr, int cmd) {
        addrEt.setText(String.format(Locale.US, extendedCb.isChecked() ? "%04X" : "%02X", addr));
        cmdEt.setText(String.format(Locale.US, "%02X", cmd));
    }

    private static String format(int addr, int cmd, boolean ext) {
        return ext
                ? String.format(Locale.US, "EXT addr %04X  cmd %02X", addr & 0xFFFF, cmd & 0xFF)
                : String.format(Locale.US, "NEC addr %02X  cmd %02X", addr & 0xFF, cmd & 0xFF);
    }

    private static int parseHex(EditText e) {
        String s = e.getText().toString().trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        try {
            return Integer.parseInt(s, 16) & 0xFFFF;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int indexOfAddress(int addr) {
        for (int i = 0; i < ADDRESS_ORDER.length; i++) {
            if (ADDRESS_ORDER[i] == addr) return i;
        }
        return -1;
    }

    private static int[] buildAddressOrder() {
        int[] first = {0x00, 0x04, 0x40, 0x20, 0x10, 0x01, 0x80};
        int[] order = new int[256];
        boolean[] used = new boolean[256];
        int n = 0;
        for (int a : first) {
            order[n++] = a;
            used[a] = true;
        }
        for (int a = 0; a < 256; a++) {
            if (!used[a]) order[n++] = a;
        }
        return order;
    }
}
