# ChangeGuard frontend

The frontend is a React and TypeScript single-page application built with Vite and Material UI.
It sits behind the API Gateway / BFF and consumes aggregated read APIs; it does not access
Kafka or backend services directly.

## Local development

```sh
npm install
cp .env.example .env.local
npm run dev
```

To run the frontend in Docker from the repository root:

```sh
docker compose up --build
```

Open <http://localhost:5173>. The
[Compose setup](../docker-compose.yaml) also starts the Connector Service on
port 8081 and Kafka on port 9092. To run only the frontend, use
`docker compose up --build frontend`. Set `FRONTEND_PORT` before starting
Compose to use a different host port if 5173 is already in use.

Set `VITE_API_BASE_URL` to the API Gateway / BFF base URL when it is available. The default
`/api` path is suitable when the frontend is served behind a same-origin reverse proxy.

## Commands

```sh
npm run dev        # Start the Vite development server
npm run typecheck  # Run the TypeScript project checks
npm run lint       # Run Oxlint
npm test           # Run unit and integration tests
npm run build      # Type-check and build production assets
npm run preview    # Preview the production build locally
```

See [the frontend architecture](../docs/architecture/frontend.md) for app boundaries,
state ownership, and the API integration direction.

## CI checks

[Frontend CI](../.github/workflows/frontend_ci.yml) runs lint, TypeScript checks,
all Vitest unit/integration tests, and the production build. It uploads JUnit
test reports. Independent jobs run the npm dependency audit, CodeQL with
extended JavaScript/TypeScript security queries, Trivy dependency/secret/
configuration scans, and the Docker build, HTTP startup check, and image scan.
The npm audit and Trivy scans fail on HIGH or CRITICAL findings. Security JSON
reports are uploaded even when a scan fails; CodeQL publishes results to GitHub
code scanning.

Frontend, workflow, and Compose changes trigger checks, and a weekly schedule
refreshes scans. External actions are pinned to commit IDs. CI and Docker use
Node.js 24.21.0, and CI runners use Ubuntu 24.04. Docker pins the Node image to
`24.21.0-alpine3.24`. Direct dependencies use exact versions matching the lockfile;
`.npmrc` also saves exact versions for future installs. The Docker dependency
stage installs npm packages, and the runtime
stage launches the Vite development server directly without bundled npm/Yarn
tooling. Its health check calls the development server. The separate build step
verifies the production assets.
