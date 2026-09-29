/**
 * PROTOTYPE — the menu model and the portfolio, in memory. In the real build the portfolio
 * comes from the CMS (`GET /api/portfolio`); these four rows stand in for it.
 */
import {
  Activity,
  Briefcase,
  Factory,
  HeartHandshake,
  Layers,
  Mail,
  Newspaper,
  PenLine,
  Plug,
  School,
  Stethoscope,
  UserRound,
  type LucideIcon,
} from 'lucide-react'

export interface PortfolioProject {
  slug: string
  name: string
  tagline: string
  icon: LucideIcon
  /** Hue for the silhouette tint, 0-360. */
  hue: number
  status: 'COMING_SOON'
}

export const PORTFOLIO: PortfolioProject[] = [
  {
    slug: 'software-factory',
    name: 'Software Factory',
    tagline: 'An autonomous loop that reviews, deploys and watches this site.',
    icon: Factory,
    hue: 212,
    status: 'COMING_SOON',
  },
  {
    slug: 'term-time',
    name: 'Term Time',
    tagline: "A school assistant for parents, grounded in what the school publishes.",
    icon: School,
    hue: 152,
    status: 'COMING_SOON',
  },
  {
    slug: 'co-parents',
    name: 'Co-Parents',
    tagline: 'Shared family admin for parents across two homes.',
    icon: HeartHandshake,
    hue: 336,
    status: 'COMING_SOON',
  },
  {
    slug: 'clinicians-veil',
    name: "Clinician's Veil",
    tagline: 'Details soon.',
    icon: Stethoscope,
    hue: 266,
    status: 'COMING_SOON',
  },
]

export interface NavItem {
  label: string
  to: string
  description: string
  icon: LucideIcon
  comingSoon?: boolean
  project?: PortfolioProject
}

export interface NavGroup {
  key: string
  label: string
  items: NavItem[]
  /** Where the group's "see all" link goes, when it has one. */
  overview?: { label: string; to: string }
}

export const PORTFOLIO_ROUTE = '/prototype/portfolio'

export const NAV_GROUPS: NavGroup[] = [
  {
    key: 'about',
    label: 'About',
    items: [
      { label: 'Profile', to: '/about', description: 'Who I am and how I lead', icon: UserRound },
      { label: 'Experience', to: '/about#roles', description: 'Roles, teams and what shipped', icon: Briefcase },
      { label: 'Skills', to: '/about#skills', description: 'Languages, platforms and practices', icon: Layers },
      { label: 'Contact', to: '/about#contact', description: 'Get in touch or grab the CV', icon: Mail },
    ],
  },
  {
    key: 'portfolio',
    label: 'Portfolio',
    items: PORTFOLIO.map(project => ({
      label: project.name,
      to: PORTFOLIO_ROUTE,
      description: project.tagline,
      icon: project.icon,
      comingSoon: true,
      project,
    })),
    overview: { label: 'All projects', to: PORTFOLIO_ROUTE },
  },
  {
    key: 'insights',
    label: 'Insights',
    items: [
      { label: 'Blog', to: '/blogs', description: 'Engineering and leadership writing', icon: PenLine },
      { label: 'News & Events', to: '/news-events', description: 'What is happening across AI and engineering', icon: Newspaper },
    ],
  },
  {
    key: 'under-the-hood',
    label: 'Under the hood',
    items: [
      { label: 'MCP server', to: '/mcp', description: 'Plug this site into your own AI tools', icon: Plug },
      { label: 'Platform status', to: '/status', description: 'What is running in production, and the changelog', icon: Activity },
    ],
  },
]

export function groupIsActive(group: NavGroup, pathname: string): boolean {
  return group.items.some(item => item.to.split('#')[0] === pathname)
    || group.overview?.to === pathname
}
