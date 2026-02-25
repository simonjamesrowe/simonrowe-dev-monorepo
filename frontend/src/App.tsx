import { BrowserRouter, Route, Routes } from 'react-router-dom'

import { AuthProvider } from './auth/AuthProvider'
import { AdminLayout } from './components/admin/AdminLayout'
import { TourProvider } from './components/tour/TourProvider'
import { AdminDashboard } from './pages/admin/AdminDashboard'
import { BlogEditor } from './pages/admin/BlogEditor'
import { BlogsAdmin } from './pages/admin/BlogsAdmin'
import { JobEditor } from './pages/admin/JobEditor'
import { JobsAdmin } from './pages/admin/JobsAdmin'
import { MediaAdmin } from './pages/admin/MediaAdmin'
import { ProfileAdmin } from './pages/admin/ProfileAdmin'
import { SkillEditor } from './pages/admin/SkillEditor'
import { SkillGroupEditor } from './pages/admin/SkillGroupEditor'
import { SkillsAdmin } from './pages/admin/SkillsAdmin'
import { TagsAdmin } from './pages/admin/TagsAdmin'
import { TourStepEditor } from './pages/admin/TourStepEditor'
import { TourStepsAdmin } from './pages/admin/TourStepsAdmin'
import { BlogDetailPage } from './pages/BlogDetailPage'
import { BlogListingPage } from './pages/BlogListingPage'
import { HomePage } from './pages/HomePage'

function App() {
  return (
    <BrowserRouter>
      <TourProvider>
        <Routes>
          <Route element={<HomePage />} path="/" />
          <Route element={<HomePage />} path="/skills-groups/:groupId" />
          <Route element={<HomePage />} path="/jobs/:jobId" />
          <Route element={<BlogListingPage />} path="/blogs" />
          <Route element={<BlogDetailPage />} path="/blogs/:id" />
          <Route
            path="/admin"
            element={
              <AuthProvider>
                <AdminLayout />
              </AuthProvider>
            }
          >
            <Route index element={<AdminDashboard />} />
            <Route path="blogs" element={<BlogsAdmin />} />
            <Route path="blogs/:id" element={<BlogEditor />} />
            <Route path="jobs" element={<JobsAdmin />} />
            <Route path="jobs/:id" element={<JobEditor />} />
            <Route path="skills" element={<SkillsAdmin />} />
            <Route path="skills/:id" element={<SkillEditor />} />
            <Route path="skill-groups/:id" element={<SkillGroupEditor />} />
            <Route path="profile" element={<ProfileAdmin />} />
            <Route path="tags" element={<TagsAdmin />} />
            <Route path="tour-steps" element={<TourStepsAdmin />} />
            <Route path="tour-steps/:id" element={<TourStepEditor />} />
            <Route path="media" element={<MediaAdmin />} />
          </Route>
        </Routes>
      </TourProvider>
    </BrowserRouter>
  )
}

export default App
