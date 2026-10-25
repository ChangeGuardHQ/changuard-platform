# CI workflows

[Repository hygiene](repository_hygiene.yml) runs for every push and pull request.
It rejects tracked files covered by ignore rules, including generated output and
local credential files. Versioned templates such as `.env.example` remain allowed.

[Backend CI](backend_ci.yml) and [Frontend CI](frontend_ci.yml) are the entry
points. They own push and pull-request path filters, weekly schedules, manual
dispatch, concurrency, and the CodeQL opt-in condition. Each entry point calls
five independent reusable workflows.

| Responsibility | Backend | Frontend |
| --- | --- | --- |
| Build and tests | [backend_verify.yml](backend_verify.yml) | [frontend_verify.yml](frontend_verify.yml) |
| Dependency, secret, and configuration scans | [backend_source_security.yml](backend_source_security.yml) | [frontend_source_security.yml](frontend_source_security.yml) |
| Semgrep source scans | [backend_sast.yml](backend_sast.yml) | [frontend_sast.yml](frontend_sast.yml) |
| CodeQL analysis | [backend_codeql.yml](backend_codeql.yml) | [frontend_codeql.yml](frontend_codeql.yml) |
| Container builds, smoke tests, and image scans | [backend_container.yml](backend_container.yml) | [frontend_container.yml](frontend_container.yml) |

Backend verification uses a matrix for `connector-service` and `change-service`,
building each through the Maven reactor with its shared dependencies. Backend
container checks start both services with PostgreSQL, Kafka, and Schema Registry. Frontend
verification runs lint, TypeScript checks, Vitest, and the production build.
Frontend container checks render the standalone AWS configuration with invented
credentials, build the static and development targets, and verify SPA routes,
auth/CSRF forwarding, signed bytes and HTTPS using disposable containers and a test
CA. They do not deploy infrastructure or request public certificates. Changes under
`deploy/` trigger these checks as well.

Backend dependency scans first install the reactor through
[`maven_with_retry.sh`](../scripts/maven_with_retry.sh). The CodeQL manual build
uses the same helper for `clean package`, recompiling the full reactor after
CodeQL initialization on each attempt. Both service verification jobs also use
the helper for `verify`, with tests enabled. The helper retries only
dependency/plugin resolution errors, with at most three attempts and 10/20-second
delays. Each attempt uses Maven's `--update-snapshots` to recheck cached missing
releases and `--errors` to retain repository diagnostics. Compilation and test
failures stop immediately, and exhausted retries preserve Maven's failure status.
The backend parent lists Maven Central before Confluent for ordinary dependencies;
Maven plugins continue to use the default Central plugin repository.

Both service wrappers pin Maven 3.10.0. Maven 3.9 and 3.10 record cached repository
identities differently, so mixing them causes repeated remote checks. The Java
setup steps include wrapper properties and module POMs in the cache key to avoid
restoring a cache populated by an incompatible wrapper version.

## Editing workflows

- Include concise inline comments explaining workflow intent, conditions,
  permissions, and important dependencies, following [the project convention](../../AGENTS.md).
- Edit the reusable workflow for the check being changed. Reusable workflows
  have only the `workflow_call` trigger; they run through their entry point.
- Keep component workflow names prefixed with `backend_` or `frontend_`. The
  entry-point path filters include all matching `.yml` files so reusable-workflow
  edits trigger the corresponding CI pipeline.
- Keep concurrency in the entry points. Called workflows share their caller's
  `github.workflow` context; duplicating that concurrency group can cancel the
  caller. See [GitHub's reusable-workflow reference](https://docs.github.com/en/actions/reference/workflows-and-actions/reusing-workflow-configurations).
- The default token has `contents: read`. Only the CodeQL call and its analysis
  job request code-scanning write permissions. Keep those declarations aligned
  because called workflows cannot increase their caller's permissions.
- Preserve pinned action SHAs, tool versions, artifact names, report uploads
  after failures, and container cleanup. Shared version documentation remains
  in the root [CI/CD guide](../../README.md#25-cicd).

Trivy is pinned to 0.76.1 in both source and container workflows. The
HIGH/CRITICAL vulnerability and secret gates remain enabled; diagnostic JSON
reports and safe package/fixed-version summaries are retained after failures.
Scanner warnings and update notices are separate from the actual findings.

Run `actionlint` from the repository root after changing workflows. Application
tests and container smoke tests run in GitHub Actions when the entry points are
triggered.
