package com.olive.commerce.public_api;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.product.BrandDtos;
import com.olive.commerce.product.BrandRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/brands")
public class BrandPublicController {

    private final BrandRepository brandRepository;

    public BrandPublicController(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    @GetMapping
    public ApiResponse<List<BrandDtos.PublicResponse>> list(
        @RequestParam(required = false) String name,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id"));
        Page<BrandDtos.PublicResponse> result = brandRepository.findAllActive(name, pageable)
            .map(BrandDtos::toPublicResponse);
        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ApiResponse.success(result.getContent(), meta);
    }
}
