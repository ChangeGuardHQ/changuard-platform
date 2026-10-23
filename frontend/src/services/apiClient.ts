export type BasicCredentials = { username: string; password: string }
export type RequestOptions = {
  auth?: BasicCredentials
  signal?: AbortSignal
  jsonBody?: string
  headers?: Record<string, string>
}

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(
  /\/+$/,
  '',
)
const identifierFields = new Set([
  'repositoryId',
  'ownerId',
  'installationId',
  'githubAccountId',
])

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/** Preserve Java long identifiers before JSON numbers lose precision in the browser. */
export function parseApiJson(text: string): unknown {
  return JSON.parse(
    text,
    (key: string, value: unknown, context?: { source?: string }) => {
      if (!identifierFields.has(key) || typeof value !== 'number') return value
      const source = context?.source
      if (source && /^[1-9]\d*$/.test(source)) return source
      if (Number.isSafeInteger(value) && value > 0) return String(value)
      throw new Error(
        'This browser cannot read the repository identifier safely. Use a current browser.',
      )
    },
  )
}

function authorization(auth: BasicCredentials): string {
  if (auth.username.includes(':'))
    throw new Error('Account names cannot contain a colon.')
  // Basic uses UTF-8 bytes; credentials never enter URLs, browser storage, query keys, or build configuration.
  const bytes = new TextEncoder().encode(`${auth.username}:${auth.password}`)
  return `Basic ${btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(''))}`
}

async function request<T>(
  method: string,
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const response = await fetch(`${apiBaseUrl}/${path.replace(/^\/+/, '')}`, {
    method,
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      // Suppress browser Basic-auth dialogs; the page provides explicit credential and error states.
      'X-Requested-With': 'XMLHttpRequest',
      ...(options.auth ? { Authorization: authorization(options.auth) } : {}),
      ...(options.jsonBody !== undefined
        ? { 'Content-Type': 'application/json' }
        : {}),
      ...options.headers,
    },
    signal: options.signal,
    ...(options.jsonBody !== undefined ? { body: options.jsonBody } : {}),
  })
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: 'Request rejected. Check the input, or restart GitHub setup if its state has expired.',
      409: 'The GitHub connection changed. Refresh it before trying again.',
      401: 'Authentication failed. Check this service’s account on Connections.',
      403: 'Access denied. Check the organization binding and account permissions.',
      404: 'The resource is unavailable for this organization.',
      503: 'The service is unavailable. Check the backend and try again.',
    }
    throw new ApiError(
      response.status,
      messages[response.status] ??
        `API request failed with ${response.status} ${response.statusText}`,
    )
  }
  if (response.status === 204) return undefined as T
  if (!response.headers.get('content-type')?.includes('json')) {
    throw new Error(
      'The API returned an unexpected response. Check the development proxy targets.',
    )
  }
  return parseApiJson(await response.text()) as T
}

export const apiClient = {
  get: <T>(path: string, options?: RequestOptions) =>
    request<T>('GET', path, options),
  post: <T>(path: string, options?: RequestOptions) =>
    request<T>('POST', path, options),
  put: <T>(path: string, options?: RequestOptions) =>
    request<T>('PUT', path, options),
}
