import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Alert,
  Box,
  Button,
  FormControlLabel,
  Paper,
  Radio,
  RadioGroup,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { trackingKey, useWorkspace } from '../../app/WorkspaceSession'
import { ApiFailure } from '../../components/common/ApiFeedback'
import { useRepositoryCatalog } from './repositories'

/** Draft controls never change the applied scope or query until the user explicitly applies them. */
export function TrackingScopePicker() {
  const { scope } = useWorkspace()
  // Applying a different scope resets drafts; a repository rename retains the same immutable identity.
  return <ScopeForm key={trackingKey(scope)} />
}

function ScopeForm() {
  const { session, scope, track } = useWorkspace()
  const [mode, setMode] = useState<'organization' | 'repository'>(
    scope?.kind ?? 'organization',
  )
  const [repositoryId, setRepositoryId] = useState(
    scope?.kind === 'repository' ? scope.repositoryId : '',
  )
  const catalog = useRepositoryCatalog(mode === 'repository')
  const repository = catalog.data?.find(
    (item) => item.repositoryId === repositoryId,
  )
  const validRepository = Boolean(
    session.connector &&
    catalog.isSuccess &&
    !catalog.error &&
    !catalog.isStale &&
    repository,
  )
  return (
    <Paper component="section" aria-label="Tracking scope" sx={{ p: 2.5 }}>
      <Stack spacing={2}>
        <Typography variant="h2">Choose what to track</Typography>
        <Typography variant="body2" color="text.secondary">
          Workspace: {session.organization}. This is your internal ChangeGuard
          organization ID.
        </Typography>
        <Box
          component="form"
          onSubmit={(event) => {
            event.preventDefault()
            if (mode === 'organization') track({ kind: 'organization' })
            else if (validRepository && repository)
              track({ kind: 'repository', ...repository })
          }}
        >
          <Stack spacing={2}>
            <RadioGroup
              row
              aria-label="Tracking mode"
              value={mode}
              onChange={(event) => {
                if (
                  event.target.value === 'organization' ||
                  event.target.value === 'repository'
                )
                  setMode(event.target.value)
              }}
            >
              <FormControlLabel
                value="organization"
                control={<Radio />}
                label="Organization"
              />
              <FormControlLabel
                value="repository"
                control={<Radio />}
                label="Single repository"
              />
            </RadioGroup>
            {mode === 'organization' ? (
              <Typography variant="body2">
                Show all recorded changes in workspace {session.organization},
                including history from disconnected repositories. New activity
                is accepted only from repositories selected on Connections.
              </Typography>
            ) : (
              <>
                {!session.connector ? (
                  <Alert severity="info">
                    Apply a GitHub connector account on Connections to choose a
                    named repository.
                  </Alert>
                ) : (
                  <>
                    {catalog.error && (
                      <ApiFailure
                        error={catalog.error}
                        retry={() => void catalog.refetch()}
                      />
                    )}
                    <TextField
                      select
                      id="tracking-repository"
                      label="Repository"
                      value={repositoryId}
                      onChange={(event) => setRepositoryId(event.target.value)}
                      disabled={catalog.isLoading || Boolean(catalog.error)}
                      slotProps={{
                        select: { native: true },
                        inputLabel: { shrink: true },
                      }}
                      helperText="Only saved, active selections are listed. IDs preserve identity across renames and distinguish repositories recreated with the same name."
                    >
                      <option value="">
                        {catalog.isLoading
                          ? 'Loading repositories…'
                          : 'Choose one repository'}
                      </option>
                      {repositoryId && !repository && (
                        <option value={repositoryId} disabled>
                          Previously selected repository · ID {repositoryId} ·
                          unavailable
                        </option>
                      )}
                      {catalog.data?.map((repo) => (
                        <option
                          key={repo.repositoryId}
                          value={repo.repositoryId}
                        >
                          {repo.fullName} · ID {repo.repositoryId}
                        </option>
                      ))}
                    </TextField>
                    {/* Native selects truncate long names on mobile; show the complete draft choice before Apply. */}
                    {repository && (
                      <Typography
                        variant="body2"
                        sx={{ overflowWrap: 'anywhere' }}
                      >
                        Repository choice: {repository.fullName} · ID{' '}
                        {repository.repositoryId}
                      </Typography>
                    )}
                    {catalog.isSuccess &&
                      !catalog.error &&
                      catalog.data.length === 0 && (
                        <Alert severity="info">
                          No active repositories are selected for intake. Save a
                          repository selection on Connections first.
                        </Alert>
                      )}
                  </>
                )}
              </>
            )}
            <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
              <Button
                type="submit"
                variant="contained"
                disabled={
                  !session.change || (mode === 'repository' && !validRepository)
                }
              >
                Apply tracking scope
              </Button>
              <Button component={Link} to="/connections">
                Manage event intake
              </Button>
              {mode === 'repository' && session.connector && (
                <Button
                  disabled={catalog.isFetching}
                  onClick={() => void catalog.refetch()}
                >
                  Refresh repository choices
                </Button>
              )}
            </Stack>
          </Stack>
        </Box>
        <Typography variant="caption" color="text.secondary">
          Applying a scope changes this tab’s view. Repository intake is
          configured separately on Connections.
        </Typography>
      </Stack>
    </Paper>
  )
}

/** Never replace an unavailable single-repository scope with organization-wide results. */
export function TrackingPaused() {
  return (
    <Alert severity="warning">
      Tracking is paused for this repository while its saved connection is
      unavailable or cannot be verified. Choose an active repository or
      explicitly apply Organization scope on Changes.
    </Alert>
  )
}
