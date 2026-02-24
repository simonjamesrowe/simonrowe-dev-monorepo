import { BrowserRouter, Route, Routes } from 'react-router-dom'

import { TourProvider } from './components/tour/TourProvider'
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
        </Routes>
      </TourProvider>
    </BrowserRouter>
  )
}

export default App
