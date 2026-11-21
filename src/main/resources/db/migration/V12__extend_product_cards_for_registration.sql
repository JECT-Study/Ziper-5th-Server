-- 1. 상품 등록용 메타데이터·이미지 상태 컬럼 확장 (JEC-54)
ALTER TABLE ziper.product_cards
    ADD COLUMN shop VARCHAR(100),
    ADD COLUMN shared_url VARCHAR(2048),
    ADD COLUMN metadata_source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN image_status VARCHAR(32) NOT NULL DEFAULT 'NONE';

-- 2. 기존 데이터 백필 (가격 NOT NULL 및 카드 이미지 필수 불변식 충족)
UPDATE ziper.product_cards
SET price = 0
WHERE price IS NULL;

UPDATE ziper.product_cards
SET fallback_icon = 'TOP'
WHERE image_url IS NULL
  AND image_file_id IS NULL
  AND fallback_icon IS NULL;

UPDATE ziper.product_cards
SET image_status = 'READY'
WHERE image_file_id IS NOT NULL;

-- 3. 가격·이미지·메타데이터 출처·이미지 상태 제약 조건 강화
ALTER TABLE ziper.product_cards
    ALTER COLUMN price SET NOT NULL,
    DROP CONSTRAINT ck_product_cards_price_non_negative,
    ADD CONSTRAINT ck_product_cards_price_non_negative CHECK (price >= 0),
    ADD CONSTRAINT ck_product_cards_image_present CHECK (
        image_url IS NOT NULL OR image_file_id IS NOT NULL OR fallback_icon IS NOT NULL
    ),
    ADD CONSTRAINT ck_product_cards_metadata_source CHECK (
        metadata_source IN ('CLIENT', 'MANUAL')
    ),
    ADD CONSTRAINT ck_product_cards_image_status CHECK (
        image_status IN ('NONE', 'PENDING', 'READY', 'FAILED')
    );
