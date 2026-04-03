import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'

import { AuthProvider } from './auth/AuthProvider'
import { AdminLayout } from './components/admin/AdminLayout'
import { Footer } from './components/layout/Footer'
import { MobileMenu } from './components/layout/MobileMenu'
import { TopNav } from './components/layout/TopNav'
import { BlogEditor } from './pages/admin/BlogEditor'
import { BlogsAdmin } from './pages/admin/BlogsAdmin'
import { JobEditor } from './pages/admin/JobEditor'
import { JobsAdmin } from './pages/admin/JobsAdmin'
import { DashboardAdmin } from './pages/admin/DashboardAdmin'
import { DataOperationsAdmin } from './pages/admin/DataOperationsAdmin'
import { MediaAdmin } from './pages/admin/MediaAdmin'
import { ProfileAdmin } from './pages/admin/ProfileAdmin'
import { SkillGroupEditor } from './pages/admin/SkillGroupEditor'
import { SkillsAdmin } from './pages/admin/SkillsAdmin'
import { TagsAdmin } from './pages/admin/TagsAdmin'
import { TourStepEditor } from './pages/admin/TourStepEditor'
import { TourStepsAdmin } from './pages/admin/TourStepsAdmin'
import { BlogDetailPage } from './pages/BlogDetailPage'
import { BlogListingPage } from './pages/BlogListingPage'
import { ExperiencePage } from './pages/ExperiencePage'
import { HomePage } from './pages/HomePage'
import { ProfilePage } from './pages/ProfilePage'

function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="app-layout">
      <TopNav />
      <MobileMenu />
      <main className="app-layout__main">
        {children}
      </main>
      <Footer />
    </div>
  )
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<PublicLayout><HomePage /></PublicLayout>} path="/" />
        <Route element={<PublicLayout><ExperiencePage /></PublicLayout>} path="/experience" />
        <Route element={<PublicLayout><BlogListingPage /></PublicLayout>} path="/blogs" />
        <Route element={<PublicLayout><BlogDetailPage /></PublicLayout>} path="/blogs/:id" />
        <Route element={<PublicLayout><ProfilePage /></PublicLayout>} path="/profile" />
        <Route
          path="/admin"
          element={
            <AuthProvider>
              <AdminLayout />
            </AuthProvider>
          }
        >
          <Route index element={<Navigate to="/admin/dashboard" replace />} />
          <Route path="dashboard" element={<DashboardAdmin />} />
          <Route path="blogs" element={<BlogsAdmin />} />
          <Route path="blogs/:id" element={<BlogEditor />} />
          <Route path="jobs" element={<JobsAdmin />} />
          <Route path="jobs/:id" element={<JobEditor />} />
          <Route path="skills" element={<SkillsAdmin />} />
          <Route path="skill-groups/:id" element={<SkillGroupEditor />} />
          <Route path="profile" element={<ProfileAdmin />} />
          <Route path="tags" element={<TagsAdmin />} />
          <Route path="tour-steps" element={<TourStepsAdmin />} />
          <Route path="tour-steps/:id" element={<TourStepEditor />} />
          <Route path="media" element={<MediaAdmin />} />
          <Route path="data-operations" element={<DataOperationsAdmin />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
