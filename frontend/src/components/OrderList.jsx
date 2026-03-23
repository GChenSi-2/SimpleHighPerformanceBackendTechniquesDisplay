import { useState, useEffect } from 'react'
import { fetchOrders } from '../api'

const STATUS_MAP = {
  0: { text: '处理中', cls: 'status-pending' },
  1: { text: '已完成', cls: 'status-done' },
  2: { text: '失败', cls: 'status-fail' },
}

export default function OrderList() {
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)

  const load = () => {
    fetchOrders()
      .then(setOrders)
      .catch(console.error)
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  if (loading) return <div className="loading">加载中...</div>

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <h2>订单列表</h2>
        <button className="btn btn-secondary" onClick={load}>刷新</button>
      </div>

      {orders.length === 0 ? (
        <div className="loading">暂无订单</div>
      ) : (
        <table className="order-table">
          <thead>
            <tr>
              <th>订单号</th>
              <th>SKU</th>
              <th>数量</th>
              <th>金额</th>
              <th>状态</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            {orders.map(o => {
              const s = STATUS_MAP[o.status] || STATUS_MAP[0]
              return (
                <tr key={o.orderNo}>
                  <td style={{ fontFamily: 'monospace', fontSize: '0.85rem' }}>{o.orderNo}</td>
                  <td>{o.skuId}</td>
                  <td>{o.quantity}</td>
                  <td style={{ color: '#e74c3c' }}>¥{o.amount}</td>
                  <td className={s.cls}>{s.text}</td>
                  <td style={{ fontSize: '0.85rem' }}>{o.createdAt}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </div>
  )
}
