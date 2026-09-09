/**
 * Auth0 tenant configuration, shared by the main site and Term Time.
 *
 * Lives in config/ rather than auth/ because the two apps are forbidden from importing each
 * other's components (see the no-restricted-imports boundary in eslint.config.js) but must not
 * drift on which tenant they authenticate against. Config is shared; components are not.
 *
 * The redirect URI is deliberately NOT here — it differs per app, and each must be registered in
 * the Auth0 dashboard's Allowed Callback URLs.
 */
export const AUTH0_DOMAIN = 'dev-igsu3mpz.us.auth0.com'
export const AUTH0_CLIENT_ID = 'UiV5ijJH99uVE88BZzNcrKeefVgLqYi7'
export const AUTH0_AUDIENCE = 'https://api.simonrowe.dev'
