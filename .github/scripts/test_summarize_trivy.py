"""Check that CI diagnostics cannot expose secret matches or inject log commands."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class TrivySummaryTests(unittest.TestCase):
    script = Path(__file__).with_name("summarize_trivy.py")

    def run_report(self, report):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            path.write_text(json.dumps(report))
            return subprocess.run([sys.executable, str(self.script), str(path)],
                                  capture_output=True, text=True, check=False)

    def test_prints_package_and_fix_without_matched_secrets_or_code(self):
        marker = "sensitive-finding-content"
        result = self.run_report({"Results": [{"Target": "app.jar", "Vulnerabilities": [{
            "Severity": "HIGH", "VulnerabilityID": "CVE-example", "PkgName": "dependency",
            "InstalledVersion": "1.0", "FixedVersion": "1.1"}], "Secrets": [{
                "Severity": "CRITICAL", "RuleID": "test-rule", "StartLine": 2, "EndLine": 3,
                "Match": marker, "Code": {"Lines": [{"Content": marker}]}, "Title": marker}]}]})
        self.assertEqual(result.returncode, 0)
        self.assertIn("HIGH CVE-example dependency: 1.0 -> 1.1", result.stdout)
        self.assertIn("CRITICAL secret rule test-rule at lines 2-3", result.stdout)
        self.assertNotIn(marker, result.stdout + result.stderr)

    def test_report_fields_cannot_inject_github_log_commands(self):
        result = self.run_report({"Results": [{"Target": "path\r\n::error::injected", "Secrets": [{
            "Severity": "HIGH", "RuleID": "rule\n::warning::injected"}]}]})
        self.assertEqual(result.returncode, 0)
        self.assertNotIn("::error::", result.stdout)
        self.assertNotIn("::warning::", result.stdout)

    def test_null_results_are_a_valid_empty_report(self):
        result = self.run_report({"Results": None})
        self.assertEqual(result.returncode, 0)
        self.assertIn("Findings in this report: 0", result.stdout)

    def test_missing_report_reports_no_scanner_result(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(self.script), str(Path(directory) / "missing.json")],
                                    capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0)
        self.assertIn("Not generated", result.stdout)
        self.assertNotIn("Findings in this report: 0", result.stdout)

    def test_malformed_report_fails_instead_of_claiming_a_clean_scan(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            path.write_text("{broken")
            result = subprocess.run([sys.executable, str(self.script), str(path)],
                                    capture_output=True, text=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Findings in this report: 0", result.stdout)


if __name__ == "__main__":
    unittest.main()
