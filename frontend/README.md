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

Set `VITE_API_BASE_URL` to the API Gateway / BFF base URL when it is available. The default
`/api` path is suitable when the frontend is served behind a same-origin reverse proxy.

## Commands

```sh
npm run dev        # Start the Vite development server
npm run typecheck  # Run the TypeScript project checks
npm run lint       # Run Oxlint
npm run build      # Type-check and build production assets
npm run preview    # Preview the production build locally
```

See [the frontend architecture](../docs/architecture/frontend.md) for app boundaries,
state ownership, and the API integration direction.
