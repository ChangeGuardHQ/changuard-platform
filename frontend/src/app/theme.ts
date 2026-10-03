import { createTheme } from '@mui/material/styles'

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#1268e8',
      dark: '#0849b5',
      light: '#e8f1ff',
      contrastText: '#ffffff',
    },
    secondary: {
      main: '#00aebd',
      dark: '#087d8a',
      light: '#e2f8f8',
      contrastText: '#06233b',
    },
    background: {
      default: '#f4f8fc',
      paper: '#ffffff',
    },
    text: {
      primary: '#10213b',
      secondary: '#61728a',
    },
    divider: '#e2eaf2',
  },
  shape: {
    borderRadius: 12,
  },
  typography: {
    fontFamily: '"Inter", "Segoe UI", Roboto, sans-serif',
    h1: {
      fontSize: '2rem',
      fontWeight: 700,
      letterSpacing: '-0.04em',
    },
    h2: {
      fontSize: '1.25rem',
      fontWeight: 650,
      letterSpacing: '-0.02em',
    },
    button: {
      fontWeight: 600,
      textTransform: 'none',
    },
  },
  components: {
    MuiButton: {
      defaultProps: {
        disableElevation: true,
      },
      styleOverrides: {
        root: {
          '&.MuiButton-containedPrimary': {
            background: 'linear-gradient(110deg, #1268e8 0%, #078fc9 58%, #00aebd 100%)',
            '&:hover': {
              background: 'linear-gradient(110deg, #0849b5 0%, #087d8a 100%)',
            },
          },
        },
      },
    },
    MuiPaper: {
      defaultProps: {
        elevation: 0,
      },
      styleOverrides: {
        root: {
          border: '1px solid #e2eaf2',
        },
      },
    },
    MuiCssBaseline: {
      styleOverrides: {
        '::selection': {
          color: '#ffffff',
          backgroundColor: '#1268e8',
        },
      },
    },
  },
})
