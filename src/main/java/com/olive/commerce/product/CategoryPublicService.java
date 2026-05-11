package com.olive.commerce.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CategoryPublicService {

    private static final String CACHE_KEY = "cache:categories:tree";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final CategoryRepository categoryRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CategoryPublicService(CategoryRepository categoryRepository,
                                StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper) {
        this.categoryRepository = categoryRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public CategoryDtos.PublicTreeResponse getTree() {
        String cached = redisTemplate.opsForValue().get(CACHE_KEY);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, CategoryDtos.PublicTreeResponse.class);
            } catch (JsonProcessingException e) {
                // Cache corrupted, fall through to rebuild
            }
        }

        CategoryDtos.PublicTreeResponse response = buildTreeFromDb();
        cacheTree(response);
        return response;
    }

    private CategoryDtos.PublicTreeResponse buildTreeFromDb() {
        List<CategoryRepository.CategoryProjection> projections = categoryRepository.findTreeAsProjections();

        // Build parent -> children map
        Map<Long, List<CategoryDtos.PublicTreeNode>> childrenMap = new HashMap<>();
        Map<Long, CategoryDtos.PublicTreeNode> nodeMap = new HashMap<>();

        for (CategoryRepository.CategoryProjection p : projections) {
            CategoryDtos.PublicTreeNode node = new CategoryDtos.PublicTreeNode(
                p.getId(), p.getName(), p.getSlug(), new ArrayList<>());
            nodeMap.put(p.getId(), node);
            // Only initialize children map for non-null parent IDs
            if (p.getParentId() != null) {
                childrenMap.putIfAbsent(p.getParentId(), new ArrayList<>());
            }
        }

        // Build tree structure
        List<CategoryDtos.PublicTreeNode> roots = new ArrayList<>();
        for (CategoryRepository.CategoryProjection p : projections) {
            CategoryDtos.PublicTreeNode node = nodeMap.get(p.getId());
            if (p.getParentId() == null) {
                roots.add(node);
            } else {
                CategoryDtos.PublicTreeNode parent = nodeMap.get(p.getParentId());
                if (parent != null) {
                    parent.children().add(node);
                }
            }
        }

        return new CategoryDtos.PublicTreeResponse(roots);
    }

    private void cacheTree(CategoryDtos.PublicTreeResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(CACHE_KEY, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            // Cache failure is not critical
        }
    }
}
