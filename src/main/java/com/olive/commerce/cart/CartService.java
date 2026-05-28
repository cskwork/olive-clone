package com.olive.commerce.cart;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.config.DomainProperties;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.inventory.Inventory;
import com.olive.commerce.inventory.InventoryRepository;
import com.olive.commerce.product.Product;
import com.olive.commerce.product.ProductOption;
import com.olive.commerce.product.ProductOptionRepository;
import com.olive.commerce.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Cart 도메인 서비스 (OLV-040).
 *
 * <p>회원 장바구니(DB) + 익명 장바구니(Redis) 관리.
 * 로그인 시 병합 로직을 포함한다 (PRD §6.4, §8.2).
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private static final String ANON_CART_KEY_PREFIX = "cart:anon:";

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final InventoryRepository inventoryRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DomainProperties domainProperties;

    // ========================================================================
    // 회원 장바구니 (Member Cart)
    // ========================================================================

    /**
     * 회원 장바구니 조회.
     *
     * @param memberId 회원 ID
     * @return 장바구니 응답 (최신 가격/상태 포함)
     */
    @Transactional(readOnly = true)
    public CartDtos.CartResponse getMemberCart(Long memberId) {
        Cart cart = cartRepository.findByMemberId(memberId)
            .orElse(Cart.create(memberId));
        return buildCartResponse(cart);
    }

    /**
     * 회원 장바구니에 아이템 추가.
     *
     * <p>이미 존재하면 수량 증분, 아니면 새로 추가.
     *
     * @param memberId 회원 ID
     * @param request  추가 요청
     * @return 추가 응답
     */
    @Transactional
    public CartDtos.AddItemResponse addMemberItem(Long memberId, CartDtos.AddItemRequest request) {
        // 옵션 상태 검증
        ProductOption option = productOptionRepository.findById(request.productOptionId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                "Product option not found: " + request.productOptionId()));

        if (option.getStatus() == ProductOption.OptionStatus.STOPPED ||
            option.getStatus() == ProductOption.OptionStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "Product option is not available for purchase: " + option.getStatus());
        }

        // 재고 검증
        Inventory inventory = inventoryRepository.findByProductOptionId(request.productOptionId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                "Inventory not found for option: " + request.productOptionId()));

        // 기존 아이템 조회
        Cart cart = cartRepository.findByMemberId(memberId)
            .orElseGet(() -> {
                Cart newCart = Cart.create(memberId);
                return cartRepository.save(newCart);
            });

        CartItem existingItem = cartItemRepository
            .findByCartIdAndProductOptionId(cart.getId(), request.productOptionId())
            .orElse(null);

        int newTotalQuantity;
        CartItem item;

        if (existingItem != null) {
            // 수량 증분
            newTotalQuantity = existingItem.getQuantity() + request.quantity();

            if (!inventory.hasAvailable(newTotalQuantity)) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY,
                    "Insufficient inventory. Available: " + inventory.getAvailableQuantity() +
                    ", Requested: " + newTotalQuantity);
            }

            existingItem.increment(request.quantity());
            item = cartItemRepository.save(existingItem);
        } else {
            // 새 아이템
            if (!inventory.hasAvailable(request.quantity())) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY,
                    "Insufficient inventory. Available: " + inventory.getAvailableQuantity() +
                    ", Requested: " + request.quantity());
            }

            item = CartItem.create(cart, request.productOptionId(), request.quantity());
            item = cartItemRepository.save(item);
            newTotalQuantity = request.quantity();
        }

        return new CartDtos.AddItemResponse(item.getId(), newTotalQuantity);
    }

    /**
     * 회원 장바구니 아이템 수량 수정.
     *
     * @param memberId    회원 ID
     * @param cartItemId  장바구니 아이템 ID
     * @param request     수정 요청
     */
    @Transactional
    public void updateMemberItemQuantity(Long memberId, Long cartItemId, CartDtos.UpdateQuantityRequest request) {
        CartItem item = cartItemRepository.findById(cartItemId)
            .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND,
                "Cart item not found: " + cartItemId));

        // 소유권 검증
        if (!Objects.equals(item.getCart().getMemberId(), memberId)) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND,
                "Cart item does not belong to member: " + memberId);
        }

        // 재고 검증
        Inventory inventory = inventoryRepository.findByProductOptionId(item.getProductOptionId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                "Inventory not found for option: " + item.getProductOptionId()));

        if (!inventory.hasAvailable(request.quantity())) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY,
                "Insufficient inventory. Available: " + inventory.getAvailableQuantity() +
                ", Requested: " + request.quantity());
        }

        item.updateQuantity(request.quantity());
        cartItemRepository.save(item);
    }

    /**
     * 회원 장바구니 아이템 삭제.
     *
     * @param memberId   회원 ID
     * @param cartItemId 장바구니 아이템 ID
     */
    @Transactional
    public void removeMemberItem(Long memberId, Long cartItemId) {
        CartItem item = cartItemRepository.findById(cartItemId)
            .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND,
                "Cart item not found: " + cartItemId));

        // 소유권 검증
        if (!Objects.equals(item.getCart().getMemberId(), memberId)) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND,
                "Cart item does not belong to member: " + memberId);
        }

        cartItemRepository.delete(item);
    }

    // ========================================================================
    // 익명 장바구니 (Anonymous Cart - Redis)
    // ========================================================================

    /**
     * 익명 장바구니 조회.
     *
     * @param sessionId 세션 ID
     * @return 장바구니 응답
     */
    public CartDtos.CartResponse getAnonymousCart(String sessionId) {
        String key = ANON_CART_KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);

        if (json == null || json.isBlank()) {
            return new CartDtos.CartResponse(List.of(), 0, BigDecimal.ZERO);
        }

        List<AnonymousCartItem> items = parseAnonymousCartItems(json);
        return buildAnonymousCartResponse(items);
    }

    /**
     * 익명 장바구니에 아이템 추가.
     *
     * @param sessionId 세션 ID
     * @param request   추가 요청
     * @return 추가 응답
     */
    public CartDtos.AddItemResponse addAnonymousItem(String sessionId, CartDtos.AddItemRequest request) {
        // 옵션 상태 검증
        ProductOption option = productOptionRepository.findById(request.productOptionId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                "Product option not found: " + request.productOptionId()));

        if (option.getStatus() == ProductOption.OptionStatus.STOPPED ||
            option.getStatus() == ProductOption.OptionStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "Product option is not available for purchase: " + option.getStatus());
        }

        // 재고 검증
        Inventory inventory = inventoryRepository.findByProductOptionId(request.productOptionId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                "Inventory not found for option: " + request.productOptionId()));

        if (!inventory.hasAvailable(request.quantity())) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY,
                "Insufficient inventory. Available: " + inventory.getAvailableQuantity() +
                ", Requested: " + request.quantity());
        }

        String key = ANON_CART_KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        List<AnonymousCartItem> items;

        if (json != null && !json.isBlank()) {
            items = parseAnonymousCartItems(json);
        } else {
            items = new ArrayList<>();
        }

        // 기존 아이템 확인
        AnonymousCartItem existing = items.stream()
            .filter(i -> i.productOptionId().equals(request.productOptionId()))
            .findFirst()
            .orElse(null);

        int newQuantity;
        if (existing != null) {
            // 수량 증분
            newQuantity = existing.quantity() + request.quantity();

            if (!inventory.hasAvailable(newQuantity)) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY,
                    "Insufficient inventory. Available: " + inventory.getAvailableQuantity() +
                    ", Requested: " + newQuantity);
            }

            items.remove(existing);
            items.add(new AnonymousCartItem(request.productOptionId(), newQuantity));
        } else {
            // 새 아이템
            newQuantity = request.quantity();
            items.add(new AnonymousCartItem(request.productOptionId(), newQuantity));
        }

        // Redis 저장
        redisTemplate.opsForValue().set(key, serializeAnonymousCartItems(items), domainProperties.getAnonCartTtl());

        return new CartDtos.AddItemResponse(null, newQuantity);
    }

    /**
     * 익명 장바구니 아이템 수량 수정.
     *
     * @param sessionId 세션 ID
     * @param optionId   옵션 ID
     * @param quantity   새 수량
     */
    public void updateAnonymousItemQuantity(String sessionId, Long optionId, Integer quantity) {
        String key = ANON_CART_KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);

        if (json == null || json.isBlank()) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND,
                "Anonymous cart not found for session: " + sessionId);
        }

        // 재고 검증
        Inventory inventory = inventoryRepository.findByProductOptionId(optionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                "Inventory not found for option: " + optionId));

        if (!inventory.hasAvailable(quantity)) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY,
                "Insufficient inventory. Available: " + inventory.getAvailableQuantity() +
                ", Requested: " + quantity);
        }

        List<AnonymousCartItem> items = parseAnonymousCartItems(json);
        AnonymousCartItem existing = items.stream()
            .filter(i -> i.productOptionId().equals(optionId))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND,
                "Item not found in anonymous cart: " + optionId));

        items.remove(existing);
        items.add(new AnonymousCartItem(optionId, quantity));

        redisTemplate.opsForValue().set(key, serializeAnonymousCartItems(items), domainProperties.getAnonCartTtl());
    }

    /**
     * 익명 장바구니 아이템 삭제.
     *
     * @param sessionId 세션 ID
     * @param optionId   옵션 ID
     */
    public void removeAnonymousItem(String sessionId, Long optionId) {
        String key = ANON_CART_KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);

        if (json == null || json.isBlank()) {
            return; // 이미 비어있음
        }

        List<AnonymousCartItem> items = parseAnonymousCartItems(json);
        items.removeIf(i -> i.productOptionId().equals(optionId));

        if (items.isEmpty()) {
            redisTemplate.delete(key);
        } else {
            redisTemplate.opsForValue().set(key, serializeAnonymousCartItems(items), domainProperties.getAnonCartTtl());
        }
    }

    /**
     * 익명 장바구니 삭제 (로그인 후 병합 완료 시).
     *
     * @param sessionId 세션 ID
     */
    public void deleteAnonymousCart(String sessionId) {
        String key = ANON_CART_KEY_PREFIX + sessionId;
        redisTemplate.delete(key);
    }

    // ========================================================================
    // 장바구니 병합 (Merge)
    // ========================================================================

    /**
     * 익명 장바구니를 회원 장바구니로 병합.
     *
     * <p>로직: union by product_option_id, sum quantities, cap at available_quantity.
     *
     * @param memberId  회원 ID
     * @param sessionId 세션 ID
     * @return 병합 응답
     */
    @Transactional
    public CartDtos.MergeResponse mergeCart(Long memberId, String sessionId) {
        String key = ANON_CART_KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);

        if (json == null || json.isBlank()) {
            // 익명 카트가 없음
            Cart cart = cartRepository.findByMemberId(memberId).orElse(null);
            int itemCount = cart != null ? cart.getItems().size() : 0;
            return new CartDtos.MergeResponse(0, itemCount);
        }

        List<AnonymousCartItem> anonItems = parseAnonymousCartItems(json);
        if (anonItems.isEmpty()) {
            redisTemplate.delete(key);
            return new CartDtos.MergeResponse(0, 0);
        }

        // 회원 장바구니 가져오기 또는 생성
        Cart memberCart = cartRepository.findByMemberId(memberId)
            .orElseGet(() -> cartRepository.save(Cart.create(memberId)));

        int mergedCount = 0;
        int totalItemCount = 0;

        for (AnonymousCartItem anonItem : anonItems) {
            // 옵션 상태 검증
            ProductOption option = productOptionRepository.findById(anonItem.productOptionId())
                .orElse(null);

            if (option == null ||
                option.getStatus() == ProductOption.OptionStatus.STOPPED ||
                option.getStatus() == ProductOption.OptionStatus.HIDDEN) {
                // 유효하지 않은 옵션은 건너뜀
                continue;
            }

            // 재고 확인
            Inventory inventory = inventoryRepository.findByProductOptionId(anonItem.productOptionId())
                .orElse(null);

            if (inventory == null) {
                continue;
            }

            // 기존 회원 카트 아이템 확인
            CartItem existingItem = cartItemRepository
                .findByCartIdAndProductOptionId(memberCart.getId(), anonItem.productOptionId())
                .orElse(null);

            int finalQuantity;
            if (existingItem != null) {
                // 수량 합산
                int sumQuantity = existingItem.getQuantity() + anonItem.quantity();
                finalQuantity = Math.min(sumQuantity, inventory.getAvailableQuantity());
                existingItem.updateQuantity(finalQuantity);
                cartItemRepository.save(existingItem);
            } else {
                // 새 아이템
                finalQuantity = Math.min(anonItem.quantity(), inventory.getAvailableQuantity());
                if (finalQuantity > 0) {
                    CartItem newItem = CartItem.create(memberCart, anonItem.productOptionId(), finalQuantity);
                    cartItemRepository.save(newItem);
                }
            }

            if (finalQuantity > 0) {
                mergedCount++;
            }
        }

        // 익명 카트 삭제
        redisTemplate.delete(key);

        // 최종 아이템 수
        totalItemCount = cartItemRepository.findByCartId(memberCart.getId()).size();

        return new CartDtos.MergeResponse(mergedCount, totalItemCount);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * 장바구니 응답 빌드 (최신 가격/상태 포함).
     */
    private CartDtos.CartResponse buildCartResponse(Cart cart) {
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());

        List<CartDtos.CartItemResponse> itemResponses = items.stream()
            .map(this::buildCartItemResponse)
            .collect(Collectors.toList());

        int totalItemCount = itemResponses.stream()
            .mapToInt(CartDtos.CartItemResponse::quantity)
            .sum();

        BigDecimal totalAmount = itemResponses.stream()
            .map(CartDtos.CartItemResponse::lineSubtotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartDtos.CartResponse(itemResponses, totalItemCount, totalAmount);
    }

    /**
     * 장바구니 아이템 응답 빌드 (최신 가격/상태 포함).
     */
    private CartDtos.CartItemResponse buildCartItemResponse(CartItem item) {
        ProductOption option = productOptionRepository.findById(item.getProductOptionId())
            .orElse(null);

        if (option == null) {
            // 옵션이 삭제됨
            return new CartDtos.CartItemResponse(
                item.getId(),
                item.getProductOptionId(),
                null,
                null,
                null,
                false,
                0,
                item.getQuantity(),
                null,
                "DELETED"
            );
        }

        Product product = option.getProduct();
        if (product == null) {
            return new CartDtos.CartItemResponse(
                item.getId(),
                item.getProductOptionId(),
                option.getOptionName(),
                null,
                null,
                false,
                0,
                item.getQuantity(),
                null,
                option.getStatus().name()
            );
        }

        // 재고
        Inventory inventory = inventoryRepository.findByProductOptionId(item.getProductOptionId())
            .orElse(null);
        int availableQuantity = inventory != null ? inventory.getAvailableQuantity() : 0;

        // 가격
        BigDecimal salePrice = product.getSalePrice();
        boolean onSale = salePrice != null && salePrice.compareTo(BigDecimal.ZERO) > 0;

        // 라인 소계
        BigDecimal lineSubtotal = onSale
            ? salePrice.add(option.getOptionPrice()).multiply(BigDecimal.valueOf(item.getQuantity()))
            : product.getBasePrice().add(option.getOptionPrice()).multiply(BigDecimal.valueOf(item.getQuantity()));

        return new CartDtos.CartItemResponse(
            item.getId(),
            item.getProductOptionId(),
            option.getOptionName(),
            product.getName(),
            salePrice != null ? salePrice.add(option.getOptionPrice()) : null,
            onSale,
            availableQuantity,
            item.getQuantity(),
            lineSubtotal,
            option.getStatus().name()
        );
    }

    /**
     * 익명 장바구니 응답 빌드.
     */
    private CartDtos.CartResponse buildAnonymousCartResponse(List<AnonymousCartItem> items) {
        List<CartDtos.CartItemResponse> itemResponses = items.stream()
            .map(item -> {
                ProductOption option = productOptionRepository.findById(item.productOptionId()).orElse(null);
                if (option == null) {
                    return new CartDtos.CartItemResponse(
                        null,
                        item.productOptionId(),
                        null,
                        null,
                        null,
                        false,
                        0,
                        item.quantity(),
                        null,
                        "DELETED"
                    );
                }

                Product product = option.getProduct();
                Inventory inventory = inventoryRepository.findByProductOptionId(item.productOptionId()).orElse(null);
                int availableQuantity = inventory != null ? inventory.getAvailableQuantity() : 0;

                BigDecimal salePrice = product != null ? product.getSalePrice() : null;
                boolean onSale = salePrice != null && salePrice.compareTo(BigDecimal.ZERO) > 0;

                BigDecimal lineSubtotal = onSale && product != null
                    ? salePrice.add(option.getOptionPrice()).multiply(BigDecimal.valueOf(item.quantity()))
                    : product != null
                        ? product.getBasePrice().add(option.getOptionPrice()).multiply(BigDecimal.valueOf(item.quantity()))
                        : BigDecimal.ZERO;

                return new CartDtos.CartItemResponse(
                    null,
                    item.productOptionId(),
                    option.getOptionName(),
                    product != null ? product.getName() : null,
                    salePrice != null ? salePrice.add(option.getOptionPrice()) : null,
                    onSale,
                    availableQuantity,
                    item.quantity(),
                    lineSubtotal,
                    option.getStatus().name()
                );
            })
            .collect(Collectors.toList());

        int totalItemCount = itemResponses.stream()
            .mapToInt(CartDtos.CartItemResponse::quantity)
            .sum();

        BigDecimal totalAmount = itemResponses.stream()
            .map(CartDtos.CartItemResponse::lineSubtotal)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartDtos.CartResponse(itemResponses, totalItemCount, totalAmount);
    }

    /**
     * 익명 장바구니 아이템 JSON 파싱.
     */
    private List<AnonymousCartItem> parseAnonymousCartItems(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>(){})
                .stream()
                .map(map -> new AnonymousCartItem(
                    ((Number) map.get("productOptionId")).longValue(),
                    ((Number) map.get("quantity")).intValue()
                ))
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to parse anonymous cart items: {}", json, e);
            return List.of();
        }
    }

    /**
     * 익명 장바구니 아이템 JSON 직렬화.
     */
    private String serializeAnonymousCartItems(List<AnonymousCartItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            log.error("Failed to serialize anonymous cart items", e);
            return "[]";
        }
    }

    /**
     * 익명 장바구니 아이템 (내부용).
     */
    private record AnonymousCartItem(
        Long productOptionId,
        Integer quantity
    ) {}
}
