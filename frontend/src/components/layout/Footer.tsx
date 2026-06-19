import { Link } from 'react-router-dom'
import styles from './Footer.module.css'

interface FooterLink {
  label: string
  href: string
  external?: boolean
}

interface FooterColumn {
  title: string
  links: FooterLink[]
}

const LINK_COLUMNS: FooterColumn[] = [
  {
    title: '고객센터',
    links: [
      { label: '자주 묻는 질문', href: '#' },
      { label: '1:1 문의', href: '#' },
      { label: '공지사항', href: '#' },
    ],
  },
  {
    title: '이용안내',
    links: [
      { label: '이용약관', href: '#' },
      { label: '개인정보처리방침', href: '#' },
      { label: '배송안내', href: '#' },
      { label: '교환/반품 안내', href: '#' },
    ],
  },
  {
    title: '쇼핑',
    links: [
      { label: '전체 상품', href: '/search' },
      { label: '랭킹', href: '/' },
      { label: '마이페이지', href: '/mypage' },
      { label: '주문 내역', href: '/orders' },
    ],
  },
  {
    title: '회사소개',
    links: [
      { label: '올리브 스토어 소개', href: '#' },
      { label: '채용정보', href: '#' },
      { label: '뉴스룸', href: '#' },
    ],
  },
]

export default function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={styles.inner}>
        {/* Link columns */}
        <div className={styles.columns}>
          {LINK_COLUMNS.map((col) => (
            <div key={col.title} className={styles.col}>
              <p className={styles.colTitle}>{col.title}</p>
              {col.links.map((link) => (
                link.external ? (
                  <a
                    key={link.href}
                    href={link.href}
                    className={styles.colLink}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {link.label}
                  </a>
                ) : (
                  <Link key={`${col.title}-${link.label}`} to={link.href} className={styles.colLink}>
                    {link.label}
                  </Link>
                )
              ))}
            </div>
          ))}
        </div>

        {/* Company info */}
        <div className={styles.company}>
          <p className={styles.brand}>OLIVE</p>
          <p className={styles.info}>
            올리브 스토어 · 자가 호스팅 커머스 데모<br />
            올리브영(Olive Young)의 UI/UX를 학습 목적으로 재현한
            오픈소스 클론이며, 실제 브랜드와 제휴·연관이 없습니다.<br />
            고객센터: 1599-0000 (데모)<br />
            운영시간: 평일 09:00 ~ 18:00
          </p>
          <p className={styles.copyright}>
            &copy; {new Date().getFullYear()} 올리브 스토어 &mdash; Olive Young clone demo (비제휴)
          </p>
        </div>
      </div>
    </footer>
  )
}
