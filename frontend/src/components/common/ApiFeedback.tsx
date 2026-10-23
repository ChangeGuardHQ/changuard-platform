import { Alert, Button, Stack } from '@mui/material'
import { Link } from 'react-router-dom'

/** Keep network failures visible instead of treating them as an empty repository or timeline. */
export function ApiFailure({
  error,
  retry,
}: {
  error: Error
  retry?: () => void
}) {
  return (
    <Alert
      severity="error"
      action={
        retry ? (
          <Button color="inherit" onClick={retry}>
            Retry
          </Button>
        ) : undefined
      }
    >
      {error.message}
    </Alert>
  )
}
export function ConnectPrompt({ service = 'change' }: { service?: string }) {
  return (
    <Alert severity="info">
      <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
        <span>Connect your {service} API account to view this workspace.</span>
        <Button component={Link} to="/connections" size="small">
          Open connections
        </Button>
      </Stack>
    </Alert>
  )
}
