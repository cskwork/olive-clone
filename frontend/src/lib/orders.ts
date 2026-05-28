import { apiGet, apiPost, apiPostWithHeaders } from './api'
import type {
  AddressResponse,
  CreateAddressRequest,
  CreateOrderRequest,
  CreateOrderResponse,
  ConfirmPaymentRequest,
  ConfirmPaymentResponse,
  OrderDetail,
} from './types'

// --- Addresses ----------------------------------------------------------------

export function fetchAddresses(signal?: AbortSignal): Promise<AddressResponse[]> {
  return apiGet<AddressResponse[]>('/me/addresses', signal)
}

export function createAddress(body: CreateAddressRequest): Promise<AddressResponse> {
  return apiPost<AddressResponse>('/me/addresses', body)
}

// --- Orders -------------------------------------------------------------------

/** POST /api/orders — creates an order (8-step pipeline). Idempotency-Key is required. */
export function createOrder(
  body: CreateOrderRequest,
  idempotencyKey: string,
): Promise<CreateOrderResponse> {
  return apiPostWithHeaders<CreateOrderResponse>('/orders', body, {
    'Idempotency-Key': idempotencyKey,
  })
}

/** POST /api/payments/confirm — mock PG confirm. */
export function confirmPayment(
  body: ConfirmPaymentRequest,
  idempotencyKey: string,
): Promise<ConfirmPaymentResponse> {
  return apiPostWithHeaders<ConfirmPaymentResponse>('/payments/confirm', body, {
    'Idempotency-Key': idempotencyKey,
  })
}

/** GET /api/orders/:orderNo — order detail. */
export function fetchOrderDetail(orderNo: string, signal?: AbortSignal): Promise<OrderDetail> {
  return apiGet<OrderDetail>(`/orders/${orderNo}`, signal)
}
