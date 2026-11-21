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

-- 직접 업로드 파일(외부 image_url 없음)은 NONE, 외부 이미지를 미러링한 파일(image_url과 image_file_id 모두 있음)은 READY
UPDATE ziper.product_cards
SET image_status = 'READY'
WHERE image_file_id IS NOT NULL
  AND image_url IS NOT NULL;

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

-- 4. 이미지 필수 CHECK와 파일 삭제 정책의 충돌 해소
-- V6의 ON DELETE SET NULL은 파일만 이미지로 가진 카드의 image_file_id를 NULL로 만들어
-- ck_product_cards_image_present를 위반한다. 카드가 참조 중인 파일의 삭제는 RESTRICT로 거부하고,
-- 호출자(고아 파일 정리, JEC-10)가 카드 이미지를 먼저 교체·해제한 뒤 파일을 삭제한다.
ALTER TABLE ziper.product_cards
    DROP CONSTRAINT fk_product_cards_image_file_id,
    ADD CONSTRAINT fk_product_cards_image_file_id FOREIGN KEY (image_file_id)
        REFERENCES ziper.files (id) ON DELETE RESTRICT;
