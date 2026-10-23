import { describe, expect, it, vi } from 'vitest'
import { ApiError, apiClient, parseApiJson } from './apiClient'
import { githubApi } from '../features/connections/api'

const json = (body: unknown) =>
  new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
  })

/** Verify browser transport boundaries, CSRF, and identifier precision rather than mocking the service wrappers. */
describe('apiClient', () => {
  it('requests JSON with explicit tab credentials and same-origin cookies', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(json({ items: [{ id: 'change-1' }] }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(
      apiClient.get('/v1/changes', {
        auth: { username: 'reader', password: 'test-pass' },
      }),
    ).resolves.toEqual({ items: [{ id: 'change-1' }] })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/changes',
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        headers: expect.objectContaining({
          Authorization: 'Basic ' + btoa('reader:test-pass'),
        }),
      }),
    )
    expect(fetchMock.mock.calls[0][0]).not.toContain('test-pass')
  })

  it('reports authorization failures and rejects a frontend HTML fallback as API data', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(new Response(null, { status: 403 }))
        .mockResolvedValueOnce(
          new Response('<html>app</html>', {
            headers: { 'Content-Type': 'text/html' },
          }),
        ),
    )
    await expect(apiClient.get('v1/changes')).rejects.toMatchObject({
      status: 403,
      name: 'ApiError',
    })
    await expect(apiClient.get('missing-api')).rejects.toThrow(
      'unexpected response',
    )
    expect(new ApiError(401, 'denied')).toBeInstanceOf(Error)
  })

  it('fetches CSRF and sends the exact long repository IDs without floating-point conversion', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        json({ headerName: 'X-XSRF-TOKEN', token: 'masked-token' }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    const auth = { username: 'admin', password: 'test-pass' }
    await githubApi.select('org/one', 'integration', ['9007199254740993'], auth)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/integrations/github/csrf')
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/v1/organizations/org%2Fone/integrations/github/integration/repositories',
      expect.objectContaining({
        method: 'PUT',
        credentials: 'same-origin',
        body: '{"repositoryIds":[9007199254740993]}',
        headers: expect.objectContaining({
          'X-XSRF-TOKEN': 'masked-token',
          Authorization: 'Basic ' + btoa('admin:test-pass'),
        }),
      }),
    )
  })

  it('decodes provider identifiers exactly and rejects identifiers that cannot fit a positive Java long', () => {
    expect(
      parseApiJson(
        '{"repositoryId":9007199254740993,"installationId":123,"pullRequestNumber":42}',
      ),
    ).toEqual({
      repositoryId: '9007199254740993',
      installationId: '123',
      pullRequestNumber: 42,
    })
    expect(() =>
      githubApi.select('org', 'id', ['1,2'], { username: 'a', password: 'b' }),
    ).toThrow('Invalid repository identifier')
    expect(() =>
      githubApi.select('org', 'id', ['9223372036854775808'], {
        username: 'a',
        password: 'b',
      }),
    ).toThrow('Invalid repository identifier')
  })
})
