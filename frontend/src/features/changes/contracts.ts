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
