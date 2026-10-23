import { Box, Button, Chip, Stack, Typography } from '@mui/material'
import { Link, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useWorkspace } from '../app/WorkspaceSession'
import { ApiFailure } from '../components/common/ApiFeedback'
import { ApiAccountsForm } from '../features/connections/ApiAccountsForm'
import {
  GitHubCallback,
  GitHubSetup,
} from '../features/connections/GitHubSetup'
import { retryRead } from '../features/changes/queries'
import { apiClient } from '../services/apiClient'
import { usePageTitle } from '../hooks/usePageTitle'

type ProjectionHealth = {
  status: string
  components?: { codeEventConsumer?: { details?: { enabled?: boolean } } }
}
/** Only readiness reported by the service is shown; saved credentials alone do not imply connectivity. */
export function ConnectionsPage() {
  usePageTitle('Connections')
  const { session, revision } = useWorkspace()
  const callback = useLocation().pathname.endsWith('/github/callback')
  const health = useQuery({
    queryKey: ['projection-health', revision],
    enabled: Boolean(session.change),
    retry: retryRead,
    queryFn: ({ signal }) =>
      apiClient.get<ProjectionHealth>('projection-health', {
        auth: session.change,
        signal,
      }),
    refetchInterval: (query) =>
      query.state.error && !retryRead(0, query.state.error) ? false : 10000,
  })
  const paused =
    health.data?.components?.codeEventConsumer?.details?.enabled === false
  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Connections</Typography>
        <Typography color="text.secondary" sx={{ mt: 1 }}>
          Connect your workspace, choose a repository, and watch real code
          activity arrive.
        </Typography>
      </Box>
      <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
        {health.data && (
          <Chip
            label={
              paused
                ? 'Projection disabled'
                : `Projection ${health.data.status}`
            }
            color={
              paused
                ? 'warning'
                : health.data.status === 'UP'
                  ? 'success'
                  : 'warning'
            }
          />
        )}
        {session.change && (
          <Button component={Link} to="/changes" size="small">
            View changes
          </Button>
        )}
      </Stack>
      {health.error && (
        <ApiFailure error={health.error} retry={() => void health.refetch()} />
      )}
      <ApiAccountsForm key={`accounts-${revision}`} />
      {callback ? (
        <GitHubCallback key={`callback-${revision}`} />
      ) : (
        <GitHubSetup key={`github-${revision}`} />
      )}
    </Stack>
  )
}
