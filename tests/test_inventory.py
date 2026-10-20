import sys
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import inventory
import profile as profile_tool
import debloat


class InventoryTests(unittest.TestCase):
    def test_draft_profile_uses_only_present_unprotected_system_candidates(self):
        report = {"version": 1, "user": 0, "device": {"model": "TV A", "serial": "one"},
                  "firmware": "brand/build:1", "installed": ["com.google.android.feedback",
                  "com.miui.tv.analytics", "com.google.android.tv.bugreportsender", "com.test.home"],
                  "disabled": ["com.google.android.tv.bugreportsender"],
                  "review": {"protected": ["com.miui.tv.analytics", "com.test.home"],
                             "system_to_review": ["com.google.android.feedback"]}}
        draft = profile_tool.draft(report)
        self.assertEqual([p["package"] for p in draft["packages"]], ["com.google.android.feedback"])
        self.assertTrue(draft["review_required"])
        self.assertEqual(draft["manual_review"], [])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "draft.json"
            path.write_text(json.dumps(draft), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "requiere revisión"):
                debloat.read_profile(path)
            draft["review_required"] = False
            path.write_text(json.dumps(draft), encoding="utf-8")
            self.assertEqual(debloat.read_profile(path)["firmware"], "brand/build:1")
        with self.assertRaisesRegex(ValueError, "inventario completo"):
            profile_tool.draft({**report, "review": None})
        with self.assertRaisesRegex(ValueError, "incoherente"):
            profile_tool.draft({**report, "review": {"protected": ["com.test.home"],
                                         "system_to_review": ["com.google.android.tv.bugreportsender"]}})
        report["installed"].append("com.google.android.apps.tv.launcherx")
        report["review"]["protected"].append("com.google.android.apps.tv.launcherx")
        manual = profile_tool.draft(report)["manual_review"]
        self.assertEqual(manual[0]["package"], "com.google.android.apps.tv.launcherx")
        self.assertTrue(manual[0]["protected"])

    def test_capture_queries_only_and_reports_exact_changes(self):
        answers = {
            "pm list packages --user 0": "package:com.test.home\npackage:com.test.ads\n",
            "pm list packages -d --user 0": "package:com.test.ads\n",
            "getprop ro.build.fingerprint": "build/a",
        }
        with patch.object(inventory, "shell", side_effect=lambda _, command: answers[command]) as query, \
                patch.object(inventory, "identity", return_value={"serial": "one"}):
            after = inventory.capture(None)
        self.assertEqual(query.call_count, 3)
        before = {**after, "disabled": []}
        self.assertEqual(inventory.compare(before, after)["newly_disabled"], ["com.test.ads"])
        self.assertEqual(inventory.compare(after, before)["reenabled"], ["com.test.ads"])

    def test_refuses_different_tv_firmware_or_invalid_results(self):
        before = {"version": 1, "user": 0, "device": {"serial": "one"},
                  "firmware": "build/a", "installed": ["com.test.ads"], "disabled": []}
        with self.assertRaisesRegex(ValueError, "TVs"):
            inventory.compare(before, {**before, "device": {"serial": "two"}})
        with self.assertRaisesRegex(ValueError, "firmware"):
            inventory.compare(before, {**before, "firmware": "build/b"})
        with self.assertRaisesRegex(ValueError, "inválidos"):
            inventory.validate({**before, "disabled": ["com.test.unknown"]})
        with self.assertRaisesRegex(ValueError, "inesperada"):
            inventory.packages("package:com.test.ads\nunauthorized\n")

    def test_review_lists_system_packages_without_classifying_them_as_telemetry(self):
        answers = {
            "pm list packages --user 0": "package:com.test.home\npackage:com.test.ads\npackage:com.test.disabled\n",
            "pm list packages -d --user 0": "package:com.test.disabled\n",
            "pm list packages -s --user 0": "package:com.test.home\npackage:com.test.ads\npackage:com.test.disabled\n",
            "getprop ro.build.fingerprint": "build/a",
        }
        with patch.object(inventory, "shell", side_effect=lambda _, cmd: answers[cmd]), \
                patch.object(inventory, "identity", return_value={"serial": "one"}), \
                patch.object(inventory, "require_user_zero") as check_user, \
                patch.object(inventory, "protected_packages", return_value={"com.test.home"}):
            result = inventory.capture(None, review=True)
        check_user.assert_called_once()
        self.assertEqual(result["review"]["protected"], ["com.test.home"])
        self.assertEqual(result["review"]["system_to_review"], ["com.test.ads"])


if __name__ == "__main__":
    unittest.main()
