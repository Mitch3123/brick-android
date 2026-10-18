# Project: Brick Native Android App

## Architecture
A native Android focus / screen-time app written in Kotlin for personal offline use.
The architecture follows Clean Architecture with Jetpack Compose, Kotlin Coroutines, and Room Database:

```
com.brick.app
├── core
│   ├── common               // Result, TimeProvider, DispatchersProvider, Constants
│   ├── security             // CryptoUtils (SHA-256), RecoveryEngine
│   ├── database             // Room Database, DAOs, TypeConverters, Entities
│   └── policy               // AppBlockPolicy, SystemWhitelist, PackageTracker
├── feature
│   ├── service              // BrickAccessibilityService, BrickForegroundService, Receivers
│   ├── nfc                  // NfcManager, ForegroundDispatchController
│   ├── qr                   // QrCodeGenerator, QrCodeAnalyzer (CameraX + ZXing)
│   ├── onboarding           // PermissionWizard, TokenPairingScreen
│   ├── home                 // MainScreen (Bricked/Unbricked State Machine), BrickToggle
│   ├── modes                // ModeListScreen, ModeEditorScreen, AppPickerScreen
│   ├── schedules            // ScheduleListScreen, ScheduleEditorScreen, ScheduleManager
│   └── stats                // StatsDashboardScreen, StreakCalculator, SessionHistoryList
└── ui
    ├── theme                // Stark Monochrome Theme (Color, Typography, Shape)
    └── components           // BrickButton, BrickCard, MonochromeToggle, StatusHeader
```

- **App Interception**: Real-time package detection via `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED`). Intercepts blocked packages and launches `BlockingOverlayActivity` with `FLAG_ACTIVITY_NEW_TASK`.
- **Token Pairing & Verification**: NFC Hardware UID extraction via Foreground Dispatch; offline QR token generation & CameraX/ZXing scanning; SHA-256 with application salt stored in Room `TokenEntity`; constant-time lookup and strict rejection of unpaired tokens.
- **Strict Mode & Anti-Tamper**: `DeviceAdminReceiver` prevents uninstallation (`DELETE_FAILED_DEVICE_POLICY_MANAGER`). Accessibility service monitors settings navigation to prevent force-stopping or disabling the service.
- **24-Hour Recovery**: Monotonic `SystemClock.elapsedRealtime()` countdown with cumulative elapsed time persisted across reboots, detection of boot ID changes, and complete immunity to wall-clock manipulation.
- **Offline & Autonomous**: 100% on-device operation, zero external servers or accounts, original stark B&W visual identity.

---

## Feature Inventory

Every feature identified in the Survey phase and user requirements is inventoried below and mapped to an implementation milestone.

