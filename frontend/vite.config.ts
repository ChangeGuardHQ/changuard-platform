import react from '@vitejs/plugin-react'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, process.cwd(), '')
  const change = {
    target: environment.CHANGE_API_PROXY_TARGET || 'http://localhost:8082',
    changeOrigin: true,
  }
  const connector = {
    target: environment.CONNECTOR_API_PROXY_TARGET || 'http://localhost:8081',
    changeOrigin: true,
  }
  // Fixed route ownership preserves backend authentication/CSRF and avoids browser CORS exceptions.
  // Targets are server-only variables: no server credentials or private App configuration enter the bundle.
  const proxy = {
    '/api/v1/changes': change,
    '/api/v1/organizations/': connector,
    '/api/v1/integrations/github/': connector,
    '^/api/projection-health$': {
      ...change,
      rewrite: () => '/actuator/health/projection',
    },
  }
  return {
    plugins: [react()],
    server: { proxy },
    preview: { proxy },
    test: {
      clearMocks: true,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
    },
  }
})
