// API types mirroring the backend DTOs (com.olive.commerce.*).
// BigDecimal serializes to a JSON number via Jackson defaults.

export interface PageMeta {
  page: number
  size: number
  total: number
}

export interface FieldErrorEntry {
  field: string
  message: string
  rejectedValue: unknown
}

export interface ErrorBody {
  code: string
  message: string
  path?: string
  traceId?: string
  fieldErrors?: FieldErrorEntry[]
}

// ApiResponse<T> uses @JsonInclude(NON_NULL): null fields are omitted.
export interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: ErrorBody
  meta?: PageMeta
}

export type SortOption = 'POPULAR' | 'LATEST' | 'PRICE_ASC' | 'PRICE_DESC' | 'RATING'

// GET /api/products -> ProductDtos.PublicListItem
export interface ProductListItem {
  productId: number
  brandName: string | null
  productName: string
  salePrice: number
  originalPrice: number
  discountRate: number
  thumbnailUrl: string | null
  rating: number
  reviewCount: number
}

// GET /api/products/{id} -> ProductDtos.PublicDetailResponse
export interface ProductOptionSummary {
  optionId: number
  optionName: string
  optionPrice: number
  status: 'ACTIVE' | 'INACTIVE' | 'SOLD_OUT'
  availableQuantity: number | null
}

export interface ProductImageDetail {
  imageId: number
  url: string
  sortOrder: number
  isThumbnail: boolean
}

export interface CategoryPath {
  categoryId: number
  categoryName: string
  categorySlug: string
}

// --- Auth (POST /api/auth/*) ---------------------------------------------------

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  expiresInSec: number
}

export interface SignupRequest {
  email: string
  password: string
  name: string
  phone?: string
}

export interface SignupResponse {
  memberId: number
}

// -------------------------------------------------------------------------------

export interface ProductDetail {
  productId: number
  brandName: string | null
  brandLogoUrl: string | null
  productName: string
  description: string | null
  salePrice: number
  originalPrice: number
  discountRate: number
  options: ProductOptionSummary[]
  images: ProductImageDetail[]
  categories: CategoryPath[]
  rating: number
  reviewCount: number
}