| # | Feature | Description | Milestone | Source |
|---|---|---|---|---|
| F01 | Project Scaffolding & Dual Build | Standalone Kotlin + Compose project scaffold with Gradle 9.1.0 wrapper, AGP 9.0.1, Java 17; single command `gradlew assemble` building debug and signed sideloadable release APKs | M1 | Survey 1 & AC-1 |
| F02 | Offline & Sideloadable Packaging | 100% offline app with original B&W identity, no trademarks, no telemetry, no backend, signed with debug keystore for sideloading | M1 | Survey 1 & R1 |
| F03 | Core Data Models & Room DB | Room database with `TokenEntity`, `ModeEntity`, `ScheduleEntity`, and `BrickSessionEntity`, TypeConverters, DAOs | M1 | Survey 2 & R2/R3/R4 |
| F04 | Cryptographic Token Storage | SHA-256 token hashing with application salt; secure comparison preventing unpaired token unbricking | M1 | Survey 2 & R2 |
| F05 | Injectable Time Provider | `TimeProvider` interface (`SystemTimeProvider` for production, `TestTimeProvider` for test clock manipulation) | M1 | Survey 2/3 & AC-10 |
| F06 | NFC Token Hardware UID Scanner | Foreground dispatch & background NFC intent handling extracting immutable tag UID | M2 | Survey 2 & R2 |
| F07 | Printable QR Token Generator | Generates high-entropy token card bitmap for printing/saving using embedded ZXing | M2 | Survey 2 & R2 |
| F08 | CameraX QR Scanner Analyzer | CameraX viewfinder analyzer decoding QR tokens via ZXing | M2 | Survey 2 & R2 |
| F09 | Token State Machine & Pairing | `TokenScanUseCase` managing token pairing, spare tokens, state toggling (Brick/Unbrick), and rejection of unpaired tokens | M2 | Survey 2 & R2 |
| F10 | Accessibility Window Interceptor | `BrickAccessibilityService` intercepting `TYPE_WINDOW_STATE_CHANGED` events to detect launched apps in real time | M2 | Survey 2 & R2 |
| F11 | System Whitelist & Policy Engine | Whitelists system essentials (Brick itself, system framework, home launcher, emergency dialer); evaluates block/allow lists | M2 | Survey 2 & R2/R3 |
| F12 | Full-Screen Blocking Overlay | `singleInstance` Activity displaying stark "YOU'RE BRICKED" message, active mode, and emergency recovery link; dismiss prevention via `GLOBAL_ACTION_HOME` | M2 | Survey 2 & R2 |
| F13 | Foreground Service Watchdog | Keeps app process alive, shows persistent notification with active mode and timer, restarts on kill via `START_STICKY` | M2 | Survey 2 & R2 |
| F14 | Reboot State Restorer | `BootReceiver` restoring active brick state and services on `BOOT_COMPLETED` | M2 | Survey 2 & R2 |
| F15 | Simulated Token Scan Test Hook | Debug-only `SimulateTokenScanReceiver` allowing ADB intent simulation of NFC and QR token scans | M2 | Survey 3 & AC-3/4/6 |
| F16 | Modes & Installed App Picker | Block-list and Allow-list profiles; installed apps discovery via `PackageManager` (`QUERY_ALL_PACKAGES`) | M3 | Survey 2 & R3 |
| F17 | Exact Schedules Engine | Auto-brick at user-defined days and times via `AlarmManager.setExactAndAllowWhileIdle()`; boot rescheduling | M3 | Survey 2 & R3 |
| F18 | Strict Mode Anti-Uninstall | `DeviceAdminReceiver` registration preventing package uninstall while bricked | M3 | Survey 2 & R3 |
| F19 | Settings Anti-Tamper Shield | Accessibility service kicks user to home screen if navigating to Settings -> Apps -> Brick to Force Stop or Clear Data | M3 | Survey 2 & R3 |
| F20 | 24-Hour Recovery Engine | Tamper-resistant cooldown based on monotonic elapsed real time; immune to system clock changes | M3 | Survey 2 & R3 |
| F21 | Recovery Reboot Persistence | Persists cumulative elapsed time across reboots; boot ID mismatch handling; prevents shortcutting via power-off or reboot | M3 | Survey 2 & User Follow-up |
| F22 | Recovery Test Clock Hooks | Debug test hook allowing advancing test clock to verify 24h recovery completion and wall-clock resistance | M3 | Survey 3 & AC-10 |
| F23 | Stark B&W Design System | Pure black `#000000`, white `#FFFFFF`, high contrast, bold sans-serif typography, custom components | M4 | Survey 2 & R4 |
| F24 | Bold State Home Screen | Prominent BRICKED / UNBRICKED state indicator, active mode chip, session timer, token prompt | M4 | Survey 2 & R4 |
| F25 | First-Run Onboarding Wizard | Guided permissions walkthrough (Accessibility, UsageStats, Overlay, DeviceAdmin, ExactAlarm, Notification, Camera) with live status | M4 | Survey 2 & R4 |
| F26 | Modes & Schedules Screens | Compose UI screens for creating, editing, and managing Modes and Schedules | M4 | Survey 2 & R3/R4 |
| F27 | Stats & Streak Calculation | Room session logging; `StreakCalculator` computing current streak and longest streak; stats dashboard UI | M4 | Survey 2 & R4 |
| F28 | Automated Emulator Test Runner | Standalone `verify_e2e.ps1` script verifying all acceptance criteria on emulator headlessly | M5 | Survey 3 & AC-1..12 |
| F29 | UI Hierarchy Verification | Automated `uiautomator dump` assertions verifying blocking screen presence and app isolation | M5 | Survey 3 & AC-4/5 |
| F30 | Reboot Mid-Cooldown Test Case | Dedicated automated test verifying recovery progress survives reboot and cannot be bypassed | M5 | Survey 3 & User Follow-up |
| F31 | Design Review Screenshot Capture | Automated screen capture saving PNGs for Onboarding, Home (Bricked/Unbricked), Modes, Schedules, Stats, Blocking screen | M5 | Survey 3 & AC Design Review |
| F32 | Project Documentation (README) | Complete guide on building, installing on device, pairing NFC, printing QR, and running test suites | M5 | Survey 1/3 & AC Docs |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|---|---|---|---|
| M1 | Foundation, Data Architecture & Build Infrastructure | F01, F02, F03, F04, F05 | none | PLANNED |
| M2 | Token Engine & Interception Core | F06, F07, F08, F09, F10, F11, F12, F13, F14, F15 | M1 | PLANNED |
| M3 | Modes, Schedules, Strict Mode & 24h Recovery | F16, F17, F18, F19, F20, F21, F22 | M1, M2 | PLANNED |
| M4 | UI Presentation, Stats Engine & Onboarding Flow | F23, F24, F25, F26, F27 | M1, M2, M3 | PLANNED |
| M5 | Final Verification, E2E Test Suite & Documentation | F28, F29, F30, F31, F32 (Phase 1: 100% E2E Pass; Phase 2: Adversarial Coverage Hardening) | M1, M2, M3, M4, TEST_READY.md | PLANNED |

