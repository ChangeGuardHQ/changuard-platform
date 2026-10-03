import { ThemeProvider } from '@mui/material'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AppRoutes } from './AppRoutes'
import { theme } from './theme'

function renderRoutes(initialPath = '/') {
  return render(
    <ThemeProvider theme={theme}>
      <MemoryRouter initialEntries={[initialPath]}>
        <AppRoutes />
      </MemoryRouter>
    </ThemeProvider>,
  )
}

describe('application routes', () => {
  it('renders the overview and shared copyright footer', () => {
    renderRoutes()

    expect(screen.getByRole('heading', { name: 'Understand every change.' })).toBeInTheDocument()
    expect(screen.getByText(/ChangeGuard\. All rights reserved\./)).toBeInTheDocument()
  })

  it('navigates to the changes page from the shared navigation', async () => {
    const user = userEvent.setup()
    renderRoutes()

    await user.click(screen.getByRole('link', { name: 'Changes' }))

    expect(screen.getByRole('heading', { name: 'Changes' })).toBeInTheDocument()
    expect(screen.getByText(/This view is ready for its first data source/)).toBeInTheDocument()
  })

  it('renders a not-found view for unknown routes', () => {
    renderRoutes('/missing')

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to overview' })).toHaveAttribute('href', '/')
  })
})
