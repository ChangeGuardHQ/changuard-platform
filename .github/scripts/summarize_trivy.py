"""Print actionable Trivy findings without exposing matched secrets or code snippets."""

import json
import sys
from pathlib import Path


def safe(value):
    """Keep report fields on one log line; prevent workflow command injection."""
    return str(value or "-").replace("\r", " ").replace("\n", " ").replace("::", ": :")


def summarize(path):
    print(f"Report: {safe(path.name)}")
    if not path.is_file():
        print("  Not generated; check the preceding build or scanner step.")
        return
    report = json.loads(path.read_text())
    total = 0
    for result in report.get("Results") or []:
        findings = (result.get("Vulnerabilities") or []) + (result.get("Misconfigurations") or [])
        secrets = result.get("Secrets") or []
        if not findings and not secrets:
            continue
        print(f"  Target: {safe(result.get('Target'))}")
        for finding in findings:
            total += 1
            if "VulnerabilityID" in finding:
                print("    {severity} {identifier} {package}: {installed} -> {fixed}".format(
                    severity=safe(finding.get("Severity")), identifier=safe(finding.get("VulnerabilityID")),
                    package=safe(finding.get("PkgName")), installed=safe(finding.get("InstalledVersion")),
                    fixed=safe(finding.get("FixedVersion") or "no fix listed")))
            else:
                print(f"    {safe(finding.get('Severity'))} configuration rule {safe(finding.get('ID'))}")
        for finding in secrets:
            total += 1
            # Never print Match, Code, or the full finding: these can contain credentials.
            print(f"    {safe(finding.get('Severity'))} secret rule {safe(finding.get('RuleID'))} "
                  f"at lines {safe(finding.get('StartLine'))}-{safe(finding.get('EndLine'))}")
    print(f"  Findings in this report: {total}")


if __name__ == "__main__":
    for filename in sys.argv[1:]:
        summarize(Path(filename))
