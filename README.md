# Acer IR Sweep

A tiny Android app that uses your phone's IR blaster to find the NEC code your TV answers to,
then saves Power / Vol+ / Vol- / Mute to a small remote.

It does NOT contain Acer's codes (none are published for the RC-525G). It searches for them.

## Build the APK

**Option A - GitHub (no PC needed):** create a free repo, upload these files keeping the folder
structure (including `.github/workflows/build.yml`), open the Actions tab, run "Build APK", then
download `acer-ir-sweep-apk` from the finished run, unzip it and install `app-debug.apk`.

**Option B - Android Studio:** open this folder, let it sync, then Build > Build APK(s).

Requires JDK 17. Android 9+ with an IR blaster.

## Use it

1. Turn the TV ON (so any button gives a visible reaction).
2. Hold the phone's top edge 1-2 m from the TV's IR sensor.
3. Tap **Auto sweep** (standard NEC). One code is sent every 1.5 s.
4. Tap **Stop** as soon as the TV reacts to anything: volume bar, mute icon, menu, power.
   Any reaction means the address is right.
5. Tick **Keep this address**, use **Prev** to step back to the code that caused it, then
   **Auto sweep** again (commands 00-FF at that address). Note which command is Power.
6. Press **Save as Power** (and Vol+, Vol-, Mute). Use the Remote row from then on.

If a full standard sweep gets no reaction, tick **Extended NEC** and try 16-bit addresses.

## Bluetooth remote

Tap **Open Bluetooth remote**. The phone then pretends to be a Bluetooth keyboard / media remote
(D-pad, OK, Back, Home, Menu, volume, mute, play/pause, channel). Power uses IR.

1. On the TV: Settings > Remotes & Accessories > Pair remote or accessory.
2. In the app: **Make phone discoverable**.
3. Pick "Phone TV Remote" on the TV and confirm. Next time use **Connect to a paired device**.

Needs Android 9+ and a phone whose Bluetooth stack allows HID device mode (most do, some
manufacturers block it). Only one app at a time can use this mode.

## Files

- `NecEncoder.java` - NEC pulse generator (checked against a real captured frame)
- `MainActivity.java` - IR sweep + saved IR remote
- `BluetoothRemoteActivity.java` - Bluetooth HID remote + IR power
