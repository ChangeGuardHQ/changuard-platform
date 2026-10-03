import { Grid, Paper, Typography } from '@mui/material'

const questions = [
  {
    number: '01',
    title: 'What changed?',
    description: 'Connect pull requests, builds, and deployments into one change timeline.',
  },
  {
    number: '02',
    title: 'Did it affect production?',
    description: 'Correlate software changes with service health and operational signals.',
  },
  {
    number: '03',
    title: 'Who or what made the change?',
    description: 'Keep human and AI-assisted engineering activity attributable and auditable.',
  },
]

export function OverviewQuestions() {
  return (
    <Grid container spacing={2}>
      {questions.map((question) => (
        <Grid key={question.number} size={{ xs: 12, md: 4 }}>
          <Paper
            sx={{
              p: 2.5,
              height: '100%',
              position: 'relative',
              overflow: 'hidden',
              transition: 'transform 180ms ease, box-shadow 180ms ease',
              '&::before': {
                content: '""',
                position: 'absolute',
                inset: '0 0 auto',
                height: 3,
                background: 'linear-gradient(90deg, #1268e8, #00c7cb)',
              },
              '&:hover': {
                transform: 'translateY(-3px)',
                boxShadow: '0 12px 28px rgba(16, 55, 102, 0.08)',
              },
            }}
          >
            <Typography
              variant="overline"
              sx={{
                color: question.number === '02' ? 'secondary.dark' : 'primary.main',
                fontWeight: 700,
                letterSpacing: '0.08em',
              }}
            >
              {question.number}
            </Typography>
            <Typography variant="h2" sx={{ mt: 1, mb: 1 }}>
              {question.title}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.7 }}>
              {question.description}
            </Typography>
          </Paper>
        </Grid>
      ))}
    </Grid>
  )
}
