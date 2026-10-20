#!/usr/bin/env bash
# ==============================================================================
# Automated End-to-End Acceptance Verification Harness for Brick Android App.
# Verifies all acceptance criteria in ORIGINAL_REQUEST.md: AC-1 through AC-12,
# including the mid-cooldown reboot recovery test case and 7 design screenshots.
# ==============================================================================

set -o pipefail

PACKAGE_NAME="com.brick.app.e2e"
CLASS_NAMESPACE="com.brick.app"
BLOCKED_TARGET_APP="com.android.calculator2"
ALLOWED_TARGET_APP="com.android.settings"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/build/reports/e2e"
SCREENSHOT_DIR="${OUTPUT_DIR}/screenshots"
ADB_BIN=""
SKIP_BUILD=0
SKIP_REBOOT=0
STOP_ON_FAILURE=0

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --adb)
      ADB_BIN="$2"
      shift 2
      ;;
    --package)
      if [[ "$2" != "com.brick.app.e2e" ]]; then echo "Only isolated E2E package is supported" >&2; exit 2; fi
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --blocked-app)
      BLOCKED_TARGET_APP="$2"
      shift 2
      ;;
    --allowed-app)
      ALLOWED_TARGET_APP="$2"
      shift 2
      ;;
    --output)
      OUTPUT_DIR="$2"
      SCREENSHOT_DIR="${OUTPUT_DIR}/screenshots"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --skip-reboot)
      SKIP_REBOOT=1
      shift
      ;;
    --stop-on-failure)
      STOP_ON_FAILURE=1
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [options]"
      echo "  --adb <path>            Path to adb executable"
      echo "  --package <name>        App package name (fixed: com.brick.app.e2e)"
      echo "  --blocked-app <name>    Blocked target app (default: com.android.calculator2)"
      echo "  --allowed-app <name>    Allowed target app (default: com.android.settings)"
      echo "  --output <dir>          Output directory (default: build/reports/e2e)"
      echo "  --skip-build            Skip Gradle assemble and unit tests"
      echo "  --skip-reboot           Skip emulator reboot tests"
      echo "  --stop-on-failure       Stop on first assertion failure"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

mkdir -p "${SCREENSHOT_DIR}"
LOG_FILE="${OUTPUT_DIR}/verification_log.txt"
REPORT_MD="${OUTPUT_DIR}/verification_report.md"
REPORT_JSON="${OUTPUT_DIR}/verification_report.json"

echo "=== Brick Native Android App E2E Verification Suite ===" > "${LOG_FILE}"
echo "Started at: $(date -u +"%Y-%m-%dT%H:%M:%SZ")" >> "${LOG_FILE}"

TOTAL_PASSED=0
TOTAL_FAILED=0
START_TIME=$(date +%s)

declare -a TEST_IDS=()
declare -a TEST_NAMES=()
declare -a TEST_RESULTS=()
declare -a TEST_DETAILS=()

log_msg() {
  local msg="$1"
  local color="${2:-0}"
  local timestamp
  timestamp=$(date +"%H:%M:%S")
  if [[ -t 1 ]]; then
    printf "\033[0;%sm[%s] %s\033[0m\n" "${color}" "${timestamp}" "${msg}"
  else
    printf "[%s] %s\n" "${timestamp}" "${msg}"
  fi
  printf "[%s] %s\n" "${timestamp}" "${msg}" >> "${LOG_FILE}"
}

log_section() {
  echo ""
  log_msg "======================================================================" "36"
  log_msg "  $1" "36"
  log_msg "======================================================================" "36"
}

assert_test() {
  local test_id="$1"
  local test_name="$2"
  local passed="$3"
  local details="$4"

  TEST_IDS+=("${test_id}")
  TEST_NAMES+=("${test_name}")
  TEST_RESULTS+=("${passed}")
  TEST_DETAILS+=("${details}")

  if [[ "${passed}" -eq 1 ]]; then
    ((TOTAL_PASSED++))
    log_msg "  [PASS] [${test_id}] ${test_name}" "32"
    [[ -n "${details}" ]] && log_msg "         Details: ${details}" "90"
  else
    ((TOTAL_FAILED++))
    log_msg "  [FAIL] [${test_id}] ${test_name}" "31"
    [[ -n "${details}" ]] && log_msg "         Failure Details: ${details}" "35"
    if [[ "${STOP_ON_FAILURE}" -eq 1 ]]; then
      log_msg "Halting execution due to --stop-on-failure flag." "31"
      generate_reports
      exit 1
    fi
  fi
}

