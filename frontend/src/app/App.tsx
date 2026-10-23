import { CssBaseline, ThemeProvider } from '@mui/material'
import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import { AppRoutes } from './AppRoutes'
import { queryClient } from './queryClient'
import { theme } from './theme'
import { WorkspaceSessionProvider } from './WorkspaceSessionProvider'

function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        {/* API accounts and caches share one tab-scoped lifetime across route navigation. */}
        <WorkspaceSessionProvider>
          <BrowserRouter>
            <AppRoutes />
          </BrowserRouter>
        </WorkspaceSessionProvider>
      </QueryClientProvider>
    </ThemeProvider>
  )
}

export default App
