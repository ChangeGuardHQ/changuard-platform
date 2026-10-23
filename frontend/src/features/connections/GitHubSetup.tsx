import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Paper,
  Stack,
  Typography,
} from '@mui/material'
import { GitHub, RefreshRounded } from '@mui/icons-material'
import { useWorkspace } from '../../app/WorkspaceSession'
import { ApiFailure, ConnectPrompt } from '../../components/common/ApiFeedback'
import { retryRead } from '../changes/queries'
import { githubApi } from './api'
import { RepositorySelection } from './RepositorySelection'

export function GitHubSetup() {
  const { session, revision } = useWorkspace()
  const [installationUrl, setInstallationUrl] = useState('')
  const query = useQuery({
    queryKey: ['github', revision, session.organization],
    enabled: Boolean(session.connector),
    retry: retryRead,
    queryFn: ({ signal }) =>
      githubApi.list(session.organization, { auth: session.connector, signal }),
  })
  const start = useMutation({
    retry: false,
    mutationFn: async () => {
      if (!session.connector)
        throw new Error('Connect the GitHub connector account first.')
      const installation = await githubApi.start(
        session.organization,
        session.connector,
      )
      const target = new URL(installation.installationUrl)
      // An unexpected backend URL must never become a credential-phishing or script link.
      if (
        target.origin !== 'https://github.com' ||
        !/^\/apps\/[^/]+\/installations\/new$/.test(target.pathname)
      )
        throw new Error(
          'The connector returned an unexpected GitHub installation address.',
        )
      return target.href
    },
    onSuccess: setInstallationUrl,
  })
  return (
    <Stack spacing={3}>
      <Paper sx={{ p: 3 }}>
        <Stack spacing={2}>
          <Typography variant="h2">2. Connect GitHub</Typography>
          <Typography color="text.secondary" variant="body2">
            Install the configured ChangeGuard GitHub App for the account that
            owns your repository.
          </Typography>
          {!session.connector ? (
            <ConnectPrompt service="GitHub connector" />
          ) : (
            <>
              <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
                <Button
                  startIcon={<GitHub />}
                  variant="outlined"
                  disabled={start.isPending || query.isError || query.isLoading}
                  onClick={() => {
                    setInstallationUrl('')
                    start.mutate()
                  }}
                >
                  Start GitHub installation
                </Button>
                {installationUrl && (
                  <Button
                    component="a"
                    href={installationUrl}
                    variant="contained"
                  >
                    Continue on GitHub
                  </Button>
                )}
              </Stack>
              {installationUrl && (
                <Alert severity="info">
                  After returning from GitHub, re-enter your API accounts to
                  complete the connection. This keeps passwords out of browser
                  storage.
                </Alert>
              )}
              {start.error && <ApiFailure error={start.error} />}
            </>
          )}
        </Stack>
      </Paper>
      <Paper sx={{ p: 3 }}>
        <Stack spacing={2.5}>
          <Stack
            direction="row"
            sx={{ alignItems: 'center', justifyContent: 'space-between' }}
          >
            <Typography variant="h2">3. Select repositories</Typography>
            <Button
              startIcon={<RefreshRounded />}
              disabled={!session.connector || query.isFetching}
              onClick={() => void query.refetch()}
            >
              Refresh
            </Button>
          </Stack>
          {query.isLoading && (
            <CircularProgress
              aria-label="Loading GitHub connections"
              size={24}
            />
          )}
          {query.error && (
            <>
              <ApiFailure
                error={query.error}
                retry={() => void query.refetch()}
              />
              <Typography variant="body2" color="text.secondary">
                For local testing, configure the GitHub App on the connector and
                enable connection management before retrying.
              </Typography>
            </>
          )}
          {query.data?.length === 0 && (
            <Alert severity="info">
              No GitHub installation is connected to {session.organization} yet.
              Start an installation above.
            </Alert>
          )}
          {query.data?.map((integration) => (
            <Box key={integration.integrationId}>
              <Stack
                direction="row"
                sx={{ gap: 1, alignItems: 'center', mb: 1 }}
              >
                <Typography sx={{ fontWeight: 600 }}>
                  {integration.githubAccountLogin || 'GitHub installation'}
                </Typography>
                <Chip
                  size="small"
                  label={integration.status}
                  color={
                    integration.status === 'ACTIVE' ? 'success' : 'default'
                  }
                />
              </Stack>
              <Typography variant="caption" color="text.secondary">
                Installation {integration.installationId}
              </Typography>
              <Divider sx={{ my: 2 }} />
              {integration.status === 'ACTIVE' ? (
                <RepositorySelection integration={integration} />
              ) : (
                <Alert severity="warning">
                  This installation is inactive. Complete a new installation
                  before selecting repositories.
                </Alert>
              )}
            </Box>
          ))}
          {!session.connector && (
            <Typography color="text.secondary">
              Apply a GitHub connector account above to load connections.
            </Typography>
          )}
        </Stack>
      </Paper>
      <Button component={Link} to="/changes" sx={{ alignSelf: 'flex-start' }}>
        View incoming changes
      </Button>
    </Stack>
  )
}

/** Completion consumes single-use state: explicit mutation, no automatic effect, polling, or retry. */
export function GitHubCallback() {
  const { session, save } = useWorkspace()
  const [parameters] = useSearchParams()
  const navigate = useNavigate()
  const state = parameters.get('state') || ''
  const installation = parameters.get('installation_id') || ''
  const valid = Boolean(
    state && state.length <= 2048 && /^[1-9]\d{0,18}$/.test(installation),
  )
  const complete = useMutation({
    retry: false,
    mutationFn: () => {
      if (!session.connector || !valid)
        throw new Error(
          'A valid GitHub callback and connector account are required.',
        )
      return githubApi.complete(state, installation, session.connector)
    },
  })
  return (
    <Paper sx={{ p: 3 }}>
      <Stack spacing={2}>
        <Typography variant="h2">Complete GitHub connection</Typography>
        {!valid ? (
          <Alert severity="error">
            The callback is missing valid installation details. Restart GitHub
            installation from Connections.
          </Alert>
        ) : (
          <Typography color="text.secondary">
            Apply the same connector account that started this installation,
            then complete it before its setup state expires.
          </Typography>
        )}
        {!session.connector && (
          <Alert severity="info">
            Apply your GitHub connector account in the form above before
            completing setup.
          </Alert>
        )}
        <Button
          variant="contained"
          disabled={
            !valid ||
            !session.connector ||
            complete.isPending ||
            complete.isSuccess
          }
          onClick={() => complete.mutate()}
        >
          Complete connection
        </Button>
        {complete.error && <ApiFailure error={complete.error} />}
        {complete.data && (
          <>
            <Alert severity="success">
              GitHub connected for {complete.data.organizationId}. Select the
              repository you want to observe next.
            </Alert>
            <Button
              onClick={async () => {
                await save({
                  ...session,
                  organization: complete.data.organizationId,
                })
                // Remove the consumed state from browser history when leaving the callback.
                navigate('/connections', { replace: true })
              }}
            >
              Select repositories
            </Button>
          </>
        )}
      </Stack>
    </Paper>
  )
}
