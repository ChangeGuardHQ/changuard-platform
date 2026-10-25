import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  Box,
  Button,
  Checkbox,
  CircularProgress,
  FormControlLabel,
  Stack,
  Typography,
} from '@mui/material'
import { useWorkspace } from '../../app/WorkspaceSession'
import { ApiFailure } from '../../components/common/ApiFeedback'
import {
  accessDenied,
  ClientDataError,
  retryRead,
  validProviderId,
} from '../../services/apiClient'
import { githubApi } from './api'
import type { GitHubIntegration, Repository } from './api'

/** Saving replaces the selected set; inaccessible active repositories require explicit deselection. */
export function RepositorySelection({
  integration,
}: {
  integration: GitHubIntegration
}) {
  const { session, revision } = useWorkspace()
  const cache = useQueryClient()
  // null follows the last saved selection; an empty Set is an explicit draft to stop this installation's intake.
  const [draft, setDraft] = useState<Set<string> | null>(null)
  const [saved, setSaved] = useState(false)
  const query = useQuery({
    queryKey: [
      'repositories',
      revision,
      session.organization,
      integration.integrationId,
    ],
    retry: retryRead,
    queryFn: async ({ signal }) => {
      const options = { auth: session.connector, signal }
      // A complete-set save needs both catalogs; either read failing must keep the combined result unavailable.
      const [available, selected] = await Promise.all([
        githubApi.available(
          session.organization,
          integration.integrationId,
          options,
        ),
        githubApi.selected(
          session.organization,
          integration.integrationId,
          options,
        ),
      ])
      // Invalid metadata must become a visible read failure, never a destructive empty selection or a render crash.
      if (
        !Array.isArray(available) ||
        !Array.isArray(selected) ||
        [...available, ...selected].some(
          (repo) =>
            !repo ||
            typeof repo.repositoryId !== 'string' ||
            !validProviderId(repo.repositoryId) ||
            typeof repo.fullName !== 'string' ||
            !repo.fullName.trim() ||
            (repo.defaultBranch != null &&
              typeof repo.defaultBranch !== 'string'),
        ) ||
        selected.some(
          (repo) => !['ACTIVE', 'DISCONNECTED'].includes(repo.status),
        )
      )
        throw new ClientDataError(
          'The repository list could not be read safely. Refresh repositories before saving.',
        )
      return { available, selected }
    },
  })
  const selected =
    draft ??
    new Set(
      query.data?.selected
        .filter((repo) => repo.status === 'ACTIVE')
        .map((repo) => repo.repositoryId) ?? [],
    )
  const availableIds = new Set(
    query.data?.available.map((repo) => repo.repositoryId) ?? [],
  )
  const unavailable =
    query.data?.selected.filter(
      (repo) =>
        repo.status === 'ACTIVE' && !availableIds.has(repo.repositoryId),
    ) ?? []
  const repositories: Repository[] = [
    ...(query.data?.available ?? []),
    ...unavailable,
  ]
  // Require an explicit decision about inaccessible selections before sending the replacement set.
  const blocked = [...selected].some((id) => !availableIds.has(id))
  // An acknowledged save can retain its accepted draft after a failed verification read; that draft is not unsaved.
  const dirty = draft !== null && !saved
  const save = useMutation({
    retry: false,
    mutationFn: async () => {
      if (!session.connector)
        throw new Error('Connect a GitHub connector account first.')
      const accepted = [...selected]
      await githubApi.select(
        session.organization,
        integration.integrationId,
        accepted,
        session.connector,
      )
      return accepted
    },
    onSuccess: async (accepted) => {
      const result = await query.refetch()
      setSaved(true)
      // A failed read after a successful save must not visually restore the previous selection.
      setDraft(result.error ? new Set(accepted) : null)
      // A saved intake change must invalidate the named repository picker, including removals.
      await cache.invalidateQueries({
        queryKey: ['tracking-repositories', revision, session.organization],
      })
    },
  })
  return (
    <Stack spacing={2}>
      {query.isLoading && (
        <CircularProgress aria-label="Loading repositories" size={24} />
      )}
      {query.error && (
        <ApiFailure error={query.error} retry={() => void query.refetch()} />
      )}
      {query.data && !accessDenied(query.error) && (
        <>
          {repositories.length === 0 && (
            <Alert severity="info">
              No repositories are available. Check this GitHub App
              installation’s repository access, then refresh.
            </Alert>
          )}
          {unavailable.length > 0 && (
            <Alert severity="warning">
              Some selected repositories are no longer accessible. Restore
              GitHub access or deselect them before saving.
            </Alert>
          )}
          <Stack spacing={1}>
            {repositories.map((repo) => (
              <Box key={repo.repositoryId}>
                <FormControlLabel
                  sx={{
                    width: '100%',
                    m: 0,
                    alignItems: 'flex-start',
                    '& .MuiFormControlLabel-label': {
                      minWidth: 0,
                      overflowWrap: 'anywhere',
                    },
                  }}
                  control={
                    <Checkbox
                      checked={selected.has(repo.repositoryId)}
                      disabled={save.isPending}
                      onChange={(event) => {
                        const next = new Set(selected)
                        if (event.target.checked) next.add(repo.repositoryId)
                        else next.delete(repo.repositoryId)
                        setDraft(next)
                        setSaved(false)
                      }}
                    />
                  }
                  label={
                    <Box>
                      <Typography>
                        {repo.fullName}
                        {!availableIds.has(repo.repositoryId)
                          ? ' · unavailable'
                          : ''}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        Repository ID {repo.repositoryId} ·{' '}
                        {repo.defaultBranch || 'No default branch'}
                      </Typography>
                    </Box>
                  }
                />
              </Box>
            ))}
          </Stack>
          <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
            <Button
              disabled={
                save.isPending ||
                query.isFetching ||
                query.isError ||
                availableIds.size === 0
              }
              onClick={() => {
                // Include current repositories without silently dropping an inaccessible saved selection.
                setDraft(new Set([...selected, ...availableIds]))
                setSaved(false)
              }}
            >
              Select all available repositories
            </Button>
            <Button
              disabled={
                save.isPending ||
                query.isFetching ||
                query.isError ||
                selected.size === 0
              }
              onClick={() => {
                setDraft(new Set())
                setSaved(false)
              }}
            >
              Clear selection
            </Button>
          </Stack>
          {dirty && (
            <Alert severity="info">
              Unsaved intake selection. Save to apply these changes.
            </Alert>
          )}
          <Typography variant="body2" color="text.secondary">
            {selected.size} selected. Saving replaces this installation’s
            selection. An empty selection stops intake for all its repositories.
            Selecting all includes currently available repositories;
            repositories created later need a new saved selection.
          </Typography>
          <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
            <Button
              variant="contained"
              disabled={
                save.isPending || blocked || query.isFetching || query.isError
              }
              onClick={() => save.mutate()}
            >
              Save repository selection
            </Button>
            <Button
              disabled={save.isPending || query.isFetching}
              onClick={() => {
                setDraft(null)
                setSaved(false)
                void query.refetch()
              }}
            >
              Refresh repositories
            </Button>
          </Stack>
        </>
      )}
      {save.error && <ApiFailure error={save.error} />}
      {saved && (
        <Alert severity="success">
          Repository selection saved.{' '}
          {selected.size === 0
            ? 'No repositories are enabled for new activity.'
            : `${selected.size} ${selected.size === 1 ? 'repository is' : 'repositories are'} enabled for new activity.`}
          {query.error
            ? ' Refresh repositories to verify the saved selection before choosing a tracking scope.'
            : ' Choose a tracking scope on Changes to view their activity.'}
        </Alert>
      )}
    </Stack>
  )
}
