import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation } from '@tanstack/react-query'
import { fetchCart } from '@/lib/cart'
import { fetchAddresses, createOrder, confirmPayment, createAddress } from '@/lib/orders'
import { useRequireAuth } from '@/lib/useRequireAuth'
import { ApiError } from '@/lib/api'
import type { CreateAddressRequest } from '@/lib/types'
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

export default function Checkout() {
  useRequireAuth()

  const navigate = useNavigate()

  const [selectedAddressId, setSelectedAddressId] = useState<number | null>(null)
  const [showAddressForm, setShowAddressForm] = useState(false)
  const [addressForm, setAddressForm] = useState<AddressFormData>(emptyForm)
  const [formError, setFormError] = useState<string | null>(null)
  const [globalError, setGlobalError] = useState<string | null>(null)

  // Queries
  const { data: cart, isLoading: cartLoading } = useQuery({
    queryKey: ['cart'],
    queryFn: ({ signal }) => fetchCart(signal),
  })

  const { data: addresses, isLoading: addressesLoading, refetch: refetchAddresses } = useQuery({
    queryKey: ['addresses'],
    queryFn: ({ signal }) => fetchAddresses(signal),
  })

  // Auto-select default address when addresses load (TanStack Query v5: no onSuccess)
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

  // Place order mutation
  const placeMutation = useMutation({
    mutationFn: async () => {
      if (!cart || cart.items.length === 0) throw new Error('장바구니가 비어있습니다.')
      if (!selectedAddressId) throw new Error('배송지를 선택해주세요.')

      const idempotencyKey = crypto.randomUUID()

      // Step 1: Create order
      const order = await createOrder(
        {
          items: cart.items.map((item) => ({
            productOptionId: item.productOptionId,
            quantity: item.quantity,
          })),
          deliveryAddressId: selectedAddressId,
          couponId: null,
          usePointAmount: null,
        },
        idempotencyKey,
      )

      // Step 2: Confirm payment via mock PG
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
      const msg = err instanceof ApiError ? err.message : (err instanceof Error ? err.message : '주문 처리 중 오류가 발생했습니다.')
      setGlobalError(msg)
    },
  })

  const handleAddressFormSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setFormError(null)
    if (!addressForm.recipientName || !addressForm.phone || !addressForm.zipcode || !addressForm.addressMain) {
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

  const shippingFee =
    cart && cart.totalAmount > 0 && cart.totalAmount < SHIPPING_THRESHOLD
      ? SHIPPING_FEE
      : 0
  const orderTotal = cart ? cart.totalAmount + shippingFee : 0

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
                      <label className={`${styles.addressCard} ${selectedAddressId === addr.id ? styles.selected : ''}`}>
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
                            {addr.isDefault && <span className={styles.defaultBadge}>기본</span>}
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
                <form className={styles.addressForm} onSubmit={handleAddressFormSubmit} noValidate>
                  {formError && (
                    <p className={styles.formError} role="alert">{formError}</p>
                  )}

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="recipientName">수령인 *</label>
                    <input
                      id="recipientName"
                      className={styles.formInput}
                      value={addressForm.recipientName}
                      onChange={(e) => setAddressForm((f) => ({ ...f, recipientName: e.target.value }))}
                      placeholder="홍길동"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="phone">연락처 *</label>
                    <input
                      id="phone"
                      className={styles.formInput}
                      value={addressForm.phone}
                      onChange={(e) => setAddressForm((f) => ({ ...f, phone: e.target.value }))}
                      placeholder="010-0000-0000"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="zipcode">우편번호 *</label>
                    <input
                      id="zipcode"
                      className={styles.formInput}
                      value={addressForm.zipcode}
                      onChange={(e) => setAddressForm((f) => ({ ...f, zipcode: e.target.value }))}
                      placeholder="12345"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="addressMain">기본 주소 *</label>
                    <input
                      id="addressMain"
                      className={styles.formInput}
                      value={addressForm.addressMain}
                      onChange={(e) => setAddressForm((f) => ({ ...f, addressMain: e.target.value }))}
                      placeholder="서울시 강남구 테헤란로 123"
                    />
                  </div>

                  <div className={styles.formRow}>
                    <label className={styles.formLabel} htmlFor="addressDetail">상세 주소</label>
                    <input
                      id="addressDetail"
                      className={styles.formInput}
                      value={addressForm.addressDetail}
                      onChange={(e) => setAddressForm((f) => ({ ...f, addressDetail: e.target.value }))}
                      placeholder="101동 202호"
                    />
                  </div>

                  <label className={styles.checkboxRow}>
                    <input
                      type="checkbox"
                      checked={addressForm.isDefault}
                      onChange={(e) => setAddressForm((f) => ({ ...f, isDefault: e.target.checked }))}
                    />
                    기본 배송지로 설정
                  </label>

                  <button
                    type="submit"
                    className={styles.saveAddressBtn}
                    disabled={createAddressMutation.isPending}
                    aria-busy={createAddressMutation.isPending}
                  >
                    {createAddressMutation.isPending ? '저장 중…' : '배송지 저장'}
                  </button>
                </form>
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
                      <span className={styles.orderItemPrice}>{formatKrw(item.lineSubtotal)}</span>
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
                <dd>{shippingFee === 0 ? <span className={styles.freeLabel}>무료</span> : formatKrw(shippingFee)}</dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>할인</dt>
                <dd>—</dd>
              </div>
            </dl>

            <div className={styles.totalRow}>
              <span className={styles.totalLabel}>총 결제 금액</span>
              <strong className={styles.totalAmount}>{formatKrw(orderTotal)}</strong>
            </div>

            {globalError && (
              <p className={styles.globalError} role="alert">{globalError}</p>
            )}

            <button
              type="button"
              className={styles.placeOrderBtn}
              onClick={() => placeMutation.mutate()}
              disabled={placeMutation.isPending || !selectedAddressId || !cart || cart.items.length === 0}
              aria-busy={placeMutation.isPending}
            >
              {placeMutation.isPending ? '주문 처리 중…' : `${formatKrw(orderTotal)} 결제하기`}
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
