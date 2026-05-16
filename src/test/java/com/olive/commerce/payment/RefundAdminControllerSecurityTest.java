package com.olive.commerce.payment;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

class RefundAdminControllerSecurityTest {

    @Test
    void refundAdminController_usesRoleBasedOrderAdminAuthorization() {
        PreAuthorize annotation = RefundAdminController.class.getAnnotation(PreAuthorize.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasRole('ORDER_ADMIN')");
    }
}
