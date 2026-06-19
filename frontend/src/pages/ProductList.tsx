// TODO: Flesh out with category/brand product grid, sort controls, and infinite scroll.
// API: GET /api/categories/:id/products or /api/brands/:id/products
// Route param: useParams().id
import { useParams } from 'react-router-dom'
import styles from './ProductList.module.css'

export default function ProductList() {
  const { id } = useParams<{ id: string }>()

  return (
    <div className={styles.page}>
      <div className="app-container">
        <h1 className={styles.heading}>카테고리 상품</h1>
        <p className={styles.placeholder}>카테고리 ID: {id} — 상품 목록 페이지 (구현 예정)</p>
      </div>
    </div>
  )
}
