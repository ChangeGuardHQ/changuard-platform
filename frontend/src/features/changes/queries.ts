import { useQuery } from '@tanstack/react-query'
import { useWorkspace } from '../../app/WorkspaceSession'
import { ApiError, apiClient } from '../../services/apiClient'
import type { Change, ChangePage, ChangeTimeline } from './contracts'

// Authentication and authorization failures need user action; background retry would only repeat them.
export const retryRead = (attempt: number, error: Error) =>
  !(error instanceof ApiError && error.status < 500) && attempt < 1
export function useChanges(page: number, repository: string, live: boolean) {
  const { session, revision } = useWorkspace()
  const parameters = new URLSearchParams({
    organizationId: session.organization,
    page: String(page),
    size: '20',
  })
  if (repository) parameters.set('repositoryId', repository)
  return useQuery({
    queryKey: ['changes', revision, session.organization, page, repository],
    queryFn: ({ signal }) =>
      apiClient.get<ChangePage>(`v1/changes?${parameters}`, {
        auth: session.change,
        signal,
      }),
    enabled: Boolean(session.change),
    retry: retryRead,
    refetchInterval: (query) =>
      live &&
      !(query.state.error instanceof ApiError && query.state.error.status < 500)
        ? 5000
        : false,
  })
}
export function useChange(id: string | undefined) {
  const { session, revision } = useWorkspace()
  const query = new URLSearchParams({ organizationId: session.organization })
  const base = `v1/changes/${encodeURIComponent(id ?? '')}`
  const options = {
    enabled: Boolean(session.change && id),
    retry: retryRead,
    refetchInterval: (query: { state: { error: Error | null } }) =>
      query.state.error && !retryRead(0, query.state.error)
        ? (false as const)
        : 5000,
  }
  return {
    detail: useQuery({
      ...options,
      queryKey: ['change', revision, session.organization, id],
      queryFn: ({ signal }) =>
        apiClient.get<Change>(`${base}?${query}`, {
          auth: session.change,
          signal,
        }),
    }),
    timeline: useQuery({
      ...options,
      queryKey: ['timeline', revision, session.organization, id],
      queryFn: ({ signal }) =>
        apiClient.get<ChangeTimeline>(`${base}/timeline?${query}`, {
          auth: session.change,
          signal,
        }),
    }),
  }
}
