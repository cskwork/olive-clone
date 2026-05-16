-- Replace unreachable placeholder image hosts with project-local product assets.
-- The storefront intentionally blocks *.local image hosts to avoid noisy browser
-- DNS errors during smoke runs, so demo thumbnails need same-origin URLs.

WITH image_updates (product_name, image_url) AS (
    VALUES
        ('키즈 매일 선크림 SPF50+ PA++++', '/images/products/the-saem-kids-suncream-spf50-thumb.png'),
        ('자작나무 수분 토너 500ml', '/images/products/roundlab-birch-toner-500ml.png'),
        ('레드 블레미쉬 진정 크림 70ml', '/images/products/drg-red-blemish-cream-70ml.png'),
        ('티트리 시카 트러블 세럼 30ml', '/images/products/bringgreen-teatree-cica-serum-30ml.png'),
        ('약산성 버블 클렌징 폼 150ml', '/images/products/the-saem-low-ph-bubble-cleansing-foam-150ml.png'),
        ('콜라겐 에센셜 마스크팩 10매', '/images/products/mediheal-collagen-mask-10ea.png'),
        ('킬래쉬 워터프루프 마스카라 블랙', '/images/products/clio-kill-lash-waterproof-mascara-black.png'),
        ('쉬폰 블러 립 틴트 로즈', '/images/products/clio-chiffon-blur-lip-tint-rose.png'),
        ('커버핏 쿠션 파운데이션 21N', '/images/products/clio-coverfit-cushion-foundation-21n.png'),
        ('로즈마리 두피 쿨링 샴푸 500ml', '/images/products/aromatica-rosemary-scalp-cooling-shampoo-500ml.png'),
        ('퍼퓸 바디워시 피그 480ml', '/images/products/aromatica-perfume-bodywash-fig-480ml.png'),
        ('시어버터 핸드크림 3종 세트', '/images/products/the-saem-sheabutter-handcream-3set.png'),
        ('남성 올인원 플루이드 150ml', '/images/products/drg-men-all-in-one-fluid-150ml.png')
)
UPDATE product_images pi
SET url = iu.image_url
FROM products p
JOIN image_updates iu ON iu.product_name = p.name
WHERE pi.product_id = p.id
  AND pi.is_thumbnail = TRUE;

WITH image_updates (product_name, image_url) AS (
    VALUES
        ('키즈 매일 선크림 SPF50+ PA++++', '/images/products/the-saem-kids-suncream-spf50-thumb.png'),
        ('자작나무 수분 토너 500ml', '/images/products/roundlab-birch-toner-500ml.png'),
        ('레드 블레미쉬 진정 크림 70ml', '/images/products/drg-red-blemish-cream-70ml.png'),
        ('티트리 시카 트러블 세럼 30ml', '/images/products/bringgreen-teatree-cica-serum-30ml.png'),
        ('약산성 버블 클렌징 폼 150ml', '/images/products/the-saem-low-ph-bubble-cleansing-foam-150ml.png'),
        ('콜라겐 에센셜 마스크팩 10매', '/images/products/mediheal-collagen-mask-10ea.png'),
        ('킬래쉬 워터프루프 마스카라 블랙', '/images/products/clio-kill-lash-waterproof-mascara-black.png'),
        ('쉬폰 블러 립 틴트 로즈', '/images/products/clio-chiffon-blur-lip-tint-rose.png'),
        ('커버핏 쿠션 파운데이션 21N', '/images/products/clio-coverfit-cushion-foundation-21n.png'),
        ('로즈마리 두피 쿨링 샴푸 500ml', '/images/products/aromatica-rosemary-scalp-cooling-shampoo-500ml.png'),
        ('퍼퓸 바디워시 피그 480ml', '/images/products/aromatica-perfume-bodywash-fig-480ml.png'),
        ('시어버터 핸드크림 3종 세트', '/images/products/the-saem-sheabutter-handcream-3set.png'),
        ('남성 올인원 플루이드 150ml', '/images/products/drg-men-all-in-one-fluid-150ml.png')
)
INSERT INTO product_images (product_id, url, sort_order, is_thumbnail)
SELECT p.id, iu.image_url, 1, TRUE
FROM products p
JOIN image_updates iu ON iu.product_name = p.name
WHERE NOT EXISTS (
    SELECT 1
    FROM product_images pi
    WHERE pi.product_id = p.id
      AND pi.is_thumbnail = TRUE
);

WITH sunscreen_details (sort_order, image_url) AS (
    VALUES
        (2, '/images/products/the-saem-kids-suncream-detail-1.png'),
        (3, '/images/products/the-saem-kids-suncream-detail-2.png')
)
UPDATE product_images pi
SET url = sd.image_url
FROM products p
CROSS JOIN sunscreen_details sd
WHERE pi.product_id = p.id
  AND p.name = '키즈 매일 선크림 SPF50+ PA++++'
  AND pi.is_thumbnail = FALSE
  AND pi.sort_order = sd.sort_order;

WITH sunscreen_details (sort_order, image_url) AS (
    VALUES
        (2, '/images/products/the-saem-kids-suncream-detail-1.png'),
        (3, '/images/products/the-saem-kids-suncream-detail-2.png')
)
INSERT INTO product_images (product_id, url, sort_order, is_thumbnail)
SELECT p.id, sd.image_url, sd.sort_order, FALSE
FROM products p
CROSS JOIN sunscreen_details sd
WHERE p.name = '키즈 매일 선크림 SPF50+ PA++++'
  AND NOT EXISTS (
      SELECT 1
      FROM product_images pi
      WHERE pi.product_id = p.id
        AND pi.sort_order = sd.sort_order
  );
