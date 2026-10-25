import { QueryClient } from '@tanstack/react-query'

// Share one browser cache across routes; feature hooks override freshness, polling and authorization-aware retries.
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: 1,
      staleTime: 30_000,
    },
  },
})
