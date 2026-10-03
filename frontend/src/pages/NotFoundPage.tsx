import { ArrowBackRounded } from '@mui/icons-material'
import { Button, Stack, Typography } from '@mui/material'
import { Link } from 'react-router-dom'
import { usePageTitle } from '../hooks/usePageTitle'

export function NotFoundPage() {
  usePageTitle('Page not found')

  return (
    <Stack spacing={2} sx={{ alignItems: 'flex-start' }}>
      <Typography variant="h1">Page not found</Typography>
      <Typography color="text.secondary">
        The page you requested does not exist.
      </Typography>
      <Button component={Link} to="/" startIcon={<ArrowBackRounded />}>
        Back to overview
      </Button>
    </Stack>
  )
}
