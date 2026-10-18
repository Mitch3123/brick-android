# Brick — Native Android Focus & Screen-Time App

A complete, native Android recreation of the **Brick** focus app ([getbrick.com](https://getbrick.com)).
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
   - Exact alarm triggering via `AlarmManager` with automatic rescheduling upon device boot.
5. **Strict Mode (Anti-Tamper & Anti-Uninstall)**:
   - `DeviceAdminReceiver` prevents uninstallation while actively bricked.
   - Intercepts navigation into device settings to prevent disabling accessibility permissions or force-stopping the app.
6. **24-Hour Cooldown Emergency Recovery**:
   - If all physical tokens are lost, initiate an emergency 24-hour countdown.
   - Monotonic elapsed time tracking (`SystemClock.elapsedRealtime()`) persisted across device reboots.
   - 100% immune to system clock tampering (advancing date/time in Settings does not bypass the cooldown).
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
- Android SDK Platforms 34, 35, or 36 installed (e.g. `%LOCALAPPDATA%\Android\Sdk`)

### 1. Build Both Debug and Release APKs
Run from the project root:
```powershell
.\gradlew.bat assemble
```
Output artifacts:
- **Debug APK**: `app\build\outputs\apk\debug\app-debug.apk` (13.6 MB)
- **Release APK**: `app\build\outputs\apk\release\app-release.apk` (9.0 MB, signed with v2 signature scheme)

### 2. Run the Full Automated Test Suite
To execute all 55 unit and adversarial test suites:
```powershell
.\gradlew.bat test
```
All unit tests pass with 0 failures across:
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
2. Tap **Settings / Tokens** or scan the tag against the back of your phone while the app is open.
3. Name your token (e.g. *"Desk Puck"* or *"Keychain"*).
4. The tag's hardware UID is securely hashed using SHA-256 with an application salt and stored on-device.

### 2. Printing a Fallback QR Code Card
1. In the app, select **Generate QR Token Card**.
2. Save or print the generated monochrome QR token bitmap.
3. Place it on your desk or in your wallet to scan whenever you want to lock/unlock without NFC.

---

## Headless Emulator Acceptance Verification

The project includes an automated test runner script `verify_e2e.ps1` for verifying on an emulator:
```powershell
.\verify_e2e.ps1
```
This script tests:
- Dual APK compilation & validation
- ADB headless permission granting
- Simulating NFC tag scans via broadcast test hooks (`com.brick.action.SIMULATE_TOKEN_SCAN`)
- UI hierarchy verification (`uiautomator dump`) confirming the blocked app is obscured
- Reboot persistence & 24h recovery clock advancement
