export interface NavigationItem {
  id: string
  label: string
}

interface SidebarProps {
  aboutImageUrl: string
  items: NavigationItem[]
  onNavigate?: (section: string) => void
}

const iconMap: Record<string, string> = {
  experience: 'EX',
  skills: 'SK',
  blog: 'BL',
  contact: 'CT',
}

export function Sidebar({ aboutImageUrl, items, onNavigate }: SidebarProps) {
  const navigateTo = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' })
    onNavigate?.(id)
  }

  return (
    <aside className="sidebar" aria-label="Desktop navigation">
      <nav>
        <ul>
          {items.map((item) => (
            <li key={item.id}>
              <button onClick={() => navigateTo(item.id)} type="button">
                {item.id === 'about' ? (
                  <img alt="About" className="sidebar__about-icon" src={aboutImageUrl} />
                ) : (
                  <span aria-hidden="true" className="sidebar__icon" data-testid={`${item.id}-icon`}>
                    {iconMap[item.id] ?? '::'}
                  </span>
                )}
                <span>{item.label}</span>
              </button>
            </li>
          ))}
        </ul>
      </nav>
    </aside>
  )
}
