import { StrictMode } from 'react'
import { ThemeProvider } from '@mui/material'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach } from 'vitest'
import { AppRoutes } from '../app/AppRoutes'
import { WorkspaceSessionProvider } from '../app/WorkspaceSessionProvider'
import { theme } from '../app/theme'

const clients: QueryClient[] = []
afterEach(() => {
  clients.splice(0).forEach((client) => client.clear())
})

/** Exercise real routing and account/cache lifetimes, including StrictMode callback safety. */
export function renderApp(initialPath = '/') {
  const cache = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  clients.push(cache)
  return {
    cache,
    ...render(
      <StrictMode>
        <ThemeProvider theme={theme}>
          <QueryClientProvider client={cache}>
            <WorkspaceSessionProvider>
              <MemoryRouter initialEntries={[initialPath]}>
                <AppRoutes />
              </MemoryRouter>
            </WorkspaceSessionProvider>
          </QueryClientProvider>
        </ThemeProvider>
      </StrictMode>,
    ),
  }
}
