import { Link, useParams } from 'react-router-dom'
import { ArrowBackRounded, RefreshRounded } from '@mui/icons-material'
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Paper,
  Stack,
  Typography,
} from '@mui/material'
import { formatTime } from '../utils/formatTime'
import { useWorkspace } from '../app/WorkspaceSession'
import { ApiFailure, ConnectPrompt } from '../components/common/ApiFeedback'
import { useChange } from '../features/changes/queries'
import { usePageTitle } from '../hooks/usePageTitle'

/** Current state and historical facts are fetched separately; each retains its own failure state. */
export function ChangeDetailPage() {
  const { id } = useParams()
  const { session } = useWorkspace()
  const { detail, timeline } = useChange(id)
  usePageTitle('Change details')
  const change = detail.data
  return (
    <Stack spacing={3}>
      <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
        <Button component={Link} to="/changes" startIcon={<ArrowBackRounded />}>
          All changes
        </Button>
        {session.change && (
          <Button
            startIcon={<RefreshRounded />}
            disabled={detail.isFetching || timeline.isFetching}
            onClick={() => {
              void detail.refetch()
              void timeline.refetch()
            }}
          >
            Refresh
          </Button>
        )}
      </Stack>
      {!session.change ? (
        <ConnectPrompt />
      ) : (
        <>
          {detail.isLoading && <CircularProgress aria-label="Loading change" />}
          {detail.error && (
            <ApiFailure
              error={detail.error}
              retry={() => void detail.refetch()}
            />
          )}
          {change && (
            <>
              <Box>
                <Stack direction="row" sx={{ gap: 1, mb: 2 }}>
                  <Chip label={change.status} color="primary" size="small" />
                  <Chip
                    label={
                      change.kind === 'PULL_REQUEST'
                        ? `Pull request #${change.pullRequestNumber}`
                        : 'Standalone commit'
                    }
                    size="small"
                    variant="outlined"
                  />
                </Stack>
                <Typography variant="h1">
                  {change.title || change.commitSha.slice(0, 8)}
                </Typography>
                <Typography color="text.secondary" sx={{ mt: 1 }}>
                  {change.repositoryFullName}
                </Typography>
              </Box>
              <Paper sx={{ p: 3 }}>
                <Typography variant="h2" sx={{ mb: 2 }}>
                  Change details
                </Typography>
                <Box
                  component="dl"
                  sx={{
                    m: 0,
                    display: 'grid',
                    gridTemplateColumns: { xs: '1fr', sm: '160px 1fr' },
                    gap: 1.5,
                    '& dt': { color: 'text.secondary', fontSize: 14 },
                    '& dd': { m: 0, overflowWrap: 'anywhere' },
                  }}
                >
                  <dt>Change ID</dt>
                  <dd>{change.id}</dd>
                  <dt>Organization</dt>
                  <dd>{change.organizationId}</dd>
                  <dt>Commit SHA</dt>
                  <dd>
                    <code>{change.commitSha}</code>
                  </dd>
                  {change.headCommitSha && (
                    <>
                      <dt>Opening head SHA</dt>
                      <dd>
                        <code>{change.headCommitSha}</code>
                      </dd>
                    </>
                  )}
                  <dt>Branches</dt>
                  <dd>
                    {change.sourceBranch || 'Not reported'}
                    {change.targetBranch ? ` → ${change.targetBranch}` : ''}
                  </dd>
                  <dt>Actor</dt>
                  <dd>
                    {change.actor
                      ? `${change.actor.id} (${change.actor.type})`
                      : 'Not reported'}
                  </dd>
                  <dt>Activity time</dt>
                  <dd>{formatTime(change.activityAt)}</dd>
                  <dt>First received</dt>
                  <dd>{formatTime(change.receivedAt)}</dd>
                </Box>
              </Paper>
            </>
          )}
          <Paper sx={{ p: 3 }}>
            <Typography variant="h2" sx={{ mb: 1 }}>
              Timeline
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
              Provider occurrence times. Repeated deliveries do not add
              duplicate facts.
            </Typography>
            {timeline.isLoading && (
              <CircularProgress aria-label="Loading timeline" size={24} />
            )}
            {timeline.error && (
              <ApiFailure
                error={timeline.error}
                retry={() => void timeline.refetch()}
              />
            )}
            {timeline.data?.entries.length === 0 && (
              <Typography color="text.secondary">
                No lifecycle facts have been recorded.
              </Typography>
            )}
            <Stack divider={<Divider />} spacing={2}>
              {timeline.data?.entries.map((entry) => (
                <Stack
                  key={`${entry.type}:${entry.eventId}`}
                  direction="row"
                  spacing={2}
                >
                  <Box
                    aria-hidden="true"
                    sx={{
                      mt: 0.75,
                      width: 10,
                      height: 10,
                      borderRadius: '50%',
                      bgcolor: 'secondary.main',
                      flexShrink: 0,
                    }}
                  />
                  <Box sx={{ minWidth: 0 }}>
                    <Typography sx={{ fontWeight: 600 }}>
                      {(
                        {
                          PullRequestOpened: 'Pull request opened',
                          PullRequestMerged: 'Pull request merged',
                          CommitCreated: 'Commit created',
                        } as Record<string, string>
                      )[entry.type] || entry.type}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {formatTime(entry.occurredAt)} ·{' '}
                      {entry.actor?.id || 'Actor not reported'}
                    </Typography>
                    <Typography
                      variant="body2"
                      component="code"
                      sx={{ display: 'block', overflowWrap: 'anywhere', mt: 1 }}
                    >
                      {entry.commitSha}
                    </Typography>
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ overflowWrap: 'anywhere' }}
                    >
                      Event {entry.eventId}
                    </Typography>
                  </Box>
                </Stack>
              ))}
            </Stack>
          </Paper>
        </>
      )}
    </Stack>
  )
}
