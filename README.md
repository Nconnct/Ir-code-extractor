# Acer IR Sweep

A tiny Android app that uses your phone's IR blaster to find the NEC code your TV answers to,
then saves Power / Vol+ / Vol- / Mute to a small remote.

It does NOT contain Acer's codes (none are published for the RC-525G). It searches for them.

## Build the APK

**Option A - GitHub (no PC needed):** create a free repo, upload these files keeping the folder
structure (including `.github/workflows/build.yml`), open the Actions tab, run "Build APK", then
download `acer-ir-sweep-apk` from the finished run, unzip it and install `app-debug.apk`.

**Option B - Android Studio:** open this folder, let it sync, then Build > Build APK(s).

Requires JDK 17. Android 5.0+ with an IR blaster.

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

## Files

- `NecEncoder.java` - NEC pulse generator (checked against a real captured frame)
- `MainActivity.java` - the whole UI
