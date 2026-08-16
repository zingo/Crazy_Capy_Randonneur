---
name: flash
description: Use when the user says "flash" or "/flash" meaning they want the latest debug APK built and installed on their Android phone. Builds the app and installs it to the connected device (or emulator if the phone isn't attached).
---

# Flash the app to the phone

When the user types "flash" or "/flash", build the debug APK and install it on
their Android device.

1. Run `adb devices`. Prefer the physical phone; if only the emulator
   (`emulator-5554`) is attached, install there and tell the user.
2. Build with JDK 21 (never skip — the APK under
   `app/build/outputs/apk/debug/app-debug.apk` can be stale):

   ```bash
   export JAVA_HOME=/home/zingo/.local/opt/jdk-21.0.12+8
   export PATH=$JAVA_HOME/bin:$PATH
   ./gradlew :app:assembleDebug
   ```

3. Install to the target device. Prefer `adb -d` (the single USB-connected
   phone) when only one is attached; fall back to the serial otherwise:

   ```bash
   adb -d install -r app/build/outputs/apk/debug/app-debug.apk
   # or, if several devices are attached:
   adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. Confirm with `Success`. If the physical phone isn't connected, tell the
   user to plug it in and run /flash again.
