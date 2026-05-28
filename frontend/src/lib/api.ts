import type { ApiResponse, PageMeta } from './types'

const BASE = '/api'

/** Raised when the backend returns an error envelope or a non-2xx status. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RequestOptions {
  method?: string
  body?: unknown
  signal?: AbortSignal
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<ApiResponse<T>> {
  const token = getAccessToken()
  const res = await fetch(`${BASE}${path}`, {
    method: opts.method ?? 'GET',
    headers: {
      Accept: 'application/json',
      ...(opts.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    signal: opts.signal,
  })

  let envelope: ApiResponse<T> | null = null
  try {
    envelope = (await res.json()) as ApiResponse<T>
  } catch {
    // non-JSON body
  }

  if (!res.ok || !envelope?.success) {
    const err = envelope?.error
    throw new ApiError(err?.message ?? `요청 실패 (${res.status})`, res.status, err?.code)
  }
  return envelope
}

/** Returns the data payload, throwing ApiError on failure. */
export async function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  const env = await request<T>(path, { signal })
  return env.data as T
}

/** Returns data + pagination meta for list endpoints. */
export async function apiGetPage<T>(
  path: string,
  signal?: AbortSignal,
): Promise<{ data: T; meta: PageMeta | undefined }> {
  const env = await request<T>(path, { signal })
  return { data: env.data as T, meta: env.meta }
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const env = await request<T>(path, { method: 'POST', body })
  return env.data as T
}

// --- Auth token storage (in-memory + localStorage mirror) -------------------
const ACCESS_TOKEN_KEY = 'olive.accessToken'
let accessToken: string | null = null

export function getAccessToken(): string | null {
  if (accessToken) return accessToken
  accessToken = localStorage.getItem(ACCESS_TOKEN_KEY)
  return accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token
  if (token) localStorage.setItem(ACCESS_TOKEN_KEY, token)
  else localStorage.removeItem(ACCESS_TOKEN_KEY)
}
