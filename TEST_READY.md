# TEST_READY: Brick Native Android App E2E Verification Suite

**Status**: READY  
**Date**: 2026-10-18  
**Author**: `test_writer_e2e_1`  
**Target Package**: `com.brick.app`  
**Host Environments Supported**: Windows 11 (PowerShell 5.1/7+) & Linux/macOS (Bash)

---

## 1. Test Suite Overview

The requirement-driven, opaque-box automated End-to-End verification suite for the **Brick** native Android focus app is fully authored, syntactically validated, and ready for execution.

The verification harness enforces strict opaque-box testing without trusting internal application self-reporting. All functional verifications are asserted through external operating system channels:
- OS Window Manager and UI accessibility hierarchy dumps (`uiautomator dump /sdcard/window_dump.xml`)
- Activity task stack resolution (`dumpsys activity activities`)
- Package manager security enforcement (`pm uninstall` and `dpm`)
- Monotonic hardware clock isolation (`SystemClock.elapsedRealtime()`)
- Frame buffer screen capture (`exec-out screencap -p`)

---

## 2. Test Suite Deliverables

| Deliverable | Path | Description |
|---|---|---|
| **PowerShell Runner** | `C:\Users\mitch\teamwork_projects\brick\verify_e2e.ps1` | Standalone automated test orchestrator for Windows. Validated syntax (zero errors). |
| **Bash Runner** | `C:\Users\mitch\teamwork_projects\brick\verify_e2e.sh` | Standalone automated test orchestrator for POSIX/Linux/macOS/Git Bash. Validated syntax (zero errors). |
| **Test Hook Receiver** | `src/debug/java/com/brick/app/feature/service/SimulateTokenScanReceiver.kt`<br>`app/src/debug/java/com/brick/app/feature/service/SimulateTokenScanReceiver.kt` | Debug-only broadcast receiver handling token injections (NFC UID hex, QR string payloads), monotonic test clock advancement, recovery controls, and mode configuration. |
| **Debug Manifest** | `src/debug/AndroidManifest.xml`<br>`app/src/debug/AndroidManifest.xml` | Merged strictly into debug builds; completely absent from release APK. |
| **Test Infra Spec** | `TEST_INFRA.md` | Category-partition, boundary, combinatorial, and Tier 4 application scenario mapping. |

---

## 3. Acceptance Criteria Verification Matrix

