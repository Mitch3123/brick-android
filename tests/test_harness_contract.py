"""Fast source-level guards for isolated E2E harness regressions (not emulator tests)."""

import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class HarnessContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shell = (ROOT / "verify_e2e.sh").read_text(encoding="utf-8")
        cls.powershell = (ROOT / "verify_e2e.ps1").read_text(encoding="utf-8")
        cls.receiver = (ROOT / "app/src/e2e/java/com/brick/app/feature/service/SimulateTokenScanReceiver.kt").read_text(encoding="utf-8")

    def test_every_explicit_fixture_broadcast_includes_stopped_package(self):
        for filename, text in (("verify_e2e.sh", self.shell), ("verify_e2e.ps1", self.powershell)):
            with self.subTest(filename=filename):
                broadcasts = [line for line in text.splitlines() if "shell am broadcast " in line and not line.strip().startswith("#")]
                self.assertGreater(len(broadcasts), 0)
                for line in broadcasts:
                    self.assertIn("shell am broadcast --include-stopped-packages -n ", line)

    def test_force_stop_checks_require_blocking_overlay_not_unbricked_home(self):
        for name in ("forceStopDump", "postRebootDump"):
            with self.subTest(check=name):
                assertion = [line for line in self.powershell.splitlines() if line.strip().startswith("Assert-Test") and "$" + name in line]
                self.assertEqual(len(assertion), 1)
                self.assertIn('Contains("YOU\'RE BRICKED")', assertion[0])
                self.assertNotIn('-match "BRICKED"', assertion[0])
        for name in ("FS_DUMP", "POST_BOOT_DUMP"):
            with self.subTest(check=name):
                checks = [line for line in self.shell.splitlines() if line.lstrip().startswith("if echo") and "${" + name + "}" in line]
                self.assertEqual(len(checks), 1)
                self.assertIn("YOU'RE BRICKED", checks[0])
                self.assertIn('package=\\\"${PACKAGE_NAME}\\\"', checks[0])

    def test_reset_cancels_existing_alarms_before_clearing_rows(self):
        handler = self.receiver.split("private suspend fun handleResetState(", 1)[1]
        self.assertLess(handler.index("getAllSchedules().forEach { scheduleManager.cancelAlarm(it.id) }"), handler.index("db.clearAllTables()"))
        self.assertIn('getSharedPreferences("brick_schedule_prefs", Context.MODE_PRIVATE)', handler)
        self.assertIn('getSharedPreferences("brick_setup", Context.MODE_PRIVATE)', handler)
        self.assertLess(handler.index('db.clearAllTables()'), handler.index('db.modeDao().insert(ModeEntity('))
        self.assertIn('isDefault = true', handler)

    def test_onboarding_starts_after_reset_and_activity_restart(self):
        for filename, text in (("verify_e2e.sh", self.shell), ("verify_e2e.ps1", self.powershell)):
            with self.subTest(filename=filename):
                reset = text.index("-a com.brick.action.RESET_STATE")
                restart = text.index("shell am force-stop", reset)
                onboarding = text.index("WELCOME TO BRICK", restart)
                self.assertLess(reset, restart)
                self.assertLess(restart, onboarding)

    def test_ui_dump_helpers_remove_stale_file_and_check_fresh_xml(self):
        for filename, text in (("verify_e2e.sh", self.shell), ("verify_e2e.ps1", self.powershell)):
            with self.subTest(filename=filename):
                helper = text.split("get_top_ui_dump() {" if filename.endswith(".sh") else "function Get-TopUiDump([string]$adb) {", 1)[1].split("\n}", 1)[0]
                self.assertIn("shell rm -f /sdcard/window_dump.xml", helper)
                self.assertIn("dumped to", helper)
                self.assertIn("<hierarchy", helper)
                self.assertIn("for attempt in 1 2 3" if filename.endswith(".sh") else "$attempt -lt 3", helper)


if __name__ == "__main__":
    unittest.main()
