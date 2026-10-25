# AWS visual testing

Run the existing application on **one Ubuntu EC2 instance**. The compiled React
frontend and a small Caddy proxy share one container; only HTTPS/HTTP are public.
PostgreSQL, Kafka, Schema Registry and the two APIs run on the internal Docker network.
The GitHub App delivers directly to EC2; no local webhook forwarder is needed.

This is a single-host visual-test environment with persistent Docker volumes.
It does not add EKS, managed databases, load balancers, a separate gateway service,
or a new authentication system. Existing service authorization, CSRF protection,
webhook signatures, migrations and event processing remain active.

## 1. Launch EC2

- Choose **Ubuntu Server 24.04 LTS, x86_64**, at least **8 GiB RAM** and **40 GiB
  encrypted gp3 storage**. A `t3.large` is one example; the memory allowance includes
  the JVMs, broker and building images on the instance. Smaller instances are untested.
- Enable a public IPv4 address on a public subnet with internet access.
- Set inbound security-group rules: TCP **22 from your IP**, and TCP **80/443 from
  the internet**. Leave backend, database, broker and Registry ports closed.
- Optionally paste [ec2-user-data.sh](ec2-user-data.sh) into **Advanced details → User
  data** before launch. It installs Docker Engine, Compose, Buildx and Git from the
  official repositories. Run `sudo cloud-init status --wait` after connecting, then
  reconnect once so the `ubuntu` user's Docker group membership takes effect.
  If Docker is already installed, skip this bootstrap.

