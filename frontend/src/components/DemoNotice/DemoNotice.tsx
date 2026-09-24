import { useQueryClient } from '@tanstack/react-query'
import { clearTokens } from '@/lib/api'
import { resetDemoState } from '@/demo/mockApi'
import styles from './DemoNotice.module.css'

/** Always-visible strip in demo builds (VITE_DEMO=1): states plainly that no server is involved. */
export default function DemoNotice() {
  const queryClient = useQueryClient()

  const handleReset = () => {
    resetDemoState()
    clearTokens()
    queryClient.clear()
    window.location.assign(import.meta.env.BASE_URL)
  }

  return (
    <aside className={styles.notice} aria-label="데모 모드 안내">
      <p className={styles.text}>
        <strong className={styles.label}>데모 모드</strong>
        <span>
          서버 없이 샘플 데이터로 동작합니다.
          <span className={styles.more}> 장바구니·주문은 이 브라우저에만 저장되며 실제 결제는 없습니다.</span>
        </span>
      </p>
      <button type="button" className={styles.reset} onClick={handleReset} aria-label="데모 데이터 초기화">
        초기화
      </button>
    </aside>
  )
}
