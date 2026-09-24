import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it } from 'vitest'
import { demoFetch, resetDemoState } from './mockApi'
import { DEMO_ACCOUNT, DEMO_PRODUCTS } from './fixtures'

type Envelope<T = any> = { success: boolean; data?: T; meta?: { page: number; size: number; total: number }; error?: { code: string } }

async function call<T = any>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}) {
  const res = await demoFetch(`/api${path}`, {
    method,
    headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  return { status: res.status, env: (await res.json()) as Envelope<T> }
}

async function login(): Promise<Record<string, string>> {
  const { env } = await call('POST', '/auth/login', { email: DEMO_ACCOUNT.email, password: DEMO_ACCOUNT.password })
  return { Authorization: `Bearer ${env.data.accessToken}` }
}

beforeEach(() => resetDemoState())

describe('demo fixtures stay in sync with the Flyway seed', () => {
  const migration = (name: string) =>
    readFileSync(resolve(process.cwd(), '../src/main/resources/db/migration', name), 'utf8')

  it('has every V15 product with the same prices', () => {
    const sql = migration('V15__demo_catalog_seed.sql')
    const rows = [...sql.matchAll(/\('(\w+)', '([^']+)', '[^']+', (\d+), (\d+), '[^']+'\)/g)]
    expect(rows).toHaveLength(12)
    for (const [, , name, base, sale] of rows) {
      const p = DEMO_PRODUCTS.find((x) => x.name === name)
      expect(p, name).toBeDefined()
      expect([p!.basePrice, p!.salePrice]).toEqual([Number(base), Number(sale)])
    }
  })

  it('uses the same image paths as V16', () => {
    const sql = migration('V16__local_demo_product_images.sql')
    for (const p of DEMO_PRODUCTS) {
      expect(sql, p.name).toContain(`('${p.name}', '${p.images[0]}')`)
    }
  })
})

describe('demo mock API', () => {
  it('serves the category tree and category listings with page meta', async () => {
    const tree = await call('GET', '/categories')
    expect(tree.env.data.categories.map((c: { name: string }) => c.name)).toEqual(['스킨케어', '메이크업', '헤어/바디'])

    const list = await call('GET', '/categories/2/products?sort=PRICE_ASC&page=0&size=20')
    expect(list.env.meta?.total).toBe(4) // 3 makeup items + the sunscreen mapped to every category
    const prices = list.env.data.map((p: { salePrice: number }) => p.salePrice)
    expect(prices).toEqual([...prices].sort((a, b) => a - b))

    expect((await call('GET', '/categories/99/products')).status).toBe(404)
  })

  it('searches by keyword', async () => {
    const { env } = await call('GET', '/search/products?keyword=' + encodeURIComponent('선크림'))
    expect(env.data.map((p: { productName: string }) => p.productName)).toEqual(['키즈 매일 선크림 SPF50+ PA++++'])
  })

  it('rejects member endpoints without a token', async () => {
    const { status, env } = await call('GET', '/cart')
    expect(status).toBe(401)
    expect(env.error?.code).toBe('UNAUTHORIZED')
  })

  it('runs cart -> order -> payment with coupon, points, and idempotent replay', async () => {
    const auth = await login()
    await call('POST', '/cart/items', { productOptionId: 4, quantity: 2 }, auth) // 레드 블레미쉬 25,200 x2
    const cart = await call('GET', '/cart', undefined, auth)
    expect(cart.env.data.totalAmount).toBe(50400)

    const addr = await call('POST', '/me/addresses', { recipientName: '홍길동', phone: '010-0000-0000', zipcode: '04524', addressMain: '서울시 중구', isDefault: true }, auth)
    const coupons = await call('GET', '/me/coupons', undefined, auth)
    const pct = coupons.env.data.find((c: { discountType: string }) => c.discountType === 'PERCENTAGE')

    const orderBody = { items: [{ productOptionId: 4, quantity: 2 }], deliveryAddressId: addr.env.data.id, couponId: pct.id, usePointAmount: 1000 }
    const created = await call('POST', '/orders', orderBody, { ...auth, 'Idempotency-Key': 'k-1' })
    // 50,400 - 10% (5,040) - 1,000 points, free shipping over 30,000
    expect(created.env.data.amount).toBe(44360)

    const replay = await call('POST', '/orders', orderBody, { ...auth, 'Idempotency-Key': 'k-1' })
    expect(replay.env.data.orderNo).toBe(created.env.data.orderNo)

    const mismatch = await call('POST', '/payments/confirm', { orderNo: created.env.data.orderNo, paymentKey: String(created.env.data.paymentKey), amount: 1 }, auth)
    expect(mismatch.status).toBe(422)

    const paid = await call('POST', '/payments/confirm', { orderNo: created.env.data.orderNo, paymentKey: String(created.env.data.paymentKey), amount: 44360 }, auth)
    expect(paid.env.data.status).toBe('PAID')

    expect((await call('GET', '/cart', undefined, auth)).env.data.items).toHaveLength(0)
    const summary = await call('GET', '/me/summary', undefined, auth)
    expect(summary.env.data).toMatchObject({ pointBalance: 4000, usableCouponCount: 2, totalOrderCount: 1 })
    const detail = await call('GET', `/orders/${created.env.data.orderNo}`, undefined, auth)
    expect(detail.env.data).toMatchObject({ status: 'PAID', discountAmount: 5040, deliveryFee: 0, finalPaymentAmount: 44360 })
  })

  it('charges shipping below the free-shipping threshold and enforces coupon minimums', async () => {
    const auth = await login()
    const addr = await call('POST', '/me/addresses', { recipientName: '홍길동', phone: '010', zipcode: '1', addressMain: 'x', isDefault: true }, auth)
    const coupons = await call('GET', '/me/coupons', undefined, auth)
    const pct = coupons.env.data.find((c: { discountType: string }) => c.discountType === 'PERCENTAGE')
    const items = [{ productOptionId: 13, quantity: 1 }] // 핸드크림 8,900

    const below = await call('POST', '/orders', { items, deliveryAddressId: addr.env.data.id, couponId: pct.id }, { ...auth, 'Idempotency-Key': 'a' })
    expect(below.status).toBe(400)

    const plain = await call('POST', '/orders', { items, deliveryAddressId: addr.env.data.id }, { ...auth, 'Idempotency-Key': 'b' })
    expect(plain.env.data.amount).toBe(8900 + 3000)
  })
})
