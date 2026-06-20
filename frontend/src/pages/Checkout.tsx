import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation } from '@tanstack/react-query'
import { fetchCart } from '@/lib/cart'
import { fetchAddresses, createOrder, confirmPayment, createAddress } from '@/lib/orders'
import { getMySummary } from '@/lib/mypage'
import { apiGet, ApiError } from '@/lib/api'
import { useRequireAuth } from '@/lib/useRequireAuth'
import type { CreateAddressRequest } from '@/lib/types'
import CouponChip from '@/components/CouponChip/CouponChip'
import styles from './Checkout.module.css'

function formatKrw(n: number): string {
  return n.toLocaleString('ko-KR') + '원'
}

const SHIPPING_THRESHOLD = 30_000
const SHIPPING_FEE = 3_000

interface AddressFormData {
  recipientName: string
  phone: string
  zipcode: string
  addressMain: string
  addressDetail: string
  isDefault: boolean
}

const emptyForm: AddressFormData = {
  recipientName: '',
  phone: '',
  zipcode: '',
  addressMain: '',
  addressDetail: '',
  isDefault: false,
}

// Shape of GET /api/me/coupons response items (MemberCouponStatus: ISSUED, USED, EXPIRED, REVOKED)
interface MemberCouponItem {
  id: number
  couponId: number
  couponName: string
  discountType: 'FIXED_AMOUNT' | 'PERCENTAGE'
  discountValue: number
  minOrderAmount: number | null
  expiresAt: string | null
  status: 'ISSUED' | 'USED' | 'EXPIRED' | 'REVOKED'
  issuedAt: string | null
}

// Calculate coupon discount against an order total
function calcCouponDiscount(coupon: MemberCouponItem, orderAmount: number): number {
  if (coupon.discountType === 'FIXED_AMOUNT') {
    return Math.min(coupon.discountValue, orderAmount)
  }
  // PERCENTAGE: discountValue is 0-100
  return Math.floor((orderAmount * coupon.discountValue) / 100)
}

