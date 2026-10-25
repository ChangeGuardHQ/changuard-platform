import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/renderApp'
import { change, jsonResponse } from '../../test/fixtures'
import {
  openWorkspace,
  organizationScope,
  repositoryScope,
} from '../../test/workspace'

const integration = {
  integrationId: 'installation-a',
  organizationId: 'local',
  installationId: '123',
  status: 'ACTIVE',
  githubAccountId: '99',
  githubAccountLogin: 'ChangeGuardHQ',
}
const repository = {
  repositoryId: '12345',
  fullName: change.repositoryFullName,
  defaultBranch: 'main',
  status: 'ACTIVE',
}
const page = (items = [change], totalElements = items.length) => ({
  items,
  page: 0,
  size: 20,
  totalElements,
  totalPages: Math.ceil(totalElements / 20),
})

/** Real routed flows cover scope choice, delayed responses, revocation, historical views and forbidden deep links. */
describe('explicit tracking scope', () => {
  it('requires an explicit scope, keeps drafts separate, and retains one exact repository across navigation', async () => {
    const id = '9007199254740993'
    const chosen = {
      ...change,
      repositoryId: id,
      title: 'Only the chosen repository',
    }
    const fetchMock = vi.fn(async (path: string) => {
      if (path.includes('projection-health'))
        return jsonResponse({ status: 'UP' })
      if (path.endsWith('/integrations/github'))
        return jsonResponse([integration])
      if (path.endsWith('/available-repositories'))
        return jsonResponse([repository])
      if (path.endsWith('/repositories'))
        return jsonResponse([{ ...repository, repositoryId: id }])
      if (path.includes('/timeline?'))
        return jsonResponse({
          changeId: change.id,
          organizationId: 'local',
          entries: [],
        })
      if (path.includes(`/changes/${change.id}?`)) return jsonResponse(chosen)
      return jsonResponse(page([chosen]))
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await openWorkspace()
    expect(
      screen.getByText(/Choose and apply a tracking scope/),
    ).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([path]) => path.includes('/v1/changes')),
    ).toBe(false)
    await userEvent.click(
      screen.getByRole('radio', { name: 'Single repository' }),
    )
    await screen.findByRole('option', { name: new RegExp(`ID ${id}$`) })
    await userEvent.selectOptions(screen.getByLabelText('Repository'), id)
    expect(
      fetchMock.mock.calls.some(([path]) => path.includes('/v1/changes')),
    ).toBe(false)
    await userEvent.click(
      screen.getByRole('button', { name: 'Apply tracking scope' }),
    )
    await userEvent.click(
      await screen.findByRole('link', { name: chosen.title }),
    )
    expect(
      await screen.findByRole('heading', { name: chosen.title }),
    ).toBeInTheDocument()
    await userEvent.click(
      screen.getByRole('link', { name: 'Back to tracked changes' }),
    )
    expect(
      await screen.findByRole('link', { name: chosen.title }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('radio', { name: 'Single repository' }),
    ).toBeChecked()
    expect(screen.getByLabelText('Repository')).toHaveValue(id)
    const listCalls = fetchMock.mock.calls.filter(([path]) =>
      path.includes('/v1/changes?'),
    )
    expect(listCalls.length).toBeGreaterThan(0)
    expect(
      listCalls.every(
        ([path]) =>
          new URL(path, 'http://local').searchParams.get('repositoryId') === id,
      ),
    ).toBe(true)
  })

  it('does not invent an empty catalog when one installation fails, or silently query organization scope', async () => {
    const fetchMock = vi.fn(async (path: string) => {
      if (path.includes('projection-health'))
        return jsonResponse({ status: 'UP' })
      if (path.endsWith('/integrations/github'))
        return jsonResponse([
          integration,
          { ...integration, integrationId: 'installation-b' },
        ])
      if (path.includes('/installation-b/'))
        return new Response(null, { status: 403 })
      if (
        path.endsWith('/available-repositories') ||
        path.endsWith('/repositories')
      )
        return jsonResponse([repository])
      return jsonResponse(page())
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await openWorkspace()
    await userEvent.click(
      screen.getByRole('radio', { name: 'Single repository' }),
    )
    expect(await screen.findByText(/Access denied/)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Apply tracking scope' }),
    ).toBeDisabled()
    expect(
      screen.queryByText(/No active repositories are selected/),
    ).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([path]) => path.includes('/v1/changes')),
    ).toBe(false)
    // Organization history is an independent, explicit read and does not require connector-admin access.
    await organizationScope()
    expect(
      await screen.findByRole('link', { name: change.title! }),
    ).toBeInTheDocument()
  })

  it('deduplicates IDs across installations and distinguishes identical names by immutable IDs', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string) => {
        if (path.includes('projection-health'))
          return jsonResponse({ status: 'UP' })
        if (path.endsWith('/integrations/github'))
          return jsonResponse([
            integration,
            { ...integration, integrationId: 'installation-b' },
            { ...integration, integrationId: 'revoked', status: 'REVOKED' },
          ])
        if (
          path.endsWith('/available-repositories') ||
          path.endsWith('/repositories')
        )
          return jsonResponse([
            repository,
            { ...repository, repositoryId: '9007199254740993' },
            { ...repository, repositoryId: '999', status: 'DISCONNECTED' },
          ])
        return jsonResponse(page())
      }),
    )
    renderApp('/connections')
    await openWorkspace()
    await userEvent.click(
      screen.getByRole('radio', { name: 'Single repository' }),
    )
    await screen.findByRole('option', { name: /ID 9007199254740993$/ })
    const options = within(screen.getByLabelText('Repository')).getAllByRole(
      'option',
    )
    expect(options).toHaveLength(3)
    expect(options.map((option) => option.getAttribute('value'))).toEqual([
      '',
      '12345',
      '9007199254740993',
    ])
    expect(
      screen.getByRole('button', { name: 'Apply tracking scope' }),
    ).toBeDisabled()
  })

  it.each(['0', '01', '-1', '9223372036854775808', '1,2'])(
    'rejects invalid repository metadata %s before any changes request',
    async (id) => {
      const fetchMock = vi.fn(async (path: string) => {
        if (path.includes('projection-health'))
          return jsonResponse({ status: 'UP' })
        if (path.endsWith('/integrations/github'))
          return jsonResponse([integration])
        if (
          path.endsWith('/repositories') ||
          path.endsWith('/available-repositories')
        )
          return jsonResponse([{ ...repository, repositoryId: id }])
        return jsonResponse(page())
      })
      vi.stubGlobal('fetch', fetchMock)
      renderApp('/connections')
      await openWorkspace()
      await userEvent.click(
        screen.getByRole('radio', { name: 'Single repository' }),
      )
      expect(
        (
          await screen.findAllByText(
            /invalid repository|could not be read safely/,
          )
        )[0],
      ).toBeInTheDocument()
      expect(
        screen.getByRole('button', { name: 'Apply tracking scope' }),
      ).toBeDisabled()
      expect(
        fetchMock.mock.calls.some(([path]) => path.includes('/v1/changes')),
      ).toBe(false)
    },
  )

  it('pauses a revoked repository and hides old rows without falling back to organization history', async () => {
    let connected = true
    const fetchMock = vi.fn(async (path: string) => {
      if (path.includes('projection-health'))
        return jsonResponse({ status: 'UP' })
      if (path.endsWith('/integrations/github'))
        return jsonResponse([
          { ...integration, status: connected ? 'ACTIVE' : 'REVOKED' },
        ])
      if (
        path.endsWith('/repositories') ||
        path.endsWith('/available-repositories')
      )
        return jsonResponse([repository])
      return jsonResponse(page())
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await openWorkspace()
    await repositoryScope()
    await screen.findByRole('link', { name: change.title! })
    connected = false
    await userEvent.click(
      screen.getByRole('button', { name: 'Refresh repository choices' }),
    )
    expect(await screen.findByText(/Tracking is paused/)).toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: change.title! }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('radio', { name: 'Single repository' }),
    ).toBeChecked()
    expect(
      fetchMock.mock.calls
        .filter(([path]) => path.includes('/v1/changes?'))
        .every(([path]) => path.includes('repositoryId=12345')),
    ).toBe(true)
  })

  it('rejects a mismatched detail URL before requesting or rendering its timeline', async () => {
    const foreign = {
      ...change,
      id: 'foreign-change',
      repositoryId: '98765',
      title: 'Do not show this other repository',
    }
    const fetchMock = vi.fn(async (path: string) => {
      if (path.includes('projection-health'))
        return jsonResponse({ status: 'UP' })
      if (path.endsWith('/integrations/github'))
        return jsonResponse([integration])
      if (
        path.endsWith('/repositories') ||
        path.endsWith('/available-repositories')
      )
        return jsonResponse([repository])
      if (path.includes('/changes/foreign-change?'))
        return jsonResponse(foreign)
      return jsonResponse(page())
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp(
      '/connections',
      <Link to="/changes/foreign-change">Open shared detail link</Link>,
    )
    await openWorkspace()
    await repositoryScope()
    await screen.findByRole('link', { name: change.title! })
    await userEvent.click(
      screen.getByRole('link', { name: 'Open shared detail link' }),
    )
    expect(
      await screen.findByText(/outside the applied tracking scope/),
    ).toBeInTheDocument()
    expect(screen.queryByText(foreign.title)).not.toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: 'Timeline' }),
    ).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([path]) => path.includes('/timeline')),
    ).toBe(false)
  })

  it('hides a cached successful list after authorization is revoked instead of showing stale private records', async () => {
    let forbidden = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string) =>
        path.includes('projection-health')
          ? jsonResponse({ status: 'UP' })
          : forbidden
            ? new Response(null, { status: 403 })
            : jsonResponse(page()),
      ),
    )
    renderApp('/connections')
    await openWorkspace(false)
    await organizationScope()
    await screen.findByRole('link', { name: change.title! })
    forbidden = true
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText(/Access denied/)).toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: change.title! }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByText(/No changes in this organization/),
    ).not.toBeInTheDocument()
  })

  it('discards late responses and requires a new explicit scope when organization changes', async () => {
    const pending: ((value: Response) => void)[] = []
    const fetchMock = vi.fn((path: string) => {
      if (path.includes('projection-health'))
        return Promise.resolve(jsonResponse({ status: 'UP' }))
      if (path.includes('organizationId=other'))
        return Promise.resolve(
          jsonResponse(
            page([
              {
                ...change,
                organizationId: 'other',
                title: 'Other organization change',
              },
            ]),
          ),
        )
      return new Promise<Response>((resolve) => pending.push(resolve))
    })
    vi.stubGlobal('fetch', fetchMock)
    const { cache } = renderApp('/connections')
    await openWorkspace(false)
    await organizationScope()
    await waitFor(() => expect(pending.length).toBeGreaterThan(0))
    await userEvent.click(
      within(
        screen.getByRole('navigation', { name: 'Main navigation' }),
      ).getByRole('link', { name: 'Connections' }),
    )
    fireEvent.change(screen.getByLabelText(/Organization ID/), {
      target: { value: 'other' },
    })
    await userEvent.click(
      screen.getByRole('button', { name: 'Apply accounts' }),
    )
    await userEvent.click(screen.getByRole('link', { name: 'Changes' }))
    expect(
      screen.getByText(/Choose and apply a tracking scope/),
    ).toBeInTheDocument()
    await organizationScope()
    await screen.findByRole('link', { name: 'Other organization change' })
    await act(async () => {
      pending.forEach((resolve) => resolve(jsonResponse(page())))
    })
    expect(
      screen.queryByRole('link', { name: change.title! }),
    ).not.toBeInTheDocument()
    expect(
      cache
        .getQueryCache()
        .getAll()
        .some((query) => query.queryKey.includes('local')),
    ).toBe(false)
  })

  it('corrects a live page that becomes out of range without showing a false empty organization', async () => {
    let shrunk = false
    const fetchMock = vi.fn(async (path: string) => {
      if (path.includes('projection-health'))
        return jsonResponse({ status: 'UP' })
      if (path.includes('page=1')) {
        shrunk = true
        return jsonResponse(page([], 1))
      }
      return jsonResponse(page([change], shrunk ? 1 : 21))
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await openWorkspace(false)
    await organizationScope()
    await screen.findByRole('link', { name: change.title! })
    await userEvent.click(
      screen.getByRole('button', { name: 'Go to next page' }),
    )
    await waitFor(() =>
      expect(screen.getByText('1–1 of 1')).toBeInTheDocument(),
    )
    expect(
      screen.queryByText(/No changes in this organization/),
    ).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(([path]) => path.includes('page=0')).length,
    ).toBeGreaterThanOrEqual(2)
  })

  it('leaves Single repository disabled without a connector account, while explicitly allowing organization history', async () => {
    const fetchMock = vi.fn(async (path: string) =>
      path.includes('projection-health')
        ? jsonResponse({ status: 'UP' })
        : jsonResponse(page()),
    )
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await openWorkspace(false)
    await userEvent.click(
      screen.getByRole('radio', { name: 'Single repository' }),
    )
    expect(
      screen.getByText(/Apply a GitHub connector account/),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Apply tracking scope' }),
    ).toBeDisabled()
    expect(
      fetchMock.mock.calls.some(([path]) => path.includes('/v1/changes')),
    ).toBe(false)
    await organizationScope()
    expect(
      await screen.findByRole('link', { name: change.title! }),
    ).toBeInTheDocument()
  })

  it('shows an empty active catalog without implicitly choosing organization scope', async () => {
    const fetchMock = vi.fn(async (path: string) =>
      path.includes('projection-health')
        ? jsonResponse({ status: 'UP' })
        : jsonResponse([]),
    )
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await openWorkspace()
    await userEvent.click(
      screen.getByRole('radio', { name: 'Single repository' }),
    )
    expect(
      await screen.findByText(/No active repositories are selected/),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Apply tracking scope' }),
    ).toBeDisabled()
    expect(
      fetchMock.mock.calls.some(([path]) => path.includes('/v1/changes')),
    ).toBe(false)
  })

  it('discards late organization results after applying a different repository scope', async () => {
    const pending: ((value: Response) => void)[] = []
    const scoped = { ...change, title: 'Scoped result remains visible' }
    const fetchMock = vi.fn((path: string) => {
      if (path.includes('projection-health'))
        return Promise.resolve(jsonResponse({ status: 'UP' }))
      if (path.endsWith('/integrations/github'))
        return Promise.resolve(jsonResponse([integration]))
      if (
        path.endsWith('/repositories') ||
        path.endsWith('/available-repositories')
      )
        return Promise.resolve(jsonResponse([repository]))
      if (path.includes('repositoryId=12345'))
        return Promise.resolve(jsonResponse(page([scoped])))
      return new Promise<Response>((resolve) => pending.push(resolve))
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await openWorkspace()
    await organizationScope()
    await waitFor(() => expect(pending.length).toBeGreaterThan(0))
    await repositoryScope()
    await screen.findByRole('link', { name: scoped.title })
    await act(async () => {
      pending.forEach((resolve) => resolve(jsonResponse(page())))
    })
    expect(
      screen.queryByRole('link', { name: change.title! }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: scoped.title })).toBeInTheDocument()
  })

  it.each([
    { ...change, organizationId: 'another-org' },
    { ...change, repositoryId: '99999' },
  ])(
    'rejects a list response outside the selected repository or organization',
    async (wrong) => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async (path: string) => {
          if (path.includes('projection-health'))
            return jsonResponse({ status: 'UP' })
          if (path.endsWith('/integrations/github'))
            return jsonResponse([integration])
          if (
            path.endsWith('/repositories') ||
            path.endsWith('/available-repositories')
          )
            return jsonResponse([repository])
          return jsonResponse(
            page([{ ...wrong, title: 'Out of scope result' }]),
          )
        }),
      )
      renderApp('/connections')
      await openWorkspace()
      await repositoryScope()
      expect(
        await screen.findByText(
          /response does not match the applied tracking scope/,
        ),
      ).toBeInTheDocument()
      expect(screen.queryByText('Out of scope result')).not.toBeInTheDocument()
    },
  )

  it.each([
    null,
    { items: null },
    page([{ ...change, commitSha: null } as unknown as typeof change]),
    page([
      {
        ...change,
        actor: { id: {}, type: 'HUMAN' },
      } as unknown as typeof change,
    ]),
  ])(
    'renders an explicit failure for an incompatible change response',
    async (response) => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async (path: string) =>
          path.includes('projection-health')
            ? jsonResponse({ status: 'UP' })
            : jsonResponse(response),
        ),
      )
      renderApp('/connections')
      await openWorkspace(false)
      await organizationScope()
      expect(
        await screen.findByText(/change list could not be read safely/),
      ).toBeInTheDocument()
      expect(
        screen.queryByRole('table', { name: 'Changes' }),
      ).not.toBeInTheDocument()
    },
  )

  it('does not render a mismatched timeline or retain detail data after timeline authorization fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string) => {
        if (path.includes('projection-health'))
          return jsonResponse({ status: 'UP' })
        if (path.includes('/timeline?'))
          return jsonResponse({
            changeId: 'another-change',
            organizationId: 'another-org',
            entries: [],
          })
        if (path.includes(`/changes/${change.id}?`)) return jsonResponse(change)
        return jsonResponse(page())
      }),
    )
    renderApp('/connections')
    await openWorkspace(false)
    await organizationScope()
    await userEvent.click(
      await screen.findByRole('link', { name: change.title! }),
    )
    expect(
      await screen.findByText(
        /timeline does not match this workspace and change/,
      ),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: change.title! }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: 'Timeline' }),
    ).not.toBeInTheDocument()
  })

  it('rejects a catalog returned for another organization without exposing its repository names', async () => {
    const fetchMock = vi.fn(async (path: string) => {
      if (path.includes('projection-health'))
        return jsonResponse({ status: 'UP' })
      if (path.endsWith('/integrations/github'))
        return jsonResponse([
          {
            ...integration,
            organizationId: 'other-tenant',
            githubAccountLogin: 'PrivateOtherOwner',
          },
        ])
      return jsonResponse([
        { ...repository, fullName: 'PrivateOtherOwner/secret' },
      ])
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await openWorkspace()
    await userEvent.click(
      screen.getByRole('radio', { name: 'Single repository' }),
    )
    expect(
      await screen.findByText(/connection list does not match this workspace/),
    ).toBeInTheDocument()
    expect(screen.queryByText(/PrivateOtherOwner/)).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Apply tracking scope' }),
    ).toBeDisabled()
    expect(
      fetchMock.mock.calls.some(([path]) => path.includes('/v1/changes')),
    ).toBe(false)
  })

  it('updates a renamed repository label while retaining its immutable ID and current scope', async () => {
    let name = repository.fullName
    const fetchMock = vi.fn(async (path: string) => {
      if (path.includes('projection-health'))
        return jsonResponse({ status: 'UP' })
      if (path.endsWith('/integrations/github'))
        return jsonResponse([integration])
      if (
        path.endsWith('/repositories') ||
        path.endsWith('/available-repositories')
      )
        return jsonResponse([{ ...repository, fullName: name }])
      return jsonResponse(page())
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await openWorkspace()
    await repositoryScope()
    await screen.findByRole('link', { name: change.title! })
    name = 'ChangeGuardHQ/renamed-platform'
    await userEvent.click(
      screen.getByRole('button', { name: 'Refresh repository choices' }),
    )
    expect(
      await screen.findByText(
        'Repository · ChangeGuardHQ/renamed-platform · ID 12345',
      ),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Repository')).toHaveValue('12345')
    expect(
      fetchMock.mock.calls
        .filter(([path]) => path.includes('/v1/changes?'))
        .every(([path]) => path.includes('repositoryId=12345')),
    ).toBe(true)
  })

  it('never lets a delayed old-workspace catalog change the new applied organization scope', async () => {
    let hold = false
    const pending: ((value: Response) => void)[] = []
    const fetchMock = vi.fn((path: string) => {
      if (path.includes('projection-health'))
        return Promise.resolve(jsonResponse({ status: 'UP' }))
      const other =
        path.includes('/organizations/other/') ||
        path.includes('organizationId=other')
      if (path.endsWith('/integrations/github')) {
        if (hold && !other)
          return new Promise<Response>((resolve) => pending.push(resolve))
        return Promise.resolve(
          jsonResponse([
            { ...integration, organizationId: other ? 'other' : 'local' },
          ]),
        )
      }
      if (
        path.endsWith('/repositories') ||
        path.endsWith('/available-repositories')
      )
        return Promise.resolve(
          jsonResponse([
            {
              ...repository,
              fullName: hold
                ? 'PrivateOldOwner/renamed-after-switch'
                : repository.fullName,
            },
          ]),
        )
      return Promise.resolve(
        jsonResponse(
          page([
            {
              ...change,
              organizationId: other ? 'other' : 'local',
              title: other ? 'New workspace result' : change.title,
            },
          ]),
        ),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp('/connections')
    await openWorkspace()
    await repositoryScope()
    await screen.findByRole('link', { name: change.title! })
    hold = true
    await userEvent.click(
      screen.getByRole('button', { name: 'Refresh repository choices' }),
    )
    await waitFor(() => expect(pending.length).toBeGreaterThan(0))
    await userEvent.click(
      within(
        screen.getByRole('navigation', { name: 'Main navigation' }),
      ).getByRole('link', { name: 'Connections' }),
    )
    fireEvent.change(screen.getByLabelText(/Organization ID/), {
      target: { value: 'other' },
    })
    await userEvent.click(
      screen.getByRole('button', { name: 'Apply accounts' }),
    )
    await userEvent.click(screen.getByRole('link', { name: 'Changes' }))
    await organizationScope()
    await screen.findByRole('link', { name: 'New workspace result' })
    await act(async () => {
      pending.forEach((resolve) => resolve(jsonResponse([integration])))
    })
    expect(screen.getByRole('radio', { name: 'Organization' })).toBeChecked()
    expect(screen.getByText('Organization history')).toBeInTheDocument()
    expect(screen.queryByText(/PrivateOldOwner/)).not.toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'New workspace result' }),
    ).toBeInTheDocument()
  })
})
