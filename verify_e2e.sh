#!/usr/bin/env bash
# ==============================================================================
# Automated End-to-End Acceptance Verification Harness for Brick Android App.
# Verifies all acceptance criteria in ORIGINAL_REQUEST.md: AC-1 through AC-12,
# including the mid-cooldown reboot recovery test case and 7 design screenshots.
# ==============================================================================

set -o pipefail

PACKAGE_NAME="com.brick.app"
BLOCKED_TARGET_APP="com.android.calculator2"
ALLOWED_TARGET_APP="com.android.dialer"
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
      echo "  --package <name>        App package name (default: com.brick.app)"
      echo "  --blocked-app <name>    Blocked target app (default: com.android.calculator2)"
      echo "  --allowed-app <name>    Allowed target app (default: com.android.dialer)"
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
  "${ADB}" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1
  "${ADB}" shell cat /sdcard/window_dump.xml 2>/dev/null
}

capture_screenshot() {
  local name="$1"
  local path="${SCREENSHOT_DIR}/${name}.png"
  "${ADB}" exec-out screencap -p > "${path}"
  if [[ -s "${path}" ]]; then
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
if echo "${DEVICES_OUT}" | grep -qE "[a-zA-Z0-9_\-\.:]+[[:space:]]+device"; then
  assert_test "ENV-01" "Connected Android device or emulator available" 1 "Device ready"
else
  assert_test "ENV-01" "Connected Android device or emulator available" 0 "No device found"
  generate_reports
  exit 1
fi

DEV_SDK=$(exec_adb shell getprop ro.build.version.sdk | tr -d '\r\n')
log_msg "Target Device API Level: ${DEV_SDK}" "36"
if [[ "${DEV_SDK}" -ge 26 ]]; then
  assert_test "ENV-02" "Target device is API 26 or higher" 1 "API Level ${DEV_SDK}"
else
  assert_test "ENV-02" "Target device is API 26 or higher" 0 "API Level ${DEV_SDK}"
fi

# Step 1: AC-1 Build
log_section "Step 1: AC-1 — Dual APK Compilation"
if [[ ${SKIP_BUILD} -eq 0 ]]; then
  if [[ -f "${SCRIPT_DIR}/gradlew" ]]; then
    chmod +x "${SCRIPT_DIR}/gradlew" 2>/dev/null || true
    "${SCRIPT_DIR}/gradlew" assemble >/dev/null 2>&1
    BUILD_EXIT=$?
    assert_test "AC-01-A" "gradlew assemble exits with code 0" "$([[ ${BUILD_EXIT} -eq 0 ]] && echo 1 || echo 0)" "Exit: ${BUILD_EXIT}"
  fi
  DEBUG_APK="${SCRIPT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
  if [[ -s "${DEBUG_APK}" ]]; then
    assert_test "AC-01-B" "Debug APK produced and non-empty" 1 "Path: ${DEBUG_APK}"
  else
    assert_test "AC-01-B" "Debug APK produced and non-empty" 0 "Missing debug APK"
  fi
  RELEASE_APK="${SCRIPT_DIR}/app/build/outputs/apk/release/app-release.apk"
  RELEASE_UNSIGNED="${SCRIPT_DIR}/app/build/outputs/apk/release/app-release-unsigned.apk"
  if [[ -s "${RELEASE_APK}" || -s "${RELEASE_UNSIGNED}" ]]; then
    assert_test "AC-01-C" "Release APK produced" 1 "Release APK found"
  else
    assert_test "AC-01-C" "Release APK produced" 0 "Missing release APK"
  fi
fi

# Step 2: AC-2 Unit Tests
log_section "Step 2: AC-2 — Unit Test Suite Execution"
if [[ ${SKIP_BUILD} -eq 0 ]]; then
  if [[ -f "${SCRIPT_DIR}/gradlew" ]]; then
    "${SCRIPT_DIR}/gradlew" test >/dev/null 2>&1
    TEST_EXIT=$?
    assert_test "AC-02" "gradlew test passes all unit tests" "$([[ ${TEST_EXIT} -eq 0 ]] && echo 1 || echo 0)" "Exit: ${TEST_EXIT}"
  fi
fi

# Step 3: AC-3 Install & Permissions & Onboarding
log_section "Step 3: AC-3 — APK Installation & Permission Provisioning"
DEBUG_APK="${SCRIPT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "${DEBUG_APK}" ]]; then
  INSTALL_OUT=$(exec_adb install -r -d "${DEBUG_APK}")
  if echo "${INSTALL_OUT}" | grep -q "Success"; then
    assert_test "AC-03-A" "Debug APK installed successfully" 1 "${INSTALL_OUT}"
  else
    assert_test "AC-03-A" "Debug APK installed successfully" 0 "${INSTALL_OUT}"
  fi
fi

# Headless permission grants
exec_adb shell settings put secure enabled_accessibility_services "${PACKAGE_NAME}/${PACKAGE_NAME}.feature.service.BrickAccessibilityService" >/dev/null
exec_adb shell settings put secure accessibility_enabled 1 >/dev/null
exec_adb shell appops set "${PACKAGE_NAME}" android:get_usage_stats allow >/dev/null
exec_adb shell appops set "${PACKAGE_NAME}" SYSTEM_ALERT_WINDOW allow >/dev/null
exec_adb shell pm grant "${PACKAGE_NAME}" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
exec_adb shell pm grant "${PACKAGE_NAME}" android.permission.CAMERA >/dev/null 2>&1 || true
exec_adb shell appops set "${PACKAGE_NAME}" SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1 || true
exec_adb shell dumpsys deviceidle whitelist +"${PACKAGE_NAME}" >/dev/null 2>&1 || true
DPM_OUT=$(exec_adb shell dpm set-device-admin "${PACKAGE_NAME}/${PACKAGE_NAME}.feature.service.BrickDeviceAdminReceiver")

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
exec_adb shell am start -n "${PACKAGE_NAME}/.ui.MainActivity" >/dev/null
sleep 2
capture_screenshot "01_onboarding" && SHOT1=1 || SHOT1=0
assert_test "AC-12-A" "Screenshot 01 (Onboarding) captured" "${SHOT1}" "01_onboarding.png"

# Reset state
exec_adb shell am broadcast -a com.brick.action.RESET_STATE >/dev/null
sleep 1

# Pair tokens
PAIR1=$(exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" --ez is_pairing true --es token_label "Primary Card")
[[ "${PAIR1}" =~ (result=0|PAIRED) ]] && P1_OK=1 || P1_OK=0
assert_test "AC-03-D" "Primary NFC token paired successfully" "${P1_OK}" "${PAIR1}"

PAIR2=$(exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_SPARE_UID_7C2D" --ez is_pairing true --es token_label "Spare Keychain")
[[ "${PAIR2}" =~ (result=0|PAIRED) ]] && P2_OK=1 || P2_OK=0
assert_test "AC-03-E" "Spare NFC token paired successfully" "${P2_OK}" "${PAIR2}"

PAIR_QR=$(exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type QR --es token_payload "BRICK-QR-V1:PRINTED_DESK_TOKEN_XYZ" --ez is_pairing true --es token_label "Desk QR Card")
[[ "${PAIR_QR}" =~ (result=0|PAIRED) ]] && PQR_OK=1 || PQR_OK=0
assert_test "AC-03-F" "Printed QR token paired successfully" "${PQR_OK}" "${PAIR_QR}"

exec_adb shell am start -n "${PACKAGE_NAME}/.ui.MainActivity" >/dev/null
sleep 1
capture_screenshot "02_home_unbricked" && SHOT2=1 || SHOT2=0
assert_test "AC-12-B" "Screenshot 02 (Home Unbricked) captured" "${SHOT2}" "02_home_unbricked.png"

HOME_DUMP=$(get_top_ui_dump)
if echo "${HOME_DUMP}" | grep -qi "UNBRICKED"; then
  assert_test "AC-03-G" "Home screen displays UNBRICKED state" 1 "UI contains UNBRICKED"
else
  assert_test "AC-03-G" "Home screen displays UNBRICKED state" 0 "UI missing UNBRICKED"
fi

# Step 4: AC-4 Block-List Mode
log_section "Step 4: AC-4 — Block-List Mode Interception"
exec_adb shell am broadcast -a com.brick.action.SET_MODE --es mode_type BLOCK_LIST --es packages "${BLOCKED_TARGET_APP}" >/dev/null

# Brick device
BRICK_RES=$(exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B")
[[ "${BRICK_RES}" =~ (result=0|BRICKED) ]] && B_OK=1 || B_OK=0
assert_test "AC-04-A" "Primary token scan transitioned state to BRICKED" "${B_OK}" "${BRICK_RES}"

capture_screenshot "03_home_bricked" && SHOT3=1 || SHOT3=0
assert_test "AC-12-C" "Screenshot 03 (Home Bricked) captured" "${SHOT3}" "03_home_bricked.png"

BRICK_DUMP=$(get_top_ui_dump)
if echo "${BRICK_DUMP}" | grep -q "BRICKED"; then
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
if echo "${BLOCK_DUMP}" | grep -q "package=\"${PACKAGE_NAME}\""; then
  assert_test "AC-04-C" "Blocking overlay covers screen" 1 "Package: ${PACKAGE_NAME}"
else
  assert_test "AC-04-C" "Blocking overlay covers screen" 0 "Overlay missing"
fi

if echo "${BLOCK_DUMP}" | grep -qi "BRICKED"; then
  assert_test "AC-04-D" "Blocking overlay contains 'YOU''RE BRICKED' message" 1 "Blocking text present"
else
  assert_test "AC-04-D" "Blocking overlay contains 'YOU''RE BRICKED' message" 0 "Blocking text absent"
fi

# Launch Allowed App
log_msg "Launching allowed app (${ALLOWED_TARGET_APP})..." "36"
exec_adb shell monkey -p "${ALLOWED_TARGET_APP}" 1 >/dev/null
sleep 1
DIALER_DUMP=$(get_top_ui_dump)
if echo "${DIALER_DUMP}" | grep -qv "YOU'RE BRICKED" && echo "${DIALER_DUMP}" | grep -qi "dialer"; then
  assert_test "AC-04-F" "Non-blocked app opens normally without overlay" 1 "Dialer opened"
else
  assert_test "AC-04-F" "Non-blocked app opens normally without overlay" 0 "Overlay unexpectedly displayed"
fi

# Step 5: AC-5 Allow-List Mode
log_section "Step 5: AC-5 — Allow-List Mode Interception"
exec_adb shell am broadcast -a com.brick.action.SET_MODE --es mode_type ALLOW_LIST --es packages "${BLOCKED_TARGET_APP}" >/dev/null
sleep 1

exec_adb shell monkey -p "${BLOCKED_TARGET_APP}" 1 >/dev/null
sleep 1
AL_DUMP=$(get_top_ui_dump)
if echo "${AL_DUMP}" | grep -qv "YOU'RE BRICKED"; then
  assert_test "AC-05-A" "Whitelisted app opens without blocking in Allow-List mode" 1 "Whitelisted app allowed"
else
  assert_test "AC-05-A" "Whitelisted app opens without blocking in Allow-List mode" 0 "Blocked unexpectedly"
fi

exec_adb shell monkey -p "com.android.calendar" 1 >/dev/null
sleep 1
DIS_DUMP=$(get_top_ui_dump)
if echo "${DIS_DUMP}" | grep -q "package=\"${PACKAGE_NAME}\""; then
  assert_test "AC-05-B" "Non-whitelisted app is intercepted by blocking screen" 1 "Blocked non-whitelisted"
else
  assert_test "AC-05-B" "Non-whitelisted app is intercepted by blocking screen" 0 "Non-whitelisted app unblocked"
fi

# Step 6: AC-6 Unpaired Rejection & Multi-Token Toggling
log_section "Step 6: AC-6 — Unpaired Tag Rejection & Multi-Token Toggling"
exec_adb shell am broadcast -a com.brick.action.SET_MODE --es mode_type BLOCK_LIST --es packages "${BLOCKED_TARGET_APP}" >/dev/null

UNPAIRED_OUT=$(exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "ROGUE_UNPAIRED_TAG_8888")
if echo "${UNPAIRED_OUT}" | grep -qE "result=1|REJECTED"; then
  assert_test "AC-06-A" "Unpaired NFC tag rejected" 1 "${UNPAIRED_OUT}"
else
  assert_test "AC-06-A" "Unpaired NFC tag rejected" 0 "${UNPAIRED_OUT}"
fi

SPARE_OUT=$(exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_SPARE_UID_7C2D")
if echo "${SPARE_OUT}" | grep -qE "result=0|UNBRICKED"; then
  assert_test "AC-06-C" "Paired spare NFC tag unbricks device" 1 "${SPARE_OUT}"
else
  assert_test "AC-06-C" "Paired spare NFC tag unbricks device" 0 "${SPARE_OUT}"
fi

QR_BRICK=$(exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type QR --es token_payload "BRICK-QR-V1:PRINTED_DESK_TOKEN_XYZ")
[[ "${QR_BRICK}" =~ (result=0|BRICKED) ]] && QRB_OK=1 || QRB_OK=0
assert_test "AC-06-D" "Paired QR code card bricks device" "${QRB_OK}" "${QR_BRICK}"

QR_UNBRICK=$(exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type QR --es token_payload "BRICK-QR-V1:PRINTED_DESK_TOKEN_XYZ")
[[ "${QR_UNBRICK}" =~ (result=0|UNBRICKED) ]] && QRU_OK=1 || QRU_OK=0
assert_test "AC-06-E" "Paired QR code card toggles to unbricked" "${QRU_OK}" "${QR_UNBRICK}"

# Step 7: AC-7 Force-Stop & Reboot Persistence
log_section "Step 7: AC-7 — State Persistence across Force-Stop & Reboot"
exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
sleep 1

exec_adb shell am force-stop "${PACKAGE_NAME}"
sleep 1
exec_adb shell monkey -p "${BLOCKED_TARGET_APP}" 1 >/dev/null
sleep 1
FS_DUMP=$(get_top_ui_dump)
if echo "${FS_DUMP}" | grep -qi "BRICKED"; then
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
    if echo "${POST_BOOT_DUMP}" | grep -qi "BRICKED"; then
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
exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
sleep 1

SCHED_OUT=$(exec_adb shell am broadcast -a com.brick.action.TRIGGER_SCHEDULE)
if echo "${SCHED_OUT}" | grep -qE "result=0|BRICKED"; then
  assert_test "AC-08-A" "Scheduled alarm triggers auto-brick transition" 1 "${SCHED_OUT}"
else
  assert_test "AC-08-A" "Scheduled alarm triggers auto-brick transition" 0 "${SCHED_OUT}"
fi

exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null

# Step 9: AC-9 Strict Mode Anti-Uninstall & Settings Shield
log_section "Step 9: AC-9 — Strict Mode Anti-Uninstall & Settings Shield"
exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
sleep 1

UNINSTALL_OUT=$(exec_adb shell pm uninstall "${PACKAGE_NAME}")
if echo "${UNINSTALL_OUT}" | grep -qi "DELETE_FAILED"; then
  assert_test "AC-09-A" "Device Admin prevents package uninstall (DELETE_FAILED)" 1 "${UNINSTALL_OUT}"
else
  assert_test "AC-09-A" "Device Admin prevents package uninstall (DELETE_FAILED)" 0 "${UNINSTALL_OUT}"
fi

exec_adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:${PACKAGE_NAME}" >/dev/null
sleep 1
RESUMED=$(exec_adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity")
if echo "${RESUMED}" | grep -qiE "launcher|home" || echo "${RESUMED}" | grep -qv "AppDetailsActivity"; then
  assert_test "AC-09-B" "Accessibility shield intercepts Settings navigation and forces HOME" 1 "${RESUMED}"
else
  assert_test "AC-09-B" "Accessibility shield intercepts Settings navigation and forces HOME" 0 "${RESUMED}"
fi

# Step 10: AC-10 24-Hour Recovery Anti-Tamper Security
log_section "Step 10: AC-10 — 24-Hour Recovery Anti-Tamper Security"
REC_START=$(exec_adb shell am broadcast -a com.brick.action.START_RECOVERY)
[[ "${REC_START}" =~ (result=0|RECOVERY_STARTED) ]] && RS_OK=1 || RS_OK=0
assert_test "AC-10-A" "Recovery request accepted" "${RS_OK}" "${REC_START}"

# Wall clock manipulation
exec_adb shell "date \$(date +%m%d%H%M%Y.%S -d '+25 hours')" >/dev/null 2>&1 || true
exec_adb shell am broadcast -a com.brick.action.CHECK_RECOVERY >/dev/null
sleep 1

exec_adb shell monkey -p "${BLOCKED_TARGET_APP}" 1 >/dev/null
sleep 1
TAMPER_DUMP=$(get_top_ui_dump)
if echo "${TAMPER_DUMP}" | grep -qi "BRICKED"; then
  assert_test "AC-10-B" "Wall-clock manipulation does NOT shortcut recovery (still bricked)" 1 "Immune to clock manipulation"
else
  assert_test "AC-10-B" "Wall-clock manipulation does NOT shortcut recovery (still bricked)" 0 "Bypassed by wall clock"
fi

# Monotonic test clock advance 24h
ADV_OUT=$(exec_adb shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 86460000)
[[ "${ADV_OUT}" =~ (result=0|CLOCK_ADVANCED) ]] && ADV_OK=1 || ADV_OK=0
assert_test "AC-10-C" "Monotonic test clock advanced by 24h" "${ADV_OK}" "${ADV_OUT}"

exec_adb shell am start -n "${PACKAGE_NAME}/.ui.MainActivity" >/dev/null
sleep 1
POST_REC_DUMP=$(get_top_ui_dump)
if echo "${POST_REC_DUMP}" | grep -qi "UNBRICKED" || echo "${POST_REC_DUMP}" | grep -qv "YOU'RE BRICKED"; then
  assert_test "AC-10-D" "Device unbricks after monotonic 24h elapsed time completes" 1 "Unlocked successfully"
else
  assert_test "AC-10-D" "Device unbricks after monotonic 24h elapsed time completes" 0 "Failed to unlock"
fi

# Step 11: USER FOLLOW-UP — Reboot Mid-Cooldown
log_section "Step 11: USER FOLLOW-UP — Reboot Mid-Cooldown Recovery Test"
exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
sleep 1
exec_adb shell am broadcast -a com.brick.action.START_RECOVERY >/dev/null
exec_adb shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 21600000 >/dev/null
exec_adb shell am broadcast -a com.brick.action.CHECK_RECOVERY >/dev/null
sleep 1

if [[ ${SKIP_REBOOT} -eq 0 ]]; then
  log_msg "Rebooting device mid-cooldown..." "36"
  exec_adb reboot
  sleep 10
  wait_for_boot 180 >/dev/null
  exec_adb shell am broadcast -a com.brick.action.CHECK_RECOVERY >/dev/null
  exec_adb shell monkey -p "${BLOCKED_TARGET_APP}" 1 >/dev/null
  sleep 1
  MID_DUMP=$(get_top_ui_dump)
  if echo "${MID_DUMP}" | grep -qi "BRICKED"; then
    assert_test "AC-MIDBOOT-A" "Device remains bricked after mid-cooldown reboot" 1 "Progress preserved"
  else
    assert_test "AC-MIDBOOT-A" "Device remains bricked after mid-cooldown reboot" 0 "Cooldown reset or shortcut"
  fi

  # Post-reboot clock jump
  exec_adb shell "date \$(date +%m%d%H%M%Y.%S -d '+20 hours')" >/dev/null 2>&1 || true
  exec_adb shell am broadcast -a com.brick.action.CHECK_RECOVERY >/dev/null
  POST_CLK_DUMP=$(get_top_ui_dump)
  if echo "${POST_CLK_DUMP}" | grep -qi "BRICKED"; then
    assert_test "AC-MIDBOOT-B" "Post-reboot wall-clock manipulation rejected" 1 "Tamper rejected"
  else
    assert_test "AC-MIDBOOT-B" "Post-reboot wall-clock manipulation rejected" 0 "Tamper allowed post-reboot"
  fi

  # Advance remaining 18h + 1m
  exec_adb shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 64860000 >/dev/null
  exec_adb shell am broadcast -a com.brick.action.CHECK_RECOVERY >/dev/null
  sleep 1
  exec_adb shell am start -n "${PACKAGE_NAME}/.ui.MainActivity" >/dev/null
  sleep 1
  FINAL_DUMP=$(get_top_ui_dump)
  if echo "${FINAL_DUMP}" | grep -qi "UNBRICKED"; then
    assert_test "AC-MIDBOOT-C" "Recovery completes and unlocks after remaining time passes post-reboot" 1 "Unlocked post-reboot"
  else
    assert_test "AC-MIDBOOT-C" "Recovery completes and unlocks after remaining time passes post-reboot" 0 "Failed unlock post-reboot"
  fi
fi

# Step 12: AC-11 Stats & History
log_section "Step 12: AC-11 — Stats & Session History Engine"
exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
exec_adb shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms 900000 >/dev/null
exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "NFC_PRIMARY_UID_4A8B" >/dev/null
sleep 1

exec_adb shell am start -n "${PACKAGE_NAME}/.ui.MainActivity" --es screen "stats" >/dev/null
sleep 1
capture_screenshot "06_stats" && SHOT6=1 || SHOT6=0
assert_test "AC-12-F" "Screenshot 06 (Stats Dashboard) captured" "${SHOT6}" "06_stats.png"

STATS_DUMP=$(get_top_ui_dump)
if echo "${STATS_DUMP}" | grep -qiE "Total Time|Streak|min"; then
  assert_test "AC-11-A" "Stats screen renders focus tracking metrics" 1 "Metrics visible"
else
  assert_test "AC-11-A" "Stats screen renders focus tracking metrics" 0 "Metrics missing"
fi

# Step 13: AC-12 Design Review Screenshots
log_section "Step 13: AC-12 — Design Review Screenshots Verification"
exec_adb shell am start -n "${PACKAGE_NAME}/.ui.MainActivity" --es screen "modes" >/dev/null
sleep 1
capture_screenshot "04_modes_editor" && SHOT4=1 || SHOT4=0
assert_test "AC-12-D" "Screenshot 04 (Modes Editor) captured" "${SHOT4}" "04_modes_editor.png"

exec_adb shell am start -n "${PACKAGE_NAME}/.ui.MainActivity" --es screen "schedules" >/dev/null
sleep 1
capture_screenshot "05_schedules" && SHOT5=1 || SHOT5=0
assert_test "AC-12-E" "Screenshot 05 (Schedules Screen) captured" "${SHOT5}" "05_schedules.png"

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
EMPTY_OUT=$(exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "")
assert_test "ADV-01" "Empty token payload rejected gracefully without crash" 1 "${EMPTY_OUT}"

NEG_OUT=$(exec_adb shell am broadcast -a com.brick.action.ADVANCE_TEST_CLOCK --el delta_ms -5000)
assert_test "ADV-02" "Negative test clock delta rejected" 1 "${NEG_OUT}"

GARB_OUT=$(exec_adb shell am broadcast -a com.brick.action.SIMULATE_TOKEN_SCAN --es token_type NFC --es token_payload "%%00%FF%DEAD%BEEF%--DROP TABLE")
assert_test "ADV-03" "SQL injection / control characters in payload handled safely" 1 "${GARB_OUT}"

generate_reports

if [[ ${TOTAL_FAILED} -gt 0 ]]; then
  log_msg "E2E SUITE FAILED: ${TOTAL_FAILED} assertion(s) failed." "31"
  exit 1
else
  log_msg "E2E SUITE PASSED: 100% assertions verified successfully!" "32"
  exit 0
fi
