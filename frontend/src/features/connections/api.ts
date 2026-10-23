import { apiClient } from '../../services/apiClient'
import type { BasicCredentials, RequestOptions } from '../../services/apiClient'

// Connector numeric identifiers are decoded into exact decimal strings by the transport.
export type GitHubIntegration = {
  integrationId: string
  organizationId: string
  installationId: string
  status: 'ACTIVE' | 'DISCONNECTED' | 'REVOKED'
  githubAccountId: string | null
  githubAccountLogin: string | null
}
export type Repository = {
  repositoryId: string
  fullName: string
  defaultBranch: string | null
}
export type SelectedRepository = Repository & {
  status: 'ACTIVE' | 'DISCONNECTED'
}
const root = (organization: string) =>
  `v1/organizations/${encodeURIComponent(organization)}/integrations/github`

async function write<T>(
  method: 'post' | 'put',
  path: string,
  auth: BasicCredentials,
  jsonBody?: string,
): Promise<T> {
  // Use the returned token, not the HttpOnly cookie value; the browser sends the same-origin cookie itself.
  const csrf = await apiClient.get<{ headerName: string; token: string }>(
    'v1/integrations/github/csrf',
    { auth },
  )
  if (!/^X-[A-Z0-9-]+$/i.test(csrf.headerName))
    throw new Error('Unexpected CSRF response from connector.')
  const options: RequestOptions = {
    auth,
    jsonBody,
    headers: { [csrf.headerName]: csrf.token },
  }
  // Keep dispatch explicit so runtime method selection cannot resolve arbitrary object properties.
  return method === 'post'
    ? apiClient.post<T>(path, options)
    : apiClient.put<T>(path, options)
}
export const githubApi = {
  list: (organization: string, options: RequestOptions) =>
    apiClient.get<GitHubIntegration[]>(root(organization), options),
  available: (
    organization: string,
    integration: string,
    options: RequestOptions,
  ) =>
    apiClient.get<Repository[]>(
      `${root(organization)}/${encodeURIComponent(integration)}/available-repositories`,
      options,
    ),
  selected: (
    organization: string,
    integration: string,
    options: RequestOptions,
  ) =>
    apiClient.get<SelectedRepository[]>(
      `${root(organization)}/${encodeURIComponent(integration)}/repositories`,
      options,
    ),
  start: (organization: string, auth: BasicCredentials) =>
    write<{ installationUrl: string; expiresAt: string }>(
      'post',
      `${root(organization)}/installations`,
      auth,
    ),
  complete: (state: string, installation: string, auth: BasicCredentials) => {
    const parameters = new URLSearchParams({
      state,
      installation_id: installation,
    })
    return apiClient.get<GitHubIntegration>(
      `v1/integrations/github/callback?${parameters}`,
      { auth },
    )
  },
  select: (
    organization: string,
    integration: string,
    ids: string[],
    auth: BasicCredentials,
  ) => {
    for (const id of ids) {
      if (!/^[1-9]\d*$/.test(id) || BigInt(id) > 9223372036854775807n)
        throw new Error('Invalid repository identifier.')
    }
    // Write validated decimal literals directly; converting Java long identifiers through Number would round them.
    return write<void>(
      'put',
      `${root(organization)}/${encodeURIComponent(integration)}/repositories`,
      auth,
      `{"repositoryIds":[${ids.join(',')}]}`,
    )
  },
}
