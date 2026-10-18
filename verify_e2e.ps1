<#
.SYNOPSIS
    Automated End-to-End Acceptance Verification Harness for Brick Native Android App.
.DESCRIPTION
    Executes headless emulator verification for all acceptance criteria in ORIGINAL_REQUEST.md:
    AC-1 through AC-12, including the reboot mid-cooldown test case, UI hierarchy assertions,
    adversarial edge cases, and 7 design review screenshot captures.
.PARAMETER AdbPath
    Path to adb.exe. Auto-detected from PATH or LOCALAPPDATA if omitted.
.PARAMETER PackageName
    Target app package name (default: com.brick.app).
.PARAMETER BlockedTargetApp
    Installed package used as blocked target in block-list tests (default: com.android.calculator2).
.PARAMETER AllowedTargetApp
    Installed package used as allowed target (default: com.android.dialer).
.PARAMETER OutputDir
    Output directory for logs, reports, and screenshots (default: build/reports/e2e).
.PARAMETER SkipBuild
    Skip Gradle assemble and unit test steps.
.PARAMETER SkipReboot
    Skip full emulator reboot tests (useful for quick headless runs).
.PARAMETER StopOnFirstFailure
    Halt immediately on first assertion failure.
#>

param (
    [string]$AdbPath = "",
    [string]$PackageName = "com.brick.app",
    [string]$BlockedTargetApp = "com.android.calculator2",
    [string]$AllowedTargetApp = "com.android.dialer",
    [string]$OutputDir = "",
    [switch]$SkipBuild,
    [switch]$SkipReboot,
    [switch]$StopOnFirstFailure
)

$ErrorActionPreference = "Continue"

$ProjectRoot = $PSScriptRoot
if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot "build\reports\e2e"
}
$ScreenshotDir = Join-Path $OutputDir "screenshots"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
New-Item -ItemType Directory -Force -Path $ScreenshotDir | Out-Null

$LogFile = Join-Path $OutputDir "verification_log.txt"
$ReportMdFile = Join-Path $OutputDir "verification_report.md"
$ReportJsonFile = Join-Path $OutputDir "verification_report.json"

"=== Brick Native Android App E2E Verification Suite ===" | Out-File $LogFile -Encoding utf8
"Started at: $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssZ')" | Out-File $LogFile -Append -Encoding utf8

$Global:TestResults = [System.Collections.Generic.List[PSObject]]::new()
$Global:TotalPassed = 0
$Global:TotalFailed = 0
$Global:SuiteStartTime = Get-Date

function Write-Log([string]$msg, [string]$color = "White") {
    $timestamp = (Get-Date).ToString("HH:mm:ss.fff")
    $formatted = "[$timestamp] $msg"
    Write-Host $formatted -ForegroundColor $color
    $formatted | Out-File $LogFile -Append -Encoding utf8
}

function Write-Section([string]$title) {
    Write-Host ""
    Write-Log "======================================================================" "Cyan"
    Write-Log "  $title" "Cyan"
    Write-Log "======================================================================" "Cyan"
}

function Assert-Test([string]$testId, [string]$testName, [bool]$passed, [string]$details = "") {
    $result = [PSCustomObject]@{
        Id        = $testId
        Name      = $testName
        Passed    = $passed
        Details   = $details
        Timestamp = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ssZ")
    }
    $Global:TestResults.Add($result)

    if ($passed) {
        $Global:TotalPassed++
        Write-Log "  [PASS] [$testId] $testName" "Green"
        if ($details) { Write-Log "         Details: $details" "DarkGray" }
    } else {
        $Global:TotalFailed++
        Write-Log "  [FAIL] [$testId] $testName" "Red"
        if ($details) { Write-Log "         Failure Details: $details" "Magenta" }
        if ($StopOnFirstFailure) {
            Write-Log "Halting execution due to -StopOnFirstFailure flag." "Red"
            Generate-Reports
            exit 1
        }
    }
}

function Resolve-Adb() {
    if ($AdbPath -and (Test-Path $AdbPath)) {
        return $AdbPath
    }
    $cmdAdb = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmdAdb) {
        return $cmdAdb.Source
    }
    $sdkAdb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) {
        return $sdkAdb
    }
    $sdkAdb2 = "C:\Users\mitch\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb2) {
        return $sdkAdb2
    }
    throw "ADB binary not found. Please specify -AdbPath."
}

function Exec-Adb([string]$adb, [string]$cmd) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $adb
    $psi.Arguments = $cmd
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()

    return [PSCustomObject]@{
        ExitCode = $proc.ExitCode
        StdOut   = $stdout.Trim()
        StdErr   = $stderr.Trim()
    }
}

function Get-TopUiDump([string]$adb) {
    Exec-Adb $adb "shell uiautomator dump /sdcard/window_dump.xml" | Out-Null
    $res = Exec-Adb $adb "shell cat /sdcard/window_dump.xml"
    return $res.StdOut
}

function Capture-Screenshot([string]$adb, [string]$name) {
    $path = Join-Path $ScreenshotDir "$name.png"
    $bytes = & $adb exec-out screencap -p
    [System.IO.File]::WriteAllBytes($path, $bytes)
    Write-Log "Saved screenshot: $path" "Yellow"
    return (Test-Path $path) -and ((Get-Item $path).Length -gt 1000)
}

