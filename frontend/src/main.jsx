import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter, Routes, Route, Link } from 'react-router-dom'
import ProductList from './components/ProductList'
import ProductDetail from './components/ProductDetail'
import OrderList from './components/OrderList'
import './index.css'

function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <nav className="navbar">
          <h1>商品库存系统</h1>
          <div className="nav-links">
            <Link to="/">商品列表</Link>
            <Link to="/orders">订单列表</Link>
          </div>
        </nav>
        <main className="container">
          <Routes>
            <Route path="/" element={<ProductList />} />
            <Route path="/product/:skuId" element={<ProductDetail />} />
            <Route path="/orders" element={<OrderList />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)
