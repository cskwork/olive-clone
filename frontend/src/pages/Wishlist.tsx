// TODO: Flesh out with wishlist grid, remove button, and empty state.
// API: listWishlist(), addWishlist(), removeWishlist() from @/lib/wishlist
import styles from './Wishlist.module.css'

export default function Wishlist() {
  return (
    <div className={styles.page}>
      <div className="app-container">
        <h1 className={styles.heading}>찜 목록</h1>
        <p className={styles.placeholder}>찜 목록 페이지 (구현 예정)</p>
      </div>
    </div>
  )
}
