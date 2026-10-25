/** Read contracts mirror change-service DTOs; missing provider facts remain null. */
export type Actor = { type: 'HUMAN' | 'BOT' | 'AI' | 'UNKNOWN'; id: string }
export type Change = {
  id: string
  organizationId: string
  kind: 'PULL_REQUEST' | 'COMMIT'
  status: 'OPEN' | 'MERGED' | 'COMMITTED'
  provider: string
  integrationId: string
  repositoryId: string
  repositoryFullName: string
  pullRequestNumber: number | null
  title: string | null
  commitSha: string
  sourceBranch: string | null
  targetBranch: string | null
  actor: Actor | null
  mergedAt: string | null
  openedAt: string | null
  headCommitSha: string | null
  activityAt: string
  receivedAt: string
  createdAt: string
}
export type ChangePage = {
  items: Change[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
export type ChangeTimeline = {
  changeId: string
  organizationId: string
  entries: {
    eventId: string
    type: string
    occurredAt: string
    commitSha: string
    actor: Actor | null
  }[]
}

const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
const nullableText = (value: unknown) =>
  value == null || typeof value === 'string'
const actor = (value: unknown) =>
  value == null ||
  (record(value) &&
    typeof value.id === 'string' &&
    typeof value.type === 'string' &&
    ['HUMAN', 'BOT', 'AI', 'UNKNOWN'].includes(value.type))

/** Reject incompatible payloads at the query boundary before identifiers, dates or actor objects reach rendering. */
export function isChange(value: unknown): value is Change {
  if (!record(value)) return false
  return (
    [
      'id',
      'organizationId',
      'repositoryId',
      'repositoryFullName',
      'provider',
      'integrationId',
      'commitSha',
      'activityAt',
      'receivedAt',
      'createdAt',
    ].every((key) => typeof value[key] === 'string' && value[key] !== '') &&
    ((value.kind === 'COMMIT' && value.status === 'COMMITTED') ||
      (value.kind === 'PULL_REQUEST' &&
        (value.status === 'OPEN' || value.status === 'MERGED') &&
        Number.isSafeInteger(value.pullRequestNumber) &&
        Number(value.pullRequestNumber) > 0)) &&
    [
      'title',
      'headCommitSha',
      'sourceBranch',
      'targetBranch',
      'openedAt',
      'mergedAt',
    ].every((key) => nullableText(value[key])) &&
    actor(value.actor)
  )
}

// Counts must agree with page size before the view uses them to correct an out-of-range page.
export function isChangePage(value: unknown): value is ChangePage {
  return (
    record(value) &&
    Array.isArray(value.items) &&
    value.items.every(isChange) &&
    ['page', 'size', 'totalElements', 'totalPages'].every(
      (key) => Number.isSafeInteger(value[key]) && Number(value[key]) >= 0,
    ) &&
    Number(value.size) > 0 &&
    value.items.length <= Number(value.size) &&
    value.items.length <= Number(value.totalElements) &&
    value.totalPages ===
      Math.ceil(Number(value.totalElements) / Number(value.size))
  )
}

// Validate shape here; the query additionally binds the timeline to its requested change and organization.
export function isTimeline(value: unknown): value is ChangeTimeline {
  return (
    record(value) &&
    typeof value.changeId === 'string' &&
    typeof value.organizationId === 'string' &&
    Array.isArray(value.entries) &&
    value.entries.every(
      (entry: unknown) =>
        record(entry) &&
        ['eventId', 'type', 'occurredAt', 'commitSha'].every(
          (key) => typeof entry[key] === 'string',
        ) &&
        actor(entry.actor),
    )
  )
}
