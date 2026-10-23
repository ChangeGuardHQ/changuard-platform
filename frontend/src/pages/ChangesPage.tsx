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
  TextField,
  Typography,
} from '@mui/material'
import { formatTime } from '../utils/formatTime'
import { useWorkspace } from '../app/WorkspaceSession'
import { ApiFailure, ConnectPrompt } from '../components/common/ApiFeedback'
import { useChanges } from '../features/changes/queries'
import { usePageTitle } from '../hooks/usePageTitle'

/** The list shows only projected backend records; polling exposes eventual consistency after a webhook. */
export function ChangesPage() {
  usePageTitle('Changes')
  const { session } = useWorkspace()
  const [page, setPage] = useState(0)
  const [filter, setFilter] = useState('')
  const [repository, setRepository] = useState('')
  const [live, setLive] = useState(true)
  const query = useChanges(page, repository, live)
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
            Follow pull requests and commits as they arrive from your connected
            repositories.
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
          <Paper sx={{ p: 2.5 }}>
            <Stack
              direction={{ xs: 'column', md: 'row' }}
              sx={{
                justifyContent: 'space-between',
                gap: 2,
                alignItems: { md: 'center' },
              }}
            >
              <Box>
                <Typography variant="overline" color="text.secondary">
                  Organization
                </Typography>
                <Typography sx={{ fontWeight: 600 }}>
                  {session.organization}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {query.data
                    ? `${query.data.totalElements} changes`
                    : 'Reading workspace'}
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
          <Box
            component="form"
            onSubmit={(event) => {
              event.preventDefault()
              setPage(0)
              setRepository(filter.trim())
            }}
          >
            <Stack
              direction="row"
              sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}
            >
              <TextField
                label="Repository ID"
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
                size="small"
                placeholder="All repositories"
                helperText="Use the ID shown on Connections"
              />
              <Button
                type="submit"
                variant="outlined"
                sx={{ alignSelf: 'flex-start' }}
              >
                Filter
              </Button>
              {repository && (
                <Button
                  sx={{ alignSelf: 'flex-start' }}
                  onClick={() => {
                    setFilter('')
                    setRepository('')
                    setPage(0)
                  }}
                >
                  Clear
                </Button>
              )}
            </Stack>
          </Box>
          {query.error && (
            <ApiFailure
              error={query.error}
              retry={() => void query.refetch()}
            />
          )}
          {query.isLoading && (
            <Stack direction="row" sx={{ gap: 2 }} role="status">
              <CircularProgress size={22} />
              <span>Loading changes…</span>
            </Stack>
          )}
          {query.data && (
            <>
              {query.isRefetchError && (
                <Alert severity="warning">
                  Showing the last successful response. Live updates are
                  unavailable.
                </Alert>
              )}
              {query.data.items.length === 0 ? (
                <Paper sx={{ p: 5, textAlign: 'center' }}>
                  <Typography variant="h2">
                    {repository
                      ? 'No changes for this repository'
                      : 'Your next change starts here'}
                  </Typography>
                  <Typography color="text.secondary" sx={{ mt: 1.5 }}>
                    Select a repository on Connections, then push a commit or
                    open a pull request.
                  </Typography>
                  <Typography
                    variant="body2"
                    color="text.secondary"
                    sx={{ mt: 1 }}
                  >
                    New activity appears after processing. Existing GitHub
                    history is not imported.
                  </Typography>
                  <Button component={Link} to="/connections" sx={{ mt: 2 }}>
                    Manage repositories
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
                      {query.data.items.map((change) => (
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
                count={query.data.totalElements}
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
                        page >= 10000 ||
                        (page + 1) * 20 >= query.data.totalElements,
                    },
                  },
                }}
              />
              <Typography variant="caption" color="text.secondary">
                Last updated{' '}
                {formatTime(new Date(query.dataUpdatedAt).toISOString())}.
                Commit records remain separate from pull requests.
              </Typography>
            </>
          )}
        </>
      )}
    </Stack>
  )
}
