import {
  Activity,
  Box,
  Briefcase,
  LayoutGrid,
  Layers,
  Mail,
  Newspaper,
  PenLine,
  Plug,
  UserRound,
  type LucideIcon,
} from 'lucide-react'

import { hasDetailPage, type PortfolioProject } from '../../types/portfolio'

export interface NavItem {
  label: string
  to: string
  description: string
  icon: LucideIcon
  /** Shown as a "Soon" badge — portfolio projects that are not yet launched. */
  comingSoon?: boolean
}

export interface NavGroup {
  key: 'about' | 'portfolio' | 'insights' | 'under-the-hood'
  label: string
  items: NavItem[]
}

/**
 * The public site's menu structure, shared by the desktop header and the phone menu sheet so
 * the two cannot drift. Portfolio is empty — and so hidden — until projects are loaded from the
 * CMS; everything else is fixed.
 */
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
    // Filled from the CMS once the portfolio ships; a group with no items is not rendered.
    items: [],
  },
  {
    key: 'insights',
    label: 'Insights',
    items: [
      { label: 'Blog', to: '/blogs', description: 'Engineering and leadership writing', icon: PenLine },
      {
        label: 'News & Events',
        to: '/news-events',
        description: 'What is happening across AI and engineering',
        icon: Newspaper,
      },
    ],
  },
  {
    key: 'under-the-hood',
    label: 'Under the hood',
    items: [
      { label: 'MCP server', to: '/mcp', description: 'Plug this site into your own AI tools', icon: Plug },
      {
        label: 'Platform status',
        to: '/status',
        description: 'What is running in production, and the changelog',
        icon: Activity,
      },
    ],
  },
]

/**
 * A group is current when the page is one of its destinations or sits beneath one — so a
 * blog post keeps Insights lit. The hash is ignored: `/about#roles` and `/about` are one page.
 */
export function groupIsActive(group: NavGroup, pathname: string): boolean {
  return group.items.some(item => {
    const path = item.to.split('#')[0]
    return pathname === path || pathname.startsWith(`${path}/`)
  })
}

/** Groups with at least one destination; an empty group would open an empty panel. */
export function visibleGroups(groups: NavGroup[] = NAV_GROUPS): NavGroup[] {
  return groups.filter(group => group.items.length > 0)
}

const ALL_PROJECTS: NavItem = {
  label: 'All projects',
  to: '/portfolio',
  description: 'Everything I am building',
  icon: LayoutGrid,
}

/**
 * The menu with the Portfolio group filled from the CMS: one item per published project (a
 * Coming soon one points at the Portfolio page, since it has no page of its own), then
 * "All projects". With no published projects the group stays empty and so is hidden; if the
 * list could not be loaded it still offers "All projects", which will explain itself.
 */
export function navGroupsWithPortfolio(
  projects: PortfolioProject[],
  loadFailed = false,
): NavGroup[] {
  const items: NavItem[] = projects.map(project => ({
    label: project.name,
    to: hasDetailPage(project) ? `/portfolio/${project.slug}` : '/portfolio',
    description: project.tagline,
    icon: Box,
    comingSoon: project.status === 'COMING_SOON',
  }))
  const portfolioItems = items.length > 0 || loadFailed ? [...items, ALL_PROJECTS] : []
  return NAV_GROUPS.map(group => (group.key === 'portfolio' ? { ...group, items: portfolioItems } : group))
}
