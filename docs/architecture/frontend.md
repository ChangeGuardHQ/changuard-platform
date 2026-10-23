# Frontend architecture

## Position in the platform

```text
Browser (React + TypeScript + Material UI)
  ├── app shell, routes, and shared UI
  ├── feature pages and user interactions
  └── API client + TanStack Query
             │ HTTPS / JSON
             ▼
       API Gateway / BFF
        ├── Change Service
        ├── Audit Service
        └── Query Service
             │
             └── platform services and read models
                    └── Kafka event stream
```

This diagram is the production target; the Gateway/BFF remains planned. For local
visual testing, the browser uses the same-origin Vite `/api` proxy to call scoped
change reads and GitHub connection APIs. Vite forwards authentication and CSRF,
without injecting privileged credentials or disabling backend security. The browser
does not connect to Kafka or databases. See [visual testing](../visual-testing.md).

## Frontend boundaries

| Area | Responsibility |
| --- | --- |
| `src/app/` | App composition, routing, MUI theme, and shared TanStack Query configuration |
| `src/components/` | Reusable presentation components and the persistent application shell |
| `src/features/` | Domain-oriented UI grouped by capability |
| `src/pages/` | Route-level page composition |
| `src/hooks/` | Reusable React behavior shared across features |
| `src/services/` | Transport boundary for API requests; no UI state or rendering |
| `src/types/` | Shared frontend contracts and navigation types |

## State and data flow

- React Router owns URL and page navigation state.
- TanStack Query owns remote/server state, including caching, retries, and refetch policy.
- Local component state is reserved for transient interaction state.
- API credentials live in the tab-scoped workspace context. Changing identity cancels
  requests and clears queries; secrets never become URL, storage, or query-key state.
- Installation completion and repository selection are explicit mutations with CSRF
  where required. Single-use callback completion is never polled or automatically retried.
- API calls flow through `src/services/apiClient.ts`; feature hooks should call that boundary
  and expose typed query results to page components.
- Current feature types mirror the service DTOs. Preserve nullable facts and provider
  identifier precision. Do not infer commit/PR relationships or lifecycle state in the browser.
- Production API response types should move behind the Gateway/BFF as that boundary is implemented.
- `VITE_API_BASE_URL` configures the API base URL. Only public frontend configuration belongs
  in `VITE_` variables. App keys and deployment secrets remain server-side; local API
  passwords are entered at runtime and kept only in the workspace context.

## Adding a feature

1. Add the feature UI and feature-specific types under `src/features/<feature>/`.
2. Add query hooks under the feature or `src/hooks/` when shared; use the API client for I/O.
3. Compose the route in `src/app/AppRoutes.tsx` and add navigation in the shared app shell as
   appropriate.
4. Keep loading, empty, and API error states explicit; do not substitute fabricated success data.
