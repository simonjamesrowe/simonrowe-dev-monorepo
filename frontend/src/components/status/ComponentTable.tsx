import type { PlatformComponent } from '../../types/platform'

interface ComponentTableProps {
  components: PlatformComponent[]
}

/**
 * The third-party images production declares.
 *
 * A definition list rather than a `<table>`: it is two columns of key/value pairs, it needs
 * to stack rather than scroll on a phone, and a semantic table would buy nothing here.
 */
export function ComponentTable({ components }: ComponentTableProps) {
  if (components.length === 0) {
    return <p className="status-page__empty">No component information available.</p>
  }

  return (
    <dl className="component-table">
      {components.map((component) => (
        <div className="component-table__row" key={component.name}>
          <dt className="component-table__name">{component.name}</dt>
          <dd className="component-table__version">
            <span className="component-table__image">{component.image}</span>
            {component.floating ? (
              <span className="component-table__tag component-table__tag--floating">
                {component.tag} — floating tag
              </span>
            ) : (
              <span className="component-table__tag">{component.tag}</span>
            )}
          </dd>
        </div>
      ))}
    </dl>
  )
}
