import { lazy, Suspense, useEffect } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'

import { AuthProvider } from './auth/AuthProvider'
import { ThemeProvider } from './contexts/ThemeContext'
import { ChatPanel } from './components/chat/ChatPanel'
import { RecaptchaGate } from './components/chat/RecaptchaGate'
import { LoadingIndicator } from './components/common/LoadingIndicator'
import { JobDetailDrawer } from './components/experience/JobDetailDrawer'
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
import { HomePage } from './pages/HomePage'

// The landing page (HomePage) stays in the initial bundle so it renders
// instantly. Every other route is code-split so its dependencies — the
// MDXEditor stack (admin), react-syntax-highlighter and mermaid (blog
// detail) — load only when that route is visited, not on first paint.
const named = <M, K extends keyof M>(loader: () => Promise<M>, key: K) =>
  lazy(() => loader().then((m) => ({ default: m[key] as React.ComponentType })))

const ProfilePage = named(() => import('./pages/ProfilePage'), 'ProfilePage')
const ExperiencePage = named(() => import('./pages/ExperiencePage'), 'ExperiencePage')
const BlogListingPage = named(() => import('./pages/BlogListingPage'), 'BlogListingPage')
const BlogDetailPage = named(() => import('./pages/BlogDetailPage'), 'BlogDetailPage')
const NewsEventsPage = named(() => import('./pages/NewsEventsPage'), 'NewsEventsPage')

const AdminLayout = named(() => import('./components/admin/AdminLayout'), 'AdminLayout')
const AggregatedContentAdmin = named(() => import('./pages/admin/AggregatedContentAdmin'), 'AggregatedContentAdmin')
const BlogEditor = named(() => import('./pages/admin/BlogEditor'), 'BlogEditor')
const BlogsAdmin = named(() => import('./pages/admin/BlogsAdmin'), 'BlogsAdmin')
const ContentSourcesAdmin = named(() => import('./pages/admin/ContentSourcesAdmin'), 'ContentSourcesAdmin')
const JobEditor = named(() => import('./pages/admin/JobEditor'), 'JobEditor')
const JobsAdmin = named(() => import('./pages/admin/JobsAdmin'), 'JobsAdmin')
const DashboardAdmin = named(() => import('./pages/admin/DashboardAdmin'), 'DashboardAdmin')
const DataOperationsAdmin = named(() => import('./pages/admin/DataOperationsAdmin'), 'DataOperationsAdmin')
const MediaAdmin = named(() => import('./pages/admin/MediaAdmin'), 'MediaAdmin')
const ProfileAdmin = named(() => import('./pages/admin/ProfileAdmin'), 'ProfileAdmin')
const SkillGroupEditor = named(() => import('./pages/admin/SkillGroupEditor'), 'SkillGroupEditor')
const SkillsAdmin = named(() => import('./pages/admin/SkillsAdmin'), 'SkillsAdmin')
const TagsAdmin = named(() => import('./pages/admin/TagsAdmin'), 'TagsAdmin')
const TourStepEditor = named(() => import('./pages/admin/TourStepEditor'), 'TourStepEditor')
const TourStepsAdmin = named(() => import('./pages/admin/TourStepsAdmin'), 'TourStepsAdmin')
const CodeExamplesAdmin = named(() => import('./pages/admin/CodeExamplesAdmin'), 'CodeExamplesAdmin')
const CodeExampleEditor = named(() => import('./pages/admin/CodeExampleEditor'), 'CodeExampleEditor')

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
    <ThemeProvider>
    <BrowserRouter>
      <Suspense fallback={<LoadingIndicator />}>
      <Routes>
        <Route element={<PublicLayout><HomePage /></PublicLayout>} path="/" />
        <Route element={<PublicLayout><ProfilePage /></PublicLayout>} path="/profile" />
        <Route element={<PublicLayout><ExperiencePage /></PublicLayout>} path="/experience" />
        <Route element={<PublicLayout><BlogListingPage /></PublicLayout>} path="/blogs" />
        <Route element={<PublicLayout><BlogDetailPage /></PublicLayout>} path="/blogs/:id" />
        <Route element={<PublicLayout><NewsEventsPage /></PublicLayout>} path="/news-events" />
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
          <Route path="aggregated-content" element={<AggregatedContentAdmin />} />
          <Route path="content-sources" element={<ContentSourcesAdmin />} />
        </Route>
      </Routes>
      </Suspense>
    </BrowserRouter>
    </ThemeProvider>
  )
}

export default App
