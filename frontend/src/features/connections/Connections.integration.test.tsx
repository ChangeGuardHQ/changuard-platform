import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/renderApp'
import { jsonResponse } from '../../test/fixtures'

const integration = {
  integrationId: 'installation-a',
  organizationId: 'local',
  installationId: 123,
  status: 'ACTIVE',
  githubAccountId: 99,
  githubAccountLogin: 'ChangeGuardHQ',
}
async function connectorAccount() {
  fireEvent.change(screen.getByLabelText('GitHub connector password'), {
    target: { value: 'test-admin-pass' },
  })
  await userEvent.click(screen.getByRole('button', { name: 'Apply accounts' }))
}

/** Test explicit setup mutations, complete-set repository selection, CSRF and callback single use. */
describe('GitHub connections', () => {
  it('saves a selected repository with its exact provider ID and the fetched CSRF token', async () => {
    let saved = false
    const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
      if (path.includes('/csrf'))
        return jsonResponse({
          headerName: 'X-XSRF-TOKEN',
          token: 'masked-csrf',
        })
      if (options.method === 'PUT') {
        saved = true
        return new Response(null, { status: 204 })
      }
      if (path.endsWith('/available-repositories'))
        return new Response(
          '[{"repositoryId":9007199254740993,"ownerId":99,"fullName":"ChangeGuardHQ/changuard-platform","defaultBranch":"main"}]',
          { headers: { 'Content-Type': 'application/json' } },
        )
      if (path.endsWith('/repositories'))
        return jsonResponse(
          saved
            ? [
                {
                  repositoryId: '9007199254740993',
                  fullName: 'ChangeGuardHQ/changuard-platform',
                  defaultBranch: 'main',
                  status: 'ACTIVE',
                },
              ]
            : [],
        )
      return jsonResponse([integration])
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await connectorAccount()
    const checkbox = await screen.findByRole('checkbox', {
      name: /ChangeGuardHQ\/changuard-platform/,
    })
    expect(
      screen.getByText(/Repository ID 9007199254740993/),
    ).toBeInTheDocument()
    await userEvent.click(checkbox)
    await userEvent.click(
      screen.getByRole('button', { name: 'Save repository selection' }),
    )
    expect(
      await screen.findByText(/Repository selection saved/),
    ).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/installation-a/repositories'),
      expect.objectContaining({
        method: 'PUT',
        body: '{"repositoryIds":[9007199254740993]}',
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'masked-csrf' }),
      }),
    )
  })

  it('completes callback state once through an explicit action, including under StrictMode', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(integration))
    vi.stubGlobal('fetch', fetchMock)
    renderApp(
      '/connections/github/callback?installation_id=123&state=opaque-state',
    )
    await connectorAccount()
    expect(fetchMock).not.toHaveBeenCalled()
    await userEvent.click(
      screen.getByRole('button', { name: 'Complete connection' }),
    )
    expect(
      await screen.findByText(/GitHub connected for local/),
    ).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0]).toEqual(
      expect.arrayContaining([
        '/api/v1/integrations/github/callback?state=opaque-state&installation_id=123',
      ]),
    )
    expect(
      screen.getByRole('button', { name: 'Complete connection' }),
    ).toBeDisabled()
    await userEvent.click(
      screen.getByRole('button', { name: 'Select repositories' }),
    )
    expect(
      await screen.findByRole('heading', { name: '2. Connect GitHub' }),
    ).toBeInTheDocument()
  })

  it('does not silently remove an inaccessible previously selected repository', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string) => {
        if (path.endsWith('/available-repositories')) return jsonResponse([])
        if (path.endsWith('/repositories'))
          return jsonResponse([
            {
              repositoryId: '12345',
              fullName: 'acme/private',
              defaultBranch: 'main',
              status: 'ACTIVE',
            },
          ])
        return jsonResponse([integration])
      }),
    )
    renderApp('/connections')
    await connectorAccount()
    const checkbox = await screen.findByRole('checkbox', {
      name: /acme\/private/,
    })
    expect(checkbox).toBeChecked()
    expect(
      screen.getByRole('button', { name: 'Save repository selection' }),
    ).toBeDisabled()
    await userEvent.click(checkbox)
    expect(
      screen.getByRole('button', { name: 'Save repository selection' }),
    ).toBeEnabled()
  })

  it('rejects a non-GitHub installation URL rather than navigating to it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string, options: RequestInit) => {
        if (path.includes('/csrf'))
          return jsonResponse({ headerName: 'X-XSRF-TOKEN', token: 'token' })
        if (options.method === 'POST')
          return jsonResponse({
            installationUrl: 'https://untrusted.example/install',
            expiresAt: '2026-10-23T15:00:00Z',
          })
        return jsonResponse([])
      }),
    )
    renderApp('/connections')
    await connectorAccount()
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Start GitHub installation' }),
      ).toBeEnabled(),
    )
    await userEvent.click(
      screen.getByRole('button', { name: 'Start GitHub installation' }),
    )
    expect(
      await screen.findByText(/unexpected GitHub installation address/),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: 'Continue on GitHub' }),
    ).not.toBeInTheDocument()
  })

  it.each([
    'installation_id=0&state=ok',
    'installation_id=01&state=ok',
    'installation_id=9223372036854775808&state=ok',
    'installation_id=123&installation_id=456&state=ok',
    'installation_id=123&state=first&state=second',
    'installation_id=123&state=%20%20',
  ])(
    'rejects ambiguous or invalid callback parameters without consuming state: %s',
    async (query) => {
      const fetchMock = vi.fn(async () => jsonResponse(integration))
      vi.stubGlobal('fetch', fetchMock)
      renderApp(`/connections/github/callback?${query}`)
      await connectorAccount()
      expect(
        screen.getByRole('button', { name: 'Complete connection' }),
      ).toBeDisabled()
      expect(
        screen.getByText(/missing valid installation details/),
      ).toBeInTheDocument()
      expect(fetchMock).not.toHaveBeenCalled()
    },
  )

  it('requires non-whitespace account names and leaves organization tracking unapplied', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    fireEvent.change(screen.getByLabelText(/Change API username/), {
      target: { value: '   ' },
    })
    fireEvent.change(screen.getByLabelText('Change API password'), {
      target: { value: 'test-pass' },
    })
    await userEvent.click(
      screen.getByRole('button', { name: 'Apply accounts' }),
    )
    expect(
      screen.getByText(/non-empty organization ID and a username/),
    ).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('selects all available repositories without dropping unavailable selections, and saves an explicitly cleared set', async () => {
    let cleared = false
    const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
      if (path.endsWith('/csrf'))
        return jsonResponse({
          headerName: 'X-XSRF-TOKEN',
          token: 'masked-token',
        })
      if (options.method === 'PUT') {
        cleared = true
        return new Response(null, { status: 204 })
      }
      if (path.endsWith('/available-repositories'))
        return jsonResponse([
          {
            repositoryId: '222',
            fullName: 'acme/public',
            defaultBranch: 'main',
          },
        ])
      if (path.endsWith('/repositories'))
        return jsonResponse(
          cleared
            ? []
            : [
                {
                  repositoryId: '12345',
                  fullName: 'acme/private',
                  defaultBranch: 'main',
                  status: 'ACTIVE',
                },
              ],
        )
      return jsonResponse([integration])
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await connectorAccount()
    await screen.findByRole('checkbox', { name: /acme\/private/ })
    await userEvent.click(
      screen.getByRole('button', { name: 'Select all available repositories' }),
    )
    expect(
      screen.getByRole('checkbox', { name: /acme\/private/ }),
    ).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /acme\/public/ })).toBeChecked()
    expect(
      screen.getByRole('button', { name: 'Save repository selection' }),
    ).toBeDisabled()
    expect(screen.getByText(/Unsaved intake selection/)).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([, options]) => options.method === 'PUT'),
    ).toBe(false)
    await userEvent.click(
      screen.getByRole('button', { name: 'Clear selection' }),
    )
    await userEvent.click(
      screen.getByRole('button', { name: 'Save repository selection' }),
    )
    expect(
      await screen.findByText(/No repositories are enabled for new activity/),
    ).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/repositories'),
      expect.objectContaining({ method: 'PUT', body: '{"repositoryIds":[]}' }),
    )
  })

  it('keeps the draft and reports a failed save without claiming ingestion was enabled', async () => {
    const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
      if (path.endsWith('/csrf'))
        return jsonResponse({
          headerName: 'X-XSRF-TOKEN',
          token: 'masked-token',
        })
      if (options.method === 'PUT') return new Response(null, { status: 503 })
      if (path.endsWith('/available-repositories'))
        return jsonResponse([
          {
            repositoryId: '12345',
            fullName: 'acme/private',
            defaultBranch: 'main',
          },
        ])
      if (path.endsWith('/repositories')) return jsonResponse([])
      return jsonResponse([integration])
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await connectorAccount()
    await userEvent.click(
      await screen.findByRole('checkbox', { name: /acme\/private/ }),
    )
    await userEvent.click(
      screen.getByRole('button', { name: 'Save repository selection' }),
    )
    expect(
      await screen.findByText(/service is unavailable/),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('checkbox', { name: /acme\/private/ }),
    ).toBeChecked()
    expect(screen.getByText(/Unsaved intake selection/)).toBeInTheDocument()
    expect(
      screen.queryByText(/Repository selection saved/),
    ).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(([, options]) => options.method === 'PUT'),
    ).toHaveLength(1)
  })

  it.each([403, 503])(
    'distinguishes an accepted save from failed verification (%s) and prevents identity changes during the write',
    async (status) => {
      let saved = false
      let release: (value: Response) => void = () => {
        throw new Error('Save has not started')
      }
      const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
        if (path.endsWith('/csrf'))
          return jsonResponse({
            headerName: 'X-XSRF-TOKEN',
            token: 'masked-token',
          })
        if (options.method === 'PUT')
          return new Promise<Response>((resolve) => {
            release = (response) => {
              saved = true
              resolve(response)
            }
          })
        if (path.endsWith('/available-repositories'))
          return saved
            ? new Response(null, { status })
            : jsonResponse([
                {
                  repositoryId: '12345',
                  fullName: 'acme/private',
                  defaultBranch: 'main',
                },
              ])
        if (path.endsWith('/repositories'))
          return saved ? new Response(null, { status }) : jsonResponse([])
        return jsonResponse([integration])
      })
      vi.stubGlobal('fetch', fetchMock)
      renderApp('/connections')
      await connectorAccount()
      await userEvent.click(
        await screen.findByRole('checkbox', { name: /acme\/private/ }),
      )
      await userEvent.click(
        screen.getByRole('button', { name: 'Save repository selection' }),
      )
      await waitFor(() =>
        expect(
          screen.getByRole('button', { name: 'Apply accounts' }),
        ).toBeDisabled(),
      )
      expect(
        screen.getByRole('button', { name: 'Forget accounts' }),
      ).toBeDisabled()
      release(new Response(null, { status: 204 }))
      expect(
        await screen.findByText(
          /Refresh repositories to verify the saved selection/,
          {},
          { timeout: 4000 },
        ),
      ).toBeInTheDocument()
      expect(
        screen.queryByText(/Unsaved intake selection/),
      ).not.toBeInTheDocument()
      expect(
        screen.getByRole('button', { name: 'Apply accounts' }),
      ).toBeEnabled()
      expect(
        fetchMock.mock.calls.filter(([, options]) => options.method === 'PUT'),
      ).toHaveLength(1)
    },
  )
})
