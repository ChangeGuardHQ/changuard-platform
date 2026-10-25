import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/renderApp'
import { change, jsonResponse } from '../../test/fixtures'

async function connect(connector = false) {
  fireEvent.change(screen.getByLabelText('Change API password'), {
    target: { value: 'test-pass' },
  })
  if (connector)
    fireEvent.change(screen.getByLabelText('GitHub connector password'), {
      target: { value: 'admin-pass' },
    })
  await userEvent.click(screen.getByRole('button', { name: 'Apply accounts' }))
  await userEvent.click(screen.getByRole('link', { name: 'Changes' }))
  await userEvent.click(
    screen.getByRole('button', { name: 'Apply tracking scope' }),
  )
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

  it('resets pagination when a named single-repository scope is applied', async () => {
    const fetchMock = vi.fn(async (path: string) => {
      if (path.includes('projection-health'))
        return jsonResponse({ status: 'UP' })
      if (path.endsWith('/integrations/github'))
        return jsonResponse([
          {
            integrationId: 'integration',
            organizationId: 'local',
            status: 'ACTIVE',
          },
        ])
      if (
        path.endsWith('/repositories') ||
        path.endsWith('/available-repositories')
      )
        return jsonResponse([
          {
            repositoryId: '12345',
            fullName: change.repositoryFullName,
            status: 'ACTIVE',
          },
        ])
      return jsonResponse({
        items: [change],
        page: 0,
        size: 20,
        totalElements: 21,
        totalPages: 2,
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await connect(true)
    await screen.findByRole('link', { name: change.title! })
    await userEvent.click(
      screen.getByRole('button', { name: 'Go to next page' }),
    )
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([path]) => path.includes('page=1')),
      ).toBe(true),
    )
    await userEvent.click(
      screen.getByRole('radio', { name: 'Single repository' }),
    )
    await screen.findByRole('option', { name: /ID 12345/ })
    await userEvent.selectOptions(screen.getByLabelText('Repository'), '12345')
    await userEvent.click(
      screen.getByRole('button', { name: 'Apply tracking scope' }),
    )
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([path]) =>
            path.includes('page=0') && path.includes('repositoryId=12345'),
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
    await userEvent.click(
      screen.getByRole('button', { name: 'Apply tracking scope' }),
    )
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
