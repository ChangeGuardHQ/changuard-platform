import { Box, Paper, Stack, Typography } from '@mui/material'
import { usePageTitle } from '../hooks/usePageTitle'

type SectionPageProps = {
  title: string
}

export function SectionPage({ title }: SectionPageProps) {
  usePageTitle(title)

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">{title}</Typography>
        <Typography color="text.secondary" sx={{ mt: 1 }}>
          A connected view of your software delivery lifecycle.
        </Typography>
      </Box>
      <Paper sx={{ p: { xs: 3, md: 4 } }}>
        <Typography variant="h2" sx={{ mb: 1 }}>
          This view is ready for its first data source
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>
          Once the ChangeGuard API is available, this page will show verified {title.toLowerCase()}{' '}
          data. The frontend does not invent sample activity or connect directly to platform
          services.
        </Typography>
      </Paper>
    </Stack>
  )
}
