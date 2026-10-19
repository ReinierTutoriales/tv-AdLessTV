import sys
from pathlib import Path
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import inventory


class InventoryTests(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
