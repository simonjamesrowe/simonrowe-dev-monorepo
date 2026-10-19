import { useEffect, useRef, useState } from 'react'
import { Check, ClipboardCopy, PackageCheck, Plus, ShoppingBasket, X } from 'lucide-react'
import { Link } from 'react-router-dom'

import { track } from '../analytics/analytics'
import { useMine, useShopping } from './data'
import { QuickAdd } from './QuickAdd'
import { Empty, Loading, Sheet } from './ui'
import type { Shop as ShopName, ShoppingItem } from './types'

const SHOPS: ShopName[] = ['Ocado', 'Amazon', 'Deliveroo']

/** The aisle the server files the owner's own typed extras under. */
const ADDED_BY_YOU = 'Added by you'

async function copy(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    area.remove()
    return ok
  }
}

/** Up next, minus the kitchen, to work through while filling an online basket. */
export function Shop() {
  const shopping = useShopping()
  const [shop, setShop] = useState<ShopName>('Ocado')
  const [extra, setExtra] = useState('')
  const [flash, setFlash] = useState<string | null>(null)
  const [pasting, setPasting] = useState(false)
  const basket = useMine((api, v: { name: string; shop: ShopName | null }) => api.basket(v.name, v.shop))
  const addExtra = useMine((api, name: string) => api.addExtra(name))
  const removeExtra = useMine((api, name: string) => api.removeExtra(name))
  const delivered = useMine((api) => api.delivered())
  const clearBasket = useMine((api) => api.clearBasket())

  // Once per visit, when the list first loads: ticking items refetches it, and each refetch is
  // not another view.
  const viewed = useRef(false)
  const loadedCount = shopping.data ? shopping.data.items.length : null
  useEffect(() => {
    if (viewed.current || loadedCount === null) return
    viewed.current = true
    track('Shopping List Viewed', { itemCount: loadedCount })
  }, [loadedCount])

  if (shopping.isLoading) return <Loading />
  const items = shopping.data?.items ?? []
  const upNextCount = shopping.data?.upNextCount ?? 0
  const todo = items.filter((i) => !i.basket)
  const inBasket = items.filter((i) => i.basket)
  const groups = new Map<string, ShoppingItem[]>()
  for (const item of items) groups.set(item.line.aisle, [...(groups.get(item.line.aisle) ?? []), item])

  async function copyText(text: string, what: string) {
    const ok = await copy(text)
    setFlash(ok ? `Copied ${what}` : 'Copy failed')
    setTimeout(() => setFlash(null), 1500)
  }

  function submitExtra() {
    const name = extra.trim()
    if (name) addExtra.mutate(name, { onSuccess: () => setExtra('') })
  }

  if (items.length === 0 && upNextCount === 0) {
    return (
      <div className="meals-page">
        <header className="meals-page__header"><h1>Shopping</h1></header>
        <Empty icon={<ShoppingBasket size={32} />} title="Nothing to buy yet">
          The list is whatever <Link to="/up-next">Up next</Link> needs that the kitchen doesn't have.
        </Empty>
      </div>
    )
  }

  return (
    <div className="meals-page">
      <header className="meals-page__header">
        <h1>Shopping</h1>
        <p className="meals-muted">For {upNextCount} recipe{upNextCount === 1 ? '' : 's'} up next, minus what's in. Tick each item as it goes in the basket.</p>
      </header>

      <div className="meals-shop-bar">
        <div className="meals-segmented meals-segmented--small" role="radiogroup" aria-label="Shop">
          {SHOPS.map((s) => (
            <button key={s} type="button" role="radio" aria-checked={s === shop}
              className={s === shop ? 'meals-segmented__on' : ''} onClick={() => setShop(s)}>{s}</button>
          ))}
        </div>
        <button type="button" className="meals-button meals-button--small" disabled={!todo.length}
          onClick={() => copyText(todo.map((i) => i.searchTerm).join('\n'), `${todo.length} items`)}>
          <ClipboardCopy size={14} /> Copy what's left
        </button>
      </div>
      {flash && <div className="meals-toast" role="status">{flash}</div>}
      {items.length === 0 && <p className="meals-muted">You already have everything Up next needs.</p>}

      {[...groups].map(([aisle, list]) => (
        // Extras are whatever the owner typed, so replay masks that aisle.
        <section key={aisle} className="meals-aisle" data-amp-mask={aisle === ADDED_BY_YOU ? '' : undefined}>
          <h2 className="meals-aisle__title">{aisle}</h2>
          <ul className="meals-shop-list">
            {list.map((item) => (
              <li key={item.line.name} className={`meals-shop-item ${item.basket ? 'meals-shop-item--done' : ''}`}>
                <button type="button" className="meals-shop-item__check" aria-pressed={!!item.basket}
                  aria-label={`${item.line.name} in basket`}
                  onClick={() => basket.mutate({ name: item.line.name, shop: item.basket ? null : shop })}>
                  {item.basket && <Check size={16} />}
                </button>
                <span className="meals-shop-item__body">
                  <span className="meals-shop-item__term">{item.searchTerm}</span>
                  <span className="meals-shop-item__why">
                    {item.basket ? `in ${item.basket} basket` : item.line.why}
                    {item.line.recipes.length > 0 && ` · ${item.line.recipes.slice(0, 2).join(', ')}${item.line.recipes.length > 2 ? ` +${item.line.recipes.length - 2}` : ''}`}
                  </span>
                </span>
                <button type="button" className="meals-icon-button" aria-label={`Copy ${item.line.name}`}
                  onClick={() => copyText(item.line.name, item.line.name)}><ClipboardCopy size={16} /></button>
                {aisle === ADDED_BY_YOU && (
                  <button type="button" className="meals-icon-button" aria-label={`Remove ${item.line.name}`}
                    onClick={() => removeExtra.mutate(item.line.name)}><X size={16} /></button>
                )}
              </li>
            ))}
          </ul>
        </section>
      ))}

      <div className="meals-row meals-row--fill">
        <input className="meals-input" placeholder="Add something else (bin bags, coffee…)" aria-label="Add something else"
          value={extra} onChange={(e) => setExtra(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') submitExtra() }} />
        <button type="button" className="meals-button" onClick={submitExtra} disabled={!extra.trim()}><Plus size={16} /> Add</button>
      </div>

      <section className="meals-panel">
        <h2 className="meals-panel__title"><PackageCheck size={18} aria-hidden="true" /> Delivery arrived?</h2>
        <p className="meals-muted meals-small">Put everything ticked into the kitchen, or paste the order confirmation so substitutions and missing items are handled.</p>
        <div className="meals-row">
          <button type="button" className="meals-button meals-button--primary" disabled={!inBasket.length || delivered.isPending}
            onClick={() => delivered.mutate(undefined)}>
            Put {inBasket.length} ticked item{inBasket.length === 1 ? '' : 's'} in the kitchen
          </button>
          <button type="button" className="meals-button" onClick={() => setPasting(true)}>Paste order instead</button>
        </div>
      </section>

      {pasting && (
        <Sheet title="Paste your order" onClose={() => setPasting(false)}>
          <QuickAdd mode="order" onDone={() => { clearBasket.mutate(undefined); setPasting(false) }} />
        </Sheet>
      )}
    </div>
  )
}
