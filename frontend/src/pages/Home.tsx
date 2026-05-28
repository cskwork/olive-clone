import { useQuery } from '@tanstack/react-query'
import { apiGetPage } from '@/lib/api'
import type { ProductListItem } from '@/lib/types'

// Foundation placeholder: proves the SPA -> /api proxy -> backend stack works.
// Replaced by the real Home (rails, hero, ranking) and Product Card in Phase 1/2.
export default function Home() {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['products', { page: 0, size: 20 }],
    queryFn: ({ signal }) => apiGetPage<ProductListItem[]>('/products?page=0&size=20', signal),
  })

  return (
    <main className="app-container" style={{ padding: 'var(--space-5)' }}>
      <h1 style={{ fontSize: 24, fontWeight: 800, marginBottom: 'var(--space-6)' }}>올리브 스토어</h1>

      {isLoading && <p>불러오는 중…</p>}
      {isError && (
        <p style={{ color: 'var(--sale-red)' }}>상품을 불러오지 못했습니다: {(error as Error).message}</p>
      )}

      {data && (
        <ul style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 'var(--space-4)' }}>
          {data.data.map((p) => (
            <li
              key={p.productId}
              style={{
                border: '1px solid var(--grey-200)',
                borderRadius: 'var(--radius-md)',
                overflow: 'hidden',
              }}
            >
              {p.thumbnailUrl && (
                <img
                  src={p.thumbnailUrl}
                  alt={p.productName}
                  style={{ width: '100%', aspectRatio: '1 / 1', objectFit: 'cover' }}
                  loading="lazy"
                />
              )}
              <div style={{ padding: 'var(--space-3)' }}>
                <p style={{ fontSize: 12, color: 'var(--grey-500)' }}>{p.brandName}</p>
                <p style={{ fontWeight: 600 }}>{p.productName}</p>
                <p style={{ fontWeight: 700 }}>{p.salePrice.toLocaleString('ko-KR')}원</p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