---

## Parallel Track: E2E Testing Track
- **Owner**: E2E Testing Orchestrator
- **Scope**: Requirement-driven, opaque-box test suite creation (Tiers 1-4) per `TEST_INFRA.md`.
- **Output**: Test suite runners and `TEST_READY.md`.

---

## Interface Contracts

### 1. Token Engine ↔ State Manager (`TokenScanUseCase` ↔ `SessionManager`)
```kotlin
interface TokenScanUseCase {
    suspend fun handleTokenScanned(tokenHash: String, tokenType: TokenType): ScanResult
}

sealed interface ScanResult {
    data class BrickedSuccessfully(val tokenLabel: String) : ScanResult
    data class UnbrickedSuccessfully(val tokenLabel: String) : ScanResult
    object UnpairedTokenRejected : ScanResult
}

interface SessionManager {
    fun isBricked(): Boolean
    fun getActiveSession(): StateFlow<BrickSessionEntity?>
    suspend fun brick(modeId: Long? = null)
    suspend fun unbrick(trigger: UnbrickTrigger)
    fun isStrictModeActive(): Boolean
}
```

### 2. Policy Engine ↔ Accessibility Service (`AppBlockPolicy` ↔ `BrickAccessibilityService`)
```kotlin
interface AppBlockPolicy {
    fun shouldBlockPackage(packageName: String): Boolean
}

interface SystemWhitelist {
    fun isWhitelisted(packageName: String): Boolean
}
```

### 3. Recovery Engine ↔ Time Provider (`RecoveryEngine` ↔ `TimeProvider`)
```kotlin
interface TimeProvider {
    fun currentTimeMillis(): Long
    fun elapsedRealtime(): Long
}

interface RecoveryEngine {
    suspend fun startRecovery()
    suspend fun onTick()
    suspend fun onRebootDetected()
    fun getRecoveryState(): StateFlow<RecoveryState>
}

data class RecoveryState(
    val isActive: Boolean,
    val remainingMs: Long,
    val lastElapsedRealtime: Long,
    val bootId: String
)
```

