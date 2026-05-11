package com.olive.commerce.public_api;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.product.CategoryDtos;
import com.olive.commerce.product.CategoryPublicService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
public class CategoryPublicController {

    private final CategoryPublicService categoryPublicService;

    public CategoryPublicController(CategoryPublicService categoryPublicService) {
        this.categoryPublicService = categoryPublicService;
    }

    @GetMapping
    public ApiResponse<CategoryDtos.PublicTreeResponse> getTree() {
        return ApiResponse.success(categoryPublicService.getTree());
    }
}
