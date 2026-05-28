import { Routes, Route } from 'react-router-dom'
import Home from './pages/Home'

// Route table is expanded during the Phase 2 vertical slice
// (category, PDP, cart, checkout). For now the storefront shell renders Home.
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="*" element={<Home />} />
    </Routes>
  )
}