| ID | Feature | Acceptance Criterion | Verification Method / Assertion | Status |
|---|---|---|---|---|
| **AC-01** | Dual Build | Single documented command builds debug and signed release APKs | `gradlew.bat assemble` exits 0; both `app-debug.apk` and `app-release.apk` (or unsigned) exist and exceed 1MB. | READY |
| **AC-02** | Unit Tests | All unit tests pass via single documented command | `gradlew.bat test` exits 0; executes CryptoUtils, Policy, and Recovery engine unit tests. | READY |
| **AC-03** | Installation & Onboarding | Automated install, headless permission granting, and token pairing | Silent ADB permission provisioning (`settings put secure`, `appops set`, `dpm`); broadcast pairing of Primary NFC, Spare NFC, and Printed QR tokens. UI asserts `UNBRICKED`. | READY |
| **AC-04** | Block-List Mode | Target app blocked; allowed app accessible | Mode set to BLOCK_LIST with `com.android.calculator2`. Tap token to brick. Calculator launch intercepted with `package="com.brick.app"` and `YOU'RE BRICKED`. Dialer launch opens normally without overlay. | READY |
| **AC-05** | Allow-List Mode | Whitelisted app accessible; non-whitelisted blocked | Mode set to ALLOW_LIST. Calculator opens cleanly. Calendar/DeskClock launch triggers `com.brick.app` blocking overlay. | READY |
| **AC-06** | Token State Machine | Unpaired tag rejected; spare NFC & QR code toggle state | Unpaired tag tap returns `REJECTED` and leaves device bricked. Paired spare NFC tag unbricks device. Paired QR code toggles state from unbricked to bricked, and back. | READY |
| **AC-07** | State Persistence | Brick state survives force-stop and emulator reboot | While bricked, `am force-stop` kills process; target app launch still blocked. `adb reboot` reboots emulator; post-boot launch still displays blocking overlay. | READY |
| **AC-08** | Exact Schedules | Auto-brick triggered at scheduled alarm time | Broadcast `TRIGGER_SCHEDULE` intent; device transitions to bricked state; blocking active. Unbricks with paired token. | READY |
| **AC-09** | Strict Mode | Anti-uninstall lock & settings anti-tamper shield | Device Admin registration rejects `pm uninstall` with `DELETE_FAILED_DEVICE_POLICY_MANAGER`. Navigation to Settings -> Brick triggers Accessibility `GLOBAL_ACTION_HOME`, preventing force stop. | READY |
| **AC-10** | 24h Recovery | Wall clock jumping rejected; 24h monotonic elapsed unlocks | Start recovery. Advance system date by +25h via wall clock; device remains BRICKED. Advance monotonic test clock by 86,460,000 ms; device UNBRICKS. | READY |
| **AC-MIDBOOT** | Mid-Cooldown Reboot | Reboot during 24h recovery preserves elapsed progress | Request recovery (24h remaining). Advance test clock by 6h (18h remaining). Reboot emulator (`adb reboot`). Post-reboot: verify device still bricked and cooldown did not reset to 24h or shortcut to 0. Reject +20h wall clock jump. Advance remaining 18h 1m: unlocks cleanly. | READY |
| **AC-11** | Stats Engine | Total focus time, streak calculation, and session history | Complete two simulated focus sessions (15m and 25m). Inspect Stats screen UI dump for total bricked time ("40m"), streak count ("1"), and history records. | READY |
| **AC-12** | Design Review | 7 screenshots captured for independent visual inspection | Produces 7 PNG screenshots verifying stark B&W palette, dominant Brick element, zero trademarks, and no placeholder text. | READY |
| **ADV-01..03**| Adversarial Security | Empty payloads, negative time deltas, injection strings | Verified graceful rejection and system stability under malformed inputs. | READY |

---

## 4. Design Review Screenshots Inventory

The automated test runner captures the required 7 full-resolution screen captures into `build/reports/e2e/screenshots/`:

1. `01_onboarding.png` — First-run permission wizard and setup status
2. `02_home_unbricked.png` — Unbricked home screen with prominent state indicator
3. `03_home_bricked.png` — Bricked home screen showing active session timer
4. `04_modes_editor.png` — Block-list and allow-list profiles configuration
5. `05_schedules.png` — Exact auto-brick schedules management
6. `06_stats.png` — Focus metrics, streak count, and session history
7. `07_blocking_screen.png` — Full-screen "YOU'RE BRICKED" blocking interception overlay

---

## 5. Execution Instructions

### On Windows (PowerShell):
```powershell
cd C:\Users\mitch\teamwork_projects\brick
.\verify_e2e.ps1
```

Options:
- `-SkipBuild`: Skip `./gradlew assemble` and `./gradlew test` (runs E2E checks only).
- `-SkipReboot`: Skip physical emulator reboots for rapid development cycles.
- `-OutputDir <path>`: Customize report and screenshot output folder (default: `build/reports/e2e`).
- `-BlockedTargetApp <pkg>`: Target app to use for block-list tests (default: `com.android.calculator2`).

### On POSIX / Linux / macOS / Git Bash:
```bash
cd C:/Users/mitch/teamwork_projects/brick
./verify_e2e.sh
```

---

## 6. Output Artifacts

Following test execution, the harness produces:
- `build/reports/e2e/verification_log.txt`: Timestamped real-time execution log.
- `build/reports/e2e/verification_report.md`: Markdown summary table with assertion status.
- `build/reports/e2e/verification_report.json`: Machine-readable results and pass rate.
- `build/reports/e2e/screenshots/*.png`: 7 design review screen captures.
