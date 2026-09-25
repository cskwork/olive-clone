-- Replace the real cosmetics brand and product-line names used by the demo seed
-- (V3, V15, V16) with fictional ones so the public demo carries no third-party
-- trademarks. Only names, slugs, logo URLs and image paths change: ids, prices,
-- stock, options, categories and every relationship stay as they were.
--
-- Rows are matched on the old seeded values, so the migration is a no-op for
-- rows an operator has already renamed or removed. The storefront demo fixtures
-- (frontend/src/demo/fixtures.ts) apply the same mapping, and
-- frontend/src/demo/mockApi.test.ts parses the VALUES lists below.

WITH brand_renames (old_slug, new_name, new_slug, new_logo_url) AS (
    VALUES
        ('thesecret', '새봄담', 'saebomdam', 'https://s3.local/brands/saebomdam.png'),
        ('roundlab', '도담랩', 'dodamlab', 'https://s3.local/brands/dodamlab.png'),
        ('drg', '더마하랑', 'dermaharang', 'https://s3.local/brands/dermaharang.png'),
        ('clio', '벨로아', 'veloa', 'https://s3.local/brands/veloa.png'),
        ('mediheal', '힐로담', 'hilodam', 'https://s3.local/brands/hilodam.png'),
        ('aromatica', '솔향공방', 'solhyang', 'https://s3.local/brands/solhyang.png'),
        ('bringgreen', '그린마루', 'greenmaru', 'https://s3.local/brands/greenmaru.png')
)
UPDATE brands b
SET name = br.new_name,
    slug = br.new_slug,
    logo_url = br.new_logo_url
FROM brand_renames br
WHERE b.slug = br.old_slug;

WITH product_renames (old_name, new_name) AS (
    VALUES
        ('자작나무 수분 토너 500ml', '새벽이슬 수분 토너 500ml'),
        ('레드 블레미쉬 진정 크림 70ml', '카밍 시카 진정 크림 70ml'),
        ('티트리 시카 트러블 세럼 30ml', '티트리 카밍 트러블 세럼 30ml'),
        ('콜라겐 에센셜 마스크팩 10매', '콜라겐 탄력 시트 마스크팩 10매'),
        ('킬래쉬 워터프루프 마스카라 블랙', '컬링 픽서 워터프루프 마스카라 블랙'),
        ('쉬폰 블러 립 틴트 로즈', '소프트 매트 립 틴트 로즈'),
        ('커버핏 쿠션 파운데이션 21N', '데일리 밀착 쿠션 파운데이션 21N')
)
UPDATE products p
SET name = pr.new_name
FROM product_renames pr
WHERE p.name = pr.old_name;

WITH image_renames (old_url, new_url) AS (
    VALUES
        ('/images/products/the-saem-kids-suncream-spf50-thumb.png', '/images/products/saebomdam-kids-suncream-spf50-thumb.png'),
        ('/images/products/the-saem-kids-suncream-detail-1.png', '/images/products/saebomdam-kids-suncream-detail-1.png'),
        ('/images/products/the-saem-kids-suncream-detail-2.png', '/images/products/saebomdam-kids-suncream-detail-2.png'),
        ('/images/products/roundlab-birch-toner-500ml.png', '/images/products/dodamlab-dew-toner-500ml.png'),
        ('/images/products/drg-red-blemish-cream-70ml.png', '/images/products/dermaharang-calming-cica-cream-70ml.png'),
        ('/images/products/bringgreen-teatree-cica-serum-30ml.png', '/images/products/greenmaru-teatree-calming-serum-30ml.png'),
        ('/images/products/the-saem-low-ph-bubble-cleansing-foam-150ml.png', '/images/products/saebomdam-low-ph-bubble-cleansing-foam-150ml.png'),
        ('/images/products/mediheal-collagen-mask-10ea.png', '/images/products/hilodam-collagen-sheet-mask-10ea.png'),
        ('/images/products/clio-kill-lash-waterproof-mascara-black.png', '/images/products/veloa-curl-fixer-waterproof-mascara-black.png'),
        ('/images/products/clio-chiffon-blur-lip-tint-rose.png', '/images/products/veloa-soft-matte-lip-tint-rose.png'),
        ('/images/products/clio-coverfit-cushion-foundation-21n.png', '/images/products/veloa-daily-fit-cushion-foundation-21n.png'),
        ('/images/products/aromatica-rosemary-scalp-cooling-shampoo-500ml.png', '/images/products/solhyang-rosemary-scalp-cooling-shampoo-500ml.png'),
        ('/images/products/aromatica-perfume-bodywash-fig-480ml.png', '/images/products/solhyang-perfume-bodywash-fig-480ml.png'),
        ('/images/products/the-saem-sheabutter-handcream-3set.png', '/images/products/saebomdam-sheabutter-handcream-3set.png'),
        ('/images/products/drg-men-all-in-one-fluid-150ml.png', '/images/products/dermaharang-men-all-in-one-fluid-150ml.png')
)
UPDATE product_images pi
SET url = ir.new_url
FROM image_renames ir
WHERE pi.url = ir.old_url;
