import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import { ThemeProvider } from '../contexts/ThemeContext'
import App from './App'
import '../styles.css'

/**
 * Term Time's entry point.
 *
 * Imports the MAIN site's stylesheet and theme provider, deliberately: Term Time should read as
 * part of simonrowe.dev, and it reuses the site's chat components which carry those class names.
 *
 * There is no auth provider. Term Time is entirely public — the restricted tier exists in the
 * backend and is simply never reachable from here, which is the point: nothing the assistant can
 * say to a visitor depends on who they are.
 *
 * ThemeProvider shares the `theme-preference` key with the main site, so a visitor who set light
 * mode there arrives here in light mode.
 */
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider>
      <App />
    </ThemeProvider>
  </StrictMode>,
)
