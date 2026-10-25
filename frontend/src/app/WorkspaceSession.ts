import { createContext, useContext } from 'react'
import type { BasicCredentials } from '../services/apiClient'

/** Tab-local identity; provider revisions isolate cached reads when accounts or organization change. */
export type WorkspaceSession = {
  organization: string
  change?: BasicCredentials
  connector?: BasicCredentials
}
/** One applied read scope per tab; a repository is identified by its immutable provider ID. */
export type TrackingScope =
  | { kind: 'organization' }
  | { kind: 'repository'; repositoryId: string; fullName: string }

export const trackingKey = (scope: TrackingScope | null) =>
  scope?.kind === 'repository'
    ? `repository:${scope.repositoryId}`
    : (scope?.kind ?? 'unselected')

type WorkspaceContextValue = {
  session: WorkspaceSession
  revision: number
  save: (session: WorkspaceSession) => Promise<void>
  forget: () => Promise<void>
  scope: TrackingScope | null
  track: (scope: TrackingScope) => void
}
export const WorkspaceContext = createContext<WorkspaceContextValue | null>(
  null,
)

export function useWorkspace() {
  const context = useContext(WorkspaceContext)
  if (!context) throw new Error('WorkspaceSessionProvider is required')
  return context
}
