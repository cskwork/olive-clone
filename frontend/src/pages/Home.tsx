import { useQuery } from '@tanstack/react-query'
import { apiGetPage } from '@/lib/api'
import type { ProductListItem } from '@/lib/types'
import ProductCard from '@/components/ProductCard/ProductCard'
import styles from './Home.module.css'

export default function Home() {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['products', { page: 0, size: 20 }],
    queryFn: ({ signal }) => apiGetPage<ProductListItem[]>('/products?page=0&size=20', signal),
  })

  return (
    <div className={styles.page}>
      <div className="app-container">
        <section className={styles.section} aria-label="추천 상품">
          <h2 className={styles.sectionTitle}>추천 상품</h2>

          {isLoading && (
            <div className={styles.grid} aria-busy="true" aria-label="상품 로딩 중">
              {Array.from({ length: 10 }, (_, i) => (
                <div key={i} className={styles.skeleton} aria-hidden="true" />
              ))}
            </div>
          )}

          {isError && (
            <p className={styles.errorMsg} role="alert">
              상품을 불러오지 못했습니다: {(error as Error).message}
            </p>
          )}

          {data && (
            <ul className={styles.grid}>
              {data.data.map((product) => (
                <li key={product.productId}>
                  <ProductCard product={product} />
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  )
}
