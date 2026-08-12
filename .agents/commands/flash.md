Build and install the latest app to the connected Android device.

1. `adb devices` to see attached devices. Prefer a physical phone over the
   emulator (`emulator-5554`); if only the emulator is attached, use it and say
   so.
2. Build with JDK 21:

   ```bash
   export JAVA_HOME=/home/zingo/.local/opt/jdk-21.0.12+8
   export PATH=$JAVA_HOME/bin:$PATH
   ./gradlew :app:assembleDebug
   ```

3. IMPORTANT: never skip the assemble step — `app/build/outputs/apk/debug/app-debug.apk`
   can be stale and must be regenerated before installing.
4. Install to the target device (replace `SERIAL` with the `adb devices`
   serial):

   ```bash
   /home/zingo/Android/Sdk/platform-tools/adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
   ```

5. Confirm with `Success`. If the physical phone isn't connected, tell the
   user to plug it in and rerun `/flash`.