resolve_adb() {
  if [[ -n "${ADB_BIN}" && -x "${ADB_BIN}" ]]; then
    echo "${ADB_BIN}"
    return
  fi
  if command -v adb &>/dev/null; then
    command -v adb
    return
  fi
  if [[ -n "${LOCALAPPDATA}" && -x "${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe" ]]; then
    echo "${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
    return
  fi
  if [[ -n "${ANDROID_HOME}" && -x "${ANDROID_HOME}/platform-tools/adb" ]]; then
    echo "${ANDROID_HOME}/platform-tools/adb"
    return
  fi
  if [[ -x "${HOME}/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]]; then
    echo "${HOME}/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    return
  fi
  echo "adb"
}

ADB=$(resolve_adb)

exec_adb() {
  "${ADB}" "$@" 2>&1
}

get_top_ui_dump() {
  local dump_result xml attempt
  for attempt in 1 2 3; do
    "${ADB}" shell rm -f /sdcard/window_dump.xml >/dev/null 2>&1 || return 1
    if dump_result=$("${ADB}" shell uiautomator dump /sdcard/window_dump.xml 2>&1) &&
       [[ "${dump_result}" == *"dumped to /sdcard/window_dump.xml"* ||
          "${dump_result}" == *"dumped to: /sdcard/window_dump.xml"* ]]; then
      xml=$("${ADB}" shell cat /sdcard/window_dump.xml 2>/dev/null) || return 1
      if [[ "${xml}" == *"<hierarchy"* ]]; then
        printf '%s\n' "${xml}"
        return 0
      fi
    fi
  done
  return 1
}

tap_ui_text() {
  local target="$1"
  local bounds
  bounds=$(get_top_ui_dump | grep -oE '<node[^>]*>' | grep -F "text=\"${target}\"" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' | sed -n '1p')
  [[ -n "${bounds}" ]] || return 1
  local left top right bottom
  read -r left top right bottom <<< "${bounds}"
  exec_adb shell input tap "$(((left + right) / 2))" "$(((top + bottom) / 2))" >/dev/null
}

capture_screenshot() {
  local name="$1"
  local path="${SCREENSHOT_DIR}/${name}.png"
  local shot_ok=0
  "${ADB}" exec-out screencap -p > "${path}" && shot_ok=1
  if [[ ${shot_ok} -eq 1 && -s "${path}" ]] && [[ "$(od -An -tx1 -N8 "${path}" | tr -d '[:space:]')" == "89504e470d0a1a0a" ]]; then
    log_msg "Saved screenshot: ${path}" "33"
    return 0
  else
    log_msg "Failed to capture screenshot: ${path}" "31"
    return 1
  fi
}

wait_for_boot() {
  local timeout="${1:-180}"
  log_msg "Waiting up to ${timeout}s for device boot completion..." "36"
  local elapsed=0
  while [[ ${elapsed} -lt ${timeout} ]]; do
    local state
    state=$("${ADB}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')
    if [[ "${state}" == "1" ]]; then
      log_msg "Device boot completed in ${elapsed}s." "32"
      sleep 5
      return 0
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
  return 1
}

generate_reports() {
  log_section "Test Suite Summary & Report Generation"
  local end_time
  end_time=$(date +%s)
  local duration=$((end_time - START_TIME))
  local total=$((TOTAL_PASSED + TOTAL_FAILED))
  local pass_rate=0
  [[ ${total} -gt 0 ]] && pass_rate=$(( (TOTAL_PASSED * 100) / total ))

  log_msg "Total Assertions: ${total}" "36"
  log_msg "Passed:           ${TOTAL_PASSED}" "32"
  log_msg "Failed:           ${TOTAL_FAILED}" "$([[ ${TOTAL_FAILED} -gt 0 ]] && echo "31" || echo "32")"
  log_msg "Pass Rate:        ${pass_rate}%" "36"
  log_msg "Total Duration:   ${duration}s" "36"

  # Write Markdown report
  {
    echo "# Brick Native Android App — E2E Verification Report"
    echo ""
    echo "- **Date**: $(date -u +"%Y-%m-%d %H:%M:%SZ")"
    echo "- **Total Assertions**: ${total}"
    echo "- **Passed**: ${TOTAL_PASSED}"
    echo "- **Failed**: ${TOTAL_FAILED}"
    echo "- **Pass Rate**: ${pass_rate}%"
    echo "- **Duration**: ${duration}s"
    echo ""
    echo "## Acceptance Criteria Results"
    echo ""
    echo "| ID | Test Name | Status | Details |"
    echo "|---|---|---|---|"
    for i in "${!TEST_IDS[@]}"; do
      local badge="❌ FAIL"
      [[ "${TEST_RESULTS[$i]}" -eq 1 ]] && badge="✅ PASS"
      local clean_details
      clean_details=$(echo "${TEST_DETAILS[$i]}" | tr '|' '/' | tr '\n' ' ')
      echo "| ${TEST_IDS[$i]} | ${TEST_NAMES[$i]} | ${badge} | ${clean_details} |"
    done
    echo ""
    echo "## Design Review Screenshots"
    echo ""
    for s in "${SCREENSHOT_DIR}"/*.png; do
      if [[ -f "${s}" ]]; then
        local bname
        bname=$(basename "${s}")
        local size
        size=$(stat -c%s "${s}" 2>/dev/null || stat -f%z "${s}" 2>/dev/null || echo "0")
        echo "- **${bname}** ($((size / 1024)) KB): \`screenshots/${bname}\`"
      fi
    done
  } > "${REPORT_MD}"
  log_msg "Wrote Markdown report to: ${REPORT_MD}" "32"

  # Write JSON report
  {
    echo "{"
    echo "  \"summary\": {"
    echo "    \"totalTests\": ${total},"
    echo "    \"passed\": ${TOTAL_PASSED},"
    echo "    \"failed\": ${TOTAL_FAILED},"
    echo "    \"passRate\": \"${pass_rate}%\","
    echo "    \"duration\": \"${duration}s\","
    echo "    \"timestamp\": \"$(date -u +"%Y-%m-%dT%H:%M:%SZ")\""
    echo "  },"
    echo "  \"results\": ["
    for i in "${!TEST_IDS[@]}"; do
      local passed_str="false"
      [[ "${TEST_RESULTS[$i]}" -eq 1 ]] && passed_str="true"
      local comma=","
      [[ $i -eq $((${#TEST_IDS[@]} - 1)) ]] && comma=""
      echo "    {"
      echo "      \"id\": \"${TEST_IDS[$i]}\","
      echo "      \"name\": \"${TEST_NAMES[$i]}\","
      echo "      \"passed\": ${passed_str},"
      echo "      \"details\": \"${TEST_DETAILS[$i]}\""
      echo "    }${comma}"
    done
    echo "  ]"
    echo "}"
  } > "${REPORT_JSON}"
  log_msg "Wrote JSON report to: ${REPORT_JSON}" "90"
}

# ==============================================================================
# MAIN TEST SUITE
# ==============================================================================

log_msg "Using ADB: ${ADB}" "36"

# Step 0: Check Device Connectivity
log_section "Step 0: ADB & Device Connectivity Verification"
DEVICES_OUT=$(exec_adb devices)
if echo "${DEVICES_OUT}" | grep -qE '^[[:alnum:]_.:-]+[[:space:]]+device[[:space:]]*$'; then
  assert_test "ENV-01" "Connected Android device or emulator available" 1 "Device ready"
else
  assert_test "ENV-01" "Connected Android device or emulator available" 0 "No device found"
  generate_reports
  exit 1
fi

DEVICE_COUNT=$(echo "${DEVICES_OUT}" | grep -cE '^[[:alnum:]_.:-]+[[:space:]]+device[[:space:]]*$')
if [[ "${DEVICE_COUNT}" -ne 1 ]]; then
  log_msg "Refusing to target ${DEVICE_COUNT} devices; connect exactly one emulator" "31"
  exit 2
fi
if [[ "$(exec_adb shell getprop ro.kernel.qemu | tr -d '\r\n')" != "1" ]]; then
  log_msg "Refusing test-only APK installation on a real phone; connect an emulator" "31"
  exit 2
fi

DEV_SDK=$(exec_adb shell getprop ro.build.version.sdk | tr -d '\r\n')
log_msg "Target Device API Level: ${DEV_SDK}" "36"
if [[ "${DEV_SDK}" =~ ^[0-9]+$ ]] && [[ "${DEV_SDK}" -ge 26 ]]; then
  assert_test "ENV-02" "Target device is API 26 or higher" 1 "API Level ${DEV_SDK}"
else
  assert_test "ENV-02" "Target device is API 26 or higher" 0 "API Level ${DEV_SDK}"
fi

# Step 1: AC-1 Build
log_section "Step 1: AC-1 — Debug, Release and Isolated E2E APK Compilation"
if [[ ${SKIP_BUILD} -eq 0 ]]; then
  if [[ -f "${SCRIPT_DIR}/gradlew" ]]; then
    "${SCRIPT_DIR}/gradlew" :app:assembleDebug :app:assembleRelease :app:assembleE2e >/dev/null 2>&1
    BUILD_EXIT=$?
    assert_test "AC-01-A" "gradlew assemble exits with code 0" "$([[ ${BUILD_EXIT} -eq 0 ]] && echo 1 || echo 0)" "Exit: ${BUILD_EXIT}"
  fi
  DEBUG_APK="${SCRIPT_DIR}/app/build/outputs/apk/e2e/app-e2e.apk"
  if [[ -s "${DEBUG_APK}" ]]; then
    assert_test "AC-01-E2E" "E2E APK produced and non-empty" 1 "Path: ${DEBUG_APK}"
  else
    assert_test "AC-01-E2E" "E2E APK produced and non-empty" 0 "Missing E2E APK"
  fi
  RELEASE_APK="${SCRIPT_DIR}/app/build/outputs/apk/release/app-release.apk"
  if [[ -s "${RELEASE_APK}" ]]; then
    assert_test "AC-01-C" "Release APK produced" 1 "Release APK found"
  else
    assert_test "AC-01-C" "Release APK produced" 0 "Missing release APK"
  fi
fi

# Step 2: AC-2 Unit Tests
log_section "Step 2: AC-2 — Unit Test Suite Execution"
if [[ ${SKIP_BUILD} -eq 0 ]]; then
  if [[ -f "${SCRIPT_DIR}/gradlew" ]]; then
    "${SCRIPT_DIR}/gradlew" :app:testDebugUnitTest >/dev/null 2>&1
    TEST_EXIT=$?
    assert_test "AC-02" "Debug JVM unit tests pass" "$([[ ${TEST_EXIT} -eq 0 ]] && echo 1 || echo 0)" "Exit: ${TEST_EXIT}"
  fi
fi

# Step 3: AC-3 Install & Permissions & Onboarding
log_section "Step 3: AC-3 — APK Installation & Permission Provisioning"
NORMAL_APK="${SCRIPT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
if [[ -s "${NORMAL_APK}" ]]; then
  NORMAL_INSTALL=$(exec_adb install -r -d "${NORMAL_APK}")
  NORMAL_RECEIVERS=$(exec_adb shell dumpsys package com.brick.app)
  if [[ "${NORMAL_INSTALL}" == *Success* ]] && ! echo "${NORMAL_RECEIVERS}" | grep -qE 'SimulateTokenScanReceiver|com.brick.action.RESET_STATE|com.brick.action.ADVANCE_TEST_CLOCK'; then
    assert_test "AC-HOOK-ISOLATION" "Ordinary debug APK registers no reset/clock receiver" 1 "Normal debug APK installed"
  else
    assert_test "AC-HOOK-ISOLATION" "Ordinary debug APK registers no reset/clock receiver" 0 "Install or manifest check failed"
  fi
else
  assert_test "AC-HOOK-ISOLATION" "Ordinary debug APK registers no reset/clock receiver" 0 "Debug APK not built"
fi
DEBUG_APK="${SCRIPT_DIR}/app/build/outputs/apk/e2e/app-e2e.apk"
if [[ ! -s "${DEBUG_APK}" ]]; then echo "E2E APK is missing; build :app:assembleE2e, never substitute normal debug" >&2; exit 2; fi
if [[ -f "${DEBUG_APK}" ]]; then
  INSTALL_OUT=$(exec_adb install -r -t -d "${DEBUG_APK}")
  if echo "${INSTALL_OUT}" | grep -q "Success"; then
    assert_test "AC-03-A" "E2E APK installed successfully" 1 "${INSTALL_OUT}"
  else
    assert_test "AC-03-A" "E2E APK installed successfully" 0 "${INSTALL_OUT}"
  fi
fi

if [[ ${SKIP_BUILD} -eq 0 && -f "${SCRIPT_DIR}/gradlew" ]]; then
  "${SCRIPT_DIR}/gradlew" :app:connectedDebugAndroidTest > "${OUTPUT_DIR}/instrumented_tests.log" 2>&1
  UI_EXIT=$?
  assert_test "AC-02-UI" "Instrumented Compose UI tests pass on emulator" "$([[ ${UI_EXIT} -eq 0 ]] && echo 1 || echo 0)" "Exit: ${UI_EXIT}; log: instrumented_tests.log"
fi

# Launch once so the fixture receiver is reachable after a fresh (stopped) install.
exec_adb shell am start -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.MainActivity" >/dev/null
# Reset state
RESET_OUT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.RESET_STATE)
[[ "${RESET_OUT}" == *'result=0, data="STATE_RESET"'* ]] && RESET_OK=1 || RESET_OK=0
assert_test "ENV-RESET" "Debug fixture reset" "${RESET_OK}" "${RESET_OUT}"
sleep 1

# Restart any preserved activity after clearing setup prefs.
exec_adb shell am force-stop "${PACKAGE_NAME}" >/dev/null

# Headless permission grants
exec_adb shell settings put secure enabled_accessibility_services "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.BrickAccessibilityService" >/dev/null
exec_adb shell settings put secure accessibility_enabled 1 >/dev/null
exec_adb shell appops set "${PACKAGE_NAME}" android:get_usage_stats allow >/dev/null
exec_adb shell appops set "${PACKAGE_NAME}" SYSTEM_ALERT_WINDOW allow >/dev/null
exec_adb shell pm grant "${PACKAGE_NAME}" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
exec_adb shell pm grant "${PACKAGE_NAME}" android.permission.CAMERA >/dev/null 2>&1 || true
exec_adb shell appops set "${PACKAGE_NAME}" SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1 || true
exec_adb shell dumpsys deviceidle whitelist +"${PACKAGE_NAME}" >/dev/null 2>&1 || true
DPM_OUT=$(exec_adb shell dpm set-device-admin "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.BrickDeviceAdminReceiver")

ACC_ENABLED=$(exec_adb shell settings get secure enabled_accessibility_services)
if echo "${ACC_ENABLED}" | grep -q "BrickAccessibilityService"; then
  assert_test "AC-03-B" "Accessibility service enabled in secure settings" 1 "${ACC_ENABLED}"
else
  assert_test "AC-03-B" "Accessibility service enabled in secure settings" 0 "${ACC_ENABLED}"
fi

if echo "${DPM_OUT}" | grep -qiE "Success|already"; then
  assert_test "AC-03-C" "Device Admin active for Strict Mode anti-uninstall" 1 "${DPM_OUT}"
else
  assert_test "AC-03-C" "Device Admin active for Strict Mode anti-uninstall" 0 "${DPM_OUT}"
fi

# Launch app and capture onboarding
exec_adb shell am start -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.MainActivity" >/dev/null
sleep 2
ONBOARD_DUMP=$(get_top_ui_dump)
if [[ "${ONBOARD_DUMP}" == *"WELCOME TO BRICK"* ]]; then ONBOARD_VISIBLE=1; else ONBOARD_VISIBLE=0; fi
assert_test "AC-03-ONBOARDING" "First-run onboarding displayed" "${ONBOARD_VISIBLE}" "First launch UI"
capture_screenshot "01_onboarding" && SHOT1=1 || SHOT1=0
assert_test "AC-12-A" "Screenshot 01 (Onboarding) captured" "${SHOT1}" "01_onboarding.png"

# Pair tokens
PAIR1=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" --ez is_pairing true --es token_label "Primary Card")
[[ "${PAIR1}" == *'result=0, data="PAIRED:'* ]] && P1_OK=1 || P1_OK=0
assert_test "AC-03-D" "Primary NFC token paired successfully" "${P1_OK}" "${PAIR1}"

PAIR2=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_SPARE_UID_7C2D" --ez is_pairing true --es token_label "Spare Keychain")
[[ "${PAIR2}" == *'result=0, data="PAIRED:'* ]] && P2_OK=1 || P2_OK=0
assert_test "AC-03-E" "Spare NFC token paired successfully" "${P2_OK}" "${PAIR2}"

PAIR_QR=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type QR --es token_payload "BRICK-QR-V1:PRINTED_DESK_TOKEN_XYZ" --ez is_pairing true --es token_label "Desk QR Card")
[[ "${PAIR_QR}" == *'result=0, data="PAIRED:'* ]] && PQR_OK=1 || PQR_OK=0
assert_test "AC-03-F" "Printed QR token paired successfully" "${PQR_OK}" "${PAIR_QR}"

# Pairing does not dismiss first-run onboarding: use the visible button.
if tap_ui_text "CONTINUE TO BRICK"; then CONTINUED=1; else CONTINUED=0; fi
sleep 1
HOME_READY=$(get_top_ui_dump)
if [[ ${CONTINUED} -eq 1 && "${HOME_READY}" == *'text="UNBRICKED"'* ]]; then READY=1; else READY=0; fi
assert_test "AC-03-CONTINUE" "First-run onboarding finishes after pairing and accessibility setup" "${READY}" "Continue tapped: ${CONTINUED}"
exec_adb shell am start -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.MainActivity" >/dev/null
sleep 1
capture_screenshot "02_home_unbricked" && SHOT2=1 || SHOT2=0
assert_test "AC-12-B" "Screenshot 02 (Home Unbricked) captured" "${SHOT2}" "02_home_unbricked.png"

HOME_DUMP=$(get_top_ui_dump)
if echo "${HOME_DUMP}" | grep -Fq 'text="UNBRICKED"'; then
  assert_test "AC-03-G" "Home screen displays UNBRICKED state" 1 "UI contains UNBRICKED"
else
  assert_test "AC-03-G" "Home screen displays UNBRICKED state" 0 "UI missing UNBRICKED"
fi

# Step 4: AC-4 Block-List Mode
log_section "Step 4: AC-4 — Block-List Mode Interception"
exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SET_MODE --es mode_type BLOCK_LIST --es packages "${BLOCKED_TARGET_APP}" >/dev/null

# Brick device
BRICK_RES=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B")
[[ "${BRICK_RES}" == *'result=0, data="BRICKED:'* ]] && B_OK=1 || B_OK=0
assert_test "AC-04-A" "Primary token scan transitioned state to BRICKED" "${B_OK}" "${BRICK_RES}"

capture_screenshot "03_home_bricked" && SHOT3=1 || SHOT3=0
assert_test "AC-12-C" "Screenshot 03 (Home Bricked) captured" "${SHOT3}" "03_home_bricked.png"

BRICK_DUMP=$(get_top_ui_dump)
if echo "${BRICK_DUMP}" | grep -Fq 'text="BRICKED"'; then
  assert_test "AC-04-B" "Home screen UI displays BRICKED dominant state" 1 "BRICKED visible"
else
  assert_test "AC-04-B" "Home screen UI displays BRICKED dominant state" 0 "BRICKED missing"
fi

# Launch Blocked App
log_msg "Launching blocked app (${BLOCKED_TARGET_APP})..." "36"
exec_adb shell monkey -p "${BLOCKED_TARGET_APP}" 1 >/dev/null
sleep 1

capture_screenshot "07_blocking_screen" && SHOT7=1 || SHOT7=0
assert_test "AC-12-G" "Screenshot 07 (Blocking Screen) captured" "${SHOT7}" "07_blocking_screen.png"

BLOCK_DUMP=$(get_top_ui_dump)
if echo "${BLOCK_DUMP}" | grep -Fq "YOU'RE BRICKED" && echo "${BLOCK_DUMP}" | grep -Fq "package=\"${PACKAGE_NAME}\""; then
  assert_test "AC-04-C" "Blocking overlay covers screen" 1 "Package: ${PACKAGE_NAME}"
else
  assert_test "AC-04-C" "Blocking overlay covers screen" 0 "Overlay missing"
fi

if echo "${BLOCK_DUMP}" | grep -Fq "YOU'RE BRICKED"; then
  assert_test "AC-04-D" "Blocking overlay contains 'YOU''RE BRICKED' message" 1 "Blocking text present"
else
  assert_test "AC-04-D" "Blocking overlay contains 'YOU''RE BRICKED' message" 0 "Blocking text absent"
fi

# Launch Allowed App
log_msg "Launching allowed app (${ALLOWED_TARGET_APP})..." "36"
exec_adb shell monkey -p "${ALLOWED_TARGET_APP}" 1 >/dev/null
sleep 1
ALLOWED_DUMP=$(get_top_ui_dump)
if echo "${ALLOWED_DUMP}" | grep -Fq "package=\"${ALLOWED_TARGET_APP}\"" && ! echo "${ALLOWED_DUMP}" | grep -Fq "YOU'RE BRICKED"; then
  assert_test "AC-04-F" "Non-blocked app opens normally without overlay" 1 "Allowed package opened"
else
  assert_test "AC-04-F" "Non-blocked app opens normally without overlay" 0 "Overlay unexpectedly displayed"
fi

# Step 5: AC-5 Allow-List Mode
log_section "Step 5: AC-5 — Allow-List Mode Interception"
MODE_ALLOW_OUT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SET_MODE --es mode_type ALLOW_LIST --es packages "${BLOCKED_TARGET_APP}")
[[ "${MODE_ALLOW_OUT}" == *'result=0, data="MODE_SET:'* ]] && MODE_ALLOW_OK=1 || MODE_ALLOW_OK=0
assert_test "AC-05-MODE" "Allow-list mode configured" "${MODE_ALLOW_OK}" "${MODE_ALLOW_OUT}"
# The active session pins its original mode, so restart it under the newly selected mode.
TO_UNBRICK=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B")
TO_REBRICK=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B")
if [[ "${TO_UNBRICK}" == *'result=0, data="UNBRICKED:'* && "${TO_REBRICK}" == *'result=0, data="BRICKED:'* ]]; then MODE_SESSION_OK=1; else MODE_SESSION_OK=0; fi
assert_test "AC-05-SESSION" "Allow-list session active" "${MODE_SESSION_OK}" "${TO_UNBRICK}; ${TO_REBRICK}"
sleep 1

exec_adb shell monkey -p "${BLOCKED_TARGET_APP}" 1 >/dev/null
sleep 1
AL_DUMP=$(get_top_ui_dump)
if echo "${AL_DUMP}" | grep -Fq "package=\"${BLOCKED_TARGET_APP}\"" && ! echo "${AL_DUMP}" | grep -Fq "YOU'RE BRICKED"; then
  assert_test "AC-05-A" "Whitelisted app opens without blocking in Allow-List mode" 1 "Whitelisted app allowed"
else
  assert_test "AC-05-A" "Whitelisted app opens without blocking in Allow-List mode" 0 "Blocked unexpectedly"
fi

exec_adb shell monkey -p "com.android.calendar" 1 >/dev/null
sleep 1
DIS_DUMP=$(get_top_ui_dump)
if echo "${DIS_DUMP}" | grep -Fq "YOU'RE BRICKED" && echo "${DIS_DUMP}" | grep -Fq "package=\"${PACKAGE_NAME}\""; then
  assert_test "AC-05-B" "Non-whitelisted app is intercepted by blocking screen" 1 "Blocked non-whitelisted"
else
  assert_test "AC-05-B" "Non-whitelisted app is intercepted by blocking screen" 0 "Non-whitelisted app unblocked"
fi

# Step 6: AC-6 Unpaired Rejection & Multi-Token Toggling
log_section "Step 6: AC-6 — Unpaired Tag Rejection & Multi-Token Toggling"
exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SET_MODE --es mode_type BLOCK_LIST --es packages "${BLOCKED_TARGET_APP}" >/dev/null

UNPAIRED_OUT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "ROGUE_UNPAIRED_TAG_8888")
if echo "${UNPAIRED_OUT}" | grep -Fq 'result=1, data="REJECTED: UNPAIRED'; then
  assert_test "AC-06-A" "Unpaired NFC tag rejected" 1 "${UNPAIRED_OUT}"
else
  assert_test "AC-06-A" "Unpaired NFC tag rejected" 0 "${UNPAIRED_OUT}"
fi

SPARE_OUT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_SPARE_UID_7C2D")
if echo "${SPARE_OUT}" | grep -Fq 'result=0, data="UNBRICKED:'; then
  assert_test "AC-06-C" "Paired spare NFC tag unbricks device" 1 "${SPARE_OUT}"
else
  assert_test "AC-06-C" "Paired spare NFC tag unbricks device" 0 "${SPARE_OUT}"
fi

QR_BRICK=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type QR --es token_payload "BRICK-QR-V1:PRINTED_DESK_TOKEN_XYZ")
[[ "${QR_BRICK}" == *'result=0, data="BRICKED:'* ]] && QRB_OK=1 || QRB_OK=0
assert_test "AC-06-D" "Paired QR code card bricks device" "${QRB_OK}" "${QR_BRICK}"

QR_UNBRICK=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type QR --es token_payload "BRICK-QR-V1:PRINTED_DESK_TOKEN_XYZ")
[[ "${QR_UNBRICK}" == *'result=0, data="UNBRICKED:'* ]] && QRU_OK=1 || QRU_OK=0
assert_test "AC-06-E" "Paired QR code card toggles to unbricked" "${QRU_OK}" "${QR_UNBRICK}"

# Step 7: AC-7 Force-Stop & Reboot Persistence
log_section "Step 7: AC-7 — State Persistence across Force-Stop & Reboot"
exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
sleep 1

exec_adb shell am force-stop "${PACKAGE_NAME}"
sleep 1
exec_adb shell monkey -p "${BLOCKED_TARGET_APP}" 1 >/dev/null
sleep 1
FS_DUMP=$(get_top_ui_dump)
if echo "${FS_DUMP}" | grep -Fq "YOU'RE BRICKED" && echo "${FS_DUMP}" | grep -Fq "package=\"${PACKAGE_NAME}\""; then
  assert_test "AC-07-A" "Blocking persists after force-stopping application process" 1 "Overlay re-asserted"
else
  assert_test "AC-07-A" "Blocking persists after force-stopping application process" 0 "Overlay lost"
fi

if [[ ${SKIP_REBOOT} -eq 0 ]]; then
  log_msg "Rebooting emulator: adb reboot..." "36"
  exec_adb reboot
  sleep 10
  if wait_for_boot 180; then
    assert_test "AC-07-B" "Device rebooted successfully" 1 "Boot complete"
    exec_adb shell monkey -p "${BLOCKED_TARGET_APP}" 1 >/dev/null
    sleep 1
    POST_BOOT_DUMP=$(get_top_ui_dump)
    if echo "${POST_BOOT_DUMP}" | grep -Fq "YOU'RE BRICKED" && echo "${POST_BOOT_DUMP}" | grep -Fq "package=\"${PACKAGE_NAME}\""; then
      assert_test "AC-07-C" "Brick state and blocking persist across device reboot" 1 "Blocking active post-reboot"
    else
      assert_test "AC-07-C" "Brick state and blocking persist across device reboot" 0 "Blocking lost post-reboot"
    fi
  else
    assert_test "AC-07-B" "Device rebooted successfully" 0 "Boot timed out"
  fi
fi

# Step 8: AC-8 Schedules Auto-Brick
log_section "Step 8: AC-8 — Exact Schedules Auto-Brick Engine"
BEFORE_SCHED=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.GET_STATUS)
if [[ "${BEFORE_SCHED}" == *'result=0, data="isBricked=true,'* ]]; then
  exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
fi
sleep 1

SCHED_OUT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.TRIGGER_SCHEDULE)
if [[ "${SCHED_OUT}" == *'result=0, data="SCHEDULE_SET: id='* ]]; then SCHED_OK=1; else SCHED_OK=0; fi
assert_test "AC-08-SET" "Next-minute alarm scheduled" "${SCHED_OK}" "${SCHED_OUT}"
PRE_SCHED=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.GET_STATUS)
if [[ "${PRE_SCHED}" == *'result=0, data="isBricked=false,'* ]]; then BASE_OK=1; else BASE_OK=0; fi
assert_test "AC-08-BASE" "Session unbricked before alarm" "${BASE_OK}" "${PRE_SCHED}"
SCHED_BRICKED=0
SCHED_STATUS="${PRE_SCHED}"
for ((attempt=0; attempt<45; attempt++)); do
  sleep 2
  SCHED_STATUS=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.GET_STATUS)
  if [[ "${SCHED_STATUS}" == *'result=0, data="isBricked=true,'* ]]; then
    SCHED_BRICKED=1
    break
  fi
done
assert_test "AC-08-A" "Scheduled alarm auto-bricks within 90 seconds" "${SCHED_BRICKED}" "${SCHED_STATUS}"
if [[ ${SCHED_BRICKED} -eq 1 ]]; then
  exec_adb shell monkey -p "${BLOCKED_TARGET_APP}" 1 >/dev/null
  sleep 1
fi
SCHED_DUMP=$(get_top_ui_dump)
if [[ ${SCHED_BRICKED} -eq 1 ]] && echo "${SCHED_DUMP}" | grep -Fq "YOU'RE BRICKED" && echo "${SCHED_DUMP}" | grep -Fq "package=\"${PACKAGE_NAME}\""; then SCHED_BLOCK_OK=1; else SCHED_BLOCK_OK=0; fi
assert_test "AC-08-B" "Blocking active following scheduled auto-brick" "${SCHED_BLOCK_OK}" "Blocking overlay after alarm"

exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null

# Step 9: AC-9 Strict Mode Anti-Uninstall & Settings Shield
log_section "Step 9: AC-9 — Strict Mode Anti-Uninstall & Settings Shield"
STRICT_OUT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.BRICK_STRICT)
STRICT_STATUS=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.GET_STATUS)
if [[ "${STRICT_OUT}" == *'result=0, data="STRICT_BRICKED"'* && "${STRICT_STATUS}" == *'isBricked=true, strictMode=true,'* ]]; then STRICT_OK=1; else STRICT_OK=0; fi
assert_test "AC-09-STRICT" "Strict session active before Settings-shield test" "${STRICT_OK}" "${STRICT_OUT}; ${STRICT_STATUS}"
sleep 1

UNINSTALL_OUT=$(exec_adb shell pm uninstall "${PACKAGE_NAME}")
if echo "${UNINSTALL_OUT}" | grep -qi "DELETE_FAILED"; then
  assert_test "AC-09-A" "Device Admin prevents package uninstall (DELETE_FAILED)" 1 "${UNINSTALL_OUT}"
else
  assert_test "AC-09-A" "Device Admin prevents package uninstall (DELETE_FAILED)" 0 "${UNINSTALL_OUT}"
fi

exec_adb logcat -c >/dev/null
SETTINGS_LAUNCH=$(exec_adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:${PACKAGE_NAME}")
sleep 1
RESUMED=$(exec_adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity")
SHIELD_LOG=$(exec_adb logcat -d -s BrickAccessibility:W)
if [[ ${STRICT_OK} -eq 1 && "${SETTINGS_LAUNCH}" != *Error* && -n "${RESUMED}" ]] && echo "${SHIELD_LOG}" | grep -Fq "intercepting Settings navigation" && echo "${RESUMED}" | grep -qiE "launcher|homeactivity" && ! echo "${RESUMED}" | grep -qi "com.android.settings"; then
  assert_test "AC-09-B" "Accessibility shield intercepts Settings navigation and forces HOME" 1 "${RESUMED}; ${SHIELD_LOG}"
else
  assert_test "AC-09-B" "Accessibility shield intercepts Settings navigation and forces HOME" 0 "${RESUMED}; ${SHIELD_LOG}"
fi

# Step 10: AC-10 24-Hour Recovery Anti-Tamper Security
log_section "Step 10: AC-10 — 24-Hour Recovery Anti-Tamper Security"
REC_START=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.START_RECOVERY)
[[ "${REC_START}" == *'result=0, data="RECOVERY_STARTED:'* ]] && RS_OK=1 || RS_OK=0
assert_test "AC-10-A" "Recovery request accepted" "${RS_OK}" "${REC_START}"

# Advance only the clock used by this E2E app, without crediting monotonic time.
WALL_JUMP=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.ADVANCE_WALL_CLOCK --el delta_ms 90000000)
WALL_STATE=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.CHECK_RECOVERY)
if [[ "${WALL_JUMP}" == *'result=0, data="WALL_ADVANCED: delta_ms=90000000, remaining_ms=86400000'* && "${WALL_STATE}" == *'active=true, remaining_ms=86400000, is_bricked=true'* ]]; then
  assert_test "AC-10-B" "Synthetic wall-clock jump leaves full 24h recovery and session bricked" 1 "${WALL_STATE}"
else
  assert_test "AC-10-B" "Synthetic wall-clock jump leaves full 24h recovery and session bricked" 0 "Wall: ${WALL_JUMP}; state: ${WALL_STATE}"
fi

# Monotonic test clock advance 24h
ADV_OUT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 86460000)
[[ "${ADV_OUT}" == *'result=0, data="CLOCK_ADVANCED:'* ]] && ADV_OK=1 || ADV_OK=0
assert_test "AC-10-C" "Monotonic test clock advanced by 24h" "${ADV_OK}" "${ADV_OUT}"

exec_adb shell am start -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.MainActivity" >/dev/null
sleep 1
POST_REC_DUMP=$(get_top_ui_dump)
if echo "${POST_REC_DUMP}" | grep -Fq 'text="UNBRICKED"'; then
  assert_test "AC-10-D" "Device unbricks after monotonic 24h elapsed time completes" 1 "Unlocked successfully"
else
  assert_test "AC-10-D" "Device unbricks after monotonic 24h elapsed time completes" 0 "Failed to unlock"
fi

# Step 11: USER FOLLOW-UP — Reboot Mid-Cooldown
log_section "Step 11: USER FOLLOW-UP — Reboot Mid-Cooldown Recovery Test"
exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
sleep 1
MID_START=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.START_RECOVERY)
MID_ADV=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 21600000)
MID_PRE=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.CHECK_RECOVERY)
if [[ "${MID_PRE}" == *'active=true, remaining_ms=64800000, is_bricked=true'* ]]; then MID_PRE_OK=1; else MID_PRE_OK=0; fi
assert_test "AC-MIDBOOT-BASE" "Recovery active after first six hours" "${MID_PRE_OK}" "${MID_PRE}"
sleep 1

if [[ ${SKIP_REBOOT} -eq 0 ]]; then
  log_msg "Rebooting device mid-cooldown..." "36"
  exec_adb reboot
  sleep 10
  if wait_for_boot 180 >/dev/null; then MID_BOOT_OK=1; else MID_BOOT_OK=0; fi
  assert_test "AC-MIDBOOT-BOOT" "Device booted during mid-cooldown test" "${MID_BOOT_OK}" "Boot completion check"
  MID_POST=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.CHECK_RECOVERY)
  MID_RESTORED=0
  MID_REMAINING=-1
  if [[ "${MID_POST}" =~ active=true,\ remaining_ms=([0-9]+),\ is_bricked=true ]]; then
    MID_REMAINING=${BASH_REMATCH[1]}
    if [[ ${MID_REMAINING} -le 64800000 && ${MID_REMAINING} -ge 64700000 ]]; then MID_RESTORED=1; fi
  fi
  assert_test "AC-MIDBOOT-PROGRESS" "Cooldown remains active across device reboot" "${MID_RESTORED}" "${MID_POST}"
  exec_adb shell monkey -p "${BLOCKED_TARGET_APP}" 1 >/dev/null
  sleep 1
  MID_DUMP=$(get_top_ui_dump)
  if echo "${MID_DUMP}" | grep -Fq "YOU'RE BRICKED"; then
    assert_test "AC-MIDBOOT-A" "Device remains bricked after mid-cooldown reboot" 1 "Progress preserved"
  else
    assert_test "AC-MIDBOOT-A" "Device remains bricked after mid-cooldown reboot" 0 "Cooldown reset or shortcut"
  fi

  # Post-reboot wall-only jump must not credit recovery elapsed time.
  WALL_REBOOT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.ADVANCE_WALL_CLOCK --el delta_ms 72000000)
  POST_WALL_STATE=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.CHECK_RECOVERY)
  if [[ "${WALL_REBOOT}" == *'result=0, data="WALL_ADVANCED: delta_ms=72000000'* && "${POST_WALL_STATE}" == *"active=true, remaining_ms=${MID_REMAINING}, is_bricked=true"* ]]; then
    assert_test "AC-MIDBOOT-B" "Post-reboot synthetic wall jump does not advance recovery" 1 "${POST_WALL_STATE}"
  else
    assert_test "AC-MIDBOOT-B" "Post-reboot synthetic wall jump does not advance recovery" 0 "Wall: ${WALL_REBOOT}; state: ${POST_WALL_STATE}"
  fi

  # Advance remaining 18h + 1m
  exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 64860000 >/dev/null
  exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.CHECK_RECOVERY >/dev/null
  sleep 1
  exec_adb shell am start -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.MainActivity" >/dev/null
  sleep 1
  FINAL_DUMP=$(get_top_ui_dump)
  if echo "${FINAL_DUMP}" | grep -Fq 'text="UNBRICKED"'; then
    assert_test "AC-MIDBOOT-C" "Recovery completes and unlocks after remaining time passes post-reboot" 1 "Unlocked post-reboot"
  else
    assert_test "AC-MIDBOOT-C" "Recovery completes and unlocks after remaining time passes post-reboot" 0 "Failed unlock post-reboot"
  fi
fi

# Step 12: AC-11 Stats & History
log_section "Step 12: AC-11 — Stats & Session History Engine"
exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 900000 >/dev/null
exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
sleep 1

exec_adb shell am start -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.MainActivity" >/dev/null
sleep 1
if tap_ui_text "STATS"; then STATS_SELECTED=1; else STATS_SELECTED=0; fi
sleep 1
capture_screenshot "06_stats" && SHOT6=1 || SHOT6=0
assert_test "AC-12-F" "Screenshot 06 (Stats Dashboard) captured" "${SHOT6}" "06_stats.png"

STATS_DUMP=$(get_top_ui_dump)
if [[ ${STATS_SELECTED} -eq 1 ]] && echo "${STATS_DUMP}" | grep -Fq "TOTAL TIME BRICKED"; then
  assert_test "AC-11-A" "Stats screen renders focus tracking metrics" 1 "Metrics visible"
else
  assert_test "AC-11-A" "Stats screen renders focus tracking metrics" 0 "Metrics missing"
fi

# Step 13: AC-12 Design Review Screenshots
log_section "Step 13: AC-12 — Design Review Screenshots Verification"
exec_adb shell am start -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.MainActivity" >/dev/null
sleep 1
if tap_ui_text "MODES"; then MODES_SELECTED=1; else MODES_SELECTED=0; fi
sleep 1
capture_screenshot "04_modes_editor" && SHOT4=1 || SHOT4=0
assert_test "AC-12-D" "Screenshot 04 (Modes Editor) captured" "$([[ ${MODES_SELECTED} -eq 1 && ${SHOT4} -eq 1 ]] && echo 1 || echo 0)" "04_modes_editor.png"

exec_adb shell am start -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.MainActivity" >/dev/null
sleep 1
if tap_ui_text "SCHEDULES"; then SCHEDULES_SELECTED=1; else SCHEDULES_SELECTED=0; fi
sleep 1
capture_screenshot "05_schedules" && SHOT5=1 || SHOT5=0
assert_test "AC-12-E" "Screenshot 05 (Schedules Screen) captured" "$([[ ${SCHEDULES_SELECTED} -eq 1 && ${SHOT5} -eq 1 ]] && echo 1 || echo 0)" "05_schedules.png"

ALL_SHOTS=1
for name in 01_onboarding 02_home_unbricked 03_home_bricked 04_modes_editor 05_schedules 06_stats 07_blocking_screen; do
  if [[ ! -s "${SCREENSHOT_DIR}/${name}.png" ]]; then
    ALL_SHOTS=0
    break
  fi
done
assert_test "AC-12-ALL" "All 7 design review screenshots present and non-empty" "${ALL_SHOTS}" "Checked in ${SCREENSHOT_DIR}"

# Step 14: Tier 4 Adversarial Robustness
log_section "Step 14: Tier 4 Adversarial & Input Boundary Hardening"
EMPTY_OUT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC)
[[ "${EMPTY_OUT}" == *'result=1, data="ERROR: Empty token payload'* ]] && EMPTY_OK=1 || EMPTY_OK=0
assert_test "ADV-01" "Missing token payload rejected gracefully without crash" "${EMPTY_OK}" "${EMPTY_OUT}"

NEG_OUT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms -5000)
[[ "${NEG_OUT}" == *'result=1, data="ERROR: delta_ms must be > 0'* ]] && NEG_OK=1 || NEG_OK=0
assert_test "ADV-02" "Negative test clock delta rejected" "${NEG_OK}" "${NEG_OUT}"

GARB_OUT=$(exec_adb shell am broadcast --include-stopped-packages -n "${PACKAGE_NAME}/${CLASS_NAMESPACE}.feature.service.SimulateTokenScanReceiver" -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "%%00%FF%DEAD%BEEF%--DROP TABLE")
[[ "${GARB_OUT}" == *"REJECTED: UNPAIRED"* ]] && GARB_OK=1 || GARB_OK=0
assert_test "ADV-03" "SQL injection / control characters in payload handled safely" "${GARB_OK}" "${GARB_OUT}"

generate_reports

if [[ ${TOTAL_FAILED} -gt 0 ]]; then
  log_msg "E2E SUITE FAILED: ${TOTAL_FAILED} assertion(s) failed." "31"
  exit 1
else
  log_msg "E2E SUITE PASSED: 100% assertions verified successfully!" "32"
  exit 0
fi
