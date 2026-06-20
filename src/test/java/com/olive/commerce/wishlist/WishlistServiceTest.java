package com.olive.commerce.wishlist;

import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.product.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WishlistService}.
 *
 * <p>Focuses on the add() paths: idempotent success when item already exists,
 * idempotent success on concurrent DataIntegrityViolationException (FIX-7), and
 * PRODUCT_NOT_FOUND when the product does not exist.
 */
@ExtendWith(MockitoExtension.class)
class WishlistServiceTest {

    @Mock
    private WishlistItemRepository wishlistItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private EntityManager em;

    private WishlistService service;

    private static final long MEMBER_ID = 1L;
    private static final long PRODUCT_ID = 10L;

    @BeforeEach
    void setUp() {
        service = new WishlistService(wishlistItemRepository, productRepository, em);
    }

    @Test
    @DisplayName("add: product not found throws PRODUCT_NOT_FOUND")
    void add_productNotFound_throwsBusinessException() {
        when(productRepository.existsById(PRODUCT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.add(MEMBER_ID, PRODUCT_ID))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                BusinessException be = (BusinessException) ex;
                assertThat(be.errorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
            });

        verify(wishlistItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("add: product already in wishlist (sequential check) is a no-op")
    void add_alreadyExists_noOp() {
        when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
        when(wishlistItemRepository.existsByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID))
            .thenReturn(true);

        assertThatCode(() -> service.add(MEMBER_ID, PRODUCT_ID)).doesNotThrowAnyException();

        verify(wishlistItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("add: concurrent duplicate insert (DataIntegrityViolationException) is treated as idempotent success")
    void add_concurrentDuplicate_dataIntegrityViolation_isIdempotentSuccess() {
        // Two threads passed the existsBy check simultaneously. The second writer hits
        // the DB UNIQUE constraint and Spring wraps it as DataIntegrityViolationException.
        when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
        when(wishlistItemRepository.existsByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID))
            .thenReturn(false);
        when(wishlistItemRepository.save(any()))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // Must NOT propagate — treated as idempotent success.
        assertThatCode(() -> service.add(MEMBER_ID, PRODUCT_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("add: new product is saved successfully")
    void add_newProduct_savedSuccessfully() {
        when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
        when(wishlistItemRepository.existsByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID))
            .thenReturn(false);
        when(wishlistItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.add(MEMBER_ID, PRODUCT_ID)).doesNotThrowAnyException();

        verify(wishlistItemRepository).save(any(WishlistItem.class));
    }
}
