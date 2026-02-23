import ReactGA from 'react-ga4'

const measurementId = import.meta.env.VITE_GA_MEASUREMENT_ID

export function initializeAnalytics(): void {
  if (!measurementId) {
    return
  }

  ReactGA.initialize(measurementId)
}

export function trackPageView(page: string): void {
  if (!measurementId) {
    return
  }

  ReactGA.send({
    hitType: 'pageview',
    page,
  })
}

export function trackHomepageEvent(action: string, label: string): void {
  if (!measurementId) {
    return
  }

  ReactGA.event({
    category: 'homepage',
    action,
    label,
  })
}
