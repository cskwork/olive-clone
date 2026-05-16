package com.olive.commerce.ui;

import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 백오피스/스모크 콘솔용 Thymeleaf 셸 컨트롤러.
 *
 * 실제 카탈로그 데이터는 공개 API({@code GET /api/products})에서 fetch하므로
 * 이 컨트롤러는 안전한 기본값과 입력 정제만 책임진다.
 */
@Controller
public class StorefrontController {

    static final int PAGE_SIZE = 20;
    static final String DEFAULT_SORT = "LATEST";
    static final Set<String> ALLOWED_SORTS =
        Set.of("LATEST", "POPULAR", "PRICE_ASC", "PRICE_DESC", "RATING");

    @GetMapping({"/", "/products"})
    public String index(
        @RequestParam(required = false, defaultValue = DEFAULT_SORT) String sort,
        @RequestParam(required = false, defaultValue = "0") String page,
        Model model
    ) {
        String normalizedSort = normalizeSort(sort);
        int normalizedPage = normalizePage(page);

        model.addAttribute("selectedSort", normalizedSort);
        model.addAttribute("selectedPage", normalizedPage);
        model.addAttribute("pageSize", PAGE_SIZE);
        return "storefront/index";
    }

    private static String normalizeSort(String raw) {
        if (raw == null) {
            return DEFAULT_SORT;
        }
        String upper = raw.trim().toUpperCase();
        return ALLOWED_SORTS.contains(upper) ? upper : DEFAULT_SORT;
    }

    private static int normalizePage(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(raw.trim()), 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
