import type { ReactNode } from 'react'

/** A shared label identifies both the desktop link and its accessible compact-navigation control. */
export type NavigationItem = {
  label: string
  path: string
  icon: ReactNode
}
