import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { apiPost, setAccessToken, ApiError } from '@/lib/api'
import type { LoginRequest, LoginResponse } from '@/lib/types'
import styles from './auth.module.css'

interface FormErrors {
  email?: string
  password?: string
}

function validate(email: string, password: string): FormErrors {
  const errs: FormErrors = {}
  if (!email) errs.email = '이메일을 입력해주세요.'
  else if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) errs.email = '이메일 형식이 올바르지 않습니다.'
  if (!password) errs.password = '비밀번호를 입력해주세요.'
  return errs
}

export default function Login() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<FormErrors>({})
  const [globalError, setGlobalError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setGlobalError(null)

    const errs = validate(email, password)
    if (Object.keys(errs).length > 0) {
      setErrors(errs)
      return
    }
    setErrors({})

    setSubmitting(true)
    try {
      const res = await apiPost<LoginResponse>('/auth/login', {
        email,
        password,
      } satisfies LoginRequest)
      setAccessToken(res.accessToken)
      navigate('/')
    } catch (err) {
      if (err instanceof ApiError) {
        setGlobalError(err.message)
      } else {
        setGlobalError('로그인 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.logo}>
          <span className={styles.logoText}>OLIVE</span>
        </div>

        <h1 className={styles.title}>로그인</h1>

        <form className={styles.form} onSubmit={handleSubmit} noValidate>
          {globalError && (
            <div className={styles.globalError} role="alert">
              {globalError}
            </div>
          )}

          <div className={styles.field}>
            <label htmlFor="email" className={styles.label}>이메일</label>
            <input
              id="email"
              type="email"
              className={`${styles.input} ${errors.email ? styles.error : ''}`}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="example@email.com"
              autoComplete="email"
              aria-invalid={Boolean(errors.email)}
              aria-describedby={errors.email ? 'email-error' : undefined}
            />
            {errors.email && (
              <span id="email-error" className={styles.fieldError} role="alert">{errors.email}</span>
            )}
          </div>

          <div className={styles.field}>
            <label htmlFor="password" className={styles.label}>비밀번호</label>
            <input
              id="password"
              type="password"
              className={`${styles.input} ${errors.password ? styles.error : ''}`}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="비밀번호 입력"
              autoComplete="current-password"
              aria-invalid={Boolean(errors.password)}
              aria-describedby={errors.password ? 'password-error' : undefined}
            />
            {errors.password && (
              <span id="password-error" className={styles.fieldError} role="alert">{errors.password}</span>
            )}
          </div>

          <button
            type="submit"
            className={styles.submitBtn}
            disabled={submitting}
            aria-busy={submitting}
          >
            {submitting ? '로그인 중…' : '로그인'}
          </button>
        </form>

        <p className={styles.footer}>
          아직 회원이 아니신가요?{' '}
          <Link to="/signup" className={styles.footerLink}>회원가입</Link>
        </p>
      </div>
    </div>
  )
}
