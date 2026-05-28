import { apiGet, apiPost, apiPatch, apiDelete } from './api'
import type {
  CartResponse,
  AddToCartRequest,
  AddToCartResponse,
  UpdateCartQuantityRequest,
} from './types'

export function fetchCart(signal?: AbortSignal): Promise<CartResponse> {
  return apiGet<CartResponse>('/cart', signal)
}

export function addToCart(body: AddToCartRequest): Promise<AddToCartResponse> {
  return apiPost<AddToCartResponse>('/cart/items', body)
}

export function updateCartItemQty(
  cartItemId: number,
  body: UpdateCartQuantityRequest,
): Promise<null> {
  return apiPatch<null>(`/cart/items/${cartItemId}`, body)
}

export function removeCartItem(cartItemId: number): Promise<null> {
  return apiDelete<null>(`/cart/items/${cartItemId}`)
}

// TODO: anonymous cart + merge
