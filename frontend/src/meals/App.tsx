import { useMemo } from 'react'
import { useAuth0 } from '@auth0/auth0-react'
import { BookOpen, CookingPot, LogOut, Upload } from 'lucide-react'
import { NavLink, Route, Routes, useLocation, useParams } from 'react-router-dom'

import { createMealsApi } from './api'
import { MealsApiContext, useLibrary, useRatings, type Library as LibraryData } from './data'
import { Home } from './Home'
import { ImportPage } from './ImportPage'
import { Library } from './Library'
import { RecipePage } from './RecipePage'
import { Empty, Loading } from './ui'
import type { Rating } from './types'

export const ROLES_CLAIM = 'https://simonrowe.dev/roles'
export const ADMIN_ROLE = 'DEV_PORTAL_ADMIN'

/**
 * Meals is for the site owner alone. The backend enforces that on every route; this gate is
 * only so a signed-in visitor without the role sees a sentence instead of a wall of 403s.
 */
export function App() {
  const { isLoading, isAuthenticated, user, error, loginWithRedirect, logout, getAccessTokenSilently } = useAuth0()
  const api = useMemo(() => createMealsApi(() => getAccessTokenSilently()), [getAccessTokenSilently])
  const { pathname } = useLocation()

  if (isLoading) return <Loading />
  if (error) return <SignIn message={`Sign-in failed: ${error.message}`} onSignIn={() => loginWithRedirect()} />
  if (!isAuthenticated) {
    return <SignIn onSignIn={() => loginWithRedirect({ appState: { returnTo: pathname } })} />
  }
  const roles = user?.[ROLES_CLAIM]
  if (!Array.isArray(roles) || !roles.includes(ADMIN_ROLE)) {
    return (
      <div className="meals-gate">
        <h1>Meals</h1>
        <p className="meals-muted">This is a private app.</p>
        <button type="button" className="meals-button" onClick={() => logout({ logoutParams: { returnTo: window.location.origin } })}>Sign out</button>
      </div>
    )
  }

  return (
    <MealsApiContext.Provider value={api}>
      <Shell onSignOut={() => logout({ logoutParams: { returnTo: window.location.origin } })} />
    </MealsApiContext.Provider>
  )
}

function SignIn({ onSignIn, message }: { onSignIn: () => void; message?: string }) {
  return (
    <div className="meals-gate">
      <h1>Meals</h1>
      {message && <p className="meals-error" role="alert">{message}</p>}
      <button type="button" className="meals-button meals-button--primary" onClick={onSignIn}>Sign in</button>
    </div>
  )
}

const NAV = [
  { to: '/', label: 'Cook', icon: CookingPot, end: true },
  { to: '/recipes', label: 'Recipes', icon: BookOpen, end: false },
  { to: '/import', label: 'Import', icon: Upload, end: true },
]

function Shell({ onSignOut }: { onSignOut: () => void }) {
  const { library, isLoading, error } = useLibrary()
  const ratings = useRatings()

  let page
  if (error || ratings.error) {
    page = <div className="meals-page"><p className="meals-error" role="alert">Could not load the library: {(error ?? ratings.error)!.message}</p></div>
  } else if (isLoading || !library || !ratings.data) {
    page = <Loading />
  } else {
    page = (
      <Routes>
        <Route path="/" element={<Home recipes={library.recipes} ratings={ratings.data} />} />
        <Route path="/recipes" element={<Library recipes={library.recipes} ratings={ratings.data} />} />
        <Route path="/recipes/:id" element={<RecipeRoute library={library} ratings={ratings.data} />} />
        <Route path="/import" element={<ImportPage />} />
        <Route path="*" element={<Empty icon={<CookingPot size={32} />} title="Nothing here" />} />
      </Routes>
    )
  }

  return (
    <div className="meals-shell">
      <nav className="meals-nav" aria-label="Main">
        <span className="meals-nav__brand">meals<span>.simonrowe.dev</span></span>
        {NAV.map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} className={({ isActive }) => `meals-nav__item ${isActive ? 'meals-nav__item--on' : ''}`}>
            <Icon size={20} aria-hidden="true" />
            <span>{label}</span>
          </NavLink>
        ))}
        <button type="button" className="meals-nav__item meals-nav__signout" onClick={onSignOut}>
          <LogOut size={20} aria-hidden="true" /><span>Sign out</span>
        </button>
      </nav>
      <main className="meals-main">{page}</main>
    </div>
  )
}

// Top-level, not nested in Shell: a component defined inside another is a new type on every
// render, which would remount the recipe page and reset its portions on each update.
function RecipeRoute({ library, ratings }: { library: LibraryData; ratings: Rating[] }) {
  const { id } = useParams()
  const recipe = id ? library.byId.get(id) : undefined
  if (!recipe) {
    return <div className="meals-page"><Empty icon={<BookOpen size={32} />} title="No such recipe" /></div>
  }
  return <RecipePage key={recipe.id} recipe={recipe} ratings={ratings} ingredients={library.ingredients} />
}
