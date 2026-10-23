import { useState } from 'react'
import type { ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { WorkspaceContext } from './WorkspaceSession'
import type { WorkspaceSession } from './WorkspaceSession'

const defaultOrganization = import.meta.env.VITE_ORGANIZATION_ID || 'local'

/** Local visual-test authentication is tab-scoped; production identity belongs at the gateway/BFF. */
export function WorkspaceSessionProvider({
  children,
}: {
  children: ReactNode
}) {
  const cache = useQueryClient()
  const [session, setSession] = useState<WorkspaceSession>({
    organization: defaultOrganization,
  })
  const [revision, setRevision] = useState(0)
  async function save(next: WorkspaceSession) {
    // Cancel before clearing: data from a prior account or tenant must not populate the new session.
    await cache.cancelQueries()
    cache.clear()
    setRevision((value) => value + 1)
    setSession(next)
  }
  return (
    <WorkspaceContext.Provider
      value={{
        session,
        revision,
        save,
        forget: () => save({ organization: session.organization }),
      }}
    >
      {children}
    </WorkspaceContext.Provider>
  )
}
