import { BrowserRouter, Route, Routes } from 'react-router-dom'

import { HomePage } from './pages/HomePage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<HomePage />} path="/" />
        <Route element={<HomePage />} path="/skills-groups/:groupId" />
        <Route element={<HomePage />} path="/jobs/:jobId" />
      </Routes>
    </BrowserRouter>
  )
}

export default App
