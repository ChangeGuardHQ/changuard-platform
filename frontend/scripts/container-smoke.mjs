/** Exercise the actual static/TLS proxy image with disposable upstreams; no GitHub or cloud credentials are used. */
import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { setTimeout as delay } from 'node:timers/promises'

const runtimeImage = process.argv[2] || 'changuard-frontend:ci'
const developmentImage = process.argv[3] || 'changuard-frontend:development'
const network = `cg-frontend-smoke-${randomUUID()}`
const containers = []
const docker = (...args) =>
  execFileSync('docker', args, { encoding: 'utf8' }).trim()
// Echo raw request bodies so proxy checks can detect changes to signed bytes and large numeric identifiers.
const mock = String.raw`
  const { createServer } = require('node:http');
  for (const [port, service] of [[8081, 'connector'], [8082, 'change']]) {
    createServer((req, res) => {
      let body = '';
      req.on('data', chunk => { body += chunk; });
      req.on('end', () => {
        res.setHeader('Content-Type', 'application/json');
        if (req.url.includes('deny=1')) res.statusCode = 401;
        if (req.url.endsWith('/csrf')) {
          const secure = req.headers['x-forwarded-proto'] === 'https' ? '; Secure' : '';
          res.setHeader('Set-Cookie', 'XSRF-TOKEN=test-cookie; Path=/; HttpOnly; SameSite=Lax' + secure);
        }
        res.end(JSON.stringify({ service, path: req.url, body, headers: req.headers }));
      });
    }).listen(port, '0.0.0.0');
  }
`

function start(image, ...args) {
  const id = docker(
    'run',
    '--detach',
    '--network',
    network,
    '--health-interval',
    '1s',
    '--health-start-period',
    '1s',
    ...args,
    image,
  )
  containers.push(id)
  return id
}

function port(container, internal) {
  // Docker assigns loopback host ports dynamically so the test can coexist with a developer's running stack.
  const state = JSON.parse(docker('inspect', container))[0]
  return state.NetworkSettings.Ports[`${internal}/tcp`][0].HostPort
}

async function ready(container) {
  // Liveness only confirms startup; the later HTTP and TLS assertions verify externally observable behavior.
  for (let attempt = 0; attempt < 60; attempt++) {
    const state = JSON.parse(docker('inspect', container))[0].State
    if (!state.Running || state.Health?.Status === 'unhealthy')
      throw new Error(`Container failed readiness: ${container}`)
    if (state.Health?.Status === 'healthy') return
    await delay(1000)
  }
  throw new Error(`Container did not become healthy: ${container}`)
}

function tlsRequest(portNumber, certificate, path, options = {}) {
  // Trust only this test container's local CA; certificate verification stays enabled.
  return new Promise((resolve, reject) => {
    const request = httpsRequest(
      {
        hostname: 'localhost',
        port: portNumber,
        path,
        family: 4,
        ca: certificate,
        method: options.method || 'GET',
        headers: options.headers,
      },
      (response) => {
        let body = ''
        response.setEncoding('utf8')
        response.on('data', (chunk) => {
          body += chunk
        })
        response.on('end', () =>
          resolve({
            status: response.statusCode,
            headers: response.headers,
            body,
          }),
        )
      },
    )
    request.on('error', reject)
    request.end(options.body)
  })
}

