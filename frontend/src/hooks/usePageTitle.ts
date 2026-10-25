import { useEffect } from 'react'

/** Reflect route changes in the browser title independently of a page's API loading state. */
export function usePageTitle(title: string) {
  useEffect(() => {
    document.title = `${title} | ChangeGuard`
  }, [title])
}
