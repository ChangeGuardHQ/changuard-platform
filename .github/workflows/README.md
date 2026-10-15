# CI workflows

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

Backend verification uses a matrix for `connector-service` and `change-service`.
Backend container checks start both services with PostgreSQL and Kafka. Frontend
verification runs lint, TypeScript checks, Vitest, and the production build.

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

Run `actionlint` from the repository root after changing workflows. Application
tests and container smoke tests run in GitHub Actions when the entry points are
triggered.
