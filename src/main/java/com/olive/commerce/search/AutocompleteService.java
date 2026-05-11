package com.olive.commerce.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.query_dsl.MatchPhrasePrefixQuery;
import org.opensearch.client.opensearch._types.query_dsl.PrefixQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 자동완성 (OLV-101 / PRD §6.3).
 *
 * <p>OpenSearch bool query — should: match_phrase_prefix(productName), prefix(brandName),
 * prefix(tags). 응답은 distinct string 리스트. case-insensitive는 productName(text+standard
 * analyzer)의 lowercase 정규화와 brandName/tags(keyword)에 lowercased prefix를 보내는 것으로
 * 처리(한글은 영향 없음, 영문만 해당).
 *
 * <p>Redis 5분 캐시 — key 형식: {@code cache:search:autocomplete:{prefix-lower}}.
 */
@Service
public class AutocompleteService {

    private static final Logger log = LoggerFactory.getLogger(AutocompleteService.class);
    private static final String CACHE_KEY_PREFIX = "cache:search:autocomplete:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_SIZE = 20;

    private final OpenSearchClient client;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AutocompleteService(
        OpenSearchClient client,
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper
    ) {
        this.client = client;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<String> suggest(String prefix, int size) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return List.of();
        }
        int capped = Math.min(Math.max(size, 1), MAX_SIZE);
        String trimmed = prefix.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        String cacheKey = CACHE_KEY_PREFIX + lower + ":" + capped;

        String cached = readCache(cacheKey);
        if (cached != null) return cached.isEmpty() ? List.of() : parseList(cached);

        List<String> suggestions = queryOpenSearch(trimmed, lower, capped);
        writeCache(cacheKey, suggestions);
        return suggestions;
    }

    private List<String> queryOpenSearch(String original, String lower, int size) {
        Query query = Query.of(q -> q.bool(b -> b
            .should(s -> s.matchPhrasePrefix(MatchPhrasePrefixQuery.of(m -> m
                .field("productName").query(original)
            )))
            .should(s -> s.prefix(PrefixQuery.of(p -> p
                .field("brandName").value(lower)
            )))
            .should(s -> s.prefix(PrefixQuery.of(p -> p
                .field("tags").value(lower)
            )))
            .minimumShouldMatch("1")
            .filter(f -> f.term(t -> t.field("status").value(v -> v.stringValue("ON_SALE"))))
        ));

        // 후보를 충분히 받아 distinct로 size 채울 수 있게 hits는 size*2.
        int hitSize = Math.min(size * 3, 60);
        SearchRequest req = SearchRequest.of(b -> b
            .index(ProductDocument.INDEX_NAME)
            .query(query)
            .size(hitSize)
            .source(s -> s.filter(f -> f.includes("productName", "brandName", "tags")))
        );

        SearchResponse<ProductDocument> response;
        try {
            response = client.search(req, ProductDocument.class);
        } catch (OpenSearchException | IOException e) {
            log.warn("Autocomplete query failed (prefix={})", original, e);
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE, "검색 일시 중단");
        }

        Set<String> ordered = new LinkedHashSet<>();
        String lowerCmp = lower;
        for (Hit<ProductDocument> hit : response.hits().hits()) {
            ProductDocument doc = hit.source();
            if (doc == null) continue;
            if (matchesPrefix(doc.productName(), lowerCmp)) ordered.add(doc.productName());
            if (matchesPrefix(doc.brandName(), lowerCmp))   ordered.add(doc.brandName());
            if (doc.tags() != null) {
                for (String tag : doc.tags()) {
                    if (matchesPrefix(tag, lowerCmp)) ordered.add(tag);
                }
            }
            if (ordered.size() >= size) break;
        }
        return new ArrayList<>(ordered).subList(0, Math.min(size, ordered.size()));
    }

    private static boolean matchesPrefix(String value, String lowerPrefix) {
        if (value == null) return false;
        return value.toLowerCase(java.util.Locale.ROOT).contains(lowerPrefix);
    }

    private String readCache(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.debug("Autocomplete cache read failed: {}", key, e);
            return null;
        }
    }

    private void writeCache(String key, List<String> values) {
        try {
            String json = values.isEmpty() ? "" : objectMapper.writeValueAsString(values);
            redisTemplate.opsForValue().set(key, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Autocomplete cache write failed: {}", key, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseList(String json) {
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            log.warn("Autocomplete cache deserialize failed", e);
            return List.of();
        }
    }
}
