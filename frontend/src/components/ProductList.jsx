import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchProducts } from '../api'

export default function ProductList() {
  const [products, setProducts] = useState([])
  const [loading, setLoading] = useState(true)
  const navigate = useNavigate()

  useEffect(() => {
    fetchProducts()
      .then(setProducts)
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="loading">加载中...</div>

  return (
    <div>
      <h2 style={{ marginBottom: '1.5rem' }}>商品列表</h2>
      <div className="product-grid">
        {products.map(p => (
          <div
            key={p.skuId}
            className="product-card"
            onClick={() => navigate(`/product/${p.skuId}`)}
          >
            <h3>{p.name}</h3>
            <div className="price">¥{p.price}</div>
            <div className="stock">库存: {p.stock} 件</div>
            <div style={{ color: '#999', fontSize: '0.8rem', marginTop: '0.3rem' }}>
              SKU: {p.skuId}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
