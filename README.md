# Brick — Native Android Focus & Screen-Time App

A native Android focus-app project for personal use. The current source is under verification; no emulator or physical-token acceptance has been completed on this machine.
Built with **Kotlin**, **Jetpack Compose**, **Room Database**, and **Kotlin Coroutines**.
100% offline, privacy-first, on-device storage with zero external analytics or network calls.

---

## Features

1. **Physical Token Toggling (NFC & QR)**:
   - Tap any generic physical NFC tag (UID extraction via Foreground Dispatch) to toggle between **BRICKED** and **UNBRICKED**.
   - Printable offline QR code fallback for devices without NFC.
   - Support for pairing multiple tokens (primary card, spare keychains, desk card).
   - Strict rejection of rogue / unpaired tokens (prevents unauthorized unbricking).
2. **App Blocking & Interception**:
   - Real-time window state detection via `BrickAccessibilityService`.
   - Full-screen stark monochrome **"YOU'RE BRICKED"** shield (`BlockingOverlayActivity`) intercepts blocked apps.
   - Prevents back-press bypass by redirecting directly to the home screen.
3. **Flexible Focus Modes**:
   - **Block-list mode**: Blocks specific distracting apps (e.g. social media, games).
   - **Allow-list mode**: Blocks everything except designated essential apps.
   - System essentials (Phone, Emergency Dialer, System UI, Brick itself) are permanently whitelisted to prevent device lockouts.
4. **Scheduled Focus Sessions**:
   - Set recurring daily/weekly schedules (e.g. 09:00 - 17:00 Work Focus).
   - Weekday-aware alarms; they rearm after firing or reboot. If exact alarms are unavailable, alarms may run late.
5. **Strict Mode (Anti-Tamper & Anti-Uninstall)**:
   - Optional device-admin registration may make uninstallation harder; Android can still let a user disable admin or accessibility.
   - Accessibility redirects Settings during a strict session but is not a tamper-proof system lock.
6. **24-Hour Cooldown Emergency Recovery**:
   - If all physical tokens are lost, initiate an emergency 24-hour countdown.
   - Monotonic elapsed time tracking (`SystemClock.elapsedRealtime()`) persisted across device reboots.
   - Countdown uses monotonic time, so moving the wall clock forward does not complete it; powered-off time is not counted.
7. **Streak & Focus Stats**:
   - Automatic recording of focus sessions in Room database.
   - Computes current streak, longest streak, total focus hours, and session history.
8. **Stark Minimalist Visual Design**:
   - High-contrast pure black (`#000000`) and pure white (`#FFFFFF`) aesthetic matching the Brick design philosophy.

---

## Project Structure

```
com.brick.app
├── core
│   ├── common               // TimeProvider, TestTimeProvider, SystemTimeProvider
│   ├── database             // Room Database, TokenEntity, ModeEntity, SessionEntity, ScheduleEntity
│   ├── policy               // AppBlockPolicy, SystemWhitelist
│   └── security             // CryptoUtils (SHA-256), RecoveryEngine (24h cooldown)
├── feature
│   ├── home                 // SessionManager, Brick/Unbrick State Machine
│   ├── interception         // BlockingOverlayActivity
│   ├── nfc                  // NfcManager (Foreground Dispatch), TokenScanUseCase
│   ├── qr                   // QrCodeGenerator (Offline Bitmap & Payload)
│   ├── schedules            // ScheduleManager, ScheduleAlarmReceiver
│   ├── service              // BrickAccessibilityService, BrickForegroundService, BootReceiver, DeviceAdminReceiver
│   └── stats                // StreakCalculator, Session metrics
└── ui
    ├── main                 // MainScreen (Home, Modes, Schedules, Stats)
    ├── onboarding           // OnboardingScreen (Permissions Wizard)
    └── theme                // BrickTheme (Monochrome Color, Typography, Shape)
```

---

## Build Instructions (Windows CLI / No Android Studio Required)

