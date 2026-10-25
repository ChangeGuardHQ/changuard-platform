import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './app/App'

// index.html owns this mount point; StrictMode also exercises effect cleanup during development.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
