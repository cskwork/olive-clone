// TODO: Flesh out with order list, status filter tabs, pagination, and order row components.
// API: listMyOrders() from @/lib/orders
import styles from './OrderHistory.module.css'

export default function OrderHistory() {
  return (
    <div className={styles.page}>
      <div className="app-container">
        <h1 className={styles.heading}>주문 내역</h1>
        <p className={styles.placeholder}>주문 내역 페이지 (구현 예정)</p>
      </div>
    </div>
  )
}
