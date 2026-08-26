import { lazy, Suspense, useEffect } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom'

import { AuthProvider } from './auth/AuthProvider'
import { ThemeProvider } from './contexts/ThemeContext'
import { ChatPanel } from './components/chat/ChatPanel'
import { RecaptchaGate } from './components/chat/RecaptchaGate'
import { LoadingIndicator } from './components/common/LoadingIndicator'
import { NarrationAudioProvider } from './components/narration/NarrationAudioProvider'
import { NarrationPlayerBar } from './components/narration/NarrationPlayerBar'
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
const McpPage = named(() => import('./pages/McpPage'), 'McpPage')
const NotFoundPage = named(() => import('./pages/NotFoundPage'), 'NotFoundPage')

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

/**
 * Redirects a legacy `/blog/:id` link to its canonical `/blogs/:id` address.
 *
 * `<Navigate>` cannot interpolate route params, so the id is read here and the
 * replacement is built by hand. `replace` keeps the stale URL out of history, so Back
 * leaves the site rather than bouncing between the two addresses.
 */
function LegacyBlogDetailRedirect() {
  const { id } = useParams<{ id: string }>()
  return <Navigate replace to={id === undefined ? '/blogs' : `/blogs/${id}`} />
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
            {/* Holds no state — the track and the audio element live in
                NarrationAudioProvider above <Routes> — so it can remount with this
                layout on every navigation while playback carries on. Being here is
                also what keeps it out of /admin, with no path sniffing. */}
            <NarrationPlayerBar />
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
      {/* Auth context is global: public pages need it for favourites (hearts render
          logged-out state and the heart click runs loginWithPopup). */}
      <AuthProvider>
      {/* Above <Routes> deliberately, and inside AuthProvider deliberately. PublicLayout
          wraps each route individually, so it — and anything inside it — remounts on
          navigation; a provider there would lose the track on the first route change,
          which is the exact thing the docked player exists to prevent. It needs to be
          inside AuthProvider because generating audio requires a session. */}
      <NarrationAudioProvider>
      <Suspense fallback={<LoadingIndicator />}>
      <Routes>
        <Route element={<PublicLayout><HomePage /></PublicLayout>} path="/" />
        <Route element={<PublicLayout><ProfilePage /></PublicLayout>} path="/profile" />
        <Route element={<PublicLayout><ExperiencePage /></PublicLayout>} path="/experience" />
        <Route element={<PublicLayout><BlogListingPage /></PublicLayout>} path="/blogs" />
        <Route element={<PublicLayout><BlogDetailPage /></PublicLayout>} path="/blogs/:id" />
        <Route element={<PublicLayout><NewsEventsPage /></PublicLayout>} path="/news-events" />
        <Route element={<PublicLayout><McpPage /></PublicLayout>} path="/mcp" />
        {/* Legacy singular paths, still shared externally. */}
        <Route element={<Navigate replace to="/blogs" />} path="/blog" />
        <Route element={<LegacyBlogDetailRedirect />} path="/blog/:id" />
        <Route path="/admin" element={<AdminLayout />}>
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
        <Route element={<PublicLayout><NotFoundPage /></PublicLayout>} path="*" />
      </Routes>
      </Suspense>
      </NarrationAudioProvider>
      </AuthProvider>
    </BrowserRouter>
    </ThemeProvider>
  )
}

export default App
