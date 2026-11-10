import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/playfair-display/wght.css'
import '@fontsource-variable/manrope/wght.css'
import '@fontsource-variable/jetbrains-mono/wght.css'
import { App } from './App'
import './styles/tokens.css'
import './styles/global.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
