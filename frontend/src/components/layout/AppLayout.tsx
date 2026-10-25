import {
  Box,
  Drawer,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Toolbar,
  Typography,
} from '@mui/material'
import {
  AccountTreeRounded,
  HubRounded,
  QueryStatsRounded,
  TimelineRounded,
  GitHub,
} from '@mui/icons-material'
import { NavLink, Outlet } from 'react-router-dom'
import type { NavigationItem } from '../../types/navigation'
import { useWorkspace } from '../../app/WorkspaceSession'
import { useTrackingAvailability } from '../../features/changes/repositories'

// Keep navigation usable on narrow windows while retaining accessible icon labels.
const drawerWidth = { xs: 76, md: 248 }
const copyrightYear = new Date().getFullYear()

const navigationItems: NavigationItem[] = [
  { label: 'Overview', path: '/', icon: <QueryStatsRounded /> },
  { label: 'Changes', path: '/changes', icon: <AccountTreeRounded /> },
  { label: 'Connections', path: '/connections', icon: <GitHub /> },
  { label: 'Services', path: '/services', icon: <HubRounded /> },
  { label: 'Activity', path: '/activity', icon: <TimelineRounded /> },
]

export function AppLayout() {
  const { session, scope } = useWorkspace()
  const { repository } = useTrackingAvailability()
  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <Drawer
        variant="permanent"
        sx={{
          width: drawerWidth,
          flexShrink: 0,
          '& .MuiDrawer-paper': {
            width: drawerWidth,
            boxSizing: 'border-box',
            border: 0,
            borderRight: '1px solid',
            borderColor: 'divider',
            background:
              'linear-gradient(180deg, #ffffff 0%, #ffffff 72%, #f1f8ff 100%)',
          },
        }}
      >
        <Toolbar sx={{ px: { xs: 1, md: 2.5 }, minHeight: '76px !important' }}>
          <Box
            component="img"
            src="/brand/changeguard-logo.png"
            alt="ChangeGuard Analytics"
            sx={{
              display: 'block',
              width: '100%',
              height: 52,
              objectFit: { xs: 'contain', md: 'cover' },
              objectPosition: 'center',
            }}
          />
        </Toolbar>
        <Box
          component="nav"
          aria-label="Main navigation"
          sx={{ px: { xs: 0.75, md: 1.5 }, pt: 2 }}
        >
          <Typography
            variant="overline"
            color="text.secondary"
            sx={{
              display: { xs: 'none', md: 'block' },
              px: 1.5,
              fontWeight: 700,
              letterSpacing: '0.1em',
            }}
          >
            Workspace
          </Typography>
          {/* Overview matches exactly; detail routes retain the Changes navigation item as active. */}
          <List sx={{ pt: 1 }}>
            {navigationItems.map((item) => (
              <ListItemButton
                key={item.path}
                component={NavLink}
                to={item.path}
                aria-label={item.label}
                end={item.path === '/'}
                sx={{
                  mb: 0.5,
                  borderRadius: 2,
                  borderLeft: '3px solid transparent',
                  color: 'text.secondary',
                  '& .MuiListItemIcon-root': {
                    color: 'inherit',
                    minWidth: 38,
                    transition: 'color 160ms ease',
                  },
                  '&:hover': {
                    bgcolor: 'secondary.light',
                    color: 'secondary.dark',
                  },
                  '&.active': {
                    bgcolor: 'primary.light',
                    color: 'primary.dark',
                    borderLeftColor: 'secondary.main',
                    '&:hover': {
                      bgcolor: 'primary.light',
                      color: 'primary.dark',
                    },
                  },
                }}
              >
                <ListItemIcon>{item.icon}</ListItemIcon>
                <ListItemText
                  sx={{ display: { xs: 'none', md: 'block' } }}
                  primary={
                    <Typography
                      component="span"
                      sx={{ fontSize: 14, fontWeight: 600 }}
                    >
                      {item.label}
                    </Typography>
                  }
                />
              </ListItemButton>
            ))}
          </List>
        </Box>
        <Box sx={{ display: { xs: 'none', md: 'block' }, mt: 'auto', p: 2.5 }}>
          <Typography variant="caption" color="text.secondary">
            Software delivery intelligence
          </Typography>
        </Box>
      </Drawer>

      <Box
        component="main"
        sx={{
          display: 'flex',
          flexDirection: 'column',
          flexGrow: 1,
          minWidth: 0,
        }}
      >
        <Box
          component="header"
          sx={{
            minHeight: 76,
            py: 1.5,
            px: { xs: 3, md: 5 },
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            background: 'linear-gradient(90deg, #ffffff 0%, #f5f9ff 100%)',
            borderBottom: '1px solid',
            borderColor: 'divider',
            boxShadow: '0 3px 16px rgba(16, 55, 102, 0.025)',
          }}
        >
          {/* Keep the applied scope visible across list, detail and connection routes. */}
          <Box
            sx={{ minWidth: 0, overflowWrap: 'anywhere', textAlign: 'right' }}
          >
            <Typography variant="caption" color="text.secondary">
              Workspace · {session.organization}
            </Typography>
            <Typography variant="body2">
              {!scope
                ? 'Tracking scope not selected'
                : scope.kind === 'organization'
                  ? 'Organization history'
                  : `Repository · ${repository?.fullName ?? scope.fullName} · ID ${scope.repositoryId}`}
            </Typography>
          </Box>
        </Box>
        <Box
          sx={{
            flexGrow: 1,
            width: '100%',
            px: { xs: 3, md: 5 },
            py: { xs: 4, md: 5 },
            maxWidth: 1440,
            mx: 'auto',
          }}
        >
          <Outlet />
        </Box>
        <Box
          component="footer"
          sx={{
            borderTop: '1px solid',
            borderColor: 'divider',
            background:
              'linear-gradient(100deg, #ffffff 0%, #f4f8ff 58%, #effbfb 100%)',
          }}
        >
          <Box
            aria-hidden="true"
            sx={{
              height: 2,
              background:
                'linear-gradient(90deg, #1268e8 0%, #078fc9 58%, #00aebd 100%)',
              opacity: 0.75,
            }}
          />
          <Box
            sx={{
              maxWidth: 1440,
              mx: 'auto',
              px: { xs: 3, md: 5 },
              py: 2,
              display: 'flex',
              alignItems: { xs: 'flex-start', sm: 'center' },
              justifyContent: 'space-between',
              flexDirection: { xs: 'column', sm: 'row' },
              gap: 0.75,
            }}
          >
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Box
                aria-hidden="true"
                sx={{
                  width: 7,
                  height: 7,
                  flexShrink: 0,
                  borderRadius: '50%',
                  background: 'linear-gradient(135deg, #1268e8, #00aebd)',
                }}
              />
              <Typography
                variant="caption"
                sx={{
                  color: 'primary.dark',
                  fontWeight: 600,
                  letterSpacing: '0.01em',
                }}
              >
                Software delivery intelligence, from code to production.
              </Typography>
            </Box>
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              © {copyrightYear} ChangeGuard. All rights reserved.
            </Typography>
          </Box>
        </Box>
      </Box>
    </Box>
  )
}
