import { useState } from 'react'
import { Link } from 'react-router-dom'
import { AccountTreeRounded, GitHub, RefreshRounded } from '@mui/icons-material'
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  FormControlLabel,
  Paper,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TablePagination,
  Typography,
} from '@mui/material'
import { formatTime } from '../utils/formatTime'
import { trackingKey, useWorkspace } from '../app/WorkspaceSession'
import { ApiFailure, ConnectPrompt } from '../components/common/ApiFeedback'
import { useChanges } from '../features/changes/queries'
import {
  TrackingPaused,
  TrackingScopePicker,
} from '../features/changes/TrackingScopePicker'
import { useTrackingAvailability } from '../features/changes/repositories'
import { accessDenied } from '../services/apiClient'
import { usePageTitle } from '../hooks/usePageTitle'

/** The applied tab scope is explicit and survives navigation; form drafts do not affect displayed results. */
export function ChangesPage() {
  usePageTitle('Changes')
  const { session, revision, scope } = useWorkspace()
  const { ready, repository } = useTrackingAvailability()
  return (
    <Stack spacing={3}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        sx={{ justifyContent: 'space-between', gap: 2 }}
      >
        <Box>
          <Chip
            icon={<AccountTreeRounded />}
            label="CODE LIFECYCLE"
            size="small"
            sx={{ mb: 1.5, bgcolor: 'primary.light', color: 'primary.dark' }}
          />
          <Typography variant="h1">Changes</Typography>
          <Typography color="text.secondary" sx={{ mt: 1 }}>
            Follow code activity in one organization or one repository.
          </Typography>
        </Box>
        <Button
          component={Link}
          to="/connections"
          startIcon={<GitHub />}
          sx={{ alignSelf: 'flex-start' }}
        >
          Connections
        </Button>
      </Stack>
      {!session.change ? (
        <ConnectPrompt />
      ) : (
        <>
          <TrackingScopePicker />
          {!scope ? (
            <Alert severity="info">
              Choose and apply a tracking scope to load changes.
            </Alert>
          ) : !ready ? (
            <TrackingPaused />
          ) : (
            // Scope/account changes start at page zero and cannot reuse another scope's in-flight results.
            <ChangesResults
              repositoryName={repository?.fullName}
              key={`${revision}:${session.organization}:${trackingKey(scope)}`}
            />
          )}
        </>
      )}
    </Stack>
  )
}

function ChangesResults({ repositoryName }: { repositoryName?: string }) {
  const { session, scope } = useWorkspace()
  const [page, setPage] = useState(0)
  const [live, setLive] = useState(true)
  const query = useChanges(page, live)
  const data = accessDenied(query.error) ? undefined : query.data
  const lastPage = data ? Math.max(0, data.totalPages - 1) : page
  const correctingPage = page > lastPage
  // Correct only a newly out-of-range page; the guarded adjustment converges before committing misleading empty results.
  if (correctingPage) setPage(lastPage)
  return (
    <>
      <Paper sx={{ p: 2.5 }}>
        <Stack
          direction={{ xs: 'column', md: 'row' }}
          sx={{
            justifyContent: 'space-between',
            gap: 2,
            alignItems: { md: 'center' },
          }}
        >
          <Box sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>
            <Typography variant="overline" color="text.secondary">
              Applied tracking scope
            </Typography>
            <Typography sx={{ fontWeight: 600 }}>
              {scope?.kind === 'repository'
                ? `Repository: ${repositoryName ?? scope.fullName} · ID ${scope.repositoryId}`
                : `Organization: ${session.organization}`}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {data
                ? `${data.totalElements} ${data.totalElements === 1 ? 'change' : 'changes'}`
                : 'Reading selected scope'}
            </Typography>
          </Box>
          <Stack
            direction="row"
            sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}
          >
            <FormControlLabel
              control={
                <Switch
                  checked={live}
                  onChange={(event) => setLive(event.target.checked)}
                />
              }
              label="Live · 5s"
            />
            <Button
              startIcon={<RefreshRounded />}
              disabled={query.isFetching}
              onClick={() => void query.refetch()}
            >
              Refresh
            </Button>
          </Stack>
        </Stack>
      </Paper>
      {query.error && (
        <ApiFailure error={query.error} retry={() => void query.refetch()} />
      )}
      {(query.isLoading || correctingPage) && (
        <Stack direction="row" sx={{ gap: 2 }} role="status">
          <CircularProgress size={22} />
          <span>Loading changes…</span>
        </Stack>
      )}
      {data && !correctingPage && (
        <>
          {query.isRefetchError && (
            <Alert severity="warning">
              Showing the last successful response for this scope. Live updates
              are unavailable.
            </Alert>
          )}
          {data.items.length === 0 ? (
            <Paper sx={{ p: 5, textAlign: 'center' }}>
              <Typography variant="h2">
                {scope?.kind === 'repository'
                  ? 'No changes for this repository'
                  : 'No changes in this organization'}
              </Typography>
              <Typography color="text.secondary" sx={{ mt: 1.5 }}>
                New activity appears after webhook processing. Existing GitHub
                history is not imported.
              </Typography>
              <Button component={Link} to="/connections" sx={{ mt: 2 }}>
                Manage event intake
              </Button>
            </Paper>
          ) : (
            <TableContainer component={Paper}>
              <Table aria-label="Changes">
                <TableHead>
                  <TableRow>
                    <TableCell>Change</TableCell>
                    <TableCell>Repository</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>Activity</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.items.map((change) => (
                    <TableRow hover key={change.id}>
                      <TableCell>
                        <Button
                          component={Link}
                          to={`/changes/${encodeURIComponent(change.id)}`}
                          sx={{
                            textAlign: 'left',
                            justifyContent: 'flex-start',
                            p: 0,
                          }}
                        >
                          {change.title ||
                            (change.kind === 'PULL_REQUEST'
                              ? `Pull request #${change.pullRequestNumber}`
                              : `Commit ${change.commitSha.slice(0, 8)}`)}
                        </Button>
                        <Typography
                          variant="caption"
                          color="text.secondary"
                          sx={{ display: 'block', mt: 0.5 }}
                        >
                          {change.kind === 'PULL_REQUEST'
                            ? `PR #${change.pullRequestNumber}`
                            : 'Commit'}{' '}
                          · {change.commitSha.slice(0, 8)}
                        </Typography>
                      </TableCell>
                      <TableCell>{change.repositoryFullName}</TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          label={change.status}
                          color={
                            change.status === 'MERGED'
                              ? 'secondary'
                              : change.status === 'OPEN'
                                ? 'primary'
                                : 'default'
                          }
                        />
                      </TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>
                        {formatTime(change.activityAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
          <TablePagination
            component="div"
            count={data.totalElements}
            page={page}
            rowsPerPage={20}
            rowsPerPageOptions={[20]}
            onPageChange={(_, next) => setPage(next)}
            labelDisplayedRows={({ from, to, count }) =>
              `${from}–${to} of ${count}`
            }
            slotProps={{
              actions: {
                nextButton: {
                  disabled:
                    page >= 10000 || (page + 1) * 20 >= data.totalElements,
                },
              },
            }}
          />
          <Typography variant="caption" color="text.secondary">
            Last updated{' '}
            {formatTime(new Date(query.dataUpdatedAt).toISOString())}. Commit
            records remain separate from pull requests.
          </Typography>
        </>
      )}
    </>
  )
}
