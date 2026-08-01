import { Link } from 'react-router-dom'
import { usePageTitle } from '../hooks/usePageTitle'

export function NotFoundPage() {
  usePageTitle('Page not found')

  return (
    <div className="not-found-page">
      <p className="not-found-page__code">404</p>
      <h1 className="not-found-page__title">Page not found</h1>
      <p className="not-found-page__message">
        That link has moved or never existed. Nothing is broken &mdash; you have just landed
        somewhere that is not there.
      </p>
      <div className="not-found-page__actions">
        <Link className="button button--primary" to="/">
          Back to home
        </Link>
        <Link className="button button--secondary" to="/blogs">
          Read the blog
        </Link>
      </div>
    </div>
  )
}