export default function Checkout() {
  useRequireAuth()

  const navigate = useNavigate()

  const [selectedAddressId, setSelectedAddressId] = useState<number | null>(null)
  const [showAddressForm, setShowAddressForm] = useState(false)
  const [addressForm, setAddressForm] = useState<AddressFormData>(emptyForm)
  const [formError, setFormError] = useState<string | null>(null)
  const [globalError, setGlobalError] = useState<string | null>(null)

  // Coupon + point state
  const [selectedCouponId, setSelectedCouponId] = useState<number | null>(null)
  const [showCouponPanel, setShowCouponPanel] = useState(false)
  const [pointInput, setPointInput] = useState('')
  const [pointError, setPointError] = useState<string | null>(null)

  // Queries
  const { data: cart, isLoading: cartLoading } = useQuery({
    queryKey: ['cart'],
    queryFn: ({ signal }) => fetchCart(signal),
  })

  const { data: addresses, isLoading: addressesLoading, refetch: refetchAddresses } = useQuery({
    queryKey: ['addresses'],
    queryFn: ({ signal }) => fetchAddresses(signal),
  })

  const { data: mySummary } = useQuery({
    queryKey: ['my-summary'],
    queryFn: ({ signal }) => getMySummary(signal),
  })

  const { data: coupons } = useQuery({
    queryKey: ['my-coupons'],
    // Fetch all coupons; filter ISSUED (usable) client-side
    queryFn: ({ signal }) => apiGet<MemberCouponItem[]>('/me/coupons', signal),
  })

  // Auto-select default address when addresses load
  useEffect(() => {
    if (addresses && addresses.length > 0 && selectedAddressId === null) {
      const defaultAddr = addresses.find((a) => a.isDefault) ?? addresses[0]
      setSelectedAddressId(defaultAddr.id)
    }
  }, [addresses, selectedAddressId])

  // Create address mutation
  const createAddressMutation = useMutation({
    mutationFn: (body: CreateAddressRequest) => createAddress(body),
    onSuccess: async (addr) => {
      setSelectedAddressId(addr.id)
      setShowAddressForm(false)
      setAddressForm(emptyForm)
      await refetchAddresses()
    },
    onError: (err: unknown) => {
      const msg = err instanceof ApiError ? err.message : '배송지 추가에 실패했습니다.'
      setFormError(msg)
    },
  })

  // Derived values
  const shippingFee =
    cart && cart.totalAmount > 0 && cart.totalAmount < SHIPPING_THRESHOLD
      ? SHIPPING_FEE
      : 0
  const productAmount = cart ? cart.totalAmount : 0

  const selectedCoupon = coupons?.find((c) => c.id === selectedCouponId) ?? null
  const couponDiscount = selectedCoupon
    ? calcCouponDiscount(selectedCoupon, productAmount)
    : 0

  const pointBalance = mySummary?.pointBalance ?? 0
  // The maximum points that can be applied is limited by both the balance
  // and the order subtotal (product + shipping) so points never exceed what's owed.
  const orderSubtotal = productAmount + shippingFee - couponDiscount
  const maxApplicablePoints = Math.min(pointBalance, Math.max(0, orderSubtotal))
  const usePointAmount = Math.max(0, Math.min(maxApplicablePoints, parseInt(pointInput || '0', 10) || 0))

  const orderTotal = Math.max(
    0,
    orderSubtotal - usePointAmount,
  )

  const handlePointInputChange = (raw: string) => {
    // Allow only digits
    const digits = raw.replace(/[^0-9]/g, '')
    setPointInput(digits)
    setPointError(null)

    const parsed = parseInt(digits || '0', 10)
    if (parsed > pointBalance) {
      setPointError(`보유 포인트(${formatKrw(pointBalance)})를 초과할 수 없습니다.`)
    } else if (parsed > orderSubtotal) {
      setPointError('포인트는 주문금액을 초과할 수 없습니다.')
    }
  }

  const handleUseAllPoints = () => {
    setPointInput(String(maxApplicablePoints))
    setPointError(null)
  }

  // Place order mutation
  const placeMutation = useMutation({
    mutationFn: async () => {
      if (!cart || cart.items.length === 0) throw new Error('장바구니가 비어있습니다.')
      if (!selectedAddressId) throw new Error('배송지를 선택해주세요.')

      const idempotencyKey = crypto.randomUUID()

      const order = await createOrder(
        {
          items: cart.items.map((item) => ({
            productOptionId: item.productOptionId,
            quantity: item.quantity,
          })),
          deliveryAddressId: selectedAddressId,
          couponId: selectedCouponId ?? null,
          usePointAmount: usePointAmount > 0 ? usePointAmount : null,
        },
        idempotencyKey,
      )

      const paymentIdempotencyKey = crypto.randomUUID()
      const confirmed = await confirmPayment(
        {
          orderNo: order.orderNo,
          paymentKey: String(order.paymentKey),
          amount: order.amount,
        },
        paymentIdempotencyKey,
      )

      return { orderNo: confirmed.orderNo }
    },
    onSuccess: ({ orderNo }) => {
      navigate(`/order/complete`, { state: { orderNo } })
    },
    onError: (err: unknown) => {
      const msg =
        err instanceof ApiError
          ? err.message
          : err instanceof Error
          ? err.message
          : '주문 처리 중 오류가 발생했습니다.'
      setGlobalError(msg)
    },
  })

  const handleAddressFormSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setFormError(null)
    if (
      !addressForm.recipientName ||
      !addressForm.phone ||
      !addressForm.zipcode ||
      !addressForm.addressMain
    ) {
      setFormError('필수 항목을 모두 입력해주세요.')
      return
    }
    createAddressMutation.mutate({
      recipientName: addressForm.recipientName,
      phone: addressForm.phone,
      zipcode: addressForm.zipcode,
      addressMain: addressForm.addressMain,
      addressDetail: addressForm.addressDetail || undefined,
      isDefault: addressForm.isDefault,
    })
  }

  const isLoading = cartLoading || addressesLoading

  if (isLoading) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <div className={styles.skeleton} aria-busy="true" aria-label="체크아웃 로딩 중" />
        </div>
      </div>
    )
  }

  // ISSUED = usable (not yet used, not expired/revoked)
  const availableCoupons = coupons?.filter((c) => c.status === 'ISSUED') ?? []

  return (
    <div className={styles.page}>
      <div className="app-container">
        <h1 className={styles.heading}>주문/결제</h1>

        <div className={styles.layout}>
          {/* Left column */}
          <div className={styles.leftCol}>
            {/* Address section */}
            <section className={styles.section} aria-label="배송지">
              <h2 className={styles.sectionTitle}>배송지</h2>

              {addresses && addresses.length > 0 ? (
                <ul className={styles.addressList}>
                  {addresses.map((addr) => (
                    <li key={addr.id}>
                      <label
                        className={`${styles.addressCard} ${selectedAddressId === addr.id ? styles.selected : ''}`}
                      >
                        <input
                          type="radio"
                          name="address"
                          className={styles.radioInput}
                          checked={selectedAddressId === addr.id}
                          onChange={() => setSelectedAddressId(addr.id)}
                          aria-label={`${addr.recipientName} 배송지 선택`}
                        />
                        <div className={styles.addressInfo}>
                          <p className={styles.addressName}>
                            {addr.recipientName}
                            {addr.isDefault && (
                              <span className={styles.defaultBadge}>기본</span>
                            )}
                          </p>
                          <p className={styles.addressText}>
                            ({addr.zipcode}) {addr.addressMain}
                            {addr.addressDetail ? ` ${addr.addressDetail}` : ''}
                          </p>
                          <p className={styles.addressPhone}>{addr.phone}</p>
                        </div>
                      </label>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className={styles.noAddress}>등록된 배송지가 없습니다.</p>
              )}

              <button
                type="button"
                className={styles.addAddressBtn}
                onClick={() => setShowAddressForm((v) => !v)}
              >
                {showAddressForm ? '닫기' : '+ 새 배송지 추가'}
              </button>

              {showAddressForm && (
                <form
                  className={styles.addressForm}
                  onSubmit={handleAddressFormSubmit}
                  noValidate
                >
                  {formError && (
                    <p className={styles.formError} role="alert">
                      {formError}
                    </p>
                  )}

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="recipientName">
                      수령인 *
                    </label>
                    <input
                      id="recipientName"
                      className={styles.formInput}
                      value={addressForm.recipientName}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, recipientName: e.target.value }))
                      }
                      placeholder="홍길동"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="phone">
                      연락처 *
                    </label>
                    <input
                      id="phone"
                      className={styles.formInput}
                      value={addressForm.phone}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, phone: e.target.value }))
                      }
                      placeholder="010-0000-0000"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="zipcode">
                      우편번호 *
                    </label>
                    <input
                      id="zipcode"
                      className={styles.formInput}
                      value={addressForm.zipcode}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, zipcode: e.target.value }))
                      }
                      placeholder="12345"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="addressMain">
                      기본 주소 *
                    </label>
                    <input
                      id="addressMain"
                      className={styles.formInput}
                      value={addressForm.addressMain}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, addressMain: e.target.value }))
                      }
                      placeholder="서울시 강남구 테헤란로 123"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="addressDetail">
                      상세 주소
                    </label>
                    <input
                      id="addressDetail"
                      className={styles.formInput}
                      value={addressForm.addressDetail}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, addressDetail: e.target.value }))
                      }
                      placeholder="101동 202호"
                    />
                  </div>

                  <label className={styles.checkboxRow}>
                    <input
                      type="checkbox"
                      checked={addressForm.isDefault}
                      onChange={(e) =>
                        setAddressForm((f) => ({ ...f, isDefault: e.target.checked }))
                      }
                    />
                    기본 배송지로 설정
                  </label>

                  <button
                    type="submit"
                    className={styles.saveAddressBtn}
                    disabled={createAddressMutation.isPending}
                    aria-busy={createAddressMutation.isPending}
                  >
                    {createAddressMutation.isPending ? '저장 중...' : '배송지 저장'}
                  </button>
                </form>
              )}
            </section>

            {/* Coupon section */}
            <section className={styles.section} aria-label="쿠폰">
              <h2 className={styles.sectionTitle}>쿠폰</h2>

              {availableCoupons.length === 0 ? (
                <p className={styles.noAddress}>사용 가능한 쿠폰이 없습니다.</p>
              ) : (
                <>
                  <button
                    type="button"
                    className={styles.couponToggleBtn}
                    onClick={() => setShowCouponPanel((v) => !v)}
                    aria-expanded={showCouponPanel}
                  >
                    {selectedCoupon
                      ? `선택됨: ${selectedCoupon.couponName}`
                      : `쿠폰 선택 (${availableCoupons.length}개 보유)`}
                    <svg
                      width="14"
                      height="14"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      aria-hidden="true"
                      className={showCouponPanel ? styles.chevronUp : ''}
                    >
                      <polyline points="6 9 12 15 18 9" />
                    </svg>
                  </button>

                  {showCouponPanel && (
                    <ul className={styles.couponList} role="listbox" aria-label="쿠폰 선택">
                      {/* Option: no coupon */}
                      <li>
                        <button
                          type="button"
                          className={`${styles.couponOption} ${selectedCouponId === null ? styles.couponOptionSelected : ''}`}
                          onClick={() => {
                            setSelectedCouponId(null)
                            setShowCouponPanel(false)
                          }}
                          role="option"
                          aria-selected={selectedCouponId === null}
                        >
                          쿠폰 사용 안 함
                        </button>
                      </li>

                      {availableCoupons.map((coupon) => {
                        const discount = calcCouponDiscount(coupon, productAmount)
                        const isSelected = selectedCouponId === coupon.id
                        const meetsMin =
                          coupon.minOrderAmount === null ||
                          productAmount >= coupon.minOrderAmount
                        return (
                          <li key={coupon.id}>
                            <button
                              type="button"
                              className={`${styles.couponOption} ${isSelected ? styles.couponOptionSelected : ''} ${!meetsMin ? styles.couponOptionDisabled : ''}`}
                              onClick={() => {
                                if (!meetsMin) return
                                setSelectedCouponId(coupon.id)
                                setShowCouponPanel(false)
                              }}
                              disabled={!meetsMin}
                              role="option"
                              aria-selected={isSelected}
                            >
                              <div className={styles.couponOptionRow}>
                                <CouponChip
                                  label={coupon.couponName}
                                  state="available"
                                />
                                <span className={styles.couponDiscount}>
                                  -{formatKrw(discount)}
                                </span>
                              </div>
                              {!meetsMin && coupon.minOrderAmount !== null && (
                                <p className={styles.couponMinNote}>
                                  {formatKrw(coupon.minOrderAmount)} 이상 주문 시 사용 가능
                                </p>
                              )}
                            </button>
                          </li>
                        )
                      })}
                    </ul>
                  )}
                </>
              )}
            </section>

            {/* Point section */}
            <section className={styles.section} aria-label="포인트">
              <h2 className={styles.sectionTitle}>포인트</h2>
              <p className={styles.pointBalance}>
                보유 포인트: <strong>{formatKrw(pointBalance)}</strong>
              </p>

              <div className={styles.pointInputRow}>
                <input
                  type="text"
                  inputMode="numeric"
                  className={`${styles.formInput} ${styles.pointInput} ${pointError ? styles.inputError : ''}`}
                  value={pointInput}
                  onChange={(e) => handlePointInputChange(e.target.value)}
                  placeholder="사용할 포인트"
                  aria-label="사용할 포인트 금액"
                  aria-describedby={pointError ? 'point-error' : undefined}
                  aria-invalid={Boolean(pointError)}
                />
                <button
                  type="button"
                  className={styles.pointAllBtn}
                  onClick={handleUseAllPoints}
                  disabled={pointBalance === 0}
                >
                  전액 사용
                </button>
              </div>

              {pointError && (
                <p id="point-error" className={styles.formError} role="alert">
                  {pointError}
                </p>
              )}

              {usePointAmount > 0 && !pointError && (
                <p className={styles.pointApplied}>
                  {formatKrw(usePointAmount)} 포인트 적용
                </p>
              )}
            </section>

            {/* Order items summary */}
            <section className={styles.section} aria-label="주문 상품">
              <h2 className={styles.sectionTitle}>주문 상품</h2>
              {cart && cart.items.length > 0 ? (
                <ul className={styles.orderItemList}>
                  {cart.items.map((item) => (
                    <li key={item.cartItemId} className={styles.orderItem}>
                      <span className={styles.orderItemName}>
                        {item.productName}
                        {item.optionName ? ` · ${item.optionName}` : ''}
                      </span>
                      <span className={styles.orderItemQty}>× {item.quantity}</span>
                      <span className={styles.orderItemPrice}>
                        {formatKrw(item.lineSubtotal)}
                      </span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className={styles.noAddress}>장바구니가 비어있습니다.</p>
              )}
            </section>
          </div>

          {/* Right: payment summary */}
          <aside className={styles.summary} aria-label="결제 정보">
            <h2 className={styles.summaryTitle}>결제 금액</h2>

            <dl className={styles.summaryList}>
              <div className={styles.summaryRow}>
                <dt>상품 금액</dt>
                <dd>{cart ? formatKrw(cart.totalAmount) : '-'}</dd>
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
              {couponDiscount > 0 && (
                <div className={styles.summaryRow}>
                  <dt>쿠폰 할인</dt>
                  <dd className={styles.discountAmount}>-{formatKrw(couponDiscount)}</dd>
                </div>
              )}
              {usePointAmount > 0 && (
                <div className={styles.summaryRow}>
                  <dt>포인트 사용</dt>
                  <dd className={styles.discountAmount}>-{formatKrw(usePointAmount)}</dd>
                </div>
              )}
              {couponDiscount === 0 && usePointAmount === 0 && (
                <div className={styles.summaryRow}>
                  <dt>할인</dt>
                  <dd className={styles.noDiscount}>없음</dd>
                </div>
              )}
            </dl>

            <div className={styles.totalRow}>
              <span className={styles.totalLabel}>총 결제 금액</span>
              <strong className={styles.totalAmount}>{formatKrw(orderTotal)}</strong>
            </div>

            {globalError && (
              <p className={styles.globalError} role="alert">
                {globalError}
              </p>
            )}

            <button
              type="button"
              className={styles.placeOrderBtn}
              onClick={() => placeMutation.mutate()}
              disabled={
                placeMutation.isPending ||
                !selectedAddressId ||
                !cart ||
                cart.items.length === 0 ||
                Boolean(pointError)
              }
              aria-busy={placeMutation.isPending}
            >
              {placeMutation.isPending ? '주문 처리 중...' : `${formatKrw(orderTotal)} 결제하기`}
            </button>

            <p className={styles.disclaimer}>
              주문 내용을 확인하였으며, 정보 제공 등에 동의합니다.
            </p>
          </aside>
        </div>
      </div>
    </div>
  )
}
