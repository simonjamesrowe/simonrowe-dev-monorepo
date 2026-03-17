import { Link } from 'react-router-dom'

const sections = [
  { path: '/admin/blogs', label: 'Blogs', description: 'Manage blog posts' },
  { path: '/admin/jobs', label: 'Jobs', description: 'Manage employment history' },
  { path: '/admin/skills', label: 'Skills', description: 'Manage skills and skill groups' },
  { path: '/admin/profile', label: 'Profile', description: 'Edit profile information' },
  { path: '/admin/tags', label: 'Tags', description: 'Manage content tags' },
  { path: '/admin/tour-steps', label: 'Tour Steps', description: 'Manage interactive tour' },
  { path: '/admin/media', label: 'Media', description: 'Upload and manage media' },
]

export function AdminDashboard() {
  return (
    <div>
      <h1>Dashboard</h1>
      <div className="admin-dashboard-grid">
        {sections.map((section) => (
          <Link key={section.path} to={section.path} className="admin-dashboard-card">
            <h3>{section.label}</h3>
            <p>{section.description}</p>
          </Link>
        ))}
      </div>
    </div>
  )
}
