import type { Change } from '../features/changes/contracts'

/** Test-only API facts never populate the live application or its empty state. */
export const change: Change = {
  id: 'ee7c3434-15d4-48a5-8305-9efee4554444',
  organizationId: 'local',
  kind: 'PULL_REQUEST',
  status: 'OPEN',
  provider: 'github',
  integrationId: 'integration',
  repositoryId: '12345',
  repositoryFullName: 'ChangeGuardHQ/changuard-platform',
  pullRequestNumber: 42,
  title: 'Connect live changes',
  commitSha: 'a'.repeat(40),
  sourceBranch: 'feature/ui',
  targetBranch: 'main',
  actor: { type: 'UNKNOWN', id: 'contributor' },
  openedAt: '2026-10-23T13:00:00Z',
  mergedAt: null,
  headCommitSha: 'a'.repeat(40),
  activityAt: '2026-10-23T13:00:00Z',
  receivedAt: '2026-10-23T13:00:01Z',
  createdAt: '2026-10-23T13:00:02Z',
}
// Precision tests use raw JSON text instead: a Number larger than 2^53 can lose digits before serialization here.
export const jsonResponse = (value: unknown) =>
  new Response(JSON.stringify(value), {
    headers: { 'Content-Type': 'application/json' },
  })
