import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
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
import { retryRead } from '../changes/queries'
import { githubApi } from './api'
import type { GitHubIntegration, Repository } from './api'

/** Saving replaces the selected set; inaccessible active repositories require explicit deselection. */
export function RepositorySelection({
  integration,
}: {
  integration: GitHubIntegration
}) {
  const { session, revision } = useWorkspace()
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
  const blocked = [...selected].some((id) => !availableIds.has(id))
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
      {query.data && (
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
          <Typography variant="body2" color="text.secondary">
            {selected.size} selected. Saving replaces this installation’s
            selection. An empty selection stops intake for all its repositories.
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
          Repository selection saved. New pushes and pull requests can now
          appear on Changes.
        </Alert>
      )}
    </Stack>
  )
}