### 4. Test Hooks Contract (`SimulateTokenScanReceiver`)
```
Action: com.brick.action.SIMULATE_TOKEN_SCAN
Extras:
  - token_type: String ("NFC" | "QR")
  - token_payload: String (raw tag UID hex or QR payload)
  - is_pairing: Boolean (default false)
  - token_label: String (optional)

Action: com.brick.action.ADVANCE_TEST_CLOCK
Extras:
  - delta_ms: Long (milliseconds to advance monotonic test clock)

Action: com.brick.action.START_RECOVERY
Action: com.brick.action.CHECK_RECOVERY
```

---

## Code Layout

The project source code will reside in `C:\Users\mitch\teamwork_projects\brick`:

```
C:\Users\mitch\teamwork_projects\brick\
├── build.gradle.kts                     // Root Gradle script
├── settings.gradle.kts                  // Gradle project settings
├── gradle.properties                    // JVM arguments and AndroidX flags
├── local.properties                     // sdk.dir pointing to Android SDK
├── gradlew.bat                          // Windows Gradle wrapper
├── gradlew                              // Unix Gradle wrapper
├── gradle/
│   ├── wrapper/gradle-wrapper.properties
│   └── libs.versions.toml               // Version catalog (AGP 9.0.1, Kotlin 2.3.20)
├── app/
│   ├── build.gradle.kts                 // Application build configuration & signing
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      // Core permissions, services, receivers
│       │   ├── java/com/brick/app/
│       │   │   ├── BrickApplication.kt
│       │   │   ├── core/
│       │   │   │   ├── common/          // TimeProvider, Result, Constants
│       │   │   │   ├── security/        // CryptoUtils, RecoveryEngine
│       │   │   │   ├── database/        // Room DB, Entities, DAOs, Converters
│       │   │   │   └── policy/          // AppBlockPolicy, SystemWhitelist
│       │   │   ├── feature/
│       │   │   │   ├── service/         // Accessibility, Foreground, Admin, Boot
│       │   │   │   ├── nfc/             // NfcManager, TokenScanUseCase
│       │   │   │   ├── qr/              // QrCodeGenerator, QrCodeAnalyzer
│       │   │   │   ├── home/            // SessionManager, MainViewModel
│       │   │   │   ├── modes/           // ModeRepository, ModeViewModel
│       │   │   │   ├── schedules/       // ScheduleManager, ScheduleReceiver
│       │   │   │   ├── stats/           // StreakCalculator, StatsViewModel
│       │   │   │   └── onboarding/      // PermissionManager, OnboardingViewModel
│       │   │   └── ui/
│       │   │       ├── MainActivity.kt
│       │   │       ├── blocking/        // BlockingOverlayActivity
│       │   │       ├── theme/           // Stark B&W Theme
│       │   │       └── screens/         // Home, Modes, Schedules, Stats, Onboarding
│       │   └── res/
│       │       ├── xml/                 // accessibility_service_config.xml, device_admin.xml
│       │       └── values/              // strings.xml, themes.xml
│       ├── debug/
│       │   ├── AndroidManifest.xml      // Debug receiver registration
│       │   └── java/com/brick/app/feature/service/SimulateTokenScanReceiver.kt
│       └── test/                        // JVM Unit tests
│           └── java/com/brick/app/
│               ├── CryptoUtilsTest.kt
│               ├── AppBlockPolicyTest.kt
│               ├── RecoveryEngineTest.kt
│               └── StreakCalculatorTest.kt
├── verify_e2e.ps1                       // Standalone automated E2E reviewer script
└── README.md                            // Complete project documentation
```
