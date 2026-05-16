-- Keep the smoke UI useful on a fresh local database.
-- V3 seeds a single product; this migration adds a broader demo catalog while
-- staying safe if the same demo rows were inserted manually during a smoke run.

SELECT setval(pg_get_serial_sequence('brands', 'id'), COALESCE((SELECT MAX(id) FROM brands), 1));
SELECT setval(pg_get_serial_sequence('categories', 'id'), COALESCE((SELECT MAX(id) FROM categories), 1));
SELECT setval(pg_get_serial_sequence('products', 'id'), COALESCE((SELECT MAX(id) FROM products), 1));
SELECT setval(pg_get_serial_sequence('product_options', 'id'), COALESCE((SELECT MAX(id) FROM product_options), 1));
SELECT setval(pg_get_serial_sequence('product_images', 'id'), COALESCE((SELECT MAX(id) FROM product_images), 1));

INSERT INTO brands (name, slug, logo_url, status)
VALUES
    ('라운드랩', 'roundlab', 'https://s3.local/brands/roundlab.png', 'ACTIVE'),
    ('닥터지', 'drg', 'https://s3.local/brands/drg.png', 'ACTIVE'),
    ('클리오', 'clio', 'https://s3.local/brands/clio.png', 'ACTIVE'),
    ('메디힐', 'mediheal', 'https://s3.local/brands/mediheal.png', 'ACTIVE'),
    ('아로마티카', 'aromatica', 'https://s3.local/brands/aromatica.png', 'ACTIVE'),
    ('브링그린', 'bringgreen', 'https://s3.local/brands/bringgreen.png', 'ACTIVE')
ON CONFLICT (name) DO NOTHING;

WITH demo_products (brand_slug, product_name, description, base_price, sale_price, category_name) AS (
    VALUES
        ('roundlab', '자작나무 수분 토너 500ml', '건조한 피부에 산뜻하게 흡수되는 대용량 수분 토너입니다.', 28000, 22400, '스킨케어'),
        ('drg', '레드 블레미쉬 진정 크림 70ml', '민감한 피부를 촉촉하게 진정시키는 데일리 수분 크림입니다.', 36000, 25200, '스킨케어'),
        ('bringgreen', '티트리 시카 트러블 세럼 30ml', '티트리와 시카 성분으로 번들거림 없이 케어하는 집중 세럼입니다.', 24000, 16800, '스킨케어'),
        ('thesecret', '약산성 버블 클렌징 폼 150ml', '풍성한 버블로 피부 장벽 부담을 줄인 약산성 클렌징 폼입니다.', 15000, 9900, '스킨케어'),
        ('mediheal', '콜라겐 에센셜 마스크팩 10매', '피부 탄력과 윤기를 위한 에센스 듬뿍 시트 마스크 세트입니다.', 22000, 14900, '스킨케어'),
        ('clio', '킬래쉬 워터프루프 마스카라 블랙', '번짐 없이 또렷한 속눈썹을 연출하는 워터프루프 마스카라입니다.', 18000, 12600, '메이크업'),
        ('clio', '쉬폰 블러 립 틴트 로즈', '가볍게 밀착되는 로즈 컬러의 소프트 블러 립 틴트입니다.', 16000, 11200, '메이크업'),
        ('clio', '커버핏 쿠션 파운데이션 21N', '얇게 밀착되면서 자연스러운 커버를 돕는 데일리 쿠션입니다.', 32000, 25600, '메이크업'),
        ('aromatica', '로즈마리 두피 쿨링 샴푸 500ml', '두피를 산뜻하게 씻어내는 로즈마리 쿨링 샴푸입니다.', 21000, 16800, '헤어/바디'),
        ('aromatica', '퍼퓸 바디워시 피그 480ml', '은은한 무화과 향으로 샤워 후 잔향을 남기는 바디워시입니다.', 19000, 15200, '헤어/바디'),
        ('thesecret', '시어버터 핸드크림 3종 세트', '휴대하기 좋은 보습 핸드크림 3종 기획 세트입니다.', 12000, 8900, '헤어/바디'),
        ('drg', '남성 올인원 플루이드 150ml', '스킨과 로션 단계를 한 번에 끝내는 산뜻한 남성 올인원입니다.', 26000, 19500, '스킨케어')
)
INSERT INTO products (brand_id, name, description, status, base_price, sale_price)
SELECT b.id, dp.product_name, dp.description, 'ON_SALE', dp.base_price, dp.sale_price
FROM demo_products dp
JOIN brands b ON b.slug = dp.brand_slug
WHERE NOT EXISTS (
    SELECT 1
    FROM products p
    WHERE p.name = dp.product_name
);

