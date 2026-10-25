import { useQuery, useQueryClient } from '@tanstack/react-query'
import { trackingKey, useWorkspace } from '../../app/WorkspaceSession'
import { apiClient, ClientDataError, retryRead } from '../../services/apiClient'
import { useTrackingAvailability } from './repositories'
import type { Change, ChangePage, ChangeTimeline } from './contracts'
import { isChange, isChangePage, isTimeline } from './contracts'

const poll = (query: { state: { error: Error | null } }) =>
  query.state.error && !retryRead(0, query.state.error) ? false : 5000

export function useChanges(page: number, live: boolean) {
  const { session, revision, scope } = useWorkspace()
  const cache = useQueryClient()
  const { ready } = useTrackingAvailability()
  const parameters = new URLSearchParams({
    organizationId: session.organization,
    page: String(page),
    size: '20',
  })
  if (scope?.kind === 'repository')
    parameters.set('repositoryId', scope.repositoryId)
  return useQuery<ChangePage, Error>({
    queryKey: [
      'changes',
      revision,
      session.organization,
      trackingKey(scope),
      page,
    ],
    enabled: Boolean(session.change && ready),
    retry: retryRead,
    refetchInterval: (query) => (live ? poll(query) : false),
    queryFn: async ({ signal }) => {
      const result = await apiClient.get<ChangePage>(
        `v1/changes?${parameters}`,
        { auth: session.change, signal },
      )
      // Do not render a misrouted response or silently broaden a single-repository request.
      if (!isChangePage(result))
        throw new ClientDataError(
          'The change list could not be read safely. No changes are displayed.',
        )
      if (
        !scope ||
        result.items.some(
          (change) =>
            change.organizationId !== session.organization ||
            (scope.kind === 'repository' &&
              change.repositoryId !== scope.repositoryId),
        )
      )
        throw new ClientDataError(
          'The response does not match the applied tracking scope. No changes are displayed.',
        )
      const lastPage = Math.max(0, result.totalPages - 1)
      if (page > lastPage) {
        // A previously cached destination page may still have the old count. Force a fresh read after correction.
        cache.removeQueries({
          queryKey: [
            'changes',
            revision,
            session.organization,
            trackingKey(scope),
            lastPage,
          ],
          exact: true,
          type: 'inactive',
        })
      }
      return result
    },
  })
}

export function useChange(id: string | undefined) {
  const { session, revision, scope } = useWorkspace()
  const { ready } = useTrackingAvailability()
  const parameters = new URLSearchParams({
    organizationId: session.organization,
  })
  const base = `v1/changes/${encodeURIComponent(id ?? '')}`
  const detail = useQuery<Change, Error>({
    queryKey: [
      'change',
      revision,
      session.organization,
      trackingKey(scope),
      id,
    ],
    enabled: Boolean(session.change && id && ready),
    retry: retryRead,
    refetchInterval: poll,
    queryFn: async ({ signal }) => {
      const result = await apiClient.get<Change>(`${base}?${parameters}`, {
        auth: session.change,
        signal,
      })
      if (!isChange(result))
        throw new ClientDataError(
          'The change detail could not be read safely. No change is displayed.',
        )
      if (
        !scope ||
        result.id !== id ||
        result.organizationId !== session.organization ||
        (scope.kind === 'repository' &&
          result.repositoryId !== scope.repositoryId)
      )
        throw new ClientDataError(
          'This change is outside the applied tracking scope. Choose the matching repository or Organization scope on Changes.',
        )
      return result
    },
  })
  const timeline = useQuery<ChangeTimeline, Error>({
    queryKey: [
      'timeline',
      revision,
      session.organization,
      trackingKey(scope),
      id,
    ],
    // A direct detail URL must pass the scope check before its timeline can be requested.
    enabled: Boolean(session.change && ready && detail.data && !detail.error),
    retry: retryRead,
    refetchInterval: poll,
    queryFn: async ({ signal }) => {
      const result = await apiClient.get<ChangeTimeline>(
        `${base}/timeline?${parameters}`,
        { auth: session.change, signal },
      )
      if (!isTimeline(result))
        throw new ClientDataError(
          'The timeline could not be read safely. No timeline is displayed.',
        )
      if (
        result.changeId !== id ||
        result.organizationId !== session.organization
      )
        throw new ClientDataError(
          'The timeline does not match this workspace and change. No timeline is displayed.',
        )
      return result
    },
  })
  return { detail, timeline, ready }
}
