# E2E 테스트 API 엔드포인트 정리

## 1. 인증 (Auth)

### POST /api/auth/signup
```json
// Request
{
  "email": "user@example.com",
  "password": "Password123!",
  "name": "홍길동",
  "phone": "01012345678"
}

// Response 201
{
  "success": true,
  "data": {
    "memberId": 1,
    "email": "user@example.com",
    "role": "USER"
  }
}
```

### POST /api/auth/login
```json
// Request
{
  "email": "user@example.com",
  "password": "Password123!"
}

// Response 200
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGci...",
    "refreshToken": "eyJhbGci...",
    "memberId": 1
  }
}
```

## 2. 상품 (Product) - Admin

### POST /api/admin/products
```json
// Request
{
  "brandId": 1,
  "name": "테스트 상품",
  "description": "설명",
  "basePrice": 10000,
  "salePrice": 10000,
  "status": "ON_SALE",
  "categoryIds": [1],
  "options": [
    {"optionName": "옵션A", "optionPrice": 10000},
    {"optionName": "옵션B", "optionPrice": 15000}
  ]
}

// Response 201
{
  "success": true,
  "data": {
    "productId": 1,
    "slug": "test-product"
  }
}
```

### POST /api/admin/products/{id}/restock
```json
// Request
{
  "productOptionId": 1,
  "quantity": 100
}

// Response 200
{
  "success": true
}
```

### POST /api/admin/products/presigned-upload
```json
// Request
{
  "fileName": "product.jpg",
  "contentType": "image/jpeg"
}

// Response 200
{
  "success": true,
  "data": {
    "presignedUrl": "https://s3...",
    "publicUrl": "https://s3.../product.jpg"
  }
}
```

## 3. 상품 (Product) - Public

### GET /api/products
```json
// Response 200
{
  "success": true,
  "data": {
    "items": [...],
    "total": 1
  }
}
```

### GET /api/products/{slug}
```json
// Response 200
{
  "success": true,
  "data": {
    "productId": 1,
    "name": "테스트 상품",
    "options": [...]
  }
}
```

## 4. 장바구니 (Cart)

### POST /api/cart/items
```json
// Request
{
  "productOptionId": 1,
  "quantity": 2
}

// Response 201
{
  "success": true,
  "data": {
    "cartItemId": 1
  }
}
```

## 5. 주문 (Order)

### POST /api/orders
```json
// Request
{
  "items": [
    {"productOptionId": 1, "quantity": 2}
  ],
  "couponId": 1,
  "usePointAmount": 500,
  "deliveryAddressId": 1
}

// Response 201
{
  "success": true,
  "data": {
    "orderNo": "ORD20260513000001",
    "amount": 20000,
    "paymentKey": "temp-payment-key"
  }
}
```

## 6. 결제 (Payment)

### POST /api/payments/confirm
Headers:
- `Idempotency-Key: <UUID>`
- `X-Mock-Pg-Behaviour: approve`

```json
// Request
{
  "orderNo": "ORD20260513000001",
  "paymentKey": "pg-payment-key-123",
  "amount": 20000
}

// Response 200
{
  "success": true,
  "data": {
    "orderNo": "ORD20260513000001",
    "status": "PAID",
    "paymentKey": "pg-payment-key-123"
  }
}
```

## 7. 배송 (Delivery) - Webhook

### POST /api/deliveries/webhook
```json
// Request (DELIVERY_COMPLETED)
{
  "invoiceNo": "1234567890",
  "status": "DELIVERED"
}

// Response 200
{
  "success": true
}
```

## 8. 리뷰 (Review)

### POST /api/reviews
```json
// Request
{
  "orderItemId": 1,
  "rating": 5,
  "content": "좋은 상품입니다"
}

// Response 201
{
  "success": true,
  "data": {
    "reviewId": 1
  }
}
```