WITH target_products AS (
    SELECT p.id, p.name, dp.category_name
    FROM products p
    JOIN (
        VALUES
            ('자작나무 수분 토너 500ml', '스킨케어'),
            ('레드 블레미쉬 진정 크림 70ml', '스킨케어'),
            ('티트리 시카 트러블 세럼 30ml', '스킨케어'),
            ('약산성 버블 클렌징 폼 150ml', '스킨케어'),
            ('콜라겐 에센셜 마스크팩 10매', '스킨케어'),
            ('킬래쉬 워터프루프 마스카라 블랙', '메이크업'),
            ('쉬폰 블러 립 틴트 로즈', '메이크업'),
            ('커버핏 쿠션 파운데이션 21N', '메이크업'),
            ('로즈마리 두피 쿨링 샴푸 500ml', '헤어/바디'),
            ('퍼퓸 바디워시 피그 480ml', '헤어/바디'),
            ('시어버터 핸드크림 3종 세트', '헤어/바디'),
            ('남성 올인원 플루이드 150ml', '스킨케어')
    ) AS dp(product_name, category_name) ON dp.product_name = p.name
)
INSERT INTO product_options (product_id, option_name, option_price, status)
SELECT tp.id, '기본', 0, 'ON_SALE'
FROM target_products tp
WHERE NOT EXISTS (
    SELECT 1
    FROM product_options po
    WHERE po.product_id = tp.id
      AND po.option_name = '기본'
);

WITH target_products AS (
    SELECT p.id
    FROM products p
    WHERE p.name IN (
        '자작나무 수분 토너 500ml',
        '레드 블레미쉬 진정 크림 70ml',
        '티트리 시카 트러블 세럼 30ml',
        '약산성 버블 클렌징 폼 150ml',
        '콜라겐 에센셜 마스크팩 10매',
        '킬래쉬 워터프루프 마스카라 블랙',
        '쉬폰 블러 립 틴트 로즈',
        '커버핏 쿠션 파운데이션 21N',
        '로즈마리 두피 쿨링 샴푸 500ml',
        '퍼퓸 바디워시 피그 480ml',
        '시어버터 핸드크림 3종 세트',
        '남성 올인원 플루이드 150ml'
    )
)
INSERT INTO product_images (product_id, url, sort_order, is_thumbnail)
SELECT tp.id, 'https://s3.local/products/demo-' || tp.id || '.png', 1, TRUE
FROM target_products tp
WHERE NOT EXISTS (
    SELECT 1
    FROM product_images pi
    WHERE pi.product_id = tp.id
      AND pi.is_thumbnail = TRUE
);

WITH target_products AS (
    SELECT p.id, dp.category_name
    FROM products p
    JOIN (
        VALUES
            ('자작나무 수분 토너 500ml', '스킨케어'),
            ('레드 블레미쉬 진정 크림 70ml', '스킨케어'),
            ('티트리 시카 트러블 세럼 30ml', '스킨케어'),
            ('약산성 버블 클렌징 폼 150ml', '스킨케어'),
            ('콜라겐 에센셜 마스크팩 10매', '스킨케어'),
            ('킬래쉬 워터프루프 마스카라 블랙', '메이크업'),
            ('쉬폰 블러 립 틴트 로즈', '메이크업'),
            ('커버핏 쿠션 파운데이션 21N', '메이크업'),
            ('로즈마리 두피 쿨링 샴푸 500ml', '헤어/바디'),
            ('퍼퓸 바디워시 피그 480ml', '헤어/바디'),
            ('시어버터 핸드크림 3종 세트', '헤어/바디'),
            ('남성 올인원 플루이드 150ml', '스킨케어')
    ) AS dp(product_name, category_name) ON dp.product_name = p.name
)
INSERT INTO product_category_mapping (product_id, category_id)
SELECT tp.id, c.id
FROM target_products tp
JOIN categories c ON c.name = tp.category_name
ON CONFLICT DO NOTHING;
