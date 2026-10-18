import { useState } from 'react'
import { Copy, KeyRound, LogOut, Palette, Trash2 } from 'lucide-react'

import { useMine, useTokens } from './data'
import { ImportPage } from './ImportPage'
import { ThemeToggle } from './ui'

/**
 * Settings: appearance, Claude Code access, signing out, and (for the site admin only, which the
 * server enforces too) loading recipe bundles.
 */
export function More({ onSignOut, canImport = true }: { onSignOut: () => void; canImport?: boolean }) {
  const tokens = useTokens()
  const [label, setLabel] = useState('Claude Code')
  const [minted, setMinted] = useState<string | null>(null)
  const mint = useMine((api, l: string) => api.mintToken(l))
  const revoke = useMine((api, id: string) => api.revokeToken(id))
  const endpoint = `${window.location.origin}/api/meals/mcp`
  const command = minted ? `claude mcp add --transport http meals ${endpoint} --header "Authorization: Bearer ${minted}"` : ''

  return (
    <div className="meals-page">
      <header className="meals-page__header"><h1>More</h1></header>

      <section className="meals-panel">
        <h2 className="meals-panel__title"><Palette size={18} aria-hidden="true" /> Appearance</h2>
        <p className="meals-muted meals-small">Light or dark. It follows your device until you choose, and simonrowe.dev shares the choice.</p>
        <div className="meals-row"><ThemeToggle withLabel /></div>
      </section>

      <section className="meals-panel">
        <h2 className="meals-panel__title"><KeyRound size={18} aria-hidden="true" /> Claude Code</h2>
        <p className="meals-muted meals-small">
          Lets Claude Code read your recipes and kitchen, plan Up next and build the shopping list, through a private MCP server.
          A token is shown once: copy the command straight into a terminal.
        </p>
        {minted ? (
          <div className="meals-minted">
            <code className="meals-code">{command}</code>
            <div className="meals-row">
              <button type="button" className="meals-button meals-button--small" onClick={() => navigator.clipboard?.writeText(command)}><Copy size={14} /> Copy</button>
              <button type="button" className="meals-button meals-button--small" onClick={() => setMinted(null)}>Done</button>
            </div>
          </div>
        ) : (
          <div className="meals-row meals-row--fill">
            <input className="meals-input" aria-label="What the token is for" value={label} maxLength={80} onChange={(e) => setLabel(e.target.value)} />
            <button type="button" className="meals-button meals-button--primary" disabled={mint.isPending}
              onClick={() => mint.mutate(label, { onSuccess: (r) => setMinted(r.value) })}>Make a token</button>
          </div>
        )}
        {mint.error && <p className="meals-error" role="alert">{mint.error.message}</p>}
        {(tokens.data?.length ?? 0) > 0 && (
          <ul className="meals-tokens">
            {tokens.data!.map((t) => (
              <li key={t.id} className={t.revokedAt ? 'meals-tokens__revoked' : ''}>
                <span><strong>{t.label}</strong> <code>{t.hint}…</code></span>
                <span className="meals-muted meals-small">
                  {t.revokedAt ? 'revoked' : t.lastUsedAt ? `last used ${new Date(t.lastUsedAt).toLocaleDateString('en-GB')}` : 'never used'}
                </span>
                {!t.revokedAt && (
                  <button type="button" className="meals-icon-button" aria-label={`Revoke ${t.label}`} onClick={() => revoke.mutate(t.id)}><Trash2 size={16} /></button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <button type="button" className="meals-button meals-more__signout" onClick={onSignOut}><LogOut size={16} /> Sign out</button>

      {canImport && <ImportPage />}
    </div>
  )
}
