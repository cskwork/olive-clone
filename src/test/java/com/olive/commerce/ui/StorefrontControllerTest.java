package com.olive.commerce.ui;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = StorefrontController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class}
)
class StorefrontControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET / renders the storefront shell wired to /api/products")
    void getRoot_rendersStorefrontShell_thatConnectsToProductApi() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("storefront/index"))
            .andExpect(model().attribute("selectedSort", "LATEST"))
            .andExpect(model().attribute("selectedPage", 0))
            .andExpect(model().attribute("pageSize", 20))
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(containsString("Commerce Catalog Console")))
            .andExpect(content().string(containsString("Connecting to /api/products")))
            .andExpect(content().string(containsString("/js/storefront.js")))
            .andExpect(content().string(containsString("data-page-size=\"20\"")))
            .andExpect(content().string(containsString("class=\"pager\"")));
    }

    @Test
    @DisplayName("GET /products preserves the requested sort option")
    void getProducts_rendersStorefrontIndex_withRequestedSort() throws Exception {
        mockMvc.perform(get("/products").param("sort", "PRICE_ASC"))
            .andExpect(status().isOk())
            .andExpect(view().name("storefront/index"))
            .andExpect(model().attribute("selectedSort", "PRICE_ASC"));
    }

    @Test
    @DisplayName("Unknown sort values fall back to LATEST instead of leaking to the API")
    void getProducts_unknownSort_fallsBackToLatest() throws Exception {
        mockMvc.perform(get("/products").param("sort", "BOGUS"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("selectedSort", "LATEST"));
    }

    @Test
    @DisplayName("Lowercase sort values are normalized to the canonical uppercase form")
    void getProducts_lowercaseSort_isNormalized() throws Exception {
        mockMvc.perform(get("/products").param("sort", "price_desc"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("selectedSort", "PRICE_DESC"));
    }

    @Test
    @DisplayName("Non-negative page parameter is propagated to the model")
    void getProducts_pageParameter_isPropagated() throws Exception {
        mockMvc.perform(get("/products").param("page", "3"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("selectedPage", 3));
    }

    @Test
    @DisplayName("Negative page parameter is clamped to 0")
    void getProducts_negativePage_isClampedToZero() throws Exception {
        mockMvc.perform(get("/products").param("page", "-5"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("selectedPage", 0));
    }

    @Test
    @DisplayName("Non-numeric page parameter does not 400 — shell defaults to page 0")
    void getProducts_nonNumericPage_fallsBackToZero() throws Exception {
        mockMvc.perform(get("/products").param("page", "abc"))
            .andExpect(status().isOk())
            .andExpect(view().name("storefront/index"))
            .andExpect(model().attribute("selectedPage", 0));
    }

    @Test
    @DisplayName("Out-of-range page parameter does not 400 — shell defaults to page 0")
    void getProducts_outOfRangePage_fallsBackToZero() throws Exception {
        mockMvc.perform(get("/products").param("page", "99999999999999999999"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("selectedPage", 0));
    }

    @Test
    @DisplayName("Out-of-range page renders data-initial-page=\"0\" so JS trusts the server-normalized value")
    void getProducts_outOfRangePage_rendersNormalizedDataInitialPage() throws Exception {
        mockMvc.perform(get("/products").param("page", "99999999999999999999"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-initial-page=\"0\"")));
    }

    @Test
    @DisplayName("Non-numeric page renders data-initial-page=\"0\" so JS trusts the server-normalized value")
    void getProducts_nonNumericPage_rendersNormalizedDataInitialPage() throws Exception {
        mockMvc.perform(get("/products").param("page", "abc"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-initial-page=\"0\"")));
    }

    @Test
    @DisplayName("Valid page parameter is reflected verbatim in data-initial-page")
    void getProducts_validPage_rendersMatchingDataInitialPage() throws Exception {
        mockMvc.perform(get("/products").param("page", "7"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-initial-page=\"7\"")));
    }

    @Test
    @DisplayName("Lowercase sort renders the canonical <option> as selected so JS can trust select.value")
    void getProducts_lowercaseSort_marksNormalizedOptionAsSelected() throws Exception {
        mockMvc.perform(get("/products").param("sort", "price_desc"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("selectedSort", "PRICE_DESC"))
            .andExpect(content().string(containsString("value=\"PRICE_DESC\" selected")))
            .andExpect(content().string(containsString("data-initial-sort=\"PRICE_DESC\"")));
    }

    @Test
    @DisplayName("Storefront shell exposes operator affordances — refresh, retry, endpoint preview, range")
    void getRoot_exposesOperatorAffordances() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"refreshBtn\"")))
            .andExpect(content().string(containsString("id=\"retryBtn\"")))
            .andExpect(content().string(containsString("id=\"endpointPreview\"")))
            .andExpect(content().string(containsString("id=\"copyEndpoint\"")))
            .andExpect(content().string(containsString("id=\"apiLatency\"")))
            .andExpect(content().string(containsString("id=\"rangeLabel\"")));
    }
}
