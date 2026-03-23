import { useState, useEffect } from 'react'
import { useParams, Link } from 'react-router-dom'
import { fetchProductDetail, placeOrder } from '../api'

export default function ProductDetail() {
  const { skuId } = useParams()
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(true)
  const [qty, setQty] = useState(1)
  const [toast, setToast] = useState(null)
  const [ordering, setOrdering] = useState(false)

  useEffect(() => {
    fetchProductDetail(skuId)
      .then(setDetail)
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [skuId])

  const handleOrder = async () => {
    setOrdering(true)
    try {
      const result = await placeOrder(skuId, qty)
      setToast(`下单成功！订单号: ${result.orderNo}`)
      // 刷新详情（获取最新库存）
      const updated = await fetchProductDetail(skuId)
      setDetail(updated)
    } catch (e) {
      setToast('下单失败: ' + e.message)
    } finally {
      setOrdering(false)
      setTimeout(() => setToast(null), 4000)
    }
  }

  if (loading) return <div className="loading">加载中...</div>
  if (!detail || !detail.product) return <div className="loading">商品不存在</div>

  const { product, realtimeStock, promotions, degradeInfo } = detail

  return (
    <div>
      <Link to="/" className="back-link">← 返回列表</Link>
      <div className="detail-page">
        <h2>{product.name}</h2>
        <div className="price">¥{product.price}</div>
        <p style={{ color: '#666', margin: '0.5rem 0' }}>{product.description}</p>

        <div className="stock-info">
          实时库存: <strong>{realtimeStock}</strong> 件
        </div>

        {/* 促销标签 */}
        {promotions && promotions.length > 0 && (
          <div style={{ margin: '1rem 0' }}>
            {promotions.map((p, i) => (
              <span key={i} className="promo-tag">{p.label}</span>
            ))}
          </div>
        )}

        {/* 降级提示 —— 技术点5: 超时降级的可视化 */}
        {degradeInfo && (
          <div className="degrade-warning">
            ⚠ 部分信息降级显示:
            {Object.entries(degradeInfo).map(([k, v]) => (
              <div key={k}> · {v}</div>
            ))}
          </div>
        )}

        {/* 下单区域 */}
        <div style={{ marginTop: '1.5rem', display: 'flex', alignItems: 'center' }}>
          <span>数量:</span>
          <input
            type="number"
            className="qty-input"
            min="1"
            max={realtimeStock}
            value={qty}
            onChange={e => setQty(Math.max(1, parseInt(e.target.value) || 1))}
          />
          <button
            className="btn btn-primary"
            onClick={handleOrder}
            disabled={ordering || realtimeStock <= 0}
          >
            {ordering ? '提交中...' : realtimeStock <= 0 ? '已售罄' : '立即下单'}
          </button>
        </div>

        {/* 技术说明 */}
        <div style={{ marginTop: '2rem', padding: '1rem', background: '#f8f9fa', borderRadius: '8px', fontSize: '0.8rem', color: '#666' }}>
          <strong>技术点说明：</strong>
          <ul style={{ marginTop: '0.5rem', paddingLeft: '1.2rem' }}>
            <li>点击"立即下单"→ 请求发到 Kafka（削峰填谷），立即返回订单号</li>
            <li>Kafka 消费端异步扣库存（原子SQL + 幂等检查 + Outbox事务）</li>
            <li>本页数据由 CompletableFuture 并发查询3个数据源聚合而成</li>
            <li>商品信息来自 Redis 缓存（互斥锁防击穿 + TTL抖动防雪崩）</li>
          </ul>
        </div>
      </div>

      {toast && <div className="toast">{toast}</div>}
    </div>
  )
}
