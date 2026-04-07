import { useEffect } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'

import { AuthProvider } from './auth/AuthProvider'
import { AdminLayout } from './components/admin/AdminLayout'
import { ChatPanel } from './components/chat/ChatPanel'
import { RecaptchaGate } from './components/chat/RecaptchaGate'
import { JobDetailDrawer } from './components/experience/JobDetailDrawer'
import { Footer } from './components/layout/Footer'
import { MobileMenu } from './components/layout/MobileMenu'
import { TopNav } from './components/layout/TopNav'
import { SkillGroupDetail } from './components/skills/SkillGroupDetail'
import { TourProvider } from './components/tour/TourProvider'
import { TourButton } from './components/tour/TourButton'
import { TourOverlay } from './components/tour/TourOverlay'
import { API_BASE_URL } from './config/api'
import { ChatProvider, useChat } from './contexts/ChatContext'
import { DrawerProvider, useDrawer } from './hooks/useDrawer'
import { useProfile } from './hooks/useProfile'
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
import { CodeExamplesAdmin } from './pages/admin/CodeExamplesAdmin'
import { CodeExampleEditor } from './pages/admin/CodeExampleEditor'
import { BlogDetailPage } from './pages/BlogDetailPage'
import { BlogListingPage } from './pages/BlogListingPage'
import { ExperiencePage } from './pages/ExperiencePage'
import { HomePage } from './pages/HomePage'

function ScrollToTop() {
  const { pathname } = useLocation()
  useEffect(() => {
    window.scrollTo(0, 0)
  }, [pathname])
  return null
}

function GlobalDrawers() {
  const { selectedJobId, selectedGroupId, openJob, openSkillGroup, closeJob, closeSkillGroup } = useDrawer()

  return (
    <>
      {selectedJobId && (
        <JobDetailDrawer
          jobId={selectedJobId}
          onClose={closeJob}
          onSkillGroupClick={openSkillGroup}
        />
      )}
      {selectedGroupId && (
        <SkillGroupDetail
          groupId={selectedGroupId}
          onClose={closeSkillGroup}
          onJobClick={openJob}
        />
      )}
    </>
  )
}

function ChatOverlay() {
  const { chatOpen, chatQuery, showRecaptcha, closeChat, handleRecaptchaVerified, cancelRecaptcha } = useChat()
  const { profile } = useProfile()

  const profileImageUrl = profile?.profileImage?.url
    ? `${API_BASE_URL}${profile.profileImage.url}`
    : undefined

  return (
    <>
      {showRecaptcha && (
        <RecaptchaGate onVerified={handleRecaptchaVerified} onCancel={cancelRecaptcha} />
      )}
      {chatOpen && (
        <ChatPanel
          initialQuery={chatQuery ?? undefined}
          onClose={closeChat}
          profileImageUrl={profileImageUrl}
          visible={chatOpen}
        />
      )}
    </>
  )
}

function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <ChatProvider>
      <DrawerProvider>
        <TourProvider>
          <div className="app-layout">
            <ScrollToTop />
            <TopNav />
            <MobileMenu />
            <main className="app-layout__main">
              {children}
            </main>
            <Footer />
            <TourButton />
            <TourOverlay />
            <GlobalDrawers />
            <ChatOverlay />
          </div>
        </TourProvider>
      </DrawerProvider>
    </ChatProvider>
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
          <Route path="code-examples" element={<CodeExamplesAdmin />} />
          <Route path="code-examples/:id" element={<CodeExampleEditor />} />
          <Route path="data-operations" element={<DataOperationsAdmin />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
