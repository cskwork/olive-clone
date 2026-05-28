import { Routes, Route } from 'react-router-dom'
import Layout from './components/layout/Layout'
import Home from './pages/Home'
import Login from './pages/auth/Login'
import Signup from './pages/auth/Signup'
import Dev from './pages/Dev'

export default function App() {
  return (
    <Routes>
      {/* Auth pages: no shared chrome (full-page layouts) */}
      <Route path="/login" element={<Login />} />
      <Route path="/signup" element={<Signup />} />

      {/* Shell: Header + Outlet + Footer */}
      <Route element={<Layout />}>
        <Route path="/" element={<Home />} />
        <Route path="/dev" element={<Dev />} />
        <Route path="*" element={<Home />} />
      </Route>
    </Routes>
  )
}
