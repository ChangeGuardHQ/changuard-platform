import { useState } from 'react'
import type { FormEvent } from 'react'
import { useIsMutating } from '@tanstack/react-query'
import {
  Alert,
  Box,
  Button,
  Divider,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useWorkspace } from '../../app/WorkspaceSession'

/** Account secrets are user-entered runtime state and are deliberately absent from public Vite variables. */
export function ApiAccountsForm() {
  const { session, save, forget } = useWorkspace()
  const writes = useIsMutating()
  const [organization, setOrganization] = useState(session.organization)
  // Local-development suggestions only: deploy/compose.yaml maps both API pairs to APP_USERNAME / APP_PASSWORD.
  const [changeUser, setChangeUser] = useState(
    session.change?.username ?? 'changeguard',
  )
  const [changePassword, setChangePassword] = useState(
    session.change?.password ?? '',
  )
  const [connectorUser, setConnectorUser] = useState(
    session.connector?.username ?? 'connector-admin',
  )
  const [connectorPassword, setConnectorPassword] = useState(
    session.connector?.password ?? '',
  )
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  async function submit(event: FormEvent) {
    event.preventDefault()
    // Whitespace-only identifiers and account names pass HTML required validation but cannot identify an account.
    if (
      !organization.trim() ||
      (changePassword && !changeUser.trim()) ||
      (connectorPassword && !connectorUser.trim())
    ) {
      setError(
        'Enter a non-empty organization ID and a username for each supplied password.',
      )
      return
    }
    if (changeUser.includes(':') || connectorUser.includes(':')) {
      setError('Account names cannot contain a colon.')
      return
    }
    if (!changePassword && !connectorPassword) {
      setError('Enter at least one service account password.')
      return
    }
    setSaving(true)
    try {
      // An empty password omits that account, supporting sessions with access to only one service.
      await save({
        organization: organization.trim(),
        change: changePassword
          ? { username: changeUser.trim(), password: changePassword }
          : undefined,
        connector: connectorPassword
          ? { username: connectorUser.trim(), password: connectorPassword }
          : undefined,
      })
    } finally {
      setSaving(false)
    }
  }
  return (
    <Paper component="section" sx={{ p: 3 }}>
      <Typography variant="h2">1. Connect API accounts</Typography>
      <Typography color="text.secondary" variant="body2" sx={{ mt: 1, mb: 3 }}>
        Use the accounts configured for your services. Credentials stay in this
        tab’s memory and are cleared on reload.
      </Typography>
      <Box
        component="form"
        autoComplete="off"
        onSubmit={(event) => void submit(event)}
      >
        <Stack spacing={2.5}>
          <TextField
            label="Organization ID"
            value={organization}
            onChange={(event) => setOrganization(event.target.value)}
            required
            helperText="Internal ChangeGuard workspace ID from server configuration (for example visual-test). Your GitHub owner is linked below."
            slotProps={{ htmlInput: { pattern: '.*\\S.*' } }}
          />
          <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 2 }}>
            <TextField
              fullWidth
              label="Change API username"
              value={changeUser}
              onChange={(event) => setChangeUser(event.target.value)}
              required={Boolean(changePassword)}
            />
            <TextField
              fullWidth
              label="Change API password"
              type="password"
              autoComplete="off"
              value={changePassword}
              onChange={(event) => setChangePassword(event.target.value)}
            />
          </Stack>
          <Divider />
          <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 2 }}>
            <TextField
              fullWidth
              label="GitHub connector username"
              value={connectorUser}
              onChange={(event) => setConnectorUser(event.target.value)}
              required={Boolean(connectorPassword)}
            />
            <TextField
              fullWidth
              label="GitHub connector password"
              type="password"
              autoComplete="off"
              value={connectorPassword}
              onChange={(event) => setConnectorPassword(event.target.value)}
            />
          </Stack>
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
            <Button
              type="submit"
              variant="contained"
              disabled={saving || writes > 0}
            >
              Apply accounts
            </Button>
            {(session.change || session.connector) && (
              <Button
                disabled={saving || writes > 0}
                onClick={() => void forget()}
              >
                Forget accounts
              </Button>
            )}
          </Stack>
        </Stack>
      </Box>
    </Paper>
  )
}