Requirements:
- JDK 17
- Android SDK Platform 36 installed (the project uses `compileSdk = 36`; e.g. `%LOCALAPPDATA%\Android\Sdk`)

### 1. Build the Debug and Release APKs
Run from the project root:
```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```
Output artifacts:
- **Debug APK**: `app\build\outputs\apk\debug\app-debug.apk`
- **Release APK**: `app\build\outputs\apk\release\app-release.apk` (check its signing before sideloading)

### 2. Run the Debug JVM Test Suite
To execute the normal debug JVM unit tests:
```powershell
.\gradlew.bat :app:testDebugUnitTest
```
The suite covers these areas; run the command and inspect its result rather than relying on a source annotation count:
- `FullBrickFeatureSuiteTest` (E2E state machine, block/allow list interception, recovery cooldown, streak stats)
- `CryptoAndTimeAdversarialTest` (SHA-256 avalanche, constant-time equality, mid-cooldown reboot survival)
- `DatabaseTest` & `DatabaseAdversarialTest` (Room CRUD, cascades, concurrency stress)
- `TimeProviderTest` & `CryptoUtilsTest`

---

## Sideloading & Installation on a Real Android Phone

1. Enable **Developer Options** and **USB Debugging** on your Android phone.
2. Connect your phone via USB.
3. Install the APK via ADB:
   ```powershell
   adb install -r -d app\build\outputs\apk\release\app-release.apk
   ```
4. Open the **Brick** app on your phone and complete the 1-minute Onboarding flow:
   - Enable **Accessibility Service** (`BrickAccessibilityService`).
   - Allow **Display Over Other Apps**.
   - (Optional) Enable **Device Admin** for Strict Mode anti-uninstall.

---

## Pairing Tokens

### 1. Pairing a Generic NFC Tag (Sticker, Card, or Keychain)
1. Open the Brick app on your phone.
2. Tap **PAIR NFC** in onboarding or on Home, then hold the tag against the back of your phone.
3. Name your token (e.g. *"Desk Puck"* or *"Keychain"*).
4. The tag's hardware UID is SHA-256 hashed and stored on-device. Do not treat the UID as a secret: many NFC tags can be copied.

### 2. Printing a Fallback QR Code Card
1. In onboarding or on Home, tap **CREATE QR TOKEN**.
2. Print or photograph the displayed QR card using another device before dismissing it. The app does not provide a save/export button.
3. Place it on your desk or in your wallet to scan whenever you want to lock/unlock without NFC.

---

## Headless Emulator Acceptance Verification

The project includes `verify_e2e.ps1` (Windows) and `verify_e2e.sh` (Bash) for checking an emulator:
```powershell
.\verify_e2e.ps1
```
These scripts require exactly one connected emulator and refuse real phones. The test-only `app-e2e.apk` carries a reset/synthetic-clock receiver absent from ordinary debug and release APKs. Before the first-run onboarding check, the runners reset the E2E fixture and setup preference for repeatable runs. They simulate token input, so a physical NFC tag and camera-scanned QR card still require separate phone testing.

Build the isolated test-only E2E APK with `.\gradlew.bat :app:assembleE2e`; the runners also build it unless started with `-SkipBuild` (PowerShell) or `--skip-build` (Bash). Do not install it on a phone.

The runners check:
- Debug, release and isolated E2E APK compilation & validation
- ADB headless permission granting
- Simulating NFC tag scans via broadcast test hooks (`com.brick.action.SIMULATE_TOKEN_SCAN`)
- UI hierarchy verification (`uiautomator dump`) confirming the blocked app is obscured
- Reboot persistence & 24h recovery clock advancement

The Compose UI tests also need an Android device/emulator: `./gradlew.bat :app:connectedDebugAndroidTest`. Both E2E scripts need a connected emulator; their isolated simulation hooks are not a physical NFC or camera test. Do not read an unexecuted script as evidence that the acceptance criteria passed; see `TEST_READY.md` for actual verification status.
