import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../test/renderApp'

// Routes share the same providers as the application, without requiring any API credentials.
describe('application routes', () => {
  it('renders the overview and shared copyright footer', () => {
    renderApp()

    expect(
      screen.getByRole('heading', { name: 'Understand every change.' }),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/ChangeGuard\. All rights reserved\./),
    ).toBeInTheDocument()
  })

  it('navigates to the changes page from the shared navigation', async () => {
    const user = userEvent.setup()
    renderApp()

    await user.click(screen.getByRole('link', { name: 'Changes' }))

    expect(screen.getByRole('heading', { name: 'Changes' })).toBeInTheDocument()
    expect(
      screen.getByText(/Connect your change API account/),
    ).toBeInTheDocument()
  })

  it('renders a not-found view for unknown routes', () => {
    renderApp('/missing')

    expect(
      screen.getByRole('heading', { name: 'Page not found' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Back to overview' }),
    ).toHaveAttribute('href', '/')
  })
})
