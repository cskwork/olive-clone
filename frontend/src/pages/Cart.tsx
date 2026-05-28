import { Link, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { fetchCart, updateCartItemQty, removeCartItem } from '@/lib/cart'
import { useRequireAuth } from '@/lib/useRequireAuth'
import type { CartItem } from '@/lib/types'
import styles from './Cart.module.css'

function formatKrw(n: number): string {
  return n.toLocaleString('ko-KR') + '원'
}

const SHIPPING_THRESHOLD = 30_000
const SHIPPING_FEE = 3_000

function ShippingFeeNote({ totalAmount }: { totalAmount: number }) {
  if (totalAmount >= SHIPPING_THRESHOLD) {
    return (
      <span className={styles.shippingFree}>무료배송</span>
    )
  }
  const remaining = SHIPPING_THRESHOLD - totalAmount
  return (
    <span className={styles.shippingNote}>
      {formatKrw(remaining)} 더 구매 시 무료배송
    </span>
  )
}

interface CartItemRowProps {
  item: CartItem
  onUpdateQty: (cartItemId: number, qty: number) => void
  onRemove: (cartItemId: number) => void
  isUpdating: boolean
}

function CartItemRow({ item, onUpdateQty, onRemove, isUpdating }: CartItemRowProps) {
  const maxQty = item.availableQuantity ?? 99

  return (
    <li className={styles.itemRow} aria-label={`${item.productName} ${item.optionName}`}>
      {/* Thumbnail placeholder */}
      <div className={styles.thumb} aria-hidden="true">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
          <rect width="24" height="24" rx="4" fill="var(--grey-200)" />
          <path d="M8 16l3-4 2 3 2-2 3 3H8z" fill="var(--grey-300)" />
        </svg>
      </div>

      <div className={styles.itemInfo}>
        <p className={styles.itemName}>{item.productName}</p>
        {item.optionName && (
          <p className={styles.itemOption}>{item.optionName}</p>
        )}
        <p className={styles.itemPrice}>{formatKrw(item.salePrice)}</p>
      </div>

      <div className={styles.itemControls}>
        {/* Qty stepper */}
        <div className={styles.stepper} role="group" aria-label={`${item.productName} 수량`}>
          <button
            type="button"
            className={styles.stepBtn}
            onClick={() => onUpdateQty(item.cartItemId, item.quantity - 1)}
            disabled={item.quantity <= 1 || isUpdating}
            aria-label="수량 줄이기"
          >
            −
          </button>
          <span className={styles.stepCount} aria-live="polite" aria-label={`수량 ${item.quantity}`}>
            {item.quantity}
          </span>
          <button
            type="button"
            className={styles.stepBtn}
            onClick={() => onUpdateQty(item.cartItemId, item.quantity + 1)}
            disabled={item.quantity >= maxQty || isUpdating}
            aria-label="수량 늘리기"
          >
            +
          </button>
        </div>

        <p className={styles.lineTotal}>{formatKrw(item.lineSubtotal)}</p>

        {/* Remove */}
        <button
          type="button"
          className={styles.removeBtn}
          onClick={() => onRemove(item.cartItemId)}
          aria-label={`${item.productName} 삭제`}
          disabled={isUpdating}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>
    </li>
  )
}

export default function Cart() {
  useRequireAuth()

  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data: cart, isLoading, isError, error } = useQuery({
    queryKey: ['cart'],
    queryFn: ({ signal }) => fetchCart(signal),
  })

  const updateMutation = useMutation({
    mutationFn: ({ cartItemId, quantity }: { cartItemId: number; quantity: number }) =>
      updateCartItemQty(cartItemId, { quantity }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cart'] })
    },
  })

  const removeMutation = useMutation({
    mutationFn: (cartItemId: number) => removeCartItem(cartItemId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cart'] })
    },
  })

  const isMutating = updateMutation.isPending || removeMutation.isPending

  const handleUpdateQty = (cartItemId: number, quantity: number) => {
    if (quantity < 1) return
    updateMutation.mutate({ cartItemId, quantity })
  }

  const handleRemove = (cartItemId: number) => {
    removeMutation.mutate(cartItemId)
  }

  const shippingFee =
    cart && cart.totalAmount > 0 && cart.totalAmount < SHIPPING_THRESHOLD
      ? SHIPPING_FEE
      : 0

  const orderTotal = cart ? cart.totalAmount + shippingFee : 0

  if (isLoading) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <div className={styles.skeleton} aria-busy="true" aria-label="장바구니 로딩 중" />
        </div>
      </div>
    )
  }

  if (isError) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <p className={styles.errorMsg} role="alert">
            장바구니를 불러오지 못했습니다: {(error as Error).message}
          </p>
        </div>
      </div>
    )
  }

  const isEmpty = !cart || cart.items.length === 0

  return (
    <div className={styles.page}>
      <div className="app-container">
        <h1 className={styles.heading}>장바구니</h1>

        {isEmpty ? (
          <div className={styles.empty}>
            <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="var(--grey-300)" strokeWidth="1.5" aria-hidden="true">
              <circle cx="9" cy="21" r="1" />
              <circle cx="20" cy="21" r="1" />
              <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6" />
            </svg>
            <p className={styles.emptyText}>장바구니가 비어있습니다.</p>
            <Link to="/" className={styles.emptyLink}>
              쇼핑 계속하기
            </Link>
          </div>
        ) : (
          <div className={styles.layout}>
            {/* Items */}
            <section className={styles.itemsSection} aria-label="장바구니 상품">
              <ul className={styles.itemList}>
                {cart.items.map((item: CartItem) => (
                  <CartItemRow
                    key={item.cartItemId}
                    item={item}
                    onUpdateQty={handleUpdateQty}
                    onRemove={handleRemove}
                    isUpdating={isMutating}
                  />
                ))}
              </ul>
            </section>

            {/* Summary */}
            <aside className={styles.summary} aria-label="주문 요약">
              <h2 className={styles.summaryTitle}>결제 예정 금액</h2>

              <dl className={styles.summaryList}>
                <div className={styles.summaryRow}>
                  <dt>상품 금액</dt>
                  <dd>{formatKrw(cart.totalAmount)}</dd>
                </div>
                <div className={styles.summaryRow}>
                  <dt>배송비</dt>
                  <dd>
                    {shippingFee === 0 ? (
                      <span className={styles.freeLabel}>무료</span>
                    ) : (
                      formatKrw(shippingFee)
                    )}
                  </dd>
                </div>
              </dl>

              <div className={styles.shippingNote}>
                <ShippingFeeNote totalAmount={cart.totalAmount} />
              </div>

              <div className={styles.totalRow}>
                <span className={styles.totalLabel}>합계</span>
                <strong className={styles.totalAmount}>{formatKrw(orderTotal)}</strong>
              </div>

              <button
                type="button"
                className={styles.checkoutBtn}
                onClick={() => navigate('/checkout')}
              >
                주문하기 ({cart.totalItemCount}개)
              </button>
            </aside>
          </div>
        )}
      </div>
    </div>
  )
}
