import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { ProductListItem } from '@/lib/types'
import RatingStars from '@/components/RatingStars/RatingStars'
import PriceDisplay from '@/components/PriceDisplay/PriceDisplay'
import styles from './ProductCard.module.css'

interface ProductCardProps {
  product: ProductListItem
}

export default function ProductCard({ product }: ProductCardProps) {
  const [wished, setWished] = useState(false)

  const handleWishlistClick = (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    setWished((prev) => !prev)
  }

  return (
    <Link
      to={`/products/${product.productId}`}
      className={styles.card}
      aria-label={`${product.productName} 상품 상세 보기`}
    >
      {/* Image */}
      <div className={styles.imageWrap}>
        {product.thumbnailUrl ? (
          <img
            src={product.thumbnailUrl}
            alt={product.productName}
            className={styles.image}
            loading="lazy"
            decoding="async"
          />
        ) : (
          <div className={styles.imagePlaceholder} aria-hidden="true">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none">
              <rect width="24" height="24" rx="4" fill="#e5e5e5" />
              <path d="M8 16l3-4 2 3 2-2 3 3H8z" fill="#bfbfbf" />
              <circle cx="15" cy="9" r="1.5" fill="#bfbfbf" />
            </svg>
          </div>
        )}

        {/* Badges */}
        <div className={styles.badges}>
          {product.discountRate >= 30 && (
            <span className={`${styles.badge} ${styles.badgeBest}`}>BEST</span>
          )}
          {product.discountRate > 0 && (
            <span className={`${styles.badge} ${styles.badgeDiscount}`}>
              {product.discountRate}%
            </span>
          )}
        </div>

        {/* Wishlist */}
        <button
          type="button"
          className={`${styles.wishlistBtn} ${wished ? styles.wished : ''}`}
          onClick={handleWishlistClick}
          aria-label={wished ? '찜 해제' : '찜하기'}
          aria-pressed={wished}
        >
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill={wished ? 'currentColor' : 'none'}
            stroke="currentColor"
            strokeWidth="2"
            aria-hidden="true"
          >
            <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
          </svg>
        </button>
      </div>

      {/* Info */}
      <div className={styles.info}>
        {product.brandName && <p className={styles.brand}>{product.brandName}</p>}
        <p className={styles.name}>{product.productName}</p>
        <div className={styles.priceRow}>
          <PriceDisplay
            salePrice={product.salePrice}
            originalPrice={product.originalPrice}
            discountRate={product.discountRate}
          />
        </div>
        {(product.reviewCount > 0 || product.rating > 0) && (
          <div className={styles.ratingRow}>
            <RatingStars
              rating={product.rating}
              reviewCount={product.reviewCount}
              size="sm"
            />
          </div>
        )}
      </div>
    </Link>
  )
}
