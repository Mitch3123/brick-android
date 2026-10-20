# Brick verification status

The earlier READY matrix was a test plan, not evidence that its acceptance checks passed. It used an old project path and treated debug broadcasts as though they were physical NFC/QR scans. Do not use it as a test certificate.

## Run from this project's root

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:assembleDebug :app:assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest
.\verify_e2e.ps1
```

The last two commands need a connected, provisioned Android device or emulator. The PowerShell and Bash runners (`verify_e2e.ps1`, `verify_e2e.sh`) use a test-only `com.brick.app.e2e` APK with E2E-only simulation hooks for token and clock cases; they refuse real phones and require exactly one connected emulator; they do **not** verify real NFC reading, camera QR decoding, or 24 hours of unattended alarm delivery. Check their assertions and reports in `build/reports/e2e/` before claiming E2E coverage. JVM test counts and successfully compiled instrumented tests do not establish passing Android UI tests.

Real-device acceptance needs pairing a physical NFC tag and a printed QR card, testing both lock and unlock, checking blocking after setup, waiting for recurring schedule alarms, and observing a real 24-hour recovery. Android accessibility and device-admin permissions can be revoked by the device owner, so strict mode is not a tamper-proof system lock.

For JVM execution results inspect `app/build/test-results/testDebugUnitTest/` and `app/build/reports/tests/testDebugUnitTest/index.html`. Record the actual Gradle exit code, test totals and failures for the current checkout before marking any item passed.

## Verification recorded 2026-10-20

The latest Gradle run of `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleRelease :app:assembleE2e` exited 0 after the E2E reset and default-mode reseed fixes. Debug JVM XML contains 139 tests, 0 failures, 0 errors and 0 skipped across 11 suites. Debug Android UI tests compiled but did not execute. Gradle `:app:tasks --all` exposes only `testDebugUnitTest`; `:app:testE2eUnitTest` is not available, so no E2E JVM-variant pass is claimed. No emulator/device was attached; neither E2E runner nor real NFC/QR, delivered alarms or real-time recovery was exercised.

Do not install `app-e2e.apk` for daily use: it has an exported test receiver that can reset state and advance synthetic monotonic and wall clocks. Its manifest marks it `testOnly` and test harnesses install it with `adb install -t` only after checking for an emulator. Normal debug and release APKs exclude this receiver and use real elapsed time.

A separate source-level regression check, `python -m unittest discover -s tests -v`, passed 5 tests for harness broadcast flags, reset/onboarding order, alarm cleanup, overlay wording and fresh UI dumps. These static checks do not replace running either emulator script. PowerShell syntax parsing passed; Bash script execution and syntax remain unverified on this Windows host.
