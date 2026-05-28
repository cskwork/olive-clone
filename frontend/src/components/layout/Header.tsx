import { Link, useLocation } from 'react-router-dom'
import { getAccessToken, setAccessToken } from '@/lib/api'
import styles from './Header.module.css'

interface SubCategory {
  label: string
  href: string
}

interface Category {
  id: string
  label: string
  subs: SubCategory[]
}

const CATEGORIES: Category[] = [
  {
    id: 'skincare',
    label: '스킨케어',
    subs: [
      { label: '토너/스킨', href: '/category/toner' },
      { label: '에센스/세럼', href: '/category/serum' },
      { label: '크림/로션', href: '/category/cream' },
      { label: '마스크팩', href: '/category/mask' },
    ],
  },
  {
    id: 'makeup',
    label: '메이크업',
    subs: [
      { label: '베이스 메이크업', href: '/category/base' },
      { label: '아이 메이크업', href: '/category/eye' },
      { label: '립 메이크업', href: '/category/lip' },
    ],
  },
  {
    id: 'hair-body',
    label: '헤어·바디',
    subs: [
      { label: '샴푸/컨디셔너', href: '/category/shampoo' },
      { label: '바디워시', href: '/category/body-wash' },
      { label: '헤어케어', href: '/category/haircare' },
    ],
  },
  {
    id: 'health-food',
    label: '건강·푸드',
    subs: [
      { label: '건강기능식품', href: '/category/health' },
      { label: '단백질/다이어트', href: '/category/protein' },
      { label: '음료·티', href: '/category/drink' },
    ],
  },
  {
    id: 'mens',
    label: '맨즈',
    subs: [
      { label: '스킨케어', href: '/category/mens-skin' },
      { label: '면도/클렌징', href: '/category/shaving' },
    ],
  },
  {
    id: 'beauty-device',
    label: '뷰티디바이스',
    subs: [
      { label: 'LED·초음파', href: '/category/led' },
      { label: '미용기기', href: '/category/device' },
    ],
  },
]

export default function Header() {
  const location = useLocation()
  const isLoggedIn = Boolean(getAccessToken())

  const handleLogout = () => {
    setAccessToken(null)
    window.location.href = '/'
  }

  const isTab = (path: string) => location.pathname === path

  return (
    <>
      <header className={styles.header}>
        {/* Top bar */}
        <div className={styles.topBar}>
          {/* Logo */}
          <Link to="/" className={styles.logoLink} aria-label="올리브 스토어 홈으로">
            <span className={styles.logoText}>OLIVE</span>
          </Link>

          {/* Search — desktop */}
          <div className={styles.searchWrap}>
            <form
              className={styles.searchForm}
              role="search"
              aria-label="상품 검색"
              onSubmit={(e) => e.preventDefault()}
            >
              <input
                type="search"
                className={styles.searchInput}
                placeholder="브랜드, 상품, 성분을 검색해보세요"
                aria-label="검색어 입력"
              />
              <button type="submit" className={styles.searchBtn} aria-label="검색">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                  <circle cx="11" cy="11" r="8" />
                  <line x1="21" y1="21" x2="16.65" y2="16.65" />
                </svg>
              </button>
            </form>
          </div>

          {/* Actions — desktop */}
          <nav className={styles.actions} aria-label="사용자 메뉴">
            {isLoggedIn ? (
              <>
                <Link to="/mypage" className={styles.iconBtn} aria-label="마이페이지">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                    <circle cx="12" cy="7" r="4" />
                  </svg>
                  <span className={styles.iconBtnLabel}>마이</span>
                </Link>
                <button type="button" className={styles.iconBtn} onClick={handleLogout} aria-label="로그아웃">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                    <polyline points="16 17 21 12 16 7" />
                    <line x1="21" y1="12" x2="9" y2="12" />
                  </svg>
                  <span className={styles.iconBtnLabel}>로그아웃</span>
                </button>
              </>
            ) : (
              <Link to="/login" className={styles.iconBtn} aria-label="로그인">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                  <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" />
                  <polyline points="10 17 15 12 10 7" />
                  <line x1="15" y1="12" x2="3" y2="12" />
                </svg>
                <span className={styles.iconBtnLabel}>로그인</span>
              </Link>
            )}
            <Link to="/cart" className={styles.iconBtn} aria-label="장바구니">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
                <line x1="3" y1="6" x2="21" y2="6" />
                <path d="M16 10a4 4 0 0 1-8 0" />
              </svg>
              <span className={styles.iconBtnLabel}>장바구니</span>
            </Link>
            <Link to="/wishlist" className={styles.iconBtn} aria-label="찜">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
              </svg>
              <span className={styles.iconBtnLabel}>찜</span>
            </Link>
          </nav>

          {/* Hamburger — mobile */}
          <button type="button" className={styles.hamburger} aria-label="메뉴 열기" aria-expanded="false">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <line x1="3" y1="6" x2="21" y2="6" />
              <line x1="3" y1="12" x2="21" y2="12" />
              <line x1="3" y1="18" x2="21" y2="18" />
            </svg>
          </button>
        </div>

        {/* Category nav — desktop */}
        <nav className={styles.catNav} aria-label="카테고리 메뉴">
          <div className={styles.catNavInner}>
            {CATEGORIES.map((cat) => (
              <div key={cat.id} className={styles.catItem}>
                <Link to={`/category/${cat.id}`} className={styles.catLink}>
                  {cat.label}
                </Link>
                {cat.subs.length > 0 && (
                  <div className={styles.mega} role="menu" aria-label={`${cat.label} 하위 메뉴`}>
                    {cat.subs.map((sub) => (
                      <Link
                        key={sub.href}
                        to={sub.href}
                        className={styles.megaLink}
                        role="menuitem"
                      >
                        {sub.label}
                      </Link>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        </nav>
      </header>

      {/* Mobile bottom tab bar */}
      <nav className={styles.bottomTab} aria-label="하단 탭 메뉴">
        <Link to="/" className={`${styles.tabItem} ${isTab('/') ? styles.active : ''}`} aria-label="홈">
          <svg width="22" height="22" viewBox="0 0 24 24" fill={isTab('/') ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
            <polyline points="9 22 9 12 15 12 15 22" />
          </svg>
          <span className={styles.tabLabel}>홈</span>
        </Link>
        <Link to="/categories" className={`${styles.tabItem} ${isTab('/categories') ? styles.active : ''}`} aria-label="카테고리">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <rect x="3" y="3" width="7" height="7" rx="1" />
            <rect x="14" y="3" width="7" height="7" rx="1" />
            <rect x="3" y="14" width="7" height="7" rx="1" />
            <rect x="14" y="14" width="7" height="7" rx="1" />
          </svg>
          <span className={styles.tabLabel}>카테고리</span>
        </Link>
        <Link to="/search" className={`${styles.tabItem} ${isTab('/search') ? styles.active : ''}`} aria-label="검색">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <span className={styles.tabLabel}>검색</span>
        </Link>
        <Link to="/wishlist" className={`${styles.tabItem} ${isTab('/wishlist') ? styles.active : ''}`} aria-label="찜">
          <svg width="22" height="22" viewBox="0 0 24 24" fill={isTab('/wishlist') ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
          </svg>
          <span className={styles.tabLabel}>찜</span>
        </Link>
        <Link to="/mypage" className={`${styles.tabItem} ${isTab('/mypage') ? styles.active : ''}`} aria-label="마이">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
            <circle cx="12" cy="7" r="4" />
          </svg>
          <span className={styles.tabLabel}>마이</span>
        </Link>
      </nav>
    </>
  )
}
