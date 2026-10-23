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
})
