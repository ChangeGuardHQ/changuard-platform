import { describe, expect, it, vi } from 'vitest'
import { apiClient } from './apiClient'

describe('apiClient', () => {
  it('requests JSON from the configured API path', async () => {
    const payload = { items: [{ id: 'change-1' }] }
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(payload), {
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiClient.get<{ items: { id: string }[] }>('/changes')).resolves.toEqual(payload)
    expect(fetchMock).toHaveBeenCalledWith('/api/changes', {
      headers: { Accept: 'application/json' },
      signal: undefined,
    })
  })

  it('throws an explicit error for unsuccessful responses', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 503, statusText: 'Unavailable' })),
    )

    await expect(apiClient.get('/changes')).rejects.toThrow(
      'API request failed with 503 Unavailable',
    )
  })
})
