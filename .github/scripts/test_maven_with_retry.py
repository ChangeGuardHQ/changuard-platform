"""Verify Maven retries preserve build failures and remain bounded without network access."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class MavenRetryTests(unittest.TestCase):
    script = Path(__file__).with_name("maven_with_retry.sh").resolve()

    def run_maven(self, results):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "results.json").write_text(json.dumps(results))
            # Substitute Maven and sleep so retries can be checked deterministically.
            command = root / "maven.py"
            command.write_text('''import json
import sys
from pathlib import Path
attempts = Path("attempts.json")
calls = json.loads(attempts.read_text()) if attempts.exists() else []
calls.append(sys.argv[1:])
attempts.write_text(json.dumps(calls))
results = json.loads(Path("results.json").read_text())
result = results[min(len(calls) - 1, len(results) - 1)]
print(result[1])
sys.exit(result[0])
''')
            sleeper = root / "sleep"
            sleeper.write_text('#!/bin/sh\nprintf "%s\\n" "$1" >> delays.txt\n')
            sleeper.chmod(0o755)
            environment = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", RUNNER_TEMP=str(root))
            result = subprocess.run(
                ["bash", str(self.script), sys.executable, str(command), "-Dexample=value with spaces", "install"],
                cwd=root, env=environment, capture_output=True, text=True, check=False,
            )
            calls = json.loads((root / "attempts.json").read_text())
            delays = (root / "delays.txt").read_text().splitlines() if (root / "delays.txt").exists() else []
            self.assertEqual(list(root.glob("maven-resolution.*")), [])
            return result, calls, delays

    def test_success_runs_once_and_preserves_arguments(self):
        result, calls, delays = self.run_maven([[0, "[INFO] BUILD SUCCESS"]])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(len(calls), 1)
        self.assertIn("-Dexample=value with spaces", calls[0])
        self.assertIn("--errors", calls[0])
        self.assertIn("--update-snapshots", calls[0])
        self.assertEqual(delays, [])

    def test_descriptor_failure_is_retried_and_keeps_diagnostics(self):
        message = "[ERROR]\tFailed to read artifact descriptor for commons-io:commons-io:jar:2.19.0"
        result, calls, delays = self.run_maven([[1, message], [0, "[INFO] BUILD SUCCESS"]])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(len(calls), 2)
        self.assertEqual(delays, ["10"])
        self.assertIn(message, result.stdout)
        self.assertTrue(all("--update-snapshots" in call for call in calls))

    def test_transfer_failure_stops_after_three_attempts_with_original_status(self):
        result, calls, delays = self.run_maven([[7, "[ERROR] Could not transfer artifact from central: HTTP 503"]])
        self.assertEqual(result.returncode, 7)
        self.assertEqual(len(calls), 3)
        self.assertEqual(delays, ["10", "20"])

    def test_codeql_connection_reset_is_retried(self):
        # Preserve the descriptor/transport combination from the failed CodeQL build.
        message = (
            "[ERROR] Failed to read artifact descriptor for org.apache.kafka:kafka-metadata:jar:4.1.2\n"
            "[ERROR] \tCaused by: Could not transfer artifact from/to central: Connection reset"
        )
        result, calls, delays = self.run_maven([[1, message], [0, "[INFO] BUILD SUCCESS"]])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(len(calls), 2)
        self.assertEqual(delays, ["10"])
        self.assertIn("Connection reset", result.stdout)

    def test_build_failures_are_not_retried(self):
        for message in ("[ERROR] COMPILATION ERROR", "[ERROR] There are test failures."):
            with self.subTest(message=message):
                result, calls, delays = self.run_maven([[1, message]])
                self.assertEqual(result.returncode, 1)
                self.assertEqual(len(calls), 1)
                self.assertEqual(delays, [])


if __name__ == "__main__":
    unittest.main()