Use [AWS security groups](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-security-groups.html)
to control ingress. The bootstrap follows [Docker's Ubuntu installation guide](https://docs.docker.com/engine/install/ubuntu/).

## 2. Choose the temporary hostname

Copy **Public IPv4 address** from the EC2 console. For example, an instance at
`203.0.113.10` uses `203.0.113.10.sslip.io`. Replace the documentation IP with yours.
Set this bare hostname as `SITE_ADDRESS`; do not include a scheme, path or port.
You can later replace it with a domain whose DNS points at the same instance.

[sslip.io](https://sslip.io/) resolves IP-based hostnames without registering a
domain. This temporary DNS depends on that public service. [Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https)
obtains and renews certificates when the hostname resolves and ports 80/443 reach
the instance. Keep SSL verification enabled in GitHub and your browser.

An automatically assigned EC2 public IP can change after stop/start. Update
`SITE_ADDRESS` and the GitHub App URLs after such a change, then recreate the
frontend container. See [AWS instance addressing](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-instance-addressing.html).

## 3. Copy the application and private configuration

Clone the repository revision containing this deployment directory onto EC2, then
work from the repository root. Authenticate Git to GitHub using your usual method.

```sh
# These files stay on EC2; the template contains no working credentials.
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
```

Edit `deploy/.env`:

| Variable | Value |
| --- | --- |
| `SITE_ADDRESS` | Your EC2-IP hostname, e.g. `YOUR_PUBLIC_IP.sslip.io`. |
| `ORGANIZATION_ID` | Internal ChangeGuard organization; default `visual-test`. |
| `APP_USERNAME`, `APP_PASSWORD` | One private test account used by both APIs. |
| `POSTGRES_PASSWORD` | Separate database password. |
| `GITHUB_APP_ID`, `GITHUB_APP_SLUG` | Your registered App's ID and slug. |
| `GITHUB_CONNECTION_ACCOUNT_ID` | Numeric ID of the GitHub account **owning the repository**, e.g. `ChangeGuardHQ`. |
| `GITHUB_WEBHOOK_SECRET` | Same value configured on the GitHub App. |
| `GITHUB_INSTALLATION_STATE_SECRET` | Separate random secret, at least 32 bytes. |
| `GITHUB_APP_PRIVATE_KEY_FILE` | Absolute path of the external PEM on EC2. |

Run `openssl rand -hex 32` separately for each password/secret. Private values
belong in the ignored `deploy/.env`, never in frontend build arguments or EC2 user data.
For the organization `ChangeGuardHQ`, obtain its numeric account ID with:

```sh
curl -fsSL https://api.github.com/orgs/ChangeGuardHQ \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
```

Securely copy your downloaded GitHub App PEM to EC2 outside the checkout. For
example, install it at the template's path with ownership matching the connector:

```sh
# Replace the first path with your uploaded App key, not an EC2 SSH key.
sudo install -D -m 0400 -o 10001 -g 10001 /path/to/uploaded-github-app.pem \
  /opt/changeguard-secrets/github-app.pem
```

The connector mounts this file read-only as a Compose secret. It supports GitHub's
downloaded RSA PEM directly. The other services never receive the key.

## 4. Start the stack

```sh
# Validate required values without printing the resolved secrets.
docker compose --env-file deploy/.env -f deploy/compose.yaml config --quiet
# Build serially to leave enough memory for JVMs on a small test instance.
COMPOSE_PARALLEL_LIMIT=1 docker compose --env-file deploy/.env -f deploy/compose.yaml \
  up --build --wait --wait-timeout 300 -d
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
```

Open `https://YOUR_PUBLIC_IP.sslip.io/connections`. Certificates can take a short
time after the frontend starts; inspect its logs if HTTPS is not available yet.
The container liveness probe confirms Caddy is running, not that ACME issuance or
every business dependency is healthy. The Connections page reports projection health.

## 5. Point the GitHub App at EC2

In the App owner's **Settings → Developer settings → GitHub Apps → Edit**, set:

| GitHub setting | Value |
| --- | --- |
| Homepage URL | `https://YOUR_PUBLIC_IP.sslip.io` |
| Setup URL | `https://YOUR_PUBLIC_IP.sslip.io/connections/github/callback` |
| Webhook URL | `https://YOUR_PUBLIC_IP.sslip.io/api/v1/integrations/github/webhook` |
| Webhook secret | Your `GITHUB_WEBHOOK_SECRET`. |
| Repository permissions | Metadata, Contents, Pull requests: read-only. |
| Event subscriptions | Push and Pull request. |

Leave **Request user authorization (OAuth) during installation** unchecked for
this App-authenticated flow. See [GitHub App settings](https://docs.github.com/en/apps/maintaining-github-apps/modifying-a-github-app-registration).

On **Connections**, enter your organization and the **same `APP_USERNAME` and
`APP_PASSWORD` in both API account pairs**. Apply accounts, start GitHub
installation, follow the returned link, and grant access to the repository. After
returning, re-enter those accounts, complete the connection and save the repository
selection. Accounts stay in tab memory and clear when leaving for GitHub.

Open **Changes**. Push a commit, open a PR and merge it. Expect a standalone
`COMMITTED` change and a PR changing from `OPEN` to `MERGED` with its stored timeline.
Only activity delivered after connection/selection is ingested. See
[lifecycle expectations](../docs/visual-testing.md#4-exercise-the-lifecycle).

## Update, inspect and stop

Run these commands from the repository root:

```sh
# Review and check out the intended revision before rebuilding.
COMPOSE_PARALLEL_LIMIT=1 docker compose --env-file deploy/.env -f deploy/compose.yaml \
  up --build --wait --wait-timeout 300 -d
docker compose --env-file deploy/.env -f deploy/compose.yaml logs --tail 100 frontend connector-service change-service
# Stop containers and retain PostgreSQL, Kafka and certificate volumes.
docker compose --env-file deploy/.env -f deploy/compose.yaml down
```

Do not add `--volumes` when retaining test history. This stack uses a distinct
`changeguard-visual` Compose project, separate from local development data.
The image tags represent the current build, so check out a known revision and
rebuild to roll back application code; database migrations may require a separate
recovery procedure. Keep test data disposable or export it before terminating EC2.

Stopping containers leaves the EC2 instance running. Stop/terminate the AWS test
resources when finished; review retained EBS/public-IP resources separately.

## Verification and troubleshooting

```sh
# Configuration tests require only Docker Compose; no credentials or running AWS resources.
python3 -m unittest discover -s deploy -p 'test_*.py'
```

- **HTTPS unavailable:** verify the public IP/hostname, ports 80/443, DNS resolution
  and frontend logs. Public CAs and shared temporary DNS can impose rate limits;
  retain the certificate volume and retry after correcting the cause.
- **502:** inspect API health/startup logs; the proxy does not hide backend failures.
- **401/403:** verify the saved account and organization match `deploy/.env`.
- **Setup callback error:** start installation from this deployment's Connections
  page and complete it with the same administrator before state expires.
- **No new changes:** inspect GitHub App recent deliveries, connector/outbox logs
  and projection health. Both producer and consumer are enabled by this stack.
