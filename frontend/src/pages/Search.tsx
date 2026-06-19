// TODO: Flesh out with search input, results grid, filter bar, and autocomplete dropdown.
// API: searchProducts(), autocomplete(), popularKeywords() from @/lib/search
import styles from './Search.module.css'

export default function Search() {
  return (
    <div className={styles.page}>
      <div className="app-container">
        <h1 className={styles.heading}>검색</h1>
        <p className={styles.placeholder}>검색 페이지 (구현 예정)</p>
      </div>
    </div>
  )
}
