import { useState, useCallback } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiGet, ApiError, getAccessToken } from '@/lib/api'
import { addToCart } from '@/lib/cart'
import type { ProductDetail as ProductDetailType } from '@/lib/types'
import type { SelectedOption } from '@/components/QuantityOptionSelector/QuantityOptionSelector'
import ImageCarousel from '@/components/ImageCarousel/ImageCarousel'
import PriceDisplay from '@/components/PriceDisplay/PriceDisplay'
import RatingStars from '@/components/RatingStars/RatingStars'
import QuantityOptionSelector from '@/components/QuantityOptionSelector/QuantityOptionSelector'
import styles from './ProductDetail.module.css'

function formatKrw(n: number): string {
  return n.toLocaleString('ko-KR') + '원'
}

export default function ProductDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [selectedOptions, setSelectedOptions] = useState<SelectedOption[]>([])
  // Plain qty used when product has no options
  const [plainQty, setPlainQty] = useState(1)
  const [toastMsg, setToastMsg] = useState<string | null>(null)

  const { data: product, isLoading, isError, error } = useQuery({
    queryKey: ['product', id],
    queryFn: ({ signal }) => apiGet<ProductDetailType>(`/products/${id}`, signal),
    enabled: Boolean(id),
  })

  const { mutate: addToCartMutate, isPending: addingToCart } = useMutation({
    mutationFn: addToCart,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cart'] })
      showToast('장바구니에 담았습니다.')
    },
    onError: (err: unknown) => {
      if (err instanceof ApiError && err.status === 401) {
        navigate('/login', { state: { from: `/products/${id}` }, replace: true })
      } else {
        const msg = err instanceof Error ? err.message : '오류가 발생했습니다.'
        showToast(msg)
      }
    },
  })

  const showToast = (msg: string) => {
    setToastMsg(msg)
    setTimeout(() => setToastMsg(null), 2500)
  }

  const handleAddToCart = useCallback(() => {
    if (!getAccessToken()) {
      navigate('/login', { state: { from: `/products/${id}` }, replace: true })
      return
    }
    if (!product) return

    if (product.options.length === 0) {
      // No options: add the product itself with plainQty
      // The backend requires productOptionId — when no options exist, productId is used
      // We use productId cast as optionId (backend behaviour for single-SKU products)
      addToCartMutate({ productOptionId: product.productId, quantity: plainQty })
    } else {
      if (selectedOptions.length === 0) {
        showToast('옵션을 선택해주세요.')
        return
      }
      selectedOptions.forEach((opt) => {
        addToCartMutate({ productOptionId: opt.optionId, quantity: opt.quantity })
      })
    }
  }, [product, selectedOptions, plainQty, addToCartMutate, navigate, id])

  const handleBuyNow = useCallback(() => {
    if (!getAccessToken()) {
      navigate('/login', { state: { from: `/products/${id}` }, replace: true })
      return
    }
    handleAddToCart()
    navigate('/cart')
  }, [handleAddToCart, navigate, id])

  const totalSelectedPrice = selectedOptions.reduce(
    (sum, opt) => sum + opt.optionPrice * opt.quantity,
    0,
  )

  if (isLoading) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <div className={styles.skeleton} aria-busy="true" aria-label="상품 로딩 중" />
        </div>
      </div>
    )
  }

  if (isError || !product) {
    return (
      <div className={styles.page}>
        <div className="app-container">
          <p className={styles.errorMsg} role="alert">
            {isError ? (error as Error).message : '상품을 찾을 수 없습니다.'}
          </p>
        </div>
      </div>
    )
  }

  const carouselImages = product.images
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((img) => ({ url: img.url, alt: product.productName }))

  const hasOptions = product.options.length > 0

  return (
    <div className={styles.page}>
      <div className="app-container">
        {/* Breadcrumb */}
        {product.categories.length > 0 && (
          <nav className={styles.breadcrumb} aria-label="카테고리 경로">
            <Link to="/" className={styles.breadcrumbLink}>홈</Link>
            {product.categories.map((cat, i) => (
              <span key={cat.categoryId} className={styles.breadcrumbItem}>
                <span className={styles.breadcrumbSep} aria-hidden="true">/</span>
                {i === product.categories.length - 1 ? (
                  <span className={styles.breadcrumbCurrent}>{cat.categoryName}</span>
                ) : (
                  <span className={styles.breadcrumbLink}>{cat.categoryName}</span>
                )}
              </span>
            ))}
          </nav>
        )}

        <div className={styles.layout}>
          {/* Left: carousel */}
          <div className={styles.imageSection}>
            <ImageCarousel images={carouselImages} />
          </div>

          {/* Right: product info */}
          <div className={styles.infoSection}>
            {product.brandName && (
              <p className={styles.brand}>{product.brandName}</p>
            )}

            <h1 className={styles.productName}>{product.productName}</h1>

            <div className={styles.ratingRow}>
              <RatingStars
                rating={product.rating}
                reviewCount={product.reviewCount}
                size="md"
              />
            </div>

            <div className={styles.priceRow}>
              <PriceDisplay
                salePrice={product.salePrice}
                originalPrice={product.originalPrice}
                discountRate={product.discountRate}
              />
            </div>

            <hr className={styles.divider} />

            {/* Option selector or plain qty */}
            {hasOptions ? (
              <QuantityOptionSelector
                options={product.options}
                onChange={setSelectedOptions}
              />
            ) : (
              <div className={styles.plainQtyRow}>
                <span className={styles.plainQtyLabel}>수량</span>
                <div className={styles.stepper} role="group" aria-label="수량">
                  <button
                    type="button"
                    className={styles.stepBtn}
                    onClick={() => setPlainQty((q) => Math.max(1, q - 1))}
                    disabled={plainQty <= 1}
                    aria-label="수량 줄이기"
                  >
                    −
                  </button>
                  <span className={styles.stepCount}>{plainQty}</span>
                  <button
                    type="button"
                    className={styles.stepBtn}
                    onClick={() => setPlainQty((q) => q + 1)}
                    aria-label="수량 늘리기"
                  >
                    +
                  </button>
                </div>
              </div>
            )}

            {/* Price summary when options selected */}
            {hasOptions && selectedOptions.length > 0 && (
              <div className={styles.totalRow}>
                <span className={styles.totalLabel}>총 상품 금액</span>
                <span className={styles.totalPrice}>
                  {formatKrw(product.salePrice * selectedOptions.reduce((s, o) => s + o.quantity, 0) + totalSelectedPrice)}
                </span>
              </div>
            )}

            {/* Action buttons — desktop: inline; mobile: sticky bar */}
            <div className={styles.actionRow}>
              <button
                type="button"
                className={styles.cartBtn}
                onClick={handleAddToCart}
                disabled={addingToCart}
                aria-busy={addingToCart}
              >
                {addingToCart ? '담는 중…' : '장바구니 담기'}
              </button>
              <button
                type="button"
                className={styles.buyBtn}
                onClick={handleBuyNow}
                disabled={addingToCart}
              >
                바로구매
              </button>
            </div>
          </div>
        </div>

        {/* Description */}
        {product.description && (
          <section className={styles.descSection} aria-label="상품 설명">
            <h2 className={styles.descTitle}>상품 설명</h2>
            <div className={styles.descBody}>{product.description}</div>
          </section>
        )}
      </div>

      {/* Mobile sticky buy bar */}
      <div className={styles.stickyBar} aria-label="구매 버튼">
        <button
          type="button"
          className={styles.stickyCartBtn}
          onClick={handleAddToCart}
          disabled={addingToCart}
        >
          장바구니 담기
        </button>
        <button
          type="button"
          className={styles.stickyBuyBtn}
          onClick={handleBuyNow}
          disabled={addingToCart}
        >
          바로구매
        </button>
      </div>

      {/* Toast notification */}
      {toastMsg && (
        <div className={styles.toast} role="status" aria-live="polite">
          {toastMsg}
        </div>
      )}
    </div>
  )
}
