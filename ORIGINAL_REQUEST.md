# Original User Request

## Initial Request — 2026-10-18T01:24:23Z

Build a complete, working recreation of the "Brick" focus / screen-time app (getbrick.com) as a native Android app for personal use: the user pairs a physical token (a generic NFC tag, or a printed QR code as fallback), taps it to "brick" the phone — blocking a chosen set of distracting apps — and must tap the same token again to "unbrick".

Working directory: C:\Users\mitch\teamwork_projects\brick
Integrity mode: development

Environment notes: Host is Windows. Java is installed; an Android SDK exists at `%LOCALAPPDATA%\Android\Sdk` (ANDROID_HOME is not set); the `android` CLI is at `C:\Users\mitch\AppData\AndroidCLI\android.exe`. Android Studio is not installed. Automated testing must run on an Android emulator — no physical device or NFC tag is available to the team (the user will test on a real phone + tag afterwards).

## Requirements

### R1. Platform and distribution
Native Android app written in Kotlin. Personal use only: produces an installable (sideloadable) APK, works fully offline, stores all data on-device, no accounts or backend. Use an original name/logo/assets — do not copy Brick's trademarks or artwork.

### R2. Token pairing and brick/unbrick loop
- The user can pair one or more tokens: any generic NFC tag, and/or a QR code the app generates for the user to print. Multiple tokens may be paired (spares).
- Scanning a paired token toggles between Bricked and Unbricked. Scanning an unpaired tag/QR must never unbrick.
- While bricked, launching any blocked app is intercepted and the user sees a full-screen "you're bricked" blocking screen instead of the app.
- Brick state and blocking must survive app process death and device reboot.

### R3. Modes, schedules, strict mode, and recovery
- **Modes:** named profiles, each either a block-list (block the selected apps) or an allow-list (block everything except the selected apps). The user chooses which mode to activate when bricking.
- **Schedules:** auto-brick at user-defined days/times using a chosen mode; unbricking still requires a paired token.
- **Strict mode (optional toggle):** while bricked, makes bypassing hard — e.g. prevents uninstalling the app and disabling the permissions/services it relies on, as far as Android allows.
- **Recovery escape (no emergency unbricks):** if all tokens are lost, the user can request an unlock which only takes effect after a 24-hour cooldown; the cooldown cannot be shortened (e.g. by changing the device clock).

### R4. Stats, onboarding, and design
- **Stats:** total time bricked, current and longest streak, and a history of past brick sessions.
- **Onboarding:** first-run flow that explains the app, walks the user through granting every required permission (with clear status of what's still missing), and pairs a first token.
- **Design:** faithful to Brick's feel — minimal, predominantly black & white, a large bold Brick/Unbrick state on the home screen, clean typography.

## Verification Resources
None provided by the user. The team must build its own emulator-based verification, including a way to simulate a paired vs. unpaired NFC tag scan and a QR scan on the emulator (test-only hooks are acceptable if they are excluded from or inert in the release build).

## Acceptance Criteria

### Build
- [ ] A single documented command builds both a debug and a release APK from a clean checkout on this Windows machine with no Android Studio.
- [ ] All unit tests pass via a single documented command.

### End-to-end (automated, on emulator, script runnable by an independent reviewer)
- [ ] Script installs the APK on a fresh emulator, completes onboarding/permission granting, and pairs a simulated NFC token.
- [ ] With a block-list mode containing a target app, after bricking, launching that app shows the blocking screen (verified from the UI hierarchy, not by the app's own self-report); a non-blocked app opens normally.
- [ ] Allow-list mode: a non-allowed app is blocked and an allowed app opens.
- [ ] An unpaired tag scan leaves the device bricked; a paired tag (including a second "spare" tag) unbricks it; a paired QR scan also toggles state.
- [ ] Brick state and blocking persist after force-stopping the app and after an emulator reboot.
- [ ] A schedule set to start within the next minutes auto-bricks the device at the scheduled time.
- [ ] With strict mode on and bricked, an `adb`-free in-UI attempt to uninstall the app or disable its required service/permission is blocked or immediately reverted.
- [ ] Recovery: requesting an unlock does not unbrick before 24h; advancing the device clock does not shortcut it; the unlock takes effect once 24h of real elapsed time has passed (testable via an injectable/test clock).
- [ ] After two completed sessions, stats show correct total bricked time, streak, and both sessions in history.

### Design review (independent agent-as-judge using emulator screenshots)
- [ ] Screenshots of onboarding, home (bricked and unbricked), modes editor, schedules, stats, and the blocking screen are produced.
- [ ] A reviewer that did not write the code confirms, per screen: predominantly black/white palette; Brick/Unbrick state is the dominant element on home; no Brick trademarks/logos; no placeholder/lorem text; no clipped or overlapping text at default font size.

### Docs
- [ ] README explaining how to build, install on a real phone, pair an NFC tag, print the QR code, and run the test suites.


## Follow-up — 2026-10-18T01:40:42Z

Note from the requester side, please relay to the orchestrator: SystemClock.elapsedRealtime() resets on reboot. If the 24h recovery relies on it alone, a reboot would either lose progress or (if mishandled) reset/shortcut the cooldown. The acceptance criterion requires the unlock to take effect after 24h of real elapsed time *and* not be shortenable by clock changes — please make sure the design handles reboots during the cooldown (e.g. persist accumulated elapsed time and never count wall-clock jumps forward), and add a test for "reboot mid-cooldown".
