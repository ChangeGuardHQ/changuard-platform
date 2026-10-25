import { fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

/** Route tests apply accounts and scope through the same explicit controls used by a person. */
export async function openWorkspace(connector = true, organization = 'local') {
  fireEvent.change(screen.getByLabelText(/Organization ID/), {
    target: { value: organization },
  })
  fireEvent.change(screen.getByLabelText('Change API password'), {
    target: { value: 'test-pass' },
  })
  if (connector)
    fireEvent.change(screen.getByLabelText('GitHub connector password'), {
      target: { value: 'test-admin-pass' },
    })
  await userEvent.click(screen.getByRole('button', { name: 'Apply accounts' }))
  await userEvent.click(screen.getByRole('link', { name: 'Changes' }))
}
// Applying API accounts deliberately leaves scope unset; callers choose the view through the real form controls.
export async function organizationScope() {
  await userEvent.click(screen.getByRole('radio', { name: 'Organization' }))
  await userEvent.click(
    screen.getByRole('button', { name: 'Apply tracking scope' }),
  )
}
export async function repositoryScope(repositoryId = '12345') {
  await userEvent.click(
    screen.getByRole('radio', { name: 'Single repository' }),
  )
  await screen.findByRole('option', { name: new RegExp(`ID ${repositoryId}$`) })
  await userEvent.selectOptions(
    screen.getByLabelText('Repository'),
    repositoryId,
  )
  await userEvent.click(
    screen.getByRole('button', { name: 'Apply tracking scope' }),
  )
}
