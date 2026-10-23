import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/renderApp'
import { change, jsonResponse } from '../../test/fixtures'

async function connect() {
  fireEvent.change(screen.getByLabelText('Change API password'), {
    target: { value: 'test-pass' },
  })
  await userEvent.click(screen.getByRole('button', { name: 'Apply accounts' }))
  await userEvent.click(screen.getByRole('link', { name: 'Changes' }))
}

/** Browse real route flows with API responses at the fetch boundary; no UI-specific mock service is used. */
describe('live change workspace', () => {
  it('loads scoped records and refreshes an open PR to its persisted merge timeline', async () => {
    let merged = false
    const fetchMock = vi.fn(async (path: string) => {
      if (path.includes('projection-health'))
        return jsonResponse({ status: 'UP' })
      const current = { ...change, status: merged ? 'MERGED' : 'OPEN' }
      if (path.includes('/timeline?'))
        return jsonResponse({
          changeId: change.id,
          organizationId: 'local',
          entries: [
            {
              eventId: 'open',
              type: 'PullRequestOpened',
              occurredAt: change.openedAt,
              commitSha: change.commitSha,
              actor: change.actor,
            },
            ...(merged
              ? [
                  {
                    eventId: 'merge',
                    type: 'PullRequestMerged',
                    occurredAt: '2026-10-23T14:00:00Z',
                    commitSha: 'b'.repeat(40),
                    actor: change.actor,
                  },
                ]
              : []),
          ],
        })
      if (path.includes(`/changes/${change.id}?`)) return jsonResponse(current)
      return jsonResponse({
        items: [current],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await connect()
    await userEvent.click(
      await screen.findByRole('link', { name: 'Connect live changes' }),
    )
    expect(await screen.findByText('Pull request opened')).toBeInTheDocument()
    expect(screen.getByText('OPEN')).toBeInTheDocument()
    merged = true
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('Pull request merged')).toBeInTheDocument()
    expect(await screen.findByText('MERGED')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([path]) =>
        path.includes('organizationId=local'),
      ),
    ).toBe(true)
  })

  it('shows authorization errors distinctly from an empty list', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string) =>
        path.includes('projection-health')
          ? jsonResponse({ status: 'UP' })
          : new Response(null, { status: 403 }),
      ),
    )
    renderApp('/connections')
    await connect()
    expect(await screen.findByText(/Access denied/)).toBeInTheDocument()
    expect(
      screen.queryByText('Your next change starts here'),
    ).not.toBeInTheDocument()
  })

  it('encodes repository filters and requests the next page from the backend', async () => {
    const fetchMock = vi.fn(async (path: string) =>
      path.includes('projection-health')
        ? jsonResponse({ status: 'UP' })
        : jsonResponse({
            items: [change],
            page: 0,
            size: 20,
            totalElements: 21,
            totalPages: 2,
          }),
    )
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await connect()
    await screen.findByRole('link', { name: change.title! })
    await userEvent.click(
      screen.getByRole('button', { name: 'Go to next page' }),
    )
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([path]) => path.includes('page=1')),
      ).toBe(true),
    )
    fireEvent.change(screen.getByLabelText('Repository ID'), {
      target: { value: 'repo/a&b' },
    })
    await userEvent.click(screen.getByRole('button', { name: 'Filter' }))
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([path]) =>
            path.includes('page=0') &&
            path.includes('repositoryId=repo%2Fa%26b'),
        ),
      ).toBe(true),
    )
  })

  it('switches organization without displaying records cached for the previous workspace', async () => {
    const fetchMock = vi.fn(async (path: string) => {
      if (path.includes('projection-health'))
        return jsonResponse({ status: 'UP' })
      const second = path.includes('organizationId=org-two')
      return jsonResponse({
        items: [
          {
            ...change,
            organizationId: second ? 'org-two' : 'local',
            title: second ? 'Second workspace change' : change.title,
          },
        ],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const { cache } = renderApp('/connections')
    await connect()
    await screen.findByRole('link', { name: change.title! })
    await userEvent.click(
      within(
        screen.getByRole('navigation', { name: 'Main navigation' }),
      ).getByRole('link', { name: 'Connections' }),
    )
    fireEvent.change(screen.getByLabelText(/Organization ID/), {
      target: { value: 'org-two' },
    })
    await userEvent.click(
      screen.getByRole('button', { name: 'Apply accounts' }),
    )
    await userEvent.click(screen.getByRole('link', { name: 'Changes' }))
    expect(
      await screen.findByRole('link', { name: 'Second workspace change' }),
    ).toBeInTheDocument()
    expect(screen.queryByText(change.title!)).not.toBeInTheDocument()
    expect(
      cache
        .getQueryCache()
        .getAll()
        .some((query) => query.queryKey.includes('local')),
    ).toBe(false)
  })

  it('forgets cached records and secrets rather than retaining them across accounts', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem')
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string) =>
        path.includes('projection-health')
          ? jsonResponse({ status: 'UP' })
          : jsonResponse({
              items: [change],
              page: 0,
              size: 20,
              totalElements: 1,
              totalPages: 1,
            }),
      ),
    )
    const { cache } = renderApp('/connections')
    await connect()
    await screen.findByRole('link', { name: change.title! })
    await userEvent.click(
      within(
        screen.getByRole('navigation', { name: 'Main navigation' }),
      ).getByRole('link', { name: 'Connections' }),
    )
    await userEvent.click(
      screen.getByRole('button', { name: 'Forget accounts' }),
    )
    await waitFor(() =>
      expect(screen.getByLabelText('Change API password')).toHaveValue(''),
    )
    await userEvent.click(screen.getByRole('link', { name: 'Changes' }))
    expect(
      screen.getByText(/Connect your change API account/),
    ).toBeInTheDocument()
    expect(screen.queryByText(change.title!)).not.toBeInTheDocument()
    expect(
      cache
        .getQueryCache()
        .getAll()
        .every((query) => query.state.data === undefined),
    ).toBe(true)
    expect(storage).not.toHaveBeenCalled()
    storage.mockRestore()
  })
})
