import { useState } from 'react'
import type { ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { WorkspaceContext } from './WorkspaceSession'
import type { TrackingScope, WorkspaceSession } from './WorkspaceSession'

// This public build value seeds the form; authorization still comes from the backend's organization bindings.
const defaultOrganization = import.meta.env.VITE_ORGANIZATION_ID || 'local'

/** Visual-test authentication is tab-scoped; hosted tests use the same service accounts over HTTPS. */
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
  const [scope, setScope] = useState<TrackingScope | null>(null)
  async function save(next: WorkspaceSession) {
    // Cancel before clearing: data from a prior account or tenant must not populate the new session.
    await cache.cancelQueries()
    cache.clear()
    // An account/organization change requires a fresh explicit scope; never carry a repository into another tenant.
    setScope(null)
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
        scope,
        track: setScope,
      }}
    >
      {children}
    </WorkspaceContext.Provider>
  )
}
