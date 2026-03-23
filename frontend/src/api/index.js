const BASE = '/api'

export async function fetchProducts() {
  const res = await fetch(`${BASE}/products`)
  return res.json()
}

export async function fetchProductDetail(skuId) {
  const res = await fetch(`${BASE}/products/${skuId}`)
  if (!res.ok) throw new Error('商品不存在')
  return res.json()
}

export async function placeOrder(skuId, quantity) {
  const res = await fetch(`${BASE}/orders`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ skuId, quantity })
  })
  return res.json()
}

export async function fetchOrders() {
  const res = await fetch(`${BASE}/orders`)
  return res.json()
}

export async function fetchOrder(orderNo) {
  const res = await fetch(`${BASE}/orders/${orderNo}`)
  if (!res.ok) return null
  return res.json()
}
