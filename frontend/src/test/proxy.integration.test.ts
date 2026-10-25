// @vitest-environment node
import { createServer as createHttpServer } from 'node:http'
import type { IncomingMessage, Server, ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import type { ViteDevServer } from 'vite'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'

const seen: {
  service: string
  path: string
  authorization: string | undefined
  cookie: string | undefined
  csrf: string | undefined
  body: string
}[] = []
const upstreams: Server[] = []
let vite: ViteDevServer
let origin: string

/** Real HTTP forwarding checks catch proxy/cookie regressions that mocked fetch cannot observe. */
async function upstream(service: string) {
  const server = createHttpServer(
    (request: IncomingMessage, response: ServerResponse) => {
      let body = ''
      request.on('data', (chunk) => {
        body += chunk
      })
      request.on('end', () => {
        seen.push({
          service,
          path: request.url!,
          authorization: request.headers.authorization,
          cookie: request.headers.cookie,
          csrf: request.headers['x-xsrf-token'] as string | undefined,
          body,
        })
        response.setHeader('Content-Type', 'application/json')
        if (request.url?.endsWith('/csrf'))
          response.setHeader(
            'Set-Cookie',
            'XSRF-TOKEN=test-cookie; Path=/; HttpOnly; SameSite=Lax',
          )
        response.end(JSON.stringify({ service }))
      })
    },
  )
  upstreams.push(server)
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`
}

beforeAll(async () => {
  // Temporary loopback upstreams replace deployment targets before Vite loads its proxy configuration.
  vi.stubEnv('CHANGE_API_PROXY_TARGET', await upstream('change'))
  vi.stubEnv('CONNECTOR_API_PROXY_TARGET', await upstream('connector'))
  vite = await createServer({
    root: fileURLToPath(new URL('../../', import.meta.url)),
    configFile: fileURLToPath(new URL('../../vite.config.ts', import.meta.url)),
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    appType: 'custom',
  })
  await vite.listen()
  origin = `http://127.0.0.1:${(vite.httpServer!.address() as AddressInfo).port}`
}, 20000)

afterAll(async () => {
  // Close real listeners before restoring proxy targets so this fixture cannot outlive its test suite.
  await vite?.close()
  await Promise.all(
    upstreams.map(
      (server) => new Promise<void>((resolve) => server.close(() => resolve())),
    ),
  )
  vi.unstubAllEnvs()
})

describe('development API proxy', () => {
  it('routes scoped change reads and projection health to change-service with caller authentication', async () => {
    const headers = { Authorization: 'Basic dGVzdC1yZWFkZXI6dGVzdC1wYXNz' }
    await fetch(`${origin}/api/v1/changes?organizationId=local`, { headers })
    await fetch(`${origin}/api/projection-health`, { headers })
    expect(seen.filter((request) => request.service === 'change')).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: '/api/v1/changes?organizationId=local',
          authorization: headers.Authorization,
        }),
        expect.objectContaining({
          path: '/actuator/health/projection',
          authorization: headers.Authorization,
        }),
      ]),
    )
  })

  it('retains the connector HttpOnly CSRF cookie, header and exact selection body', async () => {
    const response = await fetch(`${origin}/api/v1/integrations/github/csrf`)
    expect(response.headers.get('set-cookie')).toContain('HttpOnly')
    const cookie = response.headers.get('set-cookie')!.split(';')[0]
    await fetch(
      `${origin}/api/v1/organizations/local/integrations/github/install/repositories`,
      {
        method: 'PUT',
        headers: {
          Cookie: cookie,
          'X-XSRF-TOKEN': 'masked-token',
          Authorization: 'Basic dGVzdDphZG1pbg==',
          'Content-Type': 'application/json',
        },
        body: '{"repositoryIds":[9007199254740993]}',
      },
    )
    expect(seen).toContainEqual(
      expect.objectContaining({
        service: 'connector',
        cookie: 'XSRF-TOKEN=test-cookie',
        csrf: 'masked-token',
        authorization: 'Basic dGVzdDphZG1pbg==',
        body: '{"repositoryIds":[9007199254740993]}',
      }),
    )
  })
})
