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

The browser talks only to frontend-oriented APIs exposed by the API Gateway / BFF. It does not
connect to Kafka, databases, or individual domain services. The BFF aggregates read models so
the frontend does not need to coordinate calls across backend services.

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
- API calls flow through `src/services/apiClient.ts`; feature hooks should call that boundary
  and expose typed query results to page components.
- API response types should reflect the API Gateway / BFF contract. Do not reconstruct domain
  relationships in the browser when the BFF can provide the required read model.
- `VITE_API_BASE_URL` configures the API base URL. Only public frontend configuration belongs
  in `VITE_` variables; secrets must remain server-side.

## Adding a feature

1. Add the feature UI and feature-specific types under `src/features/<feature>/`.
2. Add query hooks under the feature or `src/hooks/` when shared; use the API client for I/O.
3. Compose the route in `src/app/AppRoutes.tsx` and add navigation in the shared app shell as
   appropriate.
4. Keep loading, empty, and API error states explicit; do not substitute fabricated success data.
