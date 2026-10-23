import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  // Credential/storage spies and browser globals must not leak between workspace tests.
  vi.restoreAllMocks()
})
