Run the app's test suite with JDK 21 (see AGENTS.md for the environment).

1. Unit tests + lint + assemble (fast, no device):

   ```bash
   export JAVA_HOME=/home/zingo/.local/opt/jdk-21.0.12+8
   export PATH=$JAVA_HOME/bin:$PATH
   ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

2. Run the instrumented tests on all attached devices/emulators when the
   change touches the `NavigationService`, notifications, or ghost-ride
   behavior:

   ```bash
   ./gradlew :app:connectedDebugAndroidTest
   ```

3. Report which suites passed and the test counts. If something failed, show
   the failing test names and fix before declaring success.
