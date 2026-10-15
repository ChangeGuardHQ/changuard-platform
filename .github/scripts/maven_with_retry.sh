#!/usr/bin/env bash
# Run Maven preparation with bounded retries for dependency/plugin resolution failures.
set -euo pipefail

if (( $# == 0 )); then
    echo "Usage: maven_with_retry.sh <Maven wrapper command and arguments>" >&2
    exit 2
fi

log_file=$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/maven-resolution.XXXXXX")
trap 'rm -f "$log_file"' EXIT

for attempt in 1 2 3; do
    # -U retries cached missing releases; -e exposes the underlying repository failure.
    if "$@" --batch-mode --no-transfer-progress --errors --update-snapshots 2>&1 | tee "$log_file"; then
        exit 0
    else
        statuses=("${PIPESTATUS[@]}")
    fi

    # A logging failure must also fail the job, even when Maven itself succeeded.
    if (( statuses[1] != 0 )); then
        exit "${statuses[1]}"
    fi
    status=${statuses[0]}

    # Compilation, tests, and other build failures should fail immediately.
    if ! grep -Eq '\[ERROR\].*(Failed to read artifact descriptor|Could not (transfer|resolve)|could not be resolved|Non-resolvable .*POM)' "$log_file"; then
        exit "$status"
    fi
    if (( attempt == 3 )); then
        exit "$status"
    fi

    delay=$((attempt * 10))
    printf 'Maven dependency resolution failed; retrying in %s seconds (attempt %s/3).\n' "$delay" "$((attempt + 1))"
    sleep "$delay"
done
