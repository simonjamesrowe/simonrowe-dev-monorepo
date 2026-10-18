import { useEffect, useMemo, useRef } from 'react'
import { useAuth0 } from '@auth0/auth0-react'
import { BookOpen, CookingPot, ListTodo, Menu, Refrigerator, ShoppingBasket } from 'lucide-react'
import { NavLink, Route, Routes, useLocation, useParams } from 'react-router-dom'

import { createMealsApi } from './api'
import { MealsApiContext, useLibrary, useRatings, useShopping, useUpNext, type Library as LibraryData } from './data'
import { Home } from './Home'
import { Kitchen } from './Kitchen'
import { Library } from './Library'
import { More } from './More'
import { RecipeEditor } from './RecipeEditor'
import { RecipePage } from './RecipePage'
import { Shop } from './Shop'
import { Empty, Loading } from './ui'
import { UpNext } from './UpNext'
import type { Rating } from './types'

export const ROLES_CLAIM = 'https://simonrowe.dev/roles'
export const ADMIN_ROLE = 'DEV_PORTAL_ADMIN'
/** Opens meals without making someone a site admin. Loading the books stays with the admin. */
export const MEALS_ROLE = 'MEALS_USER'

/**
 * Meals is private: the site admin, plus anyone given the meals role. The backend enforces that
 * on every route; this gate is only so a signed-in visitor without either role sees a sentence
 * instead of a wall of 403s.
 *
 * Signing in is automatic: a visitor who is not signed in is sent straight to Auth0, once per
 * page load. If that fails (an error comes back), the button stays as the way to try again.
 */
export function App() {
  const { isLoading, isAuthenticated, user, error, loginWithRedirect, logout, getAccessTokenSilently } = useAuth0()
  const api = useMemo(() => createMealsApi(() => getAccessTokenSilently()), [getAccessTokenSilently])
  const { pathname } = useLocation()
  const redirected = useRef(false)

  useEffect(() => {
    if (isLoading || isAuthenticated || error || redirected.current) return
    redirected.current = true
    void loginWithRedirect({ appState: { returnTo: pathname } })
  }, [isLoading, isAuthenticated, error, loginWithRedirect, pathname])

  if (isLoading) return <Loading />
  if (error) return <SignIn message={`Sign-in failed: ${error.message}`} onSignIn={() => loginWithRedirect({ appState: { returnTo: pathname } })} />
  if (!isAuthenticated) {
    return <SignIn message="Signing you in…" onSignIn={() => loginWithRedirect({ appState: { returnTo: pathname } })} />
  }
  const roles: unknown = user?.[ROLES_CLAIM]
  const isAdmin = Array.isArray(roles) && roles.includes(ADMIN_ROLE)
  if (!isAdmin && !(Array.isArray(roles) && roles.includes(MEALS_ROLE))) {
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
      <Shell canImport={isAdmin} onSignOut={() => logout({ logoutParams: { returnTo: window.location.origin } })} />
    </MealsApiContext.Provider>
  )
}

function SignIn({ onSignIn, message }: { onSignIn: () => void; message?: string }) {
  return (
    <div className="meals-gate">
      <h1>Meals</h1>
      {message && <p className="meals-muted" role="status">{message}</p>}
      <button type="button" className="meals-button meals-button--primary" onClick={onSignIn}>Sign in</button>
    </div>
  )
}

function Shell({ canImport, onSignOut }: { canImport: boolean; onSignOut: () => void }) {
  const { library, isLoading, error } = useLibrary()
  const ratings = useRatings()
  const upNext = useUpNext()
  const shopping = useShopping()
  const toBuy = (shopping.data?.items ?? []).filter((i) => !i.basket).length

  const nav = [
    { to: '/', label: 'Cook', icon: CookingPot, end: true, badge: 0 },
    { to: '/recipes', label: 'Recipes', icon: BookOpen, end: false, badge: 0 },
    { to: '/up-next', label: 'Up next', icon: ListTodo, end: true, badge: upNext.data?.length ?? 0 },
    { to: '/shop', label: 'Shop', icon: ShoppingBasket, end: true, badge: toBuy },
    { to: '/kitchen', label: 'Kitchen', icon: Refrigerator, end: true, badge: 0 },
    { to: '/more', label: 'More', icon: Menu, end: true, badge: 0 },
  ]

  let page
  if (error || ratings.error) {
    page = <div className="meals-page"><p className="meals-error" role="alert">Could not load the library: {(error ?? ratings.error)!.message}</p></div>
  } else if (isLoading || !library || !ratings.data) {
    page = <Loading />
  } else {
    const known = new Set(library.ingredients.keys())
    page = (
      <Routes>
        <Route path="/" element={<Home recipes={library.recipes} byId={library.byId} canImport={canImport} />} />
        <Route path="/recipes" element={<Library recipes={library.recipes} ratings={ratings.data} />} />
        <Route path="/recipes/new" element={<RecipeEditor knownIngredients={known} />} />
        <Route path="/recipes/:id" element={<RecipeRoute library={library} ratings={ratings.data} />} />
        <Route path="/recipes/:id/edit" element={<EditRoute library={library} />} />
        <Route path="/up-next" element={<UpNext byId={library.byId} templates={library.templates} />} />
        <Route path="/shop" element={<Shop />} />
        <Route path="/kitchen" element={<Kitchen ingredients={library.ingredients} />} />
        <Route path="/more" element={<More canImport={canImport} onSignOut={onSignOut} />} />
        <Route path="*" element={<Empty icon={<CookingPot size={32} />} title="Nothing here" />} />
      </Routes>
    )
  }

  return (
    <div className="meals-shell">
      <nav className="meals-nav" aria-label="Main">
        <span className="meals-nav__brand">meals<span>.simonrowe.dev</span></span>
        {nav.map(({ to, label, icon: Icon, end, badge }) => (
          <NavLink key={to} to={to} end={end} className={({ isActive }) => `meals-nav__item ${isActive ? 'meals-nav__item--on' : ''}`}>
            <Icon size={20} aria-hidden="true" />
            <span>{label}</span>
            {badge > 0 && <span className="meals-nav__badge" aria-label={`${badge} items`}>{badge}</span>}
          </NavLink>
        ))}
      </nav>
      <main className="meals-main">{page}</main>
    </div>
  )
}

// Top-level, not nested in Shell: a component defined inside another is a new type on every
// render, which would remount the page and reset its state on each update.
function RecipeRoute({ library, ratings }: { library: LibraryData; ratings: Rating[] }) {
  const { id } = useParams()
  const recipe = id ? library.byId.get(id) : undefined
  if (!recipe) {
    return <div className="meals-page"><Empty icon={<BookOpen size={32} />} title="No such recipe" /></div>
  }
  return <RecipePage key={recipe.id} recipe={recipe} ratings={ratings} ingredients={library.ingredients} />
}

function EditRoute({ library }: { library: LibraryData }) {
  const { id } = useParams()
  const recipe = id ? library.byId.get(id) : undefined
  if (!recipe) {
    return <div className="meals-page"><Empty icon={<BookOpen size={32} />} title="No such recipe" /></div>
  }
  return <RecipeEditor key={recipe.id} recipe={recipe} knownIngredients={new Set(library.ingredients.keys())} />
}
