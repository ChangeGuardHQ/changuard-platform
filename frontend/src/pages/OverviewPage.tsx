import { ArrowForwardRounded, BoltRounded } from '@mui/icons-material'
import { Box, Button, Chip, Paper, Stack, Typography } from '@mui/material'
import { Link } from 'react-router-dom'
import { OverviewQuestions } from '../features/overview/OverviewQuestions'
import { usePageTitle } from '../hooks/usePageTitle'

export function OverviewPage() {
  usePageTitle('Overview')

  return (
    <Stack spacing={4}>
      <Box>
        <Chip
          icon={<BoltRounded />}
          label="SOFTWARE CHANGE INTELLIGENCE"
          size="small"
          sx={{
            mb: 2,
            bgcolor: 'primary.light',
            color: 'primary.dark',
            fontWeight: 700,
            letterSpacing: '0.04em',
            '& .MuiChip-icon': { color: 'secondary.dark' },
          }}
        />
        <Typography
          variant="h1"
          sx={{
            mb: 1.5,
            background: 'linear-gradient(100deg, #10213b 8%, #1268e8 58%, #00aebd 100%)',
            backgroundClip: 'text',
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
          }}
        >
          Understand every change.
        </Typography>
        <Typography variant="body1" color="text.secondary" sx={{ maxWidth: 680, lineHeight: 1.8 }}>
          Follow software delivery from code to production, connect operational impact, and keep
          every engineering action accountable.
        </Typography>
      </Box>

      <Paper
        sx={{
          p: { xs: 3, md: 4 },
          display: 'flex',
          alignItems: { xs: 'flex-start', md: 'center' },
          justifyContent: 'space-between',
          flexDirection: { xs: 'column', md: 'row' },
          gap: 3,
          borderColor: '#d9e7f3',
          background:
            'radial-gradient(ellipse at 95% 0%, rgba(0, 174, 189, 0.12), transparent 38%), linear-gradient(110deg, #ffffff 28%, #edf4ff 100%)',
        }}
      >
        <Box>
          <Typography variant="h2" sx={{ mb: 1 }}>
            Your change intelligence starts here
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 620, lineHeight: 1.7 }}>
            The frontend foundation is ready. Connect it to the ChangeGuard API to populate this
            workspace with your organization&apos;s delivery events.
          </Typography>
        </Box>
        <Button
          component={Link}
          to="/changes"
          variant="contained"
          endIcon={<ArrowForwardRounded />}
          sx={{
            whiteSpace: 'nowrap',
            px: 2.25,
            py: 1.1,
            boxShadow: '0 8px 20px rgba(18, 104, 232, 0.2)',
            '&:hover': { boxShadow: '0 10px 24px rgba(8, 73, 181, 0.25)' },
          }}
        >
          Explore changes
        </Button>
      </Paper>

      <Box>
        <Typography variant="h2" sx={{ mb: 2 }}>
          The questions ChangeGuard answers
        </Typography>
        <OverviewQuestions />
      </Box>
    </Stack>
  )
}
