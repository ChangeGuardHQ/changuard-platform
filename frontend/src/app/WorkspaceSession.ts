import { createContext, useContext } from 'react'
import type { BasicCredentials } from '../services/apiClient'

/** Tab-local identity; provider revisions isolate cached reads when accounts or organization change. */
export type WorkspaceSession = {
  organization: string
  change?: BasicCredentials
  connector?: BasicCredentials
}
type WorkspaceContextValue = {
  session: WorkspaceSession
  revision: number
  save: (session: WorkspaceSession) => Promise<void>
  forget: () => Promise<void>
}
export const WorkspaceContext = createContext<WorkspaceContextValue | null>(
  null,
)

export function useWorkspace() {
  const context = useContext(WorkspaceContext)
  if (!context) throw new Error('WorkspaceSessionProvider is required')
  return context
}
