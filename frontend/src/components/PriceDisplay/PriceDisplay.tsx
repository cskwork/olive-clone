import styles from './PriceDisplay.module.css'

interface PriceDisplayProps {
  salePrice: number
  originalPrice: number
  discountRate: number
}

function formatKrw(amount: number): string {
  return amount.toLocaleString('ko-KR') + '원'
}

export default function PriceDisplay({ salePrice, originalPrice, discountRate }: PriceDisplayProps) {
  const hasDiscount = discountRate > 0 && originalPrice > salePrice

  return (
    <span className={styles.root}>
      {hasDiscount && (
        <span className={styles.discount} aria-label={`${discountRate}% 할인`}>
          {discountRate}%
        </span>
      )}
      <strong className={styles.salePrice}>{formatKrw(salePrice)}</strong>
      {hasDiscount && (
        <span className={styles.original} aria-label={`정가 ${formatKrw(originalPrice)}`}>
          {formatKrw(originalPrice)}
        </span>
      )}
    </span>
  )
}
