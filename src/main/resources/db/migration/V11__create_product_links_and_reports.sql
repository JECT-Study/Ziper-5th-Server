-- 1. 공유 상품 링크 마스터 테이블 (product_links)
CREATE TABLE ziper.product_links (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    canonical_url VARCHAR(2048) NOT NULL UNIQUE,
    shop VARCHAR(100),
    status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    checked_at TIMESTAMP WITH TIME ZONE,
    check_source VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_product_links_url_not_blank CHECK (btrim(canonical_url) <> ''),
    CONSTRAINT ck_product_links_status CHECK (status IN ('AVAILABLE', 'SOLD_OUT', 'REMOVED', 'UNKNOWN')),
    CONSTRAINT ck_product_links_check_source CHECK (
        check_source IS NULL OR check_source IN ('SERVER', 'CLIENT')
    )
);

-- 2. 클라이언트 상품 링크 상태 보고 이력 테이블 (product_link_reports)
CREATE TABLE ziper.product_link_reports (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    link_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_link_reports_link_id FOREIGN KEY (link_id)
        REFERENCES ziper.product_links (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_link_reports_user_id FOREIGN KEY (user_id)
        REFERENCES ziper.users (id) ON DELETE CASCADE,
    CONSTRAINT ck_product_link_reports_status CHECK (status IN ('AVAILABLE', 'SOLD_OUT', 'REMOVED'))
);

CREATE INDEX idx_product_link_reports_consensus
    ON ziper.product_link_reports (link_id, status, created_at DESC);
CREATE INDEX idx_product_link_reports_user_rate
    ON ziper.product_link_reports (user_id, link_id, created_at DESC);

-- 3. 상품 카드 링크 상태 컬럼 확장 및 기존 데이터 백필
ALTER TABLE ziper.product_cards
    ADD COLUMN link_id BIGINT,
    ADD COLUMN link_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN link_checked_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN link_check_source VARCHAR(32),
    ADD CONSTRAINT fk_product_cards_link_id FOREIGN KEY (link_id)
        REFERENCES ziper.product_links (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_product_cards_link_status CHECK (
        link_status IN ('AVAILABLE', 'SOLD_OUT', 'REMOVED', 'UNKNOWN')
    ),
    ADD CONSTRAINT ck_product_cards_link_check_source CHECK (
        link_check_source IS NULL OR link_check_source IN ('SERVER', 'CLIENT')
    );

INSERT INTO ziper.product_links (canonical_url, status)
SELECT DISTINCT product_url, 'UNKNOWN'
FROM ziper.product_cards
ON CONFLICT (canonical_url) DO NOTHING;

UPDATE ziper.product_cards pc
SET link_id = pl.id,
    link_status = CASE WHEN pc.is_link_valid THEN 'UNKNOWN' ELSE 'REMOVED' END
FROM ziper.product_links pl
WHERE pl.canonical_url = pc.product_url;

DROP INDEX ziper.idx_product_cards_rediscovery_queue;

ALTER TABLE ziper.product_cards
    DROP COLUMN is_link_valid;

CREATE INDEX idx_product_cards_rediscovery_queue
    ON ziper.product_cards (user_id, next_rediscovery_at, id)
    WHERE is_deleted = FALSE AND status = 'PLANNED' AND link_status NOT IN ('REMOVED', 'SOLD_OUT');

CREATE INDEX idx_product_cards_link_id
    ON ziper.product_cards (link_id)
    WHERE link_id IS NOT NULL;
