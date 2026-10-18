# E2E Test Infra: Brick Native Android App

## Test Philosophy
- Opaque-box, requirement-driven. Derived from `ORIGINAL_REQUEST.md` independently of implementation details.
- Methodology: Category-Partition + Boundary Value Analysis + Pairwise Combinatorial + Real-World Workload Testing.
- Zero reliance on application self-reporting: assertions query the OS window manager and UI hierarchy (`uiautomator dump` and `adb shell dumpsys`).

## Feature Inventory & Test Mapping
| # | Feature | Requirement Source | Tier 1 (Coverage) | Tier 2 (Boundary/Corner) | Tier 3 (Cross-Feature) | Tier 4 (Workload) |
|---|---|---|---|---|---|---|
| F01 | Build & Packaging | ORIGINAL_REQUEST §Acceptance Criteria Build | Dual APK compilation | Missing SDK / Clean build | Release signing verification | CLI-only build pipeline |
| F02 | Token Pairing | ORIGINAL_REQUEST §R2 | NFC tag pairing, QR token pairing | Corrupted payload, empty string | Multiple spare tokens (NFC 1 + NFC 2 + QR) | Full setup journey |
| F03 | Brick/Unbrick Loop | ORIGINAL_REQUEST §R2 | Toggle state via paired token | Unpaired token tap (reject) | Spare token unbrick, QR toggle | Session life-cycle |
| F04 | App Interception | ORIGINAL_REQUEST §R2 | Blocked app triggers overlay | Rapid back button, split-screen | Notification/Deep-link launch | Full focus block journey |
| F05 | State Persistence | ORIGINAL_REQUEST §R2 | Force-stop survival | Memory kill (`am kill`) | Full emulator reboot (`adb reboot`) | Reboot while bricked |
| F06 | Modes Engine | ORIGINAL_REQUEST §R3 | Block-list blocking | Allow-list blocking | Whitelisted dialer / system UI | Multi-mode switching |
| F07 | Schedules Engine | ORIGINAL_REQUEST §R3 | Exact alarm auto-brick | Alarm while device sleeping | Scheduled session + token unbrick | Full schedule workflow |
| F08 | Strict Mode | ORIGINAL_REQUEST §R3 | Device Admin anti-uninstall | Attempt `pm uninstall` via ADB | Settings -> Apps -> Brick shield | Tamper resistance |
| F09 | 24-Hour Recovery | ORIGINAL_REQUEST §R3 | Request recovery start | Wall clock forward 25h (fails) | Monotonic test clock +24h (unlocks) | Emergency unlock journey |
| F10 | Recovery Reboot | Follow-up 2026-10-18 | Mid-cooldown reboot | Clock jump after reboot | Resume countdown accurately | Reboot during recovery |
| F11 | Stats & Streak | ORIGINAL_REQUEST §R4 | Log completed session | Consecutive day session | Gap day streak reset | Multi-session history |
| F12 | Stark B&W UI | ORIGINAL_REQUEST §R4 | High-contrast palette | No lorem / placeholder text | Prominent dominant state card | 7 screenshot design review |

## Test Architecture
- **Host Runner**: PowerShell (`verify_e2e.ps1`) and Bash (`verify_e2e.sh`).
- **Target Platform**: Android Emulator API 34 (headless AVD `medium_phone`).
- **Assertion Channels**:
  * `adb shell uiautomator dump /sdcard/window_dump.xml`: UI hierarchy XML parsing.
  * `adb exec-out screencap -p`: Screen capture for visual inspection.
  * `adb shell pm list packages`: Package state.
  * `adb shell dumpsys activity`: Resumed activity inspection.
- **Test Hooks**:
  * Broadcast receiver `SimulateTokenScanReceiver` registered strictly in debug source set (`src/debug/`).
  * Non-existent in release APK.

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Expected Outcome |
|---|---|---|---|
| S1 | Focus Workday Session | F02, F03, F04, F05, F11 | User pairs NFC tag, bricks with "Deep Work" block-list, attempts blocked app (blocked), force-stops app (still blocked), unbricks with tag, stats updated. |
| S2 | Bedtime Allow-List Schedule | F06, F07, F04, F03 | Allow-list mode active via schedule; phone/alarm accessible, browser/social blocked; unbricks in morning with spare NFC tag. |
| S3 | Lost Token 24h Escape Attempt | F09, F10, F08 | User loses token, requests 24h recovery, attempts to change system date (denied), reboots device (progress preserved), completes 24h real time (unbricked). |
| S4 | Anti-Tamper Bypass Defense | F08, F04 | User tries to uninstall Brick while bricked (denied by Device Admin), tries to Force Stop in Settings (kicked to Home by Accessibility shield). |
| S5 | Spare Token Family/Desk Setup | F02, F03 | User pairs primary NFC tag, spare NFC tag, and printed desk QR code; any token can unbrick the session. |

## Minimum Thresholds Summary
- Tier 1: ≥60 test assertions across all features
- Tier 2: ≥60 boundary/corner cases
- Tier 3: Pairwise coverage of major feature interactions
- Tier 4: ≥5 realistic end-to-end user application workflows
