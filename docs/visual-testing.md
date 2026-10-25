# Live visual testing with GitHub

For AWS, start with [the EC2 deployment guide](../deploy/README.md). It uses a
temporary public-IP hostname, HTTPS and direct GitHub webhooks. The steps below
describe the local development stack and webhook forwarding.

This guide connects a real repository to the local UI. It requires your own
GitHub App configuration; automated tests use isolated fixtures and do not register
an App or change repository settings for you.

## 1. Configure the GitHub App

Use an App installed on the GitHub account/organization that owns the repository.
For `ChangeGuardHQ/changuard-platform`, the trusted provider account is
`ChangeGuardHQ`, not the individual contributor or the repository ID.

Configure:

- Repository permissions: Metadata, Contents and Pull requests at read level.
- Webhook subscriptions: **Push** and **Pull request**. Installation lifecycle
  deliveries are handled separately by the connector.
- Webhook URL: a public HTTPS endpoint forwarding to the connector's
  `/api/v1/integrations/github/webhook` on local port 8081.
- Webhook secret: the same private value used for `GITHUB_WEBHOOK_SECRET`.
- **Setup URL**: `http://localhost:5173/connections/github/callback` when the browser
  runs on this machine. Adjust the host/port to the frontend's actual browser URL.
  This is the post-installation Setup URL, rather than the OAuth user-authorization
  callback URL. This initial flow uses App authentication, not user OAuth.

GitHub cannot deliver webhooks directly to localhost. Use the
[official local-webhook testing guide](https://docs.github.com/en/webhooks/testing-and-troubleshooting-webhooks/testing-webhooks)
for forwarding. Forward only the connector webhook traffic; the Vite development
UI can stay local. See GitHub's
[Setup URL explanation](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/about-the-setup-url)
for the redirect parameters and its distinction from OAuth callbacks.

## 2. Configure the local stack

Copy the root example if you do not already have private configuration:

```sh
cp .env.example .env
```

Fill the existing GitHub App/connection variables in `.env`:

| Variable | Value |
| --- | --- |
| `GITHUB_APP_ID`, `GITHUB_APP_SLUG` | Your registered App. |
| `GITHUB_APP_PRIVATE_KEY_FILE` | External RSA PEM file readable by container UID 10001. |
| `GITHUB_WEBHOOK_SECRET` | The webhook secret configured on the App. |
| `GITHUB_INSTALLATION_STATE_SECRET` | A separate random secret of at least 32 bytes. |
| `GITHUB_CONNECTION_ORGANIZATION_ID` | Internal ChangeGuard organization, e.g. `local`. |
| `GITHUB_CONNECTION_ACCOUNT_ID` | Numeric GitHub owner account ID verified by the connector. |
| `GITHUB_CONNECTION_ADMINISTRATORS` | Authorized connector username, e.g. `connector-admin`. |
| `GITHUB_CONNECTION_USERNAME`, `GITHUB_CONNECTION_PASSWORD` | That connector account and its private password. |
| `CHANGE_ORGANIZATION_ID` | Same internal organization as the connection binding. |
| `CHANGE_SERVICE_USER`, `CHANGE_SERVICE_PASSWORD` | Change read account; example defaults are local-only. |
| `CHANGE_ORGANIZATION_READERS` | Must include that change read username. |
| `GITHUB_CODE_LIFECYCLE_ENABLED` | `true` for pushes and opened PRs. |

Keep keys outside the repository and private values in ignored configuration.
The frontend does not need App keys, access tokens, or public `VITE_*` passwords.
The backend [GitHub connection guide](../backend/services/connector-service/README.md)
explains trusted ownership and key mounting in more detail.

From the repository root:

```sh
# The override enables connection management and mounts the external App key.
docker compose -f docker-compose.yaml -f docker-compose.github.yaml up --build -d
curl --fail http://localhost:8082/actuator/health/readiness
```

Allow migrations, Registry registration and the consumer to start before creating
test activity. Set `KAFKA_LISTENER_AUTO_STARTUP=true`. The default source topic is
`changeguard.code-events.v1`; retain its dedicated change-service DLT for recovery.
When topic auto-creation is disabled, provision the DLT using the
[consumer recovery guide](../backend/services/change-service/README.md#recover-a-quarantined-event).

## 3. Connect through the UI

1. Open <http://localhost:5173/connections> (adjust `FRONTEND_PORT` if needed).
2. Apply your internal organization and the change-service reader plus connector
   administrator accounts. The two services can use different usernames/passwords.
3. Verify projection health reports UP. A disabled projection can serve existing
   records but will not process new events.
4. Choose **Start GitHub installation**, then **Continue on GitHub**. Install the
   configured App on the account that owns the repository and grant repository access.
5. On return to the frontend Setup URL, re-enter the API accounts. Passwords are
   cleared by navigation and remain out of browser storage. Use the same connector
   username that began installation, then click **Complete connection** once.
6. Choose **Select repositories**, select `ChangeGuardHQ/changuard-platform`, and
   click **Save repository selection**. App-level access and ChangeGuard selection
   are both required. Note the displayed repository ID for optional list filtering.
7. Open **Changes** and leave **Live · 5s** enabled.

If setup state expires (default ten minutes), restart installation rather than
reusing a consumed callback. Changing the organization field cannot grant backend
permissions; it must match the server-owned reader/administrator bindings.

## 4. Exercise the lifecycle

Use a small test branch and ordinary repository activity:

| Action after selection | Expected UI behavior |
| --- | --- |
| Push a commit | A standalone COMMITTED change appears. |
| Open a PR | A PULL_REQUEST change appears with OPEN status and an opening timeline fact. |
| Merge the PR | The same PR UUID becomes MERGED, preserving opening and merge facts. |
| Redeliver the same webhook from GitHub | No extra business change or timeline fact. |

Commit records intentionally remain separate from PRs, even when a SHA matches a
head or merge commit. Late opening deliveries enrich a merge's timeline without
downgrading its status. Branch equality does not create a domain relationship.
Times come from provider occurrences; unknown facts remain absent.

Existing GitHub history is not backfilled. Only new activity delivered after
connection and selection is accepted. Build/deployment correlation is not part
of this visual test. Processing is asynchronous; use manual **Refresh** when
checking a delivery immediately.

## 5. Find a broken stage

- **401:** correct the appropriate service account on Connections.
- **403:** verify internal organization bindings, administrator/read permissions,
  selected repository, active installation and trusted GitHub owner ID.
- **GitHub setup 404:** start the connector with the opt-in GitHub override.
- **No delivery in GitHub's App delivery log:** verify subscriptions and public
  webhook forwarding. Check GitHub delivery status and response code.
- **Delivery accepted but no visible change:** verify outbox publication, consumer
  status, matching topic/Registry configuration and the organization/repository filter.
- **Projection disabled or unavailable:** check the consumer flag and backend logs;
  read API readiness alone does not prove event-processing progress.
- **Permanent failure:** inspect quarantine and repair/replay through the recovery
  guide. Do not delete receipts or advance offsets to fabricate success.
- **Unexpected HTML API response:** check the local proxy targets or use the
  deployed Caddy image, which owns the same-origin `/api` routes.

Use the UI's explicit error and last-updated states when assessing results. An
empty view is distinct from an unavailable or unauthorized API.
