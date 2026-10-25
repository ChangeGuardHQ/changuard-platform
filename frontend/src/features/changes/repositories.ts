import { useQuery } from '@tanstack/react-query'
import { useWorkspace } from '../../app/WorkspaceSession'
import {
  ClientDataError,
  retryRead,
  validProviderId,
} from '../../services/apiClient'
import { githubApi } from '../connections/api'

export type TrackedRepository = { repositoryId: string; fullName: string }

/** Resolve all saved active selections atomically; partial or failed reads never imply an empty catalog. */
export function useRepositoryCatalog(enabled = true) {
  const { session, revision } = useWorkspace()
  return useQuery<TrackedRepository[], Error>({
    queryKey: ['tracking-repositories', revision, session.organization],
    enabled: enabled && Boolean(session.connector),
    // Revalidate saved intake promptly without borrowing the application's general query-cache lifetime.
    staleTime: 30000,
    // Returning from GitHub re-checks saved selections even when general dashboard focus refresh is disabled.
    refetchOnWindowFocus: 'always',
    retry: retryRead,
    refetchInterval: (query) =>
      query.state.error && !retryRead(0, query.state.error) ? false : 15000,
    queryFn: async ({ signal }) => {
      const options = { auth: session.connector, signal }
      const integrations = await githubApi.list(session.organization, options)
      const selections = await Promise.all(
        integrations
          .filter((item) => item.status === 'ACTIVE')
          .map(async (item) => {
            const repositories = await githubApi.selected(
              session.organization,
              item.integrationId,
              options,
            )
            if (
              !Array.isArray(repositories) ||
              repositories.some(
                (repo) =>
                  !repo || !['ACTIVE', 'DISCONNECTED'].includes(repo.status),
              )
            )
              throw new ClientDataError(
                'The repository selection could not be read safely.',
              )
            return repositories.filter((repo) => repo.status === 'ACTIVE')
          }),
      )
      const unique = new Map<string, TrackedRepository>()
      for (const repository of selections.flat()) {
        if (
          typeof repository.repositoryId !== 'string' ||
          !validProviderId(repository.repositoryId) ||
          typeof repository.fullName !== 'string' ||
          !repository.fullName.trim()
        )
          throw new ClientDataError(
            'The connector returned an invalid repository identifier or name. Refresh Connections.',
          )
        // The same repository can occur in multiple installations; its provider ID, not its name, is its identity.
        unique.set(repository.repositoryId, {
          repositoryId: repository.repositoryId,
          fullName: repository.fullName,
        })
      }
      return [...unique.values()].sort(
        (a, b) =>
          a.fullName.localeCompare(b.fullName) ||
          a.repositoryId.localeCompare(b.repositoryId),
      )
    },
  })
}

/** Organization history needs no intake catalog; a repository scope requires a fresh, active saved selection. */
export function useTrackingAvailability() {
  const { session, scope } = useWorkspace()
  const catalog = useRepositoryCatalog(scope?.kind === 'repository')
  const repository =
    scope?.kind === 'repository'
      ? catalog.data?.find((repo) => repo.repositoryId === scope.repositoryId)
      : undefined
  const ready = Boolean(
    scope &&
    (scope.kind === 'organization' ||
      (session.connector &&
        catalog.isSuccess &&
        !catalog.error &&
        !catalog.isStale &&
        repository)),
  )
  // Names are derived metadata only. A late catalog response must never write back into workspace identity/scope.
  return { ready, catalog, repository }
}