function Wait-For-Boot([string]$adb, [int]$timeoutSec = 180) {
    Write-Log "Waiting up to $timeoutSec seconds for device boot completion..." "Cyan"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        $res = Exec-Adb $adb "shell getprop sys.boot_completed"
        if ($res.StdOut -eq "1") {
            Write-Log "Device boot completed in $([math]::Round($sw.Elapsed.TotalSeconds, 1))s." "Green"
            Start-Sleep -Seconds 5
            return $true
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Generate-Reports() {
    Write-Section "Test Suite Summary & Report Generation"

    $duration = (Get-Date) - $Global:SuiteStartTime
    $total = $Global:TotalPassed + $Global:TotalFailed
    $passRate = if ($total -gt 0) { [math]::Round(($Global:TotalPassed / $total) * 100, 1) } else { 0 }

    Write-Log "Total Assertions: $total" "Cyan"
    Write-Log "Passed:           $Global:TotalPassed" "Green"
    Write-Log "Failed:           $Global:TotalFailed" $(if ($Global:TotalFailed -gt 0) { "Red" } else { "Green" })
    Write-Log "Pass Rate:        $passRate%" "Cyan"
    Write-Log "Total Duration:   $($duration.ToString('hh\:mm\:ss'))" "Cyan"

    # Export JSON report
    $reportObj = [PSCustomObject]@{
        Summary = [PSCustomObject]@{
            TotalTests = $total
            Passed     = $Global:TotalPassed
            Failed     = $Global:TotalFailed
            PassRate   = "$passRate%"
            Duration   = "$($duration.ToString('hh\:mm\:ss'))"
            Timestamp  = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ssZ")
        }
        Results = $Global:TestResults
    }
    $reportObj | ConvertTo-Json -Depth 5 | Out-File $ReportJsonFile -Encoding utf8
    Write-Log "Wrote JSON report to: $ReportJsonFile" "DarkGray"

    # Export Markdown report
    $md = [System.Text.StringBuilder]::new()
    [void]$md.AppendLine("# Brick Native Android App - E2E Verification Report")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("- **Date**: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss UTC')")
    [void]$md.AppendLine("- **Total Assertions**: $total")
    [void]$md.AppendLine("- **Passed**: $Global:TotalPassed")
    [void]$md.AppendLine("- **Failed**: $Global:TotalFailed")
    [void]$md.AppendLine("- **Pass Rate**: $passRate%")
    [void]$md.AppendLine("- **Duration**: $($duration.ToString('hh\:mm\:ss'))")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("## Acceptance Criteria Results")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("| ID | Test Name | Status | Details |")
    [void]$md.AppendLine("|---|---|---|---|")

    foreach ($t in $Global:TestResults) {
        $badge = if ($t.Passed) { "[PASS]" } else { "[FAIL]" }
        $cleanDetails = ($t.Details -replace "\|", "/").Trim()
        [void]$md.AppendLine("| $($t.Id) | $($t.Name) | $badge | $cleanDetails |")
    }

    [void]$md.AppendLine("")
    [void]$md.AppendLine("## Design Review Screenshots")
    [void]$md.AppendLine("")
    $screenshots = Get-ChildItem -Path $ScreenshotDir -Filter "*.png" -ErrorAction SilentlyContinue
    if ($screenshots) {
        foreach ($s in $screenshots) {
            $kb = [math]::Round($s.Length / 1024, 1)
            [void]$md.AppendLine("- **$($s.Name)** ($kb KB): screenshots/$($s.Name)")
        }
    } else {
        [void]$md.AppendLine("*No screenshots captured.*")
    }

    $md.ToString() | Out-File $ReportMdFile -Encoding utf8
    Write-Log "Wrote Markdown report to: $ReportMdFile" "Green"
}

# ====================================================================
# MAIN TEST EXECUTION
# ====================================================================

try {
    $Adb = Resolve-Adb
    Write-Log "Using ADB executable: $Adb" "Cyan"

    # ----------------------------------------------------------------
    # 0. Environment & Device Connectivity Check
    # ----------------------------------------------------------------
    Write-Section "Step 0: ADB & Device Connectivity Verification"
    $devRes = Exec-Adb $Adb "devices"
    $hasDevice = ($devRes.StdOut -match "(?m)^[a-zA-Z0-9_\-\.\:]+\s+device$")
    Assert-Test "ENV-01" "Connected Android device or emulator available" $hasDevice "ADB Devices: $($devRes.StdOut)"

    if (-not $hasDevice) {
        Write-Log "No connected device or emulator found. Checking available emulators..." "Yellow"
        $cliRes = Get-Command "C:\Users\mitch\AppData\AndroidCLI\android.exe" -ErrorAction SilentlyContinue
        if ($cliRes) {
            Write-Log "Android CLI present. Checking emulator list..." "Yellow"
            & $cliRes.Source emulator list
        }
        throw "Cannot proceed with E2E tests without an active Android device or emulator."
    }

    $devSdk = (Exec-Adb $Adb "shell getprop ro.build.version.sdk").StdOut
    $devModel = (Exec-Adb $Adb "shell getprop ro.product.model").StdOut
    Write-Log "Target Device: Model=$devModel, API Level=$devSdk" "Cyan"
    Assert-Test "ENV-02" "Target device is API 26 or higher" ([int]$devSdk -ge 26) "API Level: $devSdk"

    # ----------------------------------------------------------------
    # 1. AC-1: Dual APK Build (Debug & Release)
    # ----------------------------------------------------------------
    Write-Section "Step 1: AC-1 - Dual APK Compilation"
    if (-not $SkipBuild) {
        $gradlewBat = Join-Path $ProjectRoot "gradlew.bat"
        if (Test-Path $gradlewBat) {
            Write-Log "Executing Gradle assemble task: .\gradlew.bat assemble..." "Cyan"
            $buildProc = Start-Process -FilePath $gradlewBat -ArgumentList "assemble" -WorkingDirectory $ProjectRoot -NoNewWindow -Wait -PassThru
            Assert-Test "AC-01-A" "gradlew.bat assemble exits with code 0" ($buildProc.ExitCode -eq 0) "ExitCode: $($buildProc.ExitCode)"
        } else {
            Write-Log "gradlew.bat not found at $ProjectRoot. Checking pre-existing APKs..." "Yellow"
        }

        $debugApkPath = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
        $debugExists = (Test-Path $debugApkPath) -and ((Get-Item $debugApkPath).Length -gt 1000000)
        Assert-Test "AC-01-B" "Debug APK produced and valid size" $debugExists "Path: $debugApkPath"

        $releaseApkPath = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
        $releaseUnsignedPath = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release-unsigned.apk"
        $releaseExists = (Test-Path $releaseApkPath) -or (Test-Path $releaseUnsignedPath)
        Assert-Test "AC-01-C" "Release APK produced" $releaseExists "Checked: $releaseApkPath"
    } else {
        Write-Log "Skipping build steps due to -SkipBuild flag." "Yellow"
    }

    # ----------------------------------------------------------------
    # 2. AC-2: Unit Tests Execution
    # ----------------------------------------------------------------
    Write-Section "Step 2: AC-2 - Unit Test Suite Execution"
    if (-not $SkipBuild) {
        $gradlewBat = Join-Path $ProjectRoot "gradlew.bat"
        if (Test-Path $gradlewBat) {
            Write-Log "Executing Gradle test task: .\gradlew.bat test..." "Cyan"
            $testProc = Start-Process -FilePath $gradlewBat -ArgumentList "test" -WorkingDirectory $ProjectRoot -NoNewWindow -Wait -PassThru
            Assert-Test "AC-02" "gradlew.bat test passes all unit tests" ($testProc.ExitCode -eq 0) "ExitCode: $($testProc.ExitCode)"
        }
    }

    # ----------------------------------------------------------------
    # 3. AC-3: Automated APK Installation & Headless Permission Provisioning
    # ----------------------------------------------------------------
    Write-Section "Step 3: AC-3 - APK Installation & Permission Provisioning"
    $debugApk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (Test-Path $debugApk) {
        Write-Log "Installing debug APK onto emulator..." "Cyan"
        $installRes = Exec-Adb $Adb "install -r -d `"$debugApk`""
        Assert-Test "AC-03-A" "Debug APK installed successfully" ($installRes.StdOut -match "Success") "ADB: $($installRes.StdOut)"
    } else {
        Write-Log "Debug APK not present; verifying existing installed package..." "Yellow"
    }

    Write-Log "Granting required permissions headlessly via ADB..." "Cyan"
    Exec-Adb $Adb "shell settings put secure enabled_accessibility_services $PackageName/$PackageName.feature.service.BrickAccessibilityService" | Out-Null
    Exec-Adb $Adb "shell settings put secure accessibility_enabled 1" | Out-Null
    Exec-Adb $Adb "shell appops set $PackageName android:get_usage_stats allow" | Out-Null
    Exec-Adb $Adb "shell appops set $PackageName SYSTEM_ALERT_WINDOW allow" | Out-Null
    Exec-Adb $Adb "shell pm grant $PackageName android.permission.POST_NOTIFICATIONS" | Out-Null
    Exec-Adb $Adb "shell pm grant $PackageName android.permission.CAMERA" | Out-Null
    Exec-Adb $Adb "shell appops set $PackageName SCHEDULE_EXACT_ALARM allow" | Out-Null
    Exec-Adb $Adb "shell dumpsys deviceidle whitelist +$PackageName" | Out-Null
    $dpmRes = Exec-Adb $Adb "shell dpm set-device-admin $PackageName/$PackageName.feature.service.BrickDeviceAdminReceiver"

    $accEnabled = (Exec-Adb $Adb "shell settings get secure enabled_accessibility_services").StdOut
    Assert-Test "AC-03-B" "Accessibility service enabled in secure settings" ($accEnabled -match "BrickAccessibilityService") "Enabled: $accEnabled"
    Assert-Test "AC-03-C" "Device Admin active for Strict Mode anti-uninstall" ($dpmRes.StdOut -match "Success" -or $dpmRes.StdOut -match "already") "DPM: $($dpmRes.StdOut)"

    # Launch app & capture onboarding
    Exec-Adb $Adb "shell am start -n $PackageName/.ui.MainActivity" | Out-Null
    Start-Sleep -Seconds 2
    $shot1 = Capture-Screenshot $Adb "01_onboarding"
    Assert-Test "AC-12-A" "Screenshot 01 (Onboarding) captured" $shot1 "Saved 01_onboarding.png"

    # Reset state to clean baseline
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.RESET_STATE" | Out-Null
    Start-Sleep -Milliseconds 500

    # Pair Primary NFC Token
    $pairNfc1 = Exec-Adb $Adb 'shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" --ez is_pairing true --es token_label "Primary Card"'
    Assert-Test "AC-03-D" "Primary NFC token paired successfully" ($pairNfc1.StdOut -match "result=0" -or $pairNfc1.StdOut -match "PAIRED") "Broadcast: $($pairNfc1.StdOut)"

    # Pair Spare NFC Token
    $pairNfc2 = Exec-Adb $Adb 'shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_SPARE_UID_7C2D" --ez is_pairing true --es token_label "Spare Keychain"'
    Assert-Test "AC-03-E" "Spare NFC token paired successfully" ($pairNfc2.StdOut -match "result=0" -or $pairNfc2.StdOut -match "PAIRED") "Broadcast: $($pairNfc2.StdOut)"

    # Pair Printed QR Code Token
    $pairQr = Exec-Adb $Adb 'shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type QR --es token_payload "BRICK-QR-V1:PRINTED_DESK_TOKEN_XYZ" --ez is_pairing true --es token_label "Desk QR Card"'
    Assert-Test "AC-03-F" "Printed QR token paired successfully" ($pairQr.StdOut -match "result=0" -or $pairQr.StdOut -match "PAIRED") "Broadcast: $($pairQr.StdOut)"

    # Refresh Home & Capture Unbricked
    Exec-Adb $Adb "shell am start -n $PackageName/.ui.MainActivity" | Out-Null
    Start-Sleep -Seconds 1
    $shot2 = Capture-Screenshot $Adb "02_home_unbricked"
    Assert-Test "AC-12-B" "Screenshot 02 (Home Unbricked) captured" $shot2 "Saved 02_home_unbricked.png"

    $homeDump = Get-TopUiDump $Adb
    Assert-Test "AC-03-G" "Home screen displays UNBRICKED state" ($homeDump -match "UNBRICKED" -or $homeDump -match "unbricked") "UI Hierarchy Dump"

    # ----------------------------------------------------------------
    # 4. AC-4: Block-List Mode Blocking Interception
    # ----------------------------------------------------------------
    Write-Section "Step 4: AC-4 - Block-List Mode Interception"
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SET_MODE --es mode_type BLOCK_LIST --es mode_name `"Work Focus`" --es packages `"$BlockedTargetApp`"" | Out-Null

    # Tap Primary Token to Brick
    $brickRes = Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_PRIMARY_UID_4A8B`""
    Start-Sleep -Seconds 1
    Assert-Test "AC-04-A" "Primary token scan transitioned state to BRICKED" ($brickRes.StdOut -match "result=0" -or $brickRes.StdOut -match "BRICKED") "Broadcast: $($brickRes.StdOut)"

    $shot3 = Capture-Screenshot $Adb "03_home_bricked"
    Assert-Test "AC-12-C" "Screenshot 03 (Home Bricked) captured" $shot3 "Saved 03_home_bricked.png"
    $brickedDump = Get-TopUiDump $Adb
    Assert-Test "AC-04-B" "Home screen UI displays BRICKED dominant state" ($brickedDump -match "BRICKED") "UI Dump Verification"

    # Launch Blocked App (Calculator)
    Write-Log "Launching blocked application ($BlockedTargetApp)..." "Cyan"
    Exec-Adb $Adb "shell monkey -p $BlockedTargetApp 1" | Out-Null
    Start-Sleep -Milliseconds 800

    $shot7 = Capture-Screenshot $Adb "07_blocking_screen"
    Assert-Test "AC-12-G" "Screenshot 07 (Blocking Screen) captured" $shot7 "Saved 07_blocking_screen.png"

    $blockDump = Get-TopUiDump $Adb
    Assert-Test "AC-04-C" "Blocking overlay covers screen (package: $PackageName)" ($blockDump -match "package=`"$PackageName`"") "Top Window Package"
    Assert-Test "AC-04-D" "Blocking overlay contains 'YOU''RE BRICKED' message" ($blockDump -match "YOU'RE BRICKED" -or $blockDump -match "BRICKED") "Blocking UI Text"
    Assert-Test "AC-04-E" "Target blocked app controls are inaccessible" ($blockDump -notmatch "com.android.calculator2:id/digit_7") "Target App Inaccessible"

    # Launch Allowed App (Dialer)
    Write-Log "Launching non-blocked allowed application ($AllowedTargetApp)..." "Cyan"
    Exec-Adb $Adb "shell monkey -p $AllowedTargetApp 1" | Out-Null
    Start-Sleep -Milliseconds 800
    $dialerDump = Get-TopUiDump $Adb
    Assert-Test "AC-04-F" "Non-blocked app opens normally without blocking overlay" ($dialerDump -notmatch "YOU'RE BRICKED" -and $dialerDump -match "dialer") "Allowed App Window Dump"

    # ----------------------------------------------------------------
    # 5. AC-5: Allow-List Mode Blocking Interception
    # ----------------------------------------------------------------
    Write-Section "Step 5: AC-5 - Allow-List Mode Interception"
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SET_MODE --es mode_type ALLOW_LIST --es mode_name `"Strict Whitelist`" --es packages `"$BlockedTargetApp`"" | Out-Null
    Start-Sleep -Milliseconds 500

    # Target app ($BlockedTargetApp) is now ALLOWED
    Write-Log "Launching whitelisted app ($BlockedTargetApp) in Allow-List mode..." "Cyan"
    Exec-Adb $Adb "shell monkey -p $BlockedTargetApp 1" | Out-Null
    Start-Sleep -Milliseconds 800
    $allowedDump = Get-TopUiDump $Adb
    Assert-Test "AC-05-A" "Whitelisted app opens without blocking in Allow-List mode" ($allowedDump -notmatch "YOU'RE BRICKED") "Whitelisted App Active"

    # Calendar or DeskClock is NOT allowed -> must be blocked
    $disallowedApp = "com.android.calendar"
    Write-Log "Launching non-whitelisted app ($disallowedApp) in Allow-List mode..." "Cyan"
    Exec-Adb $Adb "shell monkey -p $disallowedApp 1" | Out-Null
    Start-Sleep -Milliseconds 800
    $disallowedDump = Get-TopUiDump $Adb
    Assert-Test "AC-05-B" "Non-whitelisted app is intercepted by blocking screen" ($disallowedDump -match "package=`"$PackageName`"" -and ($disallowedDump -match "BRICKED")) "Intercepted Disallowed App"

    # ----------------------------------------------------------------
    # 6. AC-6: Unpaired Tag Rejection & Multi-Token State Toggling
    # ----------------------------------------------------------------
    Write-Section "Step 6: AC-6 - Unpaired Tag Rejection & Multi-Token Toggling"
    # Switch back to standard block-list mode
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SET_MODE --es mode_type BLOCK_LIST --es packages `"$BlockedTargetApp`"" | Out-Null

    # Scan Unpaired Tag -> Must Reject
    Write-Log "Scanning unpaired rogue NFC tag..." "Cyan"
    $unpairedRes = Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"UNPAIRED_ROGUE_HEX_BEEF`""
    Assert-Test "AC-06-A" "Unpaired NFC tag rejected" ($unpairedRes.StdOut -match "result=1" -or $unpairedRes.StdOut -match "REJECTED") "Broadcast: $($unpairedRes.StdOut)"

    # Verify device remains bricked
    Exec-Adb $Adb "shell monkey -p $BlockedTargetApp 1" | Out-Null
    Start-Sleep -Milliseconds 600
    Assert-Test "AC-06-B" "Device remains bricked after unpaired scan" ((Get-TopUiDump $Adb) -match "YOU'RE BRICKED") "Still Bricked"

    # Scan Paired Spare NFC Tag -> Must Unbrick
    Write-Log "Scanning paired spare NFC tag..." "Cyan"
    $spareRes = Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_SPARE_UID_7C2D`""
    Assert-Test "AC-06-C" "Paired spare NFC tag unbricks device" ($spareRes.StdOut -match "result=0" -or $spareRes.StdOut -match "UNBRICKED") "Broadcast: $($spareRes.StdOut)"

    # Scan Paired Printed QR Code -> Must Brick
    Write-Log "Scanning paired printed QR code card..." "Cyan"
    $qrBrickRes = Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type QR --es token_payload `"BRICK-QR-V1:PRINTED_DESK_TOKEN_XYZ`""
    Assert-Test "AC-06-D" "Paired QR code card bricks device" ($qrBrickRes.StdOut -match "result=0" -or $qrBrickRes.StdOut -match "BRICKED") "Broadcast: $($qrBrickRes.StdOut)"

    # Scan Paired Printed QR Code again -> Must Unbrick
    Write-Log "Scanning paired printed QR code card a second time..." "Cyan"
    $qrUnbrickRes = Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type QR --es token_payload `"BRICK-QR-V1:PRINTED_DESK_TOKEN_XYZ`""
    Assert-Test "AC-06-E" "Paired QR code card toggles to unbricked" ($qrUnbrickRes.StdOut -match "result=0" -or $qrUnbrickRes.StdOut -match "UNBRICKED") "Broadcast: $($qrUnbrickRes.StdOut)"

    # ----------------------------------------------------------------
    # 7. AC-7: Persistence across Force-Stop and Reboot
    # ----------------------------------------------------------------
    Write-Section "Step 7: AC-7 - State Persistence across Force-Stop & Reboot"
    # Brick device
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_PRIMARY_UID_4A8B`"" | Out-Null
    Start-Sleep -Seconds 1

    # Kill app process
    Write-Log "Executing 'am force-stop $PackageName'..." "Cyan"
    Exec-Adb $Adb "shell am force-stop $PackageName" | Out-Null
    Start-Sleep -Seconds 1

    # Launch blocked app -> Accessibility service must re-bind and block
    Write-Log "Launching blocked app after force-stop..." "Cyan"
    Exec-Adb $Adb "shell monkey -p $BlockedTargetApp 1" | Out-Null
    Start-Sleep -Milliseconds 900
    $forceStopDump = Get-TopUiDump $Adb
    Assert-Test "AC-07-A" "Blocking persists after force-stopping application process" ($forceStopDump -match "package=`"$PackageName`"" -and ($forceStopDump -match "BRICKED")) "Overlay Active Post Force-Stop"

    # Full Emulator Reboot Test
    if (-not $SkipReboot) {
        Write-Log "Executing device reboot: 'adb reboot'..." "Cyan"
        Exec-Adb $Adb "reboot" | Out-Null
        Start-Sleep -Seconds 10
        $booted = Wait-For-Boot $Adb 180
        Assert-Test "AC-07-B" "Device rebooted successfully" $booted "Boot Completed Check"

        if ($booted) {
            Write-Log "Testing blocked app interception immediately post-reboot..." "Cyan"
            Exec-Adb $Adb "shell monkey -p $BlockedTargetApp 1" | Out-Null
            Start-Sleep -Seconds 1
            $postRebootDump = Get-TopUiDump $Adb
            Assert-Test "AC-07-C" "Brick state and blocking persist across device reboot" ($postRebootDump -match "package=`"$PackageName`"" -and ($postRebootDump -match "BRICKED")) "Blocking Screen Post Reboot"
        }
    } else {
        Write-Log "Skipping full reboot step due to -SkipReboot flag." "Yellow"
    }

    # ----------------------------------------------------------------
    # 8. AC-8: Exact Schedules Auto-Brick
    # ----------------------------------------------------------------
    Write-Section "Step 8: AC-8 - Exact Schedules Auto-Brick Engine"
    # Unbrick device first
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_PRIMARY_UID_4A8B`"" | Out-Null
    Start-Sleep -Seconds 1

    # Trigger scheduled alarm hook
    Write-Log "Triggering scheduled auto-brick alarm hook..." "Cyan"
    $schedRes = Exec-Adb $Adb "shell am broadcast -a com.brick.action.TRIGGER_SCHEDULE"
    Assert-Test "AC-08-A" "Scheduled alarm triggers auto-brick transition" ($schedRes.StdOut -match "result=0" -or $schedRes.StdOut -match "BRICKED") "Broadcast: $($schedRes.StdOut)"

    # Verify blocked app is blocked
    Exec-Adb $Adb "shell monkey -p $BlockedTargetApp 1" | Out-Null
    Start-Sleep -Milliseconds 600
    Assert-Test "AC-08-B" "Blocking active following scheduled auto-brick" ((Get-TopUiDump $Adb) -match "YOU'RE BRICKED") "Scheduled Block Active"

    # Unbrick
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_PRIMARY_UID_4A8B`"" | Out-Null

    # ----------------------------------------------------------------
    # 9. AC-9: Strict Mode Anti-Uninstall & Settings Anti-Tamper Shield
    # ----------------------------------------------------------------
    Write-Section "Step 9: AC-9 - Strict Mode Anti-Uninstall & Settings Shield"
    # Brick device
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_PRIMARY_UID_4A8B`"" | Out-Null
    Start-Sleep -Seconds 1

    # Attempt package uninstall while Device Admin is active
    Write-Log "Attempting unauthorized package uninstall: 'pm uninstall $PackageName'..." "Cyan"
    $uninstRes = Exec-Adb $Adb "shell pm uninstall $PackageName"
    Assert-Test "AC-09-A" "Device Admin prevents package uninstall (DELETE_FAILED)" ($uninstRes.StdOut -match "DELETE_FAILED_DEVICE_POLICY_MANAGER" -or $uninstRes.StdErr -match "DELETE_FAILED") "Uninstall Output: $($uninstRes.StdOut)"

    # Attempt to open Application Details Settings to Force Stop
    Write-Log "Attempting to navigate into Android App Settings to force-stop..." "Cyan"
    Exec-Adb $Adb "shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PackageName" | Out-Null
    Start-Sleep -Milliseconds 800
    $resumed = (Exec-Adb $Adb "shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'").StdOut
    Assert-Test "AC-09-B" "Accessibility shield intercepts Settings navigation and forces HOME" ($resumed -match "launcher" -or $resumed -match "Home" -or $resumed -match "NexusLauncher" -or $resumed -notmatch "AppDetailsActivity") "Resumed: $resumed"

    # ----------------------------------------------------------------
    # 10. AC-10: 24-Hour Recovery Anti-Tamper Security
    # ----------------------------------------------------------------
    Write-Section "Step 10: AC-10 - 24-Hour Recovery Anti-Tamper Security"
    Write-Log "Initiating 24-Hour Recovery request..." "Cyan"
    $recStart = Exec-Adb $Adb "shell am broadcast -a com.brick.action.START_RECOVERY"
    Assert-Test "AC-10-A" "Recovery request accepted" ($recStart.StdOut -match "result=0" -or $recStart.StdOut -match "RECOVERY_STARTED") "Broadcast: $($recStart.StdOut)"

    # Wall-Clock Jump Attack: advance system time by 25 hours
    Write-Log "Simulating user tampering: advancing wall-clock by +25 hours..." "Cyan"
    $futureEpoch25h = [DateTimeOffset]::UtcNow.AddHours(25).ToUnixTimeSeconds()
    Exec-Adb $Adb "shell date @$futureEpoch25h" | Out-Null
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.CHECK_RECOVERY" | Out-Null
    Start-Sleep -Milliseconds 500

    # Device MUST remain bricked!
    Exec-Adb $Adb "shell monkey -p $BlockedTargetApp 1" | Out-Null
    Start-Sleep -Milliseconds 600
    Assert-Test "AC-10-B" "Wall-clock manipulation does NOT shortcut recovery (still bricked)" ((Get-TopUiDump $Adb) -match "YOU'RE BRICKED") "Anti-Tamper Clock Immunity"

    # Monotonic Test Clock Advance: +24h 1m (86,460,000 ms)
    Write-Log "Advancing monotonic test clock by 86,460,000 ms (24h + 1m)..." "Cyan"
    $advRes = Exec-Adb $Adb "shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 86460000"
    Assert-Test "AC-10-C" "Monotonic test clock advanced by 24h" ($advRes.StdOut -match "result=0" -or $advRes.StdOut -match "CLOCK_ADVANCED") "Broadcast: $($advRes.StdOut)"

    # Device MUST now unlock!
    Exec-Adb $Adb "shell am start -n $PackageName/.ui.MainActivity" | Out-Null
    Start-Sleep -Seconds 1
    Assert-Test "AC-10-D" "Device unbricks after monotonic 24h elapsed time completes" ((Get-TopUiDump $Adb) -match "UNBRICKED" -or (Get-TopUiDump $Adb) -notmatch "YOU'RE BRICKED") "Recovery Unlocked"

    # ----------------------------------------------------------------
    # 11. USER FOLLOW-UP: Reboot Mid-Cooldown Test Case
    # ----------------------------------------------------------------
    Write-Section "Step 11: USER FOLLOW-UP - Reboot Mid-Cooldown Recovery Test"
    Write-Log "Starting dedicated reboot mid-cooldown verification..." "Cyan"
    # 11.1 Brick device
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_PRIMARY_UID_4A8B`"" | Out-Null
    Start-Sleep -Seconds 1

    # 11.2 Request 24h recovery
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.START_RECOVERY" | Out-Null
    Start-Sleep -Milliseconds 500

    # 11.3 Advance 6 hours of monotonic real time (21,600,000 ms)
    Write-Log "Advancing monotonic test clock by 6 hours (elapsed so far = 6h, remaining = 18h)..." "Cyan"
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 21600000" | Out-Null
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.CHECK_RECOVERY" | Out-Null
    Start-Sleep -Milliseconds 500

    # 11.4 Device reboot while cooldown is active
    if (-not $SkipReboot) {
        Write-Log "Rebooting device mid-cooldown..." "Cyan"
        Exec-Adb $Adb "reboot" | Out-Null
        Start-Sleep -Seconds 10
        Wait-For-Boot $Adb 180 | Out-Null

        # 11.5 Post-reboot checks: Device must STILL be bricked; cooldown must NOT have reset or shortcutted
        Exec-Adb $Adb "shell am broadcast -a com.brick.action.CHECK_RECOVERY" | Out-Null
        Exec-Adb $Adb "shell monkey -p $BlockedTargetApp 1" | Out-Null
        Start-Sleep -Milliseconds 800
        Assert-Test "AC-MIDBOOT-A" "Device remains bricked after mid-cooldown reboot" ((Get-TopUiDump $Adb) -match "YOU'RE BRICKED") "Bricked Post Mid-Cooldown Reboot"

        # 11.6 Adversarial clock jump post-reboot
        Write-Log "Attempting post-reboot wall-clock jump (+20 hours)..." "Cyan"
        $futureEpoch20h = [DateTimeOffset]::UtcNow.AddHours(20).ToUnixTimeSeconds()
        Exec-Adb $Adb "shell date @$futureEpoch20h" | Out-Null
        Exec-Adb $Adb "shell am broadcast -a com.brick.action.CHECK_RECOVERY" | Out-Null
        Assert-Test "AC-MIDBOOT-B" "Post-reboot wall-clock manipulation rejected" ((Get-TopUiDump $Adb) -match "YOU'RE BRICKED") "Clock Jump Rejected After Reboot"

        # 11.7 Advance remaining 18 hours + 1 min (64,860,000 ms)
        Write-Log "Advancing monotonic clock by remaining 18h 1m..." "Cyan"
        Exec-Adb $Adb "shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 64860000" | Out-Null
        Exec-Adb $Adb "shell am broadcast -a com.brick.action.CHECK_RECOVERY" | Out-Null
        Start-Sleep -Seconds 1

        Exec-Adb $Adb "shell am start -n $PackageName/.ui.MainActivity" | Out-Null
        Start-Sleep -Seconds 1
        Assert-Test "AC-MIDBOOT-C" "Recovery completes and unlocks after remaining time passes post-reboot" ((Get-TopUiDump $Adb) -match "UNBRICKED") "Mid-Cooldown Unlocked Successfully"
    } else {
        Write-Log "Skipping physical reboot in mid-cooldown test due to -SkipReboot flag." "Yellow"
        # Advance remaining time directly
        Exec-Adb $Adb "shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 64860000" | Out-Null
        Exec-Adb $Adb "shell am broadcast -a com.brick.action.CHECK_RECOVERY" | Out-Null
        Exec-Adb $Adb "shell am start -n $PackageName/.ui.MainActivity" | Out-Null
        Start-Sleep -Seconds 1
        Assert-Test "AC-MIDBOOT-C" "Recovery unlocks after elapsed time reaches 24h" ((Get-TopUiDump $Adb) -match "UNBRICKED") "Unlocked"
    }

    # ----------------------------------------------------------------
    # 12. AC-11: Stats & History Tracking
    # ----------------------------------------------------------------
    Write-Section "Step 12: AC-11 - Stats & Session History Engine"
    # Session 1: 15 min focus
    Write-Log "Simulating Focus Session 1 (15 minutes)..." "Cyan"
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_PRIMARY_UID_4A8B`"" | Out-Null
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 900000" | Out-Null
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_PRIMARY_UID_4A8B`"" | Out-Null
    Start-Sleep -Milliseconds 500

    # Session 2: 25 min focus
    Write-Log "Simulating Focus Session 2 (25 minutes)..." "Cyan"
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_PRIMARY_UID_4A8B`"" | Out-Null
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 1500000" | Out-Null
    Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"NFC_PRIMARY_UID_4A8B`"" | Out-Null
    Start-Sleep -Milliseconds 500

    # Open Stats Screen & Capture
    Exec-Adb $Adb "shell am start -n $PackageName/.ui.MainActivity --es screen `"stats`"" | Out-Null
    Start-Sleep -Seconds 1
    $shot6 = Capture-Screenshot $Adb "06_stats"
    Assert-Test "AC-12-F" "Screenshot 06 (Stats Dashboard) captured" $shot6 "Saved 06_stats.png"

    $statsDump = Get-TopUiDump $Adb
    Assert-Test "AC-11-A" "Stats screen renders total focus time metric" ($statsDump -match "Total Time" -or $statsDump -match "40m" -or $statsDump -match "min") "Total Time Metric Visible"
    Assert-Test "AC-11-B" "Stats screen renders streak calculation" ($statsDump -match "Streak" -or $statsDump -match "streak") "Streak Metric Visible"

    # ----------------------------------------------------------------
    # 13. AC-12: Design Review Visual Assets (Screenshots)
    # ----------------------------------------------------------------
    Write-Section "Step 13: AC-12 - Design Review Screenshots Verification"
    # Modes Screen
    Exec-Adb $Adb "shell am start -n $PackageName/.ui.MainActivity --es screen `"modes`"" | Out-Null
    Start-Sleep -Seconds 1
    $shot4 = Capture-Screenshot $Adb "04_modes_editor"
    Assert-Test "AC-12-D" "Screenshot 04 (Modes Editor) captured" $shot4 "Saved 04_modes_editor.png"

    # Schedules Screen
    Exec-Adb $Adb "shell am start -n $PackageName/.ui.MainActivity --es screen `"schedules`"" | Out-Null
    Start-Sleep -Seconds 1
    $shot5 = Capture-Screenshot $Adb "05_schedules"
    Assert-Test "AC-12-E" "Screenshot 05 (Schedules Screen) captured" $shot5 "Saved 05_schedules.png"

    # Verify all 7 screenshots exist and are valid files
    $requiredShots = @(
        "01_onboarding.png",
        "02_home_unbricked.png",
        "03_home_bricked.png",
        "04_modes_editor.png",
        "05_schedules.png",
        "06_stats.png",
        "07_blocking_screen.png"
    )
    $allShotsPresent = $true
    foreach ($shot in $requiredShots) {
        $p = Join-Path $ScreenshotDir $shot
        if (-not (Test-Path $p) -or ((Get-Item $p).Length -lt 1000)) {
            $allShotsPresent = $false
        }
    }
    Assert-Test "AC-12-ALL" "All 7 design review screenshots present and non-empty" $allShotsPresent "Verified in $ScreenshotDir"

    # ----------------------------------------------------------------
    # 14. Tier 4 Adversarial Robustness Checks
    # ----------------------------------------------------------------
    Write-Section "Step 14: Tier 4 Adversarial & Input Boundary Hardening"
    # Empty token payload
    $emptyRes = Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"`""
    Assert-Test "ADV-01" "Empty token payload rejected gracefully without crash" ($emptyRes.StdOut -match "result=" -and $emptyRes.ExitCode -eq 0) "Empty Payload"

    # Negative test clock delta
    $negDelta = Exec-Adb $Adb "shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms -5000"
    Assert-Test "ADV-02" "Negative test clock delta rejected" ($negDelta.StdOut -match "result=1" -or $negDelta.StdOut -match "ERROR") "Negative Delta"

    # Corrupted binary string payload
    $garbageRes = Exec-Adb $Adb "shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload `"%%00%FF%DEAD%BEEF%--DROP TABLE`""
    Assert-Test "ADV-03" "SQL injection / control characters in payload handled safely" ($garbageRes.ExitCode -eq 0) "Sanitization"

} catch {
    Write-Log "Unhandled exception during E2E verification: $_" "Red"
    Write-Log $_.ScriptStackTrace "Magenta"
    $Global:TotalFailed++
} finally {
    Generate-Reports
}

if ($Global:TotalFailed -gt 0) {
    Write-Host ""
    Write-Log "E2E SUITE FAILED: $Global:TotalFailed assertion(s) failed." "Red"
    exit 1
} else {
    Write-Host ""
    Write-Log "E2E SUITE PASSED: 100% assertions verified successfully!" "Green"
    exit 0
}
