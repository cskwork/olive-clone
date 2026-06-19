import { useRef } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { apiGetPage } from '@/lib/api'
import type { ProductListItem } from '@/lib/types'
import ProductCard from '@/components/ProductCard/ProductCard'
import styles from './Home.module.css'

// ---- Hero banners ----------------------------------------------------------------

interface HeroBanner {
  id: string
  eyebrow: string
  headline: string
  sub: string
  cta: string
  ctaHref: string
  bg: string
  accentColor: string
}

const HERO_BANNERS: HeroBanner[] = [
  {
    id: 'hero-1',
    eyebrow: '이달의 특가',
    headline: '봄 뷰티\n기획전',
    sub: '지금 가장 핫한 봄 스킨케어를 최대 50% 할인된 가격에 만나보세요',
    cta: '바로 보기',
    ctaHref: '/search',
    bg: 'linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%)',
    accentColor: '#9bce26',
  },
  {
    id: 'hero-2',
    eyebrow: '신상 입고',
    headline: '새봄\n신상품',
    sub: '최신 트렌드를 반영한 신제품을 가장 먼저 만나보세요',
    cta: '신상품 보기',
    ctaHref: '/search',
    bg: 'linear-gradient(135deg, #1c1c1c 0%, #2d1b00 50%, #3d2200 100%)',
    accentColor: '#ffb400',
  },
]

// ---- Rail section titles ---------------------------------------------------------

interface RailConfig {
  key: string
  title: string
  path: string
  badge?: string
}

const RAILS: RailConfig[] = [
  { key: 'recommended', title: '추천 상품', path: '/products?page=0&size=10' },
  { key: 'rankings', title: '실시간 랭킹', path: '/products/rankings?page=0&size=10', badge: 'HOT' },
  { key: 'bestsellers', title: '베스트셀러', path: '/products/best-sellers?page=0&size=10', badge: 'BEST' },
]

// ---- Skeleton cards -------------------------------------------------------------

function SkeletonCard() {
  return (
    <div className={styles.skeletonCard} aria-hidden="true">
      <div className={`${styles.skeletonImg} skeleton-shimmer`} />
      <div className={styles.skeletonBody}>
        <div className={`${styles.skeletonLine} ${styles.skeletonLineSm} skeleton-shimmer`} />
        <div className={`${styles.skeletonLine} skeleton-shimmer`} />
        <div className={`${styles.skeletonLine} ${styles.skeletonLineMd} skeleton-shimmer`} />
      </div>
    </div>
  )
}

// ---- Product Rail ---------------------------------------------------------------

interface ProductRailProps {
  config: RailConfig
}

function ProductRail({ config }: ProductRailProps) {
  const railRef = useRef<HTMLDivElement>(null)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['products-rail', config.key],
    queryFn: ({ signal }) =>
      apiGetPage<ProductListItem[]>(config.path, signal),
    staleTime: 2 * 60 * 1000,
  })

  const products = data?.data ?? []

  return (
    <section className={styles.rail} aria-label={config.title}>
      <div className={styles.railHeader}>
        <h2 className={styles.railTitle}>
          {config.badge && (
            <span className={styles.railBadge}>{config.badge}</span>
          )}
          {config.title}
        </h2>
        <Link to="/search" className={styles.railMore} aria-label={`${config.title} 전체 보기`}>
          전체 보기
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </Link>
      </div>

      {isError && (
        <p className="error-state" role="alert">
          상품을 불러오지 못했습니다.
        </p>
      )}

      {/* Mobile: horizontal scroll; Desktop: grid */}
      <div className={styles.railScroll} ref={railRef} aria-busy={isLoading}>
        <ul className={styles.railList}>
          {isLoading &&
            Array.from({ length: 6 }, (_, i) => (
              <li key={i} className={styles.railItem}>
                <SkeletonCard />
              </li>
            ))}
          {!isLoading && products.length === 0 && !isError && (
            <li className={styles.railEmpty}>
              <span className="empty-state">상품이 없습니다.</span>
            </li>
          )}
          {products.map((product) => (
            <li key={product.productId} className={styles.railItem}>
              <ProductCard product={product} />
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}

// ---- Hero Banner ----------------------------------------------------------------

function HeroBannerBlock() {
  return (
    <section className={styles.hero} aria-label="메인 배너">
      <div className={styles.heroPrimary} style={{ background: HERO_BANNERS[0].bg }}>
        <div className={styles.heroContent}>
          <span className={styles.heroEyebrow} style={{ color: HERO_BANNERS[0].accentColor }}>
            {HERO_BANNERS[0].eyebrow}
          </span>
          <h1 className={styles.heroHeadline}>
            {HERO_BANNERS[0].headline.split('\n').map((line, i) => (
              <span key={i} className={styles.heroHeadlineLine}>{line}</span>
            ))}
          </h1>
          <p className={styles.heroSub}>{HERO_BANNERS[0].sub}</p>
          <Link
            to={HERO_BANNERS[0].ctaHref}
            className={styles.heroCta}
          >
            {HERO_BANNERS[0].cta}
          </Link>
        </div>
        <div className={styles.heroDecoration} aria-hidden="true">
          <div className={styles.heroDeco1} />
          <div className={styles.heroDeco2} />
          <div className={styles.heroDeco3} />
        </div>
      </div>

      <div className={styles.heroSecondary}>
        <div
          className={styles.heroSecCard}
          style={{ background: HERO_BANNERS[1].bg }}
        >
          <div className={styles.heroSecContent}>
            <span className={styles.heroSecEyebrow} style={{ color: HERO_BANNERS[1].accentColor }}>
              {HERO_BANNERS[1].eyebrow}
            </span>
            <p className={styles.heroSecHeadline}>
              {HERO_BANNERS[1].headline.split('\n').map((line, i) => (
                <span key={i} className={styles.heroHeadlineLine}>{line}</span>
              ))}
            </p>
            <Link to={HERO_BANNERS[1].ctaHref} className={styles.heroSecCta}>
              {HERO_BANNERS[1].cta}
            </Link>
          </div>
        </div>

        <div className={styles.heroQuickLinks}>
          <Link to="/search" className={styles.quickLink}>
            <span className={styles.quickLinkIcon} aria-hidden="true">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <circle cx="11" cy="11" r="8" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
            </span>
            <span className={styles.quickLinkLabel}>전체검색</span>
          </Link>
          <Link to="/category/skincare" className={styles.quickLink}>
            <span className={styles.quickLinkIcon} aria-hidden="true">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z" />
                <path d="M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z" />
              </svg>
            </span>
            <span className={styles.quickLinkLabel}>스킨케어</span>
          </Link>
          <Link to="/category/makeup" className={styles.quickLink}>
            <span className={styles.quickLinkIcon} aria-hidden="true">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <path d="M12 20h9" />
                <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
              </svg>
            </span>
            <span className={styles.quickLinkLabel}>메이크업</span>
          </Link>
          <Link to="/wishlist" className={styles.quickLink}>
            <span className={styles.quickLinkIcon} aria-hidden="true">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
              </svg>
            </span>
            <span className={styles.quickLinkLabel}>찜 목록</span>
          </Link>
        </div>
      </div>
    </section>
  )
}

// ---- Page -----------------------------------------------------------------------

export default function Home() {
  return (
    <div className={styles.page}>
      <HeroBannerBlock />

      <div className={styles.railsContainer}>
        {RAILS.map((rail) => (
          <ProductRail key={rail.key} config={rail} />
        ))}
      </div>
    </div>
  )
}