docker('network', 'create', network)
try {
  // Reuse the verified development image for the mock server, avoiding an extra test-image dependency.
  const backend = docker(
    'run',
    '--detach',
    '--network',
    network,
    '--network-alias',
    'connector-service',
    '--network-alias',
    'change-service',
    '--entrypoint',
    'node',
    developmentImage,
    '-e',
    mock,
  )
  containers.push(backend)

  // A disposable writable directory uses the runtime UID; application certificate volumes remain untouched.
  const common = [
    '--read-only',
    '--cap-drop',
    'ALL',
    '--security-opt',
    'no-new-privileges:true',
    '--tmpfs',
    '/data:uid=10001,gid=10001,mode=0700',
  ]
  const http = start(runtimeImage, ...common, '--publish', '127.0.0.1::8080')
  await ready(http)
  const origin = `http://127.0.0.1:${port(http, 8080)}`
  const home = await fetch(origin)
  const html = await home.text()
  assert.equal(home.status, 200)
  assert.match(html, /\/assets\/index-[^" ]+\.js/)
  assert.doesNotMatch(html, /\/src\/main|@vite\/client/)
  assert.equal(home.headers.get('cache-control'), 'no-cache')
  for (const path of [
    '/changes/example-id',
    '/connections/github/callback?state=test',
  ]) {
    const response = await fetch(`${origin}${path}`)
    assert.equal(await response.text(), html)
  }
  const asset = html.match(/src="([^" ]+\.js)"/)[1]
  assert.match(
    (await fetch(`${origin}${asset}`)).headers.get('cache-control'),
    /immutable/,
  )
  assert.equal((await fetch(`${origin}/assets/missing.js`)).status, 404)
  for (const path of ['/api', '/api/unknown'])
    assert.equal((await fetch(`${origin}${path}`)).status, 404)
  const auth = `Basic ${Buffer.from('test-user:test-pass').toString('base64')}`
  const headers = { Authorization: auth, 'X-Requested-With': 'XMLHttpRequest' }
  for (const [path, service, expectedPath] of [
    [
      '/api/v1/changes?organizationId=test',
      'change',
      '/api/v1/changes?organizationId=test',
    ],
    ['/api/projection-health', 'change', '/actuator/health/projection'],
    [
      '/api/v1/organizations/test/integrations/github',
      'connector',
      '/api/v1/organizations/test/integrations/github',
    ],
  ]) {
    const response = await fetch(`${origin}${path}`, { headers })
    const result = await response.json()
    assert.equal(result.service, service)
    assert.equal(result.path, expectedPath)
    assert.equal(result.headers.authorization, auth)
    assert.equal(response.headers.get('cache-control'), 'no-store')
  }
  assert.equal((await fetch(`${origin}/api/v1/changes?deny=1`)).status, 401)
  const body = '{"repositoryIds":[9007199254740993]}'
  const selected = await fetch(
    `${origin}/api/v1/organizations/test/integrations/github/install/repositories`,
    {
      method: 'PUT',
      headers: {
        ...headers,
        Cookie: 'XSRF-TOKEN=test-cookie',
        'X-XSRF-TOKEN': 'masked-token',
        'Content-Type': 'application/json',
      },
      body,
    },
  )
  const selection = await selected.json()
  assert.equal(selection.body, body)
  assert.equal(selection.headers.cookie, 'XSRF-TOKEN=test-cookie')
  assert.equal(selection.headers['x-xsrf-token'], 'masked-token')
  const webhookBody = ' { "action": "opened" }\n'
  const webhook = await fetch(`${origin}/api/v1/integrations/github/webhook`, {
    method: 'POST',
    headers: {
      'X-Hub-Signature-256': 'sha256=test-signature',
      'Content-Type': 'application/json',
    },
    body: webhookBody,
  })
  const delivery = await webhook.json()
  assert.equal(delivery.body, webhookBody)
  assert.equal(delivery.headers['x-hub-signature-256'], 'sha256=test-signature')
  assert.equal(docker('exec', http, 'id', '-u'), '10001')
  assert.throws(
    () => docker('exec', http, 'sh', '-c', 'command -v node'),
    /Command failed/,
  )

  // localhost uses Caddy's local CA, exercising TLS without requesting a public ACME certificate.
  const tls = start(
    runtimeImage,
    ...common,
    '--env',
    'SITE_ADDRESS=localhost',
    '--publish',
    '127.0.0.1::8443',
    '--publish',
    '127.0.0.1::8080',
  )
  await ready(tls)
  const certificate = docker(
    'exec',
    tls,
    'cat',
    '/data/caddy/pki/authorities/local/root.crt',
  )
  const response = await tlsRequest(
    port(tls, 8443),
    certificate,
    '/api/v1/integrations/github/csrf',
    { headers },
  )
  assert.equal(response.status, 200)
  assert.equal(JSON.parse(response.body).headers['x-forwarded-proto'], 'https')
  assert.match(response.headers['set-cookie'][0], /HttpOnly.*Secure/)
  const redirect = await new Promise((resolve, reject) => {
    // The HTTP client lets the test set Host independently of the random loopback port.
    const request = httpRequest(
      {
        hostname: '127.0.0.1',
        port: port(tls, 8080),
        path: '/connections',
        headers: { Host: 'localhost' },
      },
      (response) => {
        response.resume()
        resolve(response)
      },
    )
    request.on('error', reject)
    request.end()
  })
  assert.equal(redirect.statusCode, 308)
  assert.equal(redirect.headers.location, 'https://localhost/connections')

  const development = start(developmentImage, '--publish', '127.0.0.1::5173')
  await ready(development)
  assert.match(
    await (await fetch(`http://127.0.0.1:${port(development, 5173)}`)).text(),
    /\/src\/main\.tsx/,
  )
  console.log(
    'PASS: static assets, SPA routes, API routing, auth, CSRF, exact IDs, signed bytes, verified TLS, redirects and development startup',
  )
} catch (error) {
  for (const container of containers)
    console.error(
      spawnSync('docker', ['logs', container], {
        encoding: 'utf8',
      }).output.join(''),
    )
  throw error
} finally {
  // Remove only resources created by this invocation; named application volumes are never touched.
  for (const container of containers.reverse())
    spawnSync('docker', ['rm', '--force', '--volumes', container], {
      stdio: 'ignore',
    })
  spawnSync('docker', ['network', 'rm', network], { stdio: 'ignore' })
}
