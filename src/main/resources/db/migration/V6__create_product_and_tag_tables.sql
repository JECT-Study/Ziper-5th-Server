-- 1. 활성 보드 이름 유저별 중복 방지 (RQ-BOR-D2)
CREATE UNIQUE INDEX uq_product_boards_active_user_name
    ON ziper.product_boards (user_id, name)
    WHERE is_deleted = FALSE;

-- 2. 이미지 파일 메타데이터 테이블 (files)
CREATE TABLE ziper.files (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_files_size_non_negative CHECK (file_size >= 0)
);

-- 3. 해시태그 마스터 테이블 (tags) 및 기본 계절 태그 시드
CREATE TABLE ziper.tags (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title VARCHAR(64) NOT NULL UNIQUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tags_title_not_blank CHECK (btrim(title) <> '')
);

INSERT INTO ziper.tags (title, is_default)
VALUES ('봄', TRUE),
       ('여름', TRUE),
       ('가을', TRUE),
       ('겨울', TRUE);

-- 4. 상품 카드 테이블 (product_cards)
CREATE TABLE ziper.product_cards (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_url VARCHAR(2048) NOT NULL,
    product_title VARCHAR(255) NOT NULL,
    price BIGINT,
    memo TEXT,
    image_url TEXT,
    image_file_id BIGINT,
    fallback_icon VARCHAR(64),
    status VARCHAR(64) NOT NULL DEFAULT 'PLANNED',
    is_link_valid BOOLEAN NOT NULL DEFAULT TRUE,
    rediscovery_stage INTEGER NOT NULL DEFAULT 0,
    last_interacted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_rediscovery_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '14 days'),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_cards_user_url UNIQUE (user_id, product_url),
    CONSTRAINT fk_product_cards_user_id FOREIGN KEY (user_id)
        REFERENCES ziper.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_cards_image_file_id FOREIGN KEY (image_file_id)
        REFERENCES ziper.files (id) ON DELETE SET NULL,
    CONSTRAINT ck_product_cards_url_not_blank CHECK (btrim(product_url) <> ''),
    CONSTRAINT ck_product_cards_title_not_blank CHECK (btrim(product_title) <> ''),
    CONSTRAINT ck_product_cards_price_non_negative CHECK (price IS NULL OR price >= 0),
    CONSTRAINT ck_product_cards_status CHECK (status IN ('PLANNED', 'PURCHASED')),
    CONSTRAINT ck_product_cards_fallback_icon CHECK (
        fallback_icon IS NULL OR fallback_icon IN ('TOP', 'PANTS', 'SKIRT', 'ACCESSORY', 'BEAUTY')
    ),
    CONSTRAINT ck_product_cards_rediscovery_stage CHECK (rediscovery_stage BETWEEN 0 AND 2),
    CONSTRAINT ck_product_cards_deleted_consistency CHECK (
        (is_deleted = FALSE AND deleted_at IS NULL) OR (is_deleted = TRUE AND deleted_at IS NOT NULL)
    )
);

CREATE INDEX idx_product_cards_user_status_deleted
    ON ziper.product_cards (user_id, status, is_deleted);
CREATE INDEX idx_product_cards_rediscovery_queue
    ON ziper.product_cards (user_id, next_rediscovery_at, id)
    WHERE is_deleted = FALSE AND status = 'PLANNED' AND is_link_valid = TRUE;
CREATE INDEX idx_product_cards_trash_cleanup
    ON ziper.product_cards (deleted_at)
    WHERE is_deleted = TRUE;

-- 5. 보드 - 상품 카드 매핑 테이블 (product_board_card)
CREATE TABLE ziper.product_board_card (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    board_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_board_card UNIQUE (board_id, card_id),
    CONSTRAINT fk_product_board_card_board_id FOREIGN KEY (board_id)
        REFERENCES ziper.product_boards (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_board_card_card_id FOREIGN KEY (card_id)
        REFERENCES ziper.product_cards (id) ON DELETE CASCADE
);

CREATE INDEX idx_product_board_card_card_id ON ziper.product_board_card (card_id);
CREATE INDEX idx_product_board_card_board_created_at ON ziper.product_board_card (board_id, created_at DESC, id DESC);

-- 6. 상품 카드 - 해시태그 매핑 테이블 (product_card_tags)
CREATE TABLE ziper.product_card_tags (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_card_tags UNIQUE (card_id, tag_id),
    CONSTRAINT fk_product_card_tags_card_id FOREIGN KEY (card_id)
        REFERENCES ziper.product_cards (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_card_tags_tag_id FOREIGN KEY (tag_id)
        REFERENCES ziper.tags (id) ON DELETE CASCADE
);

CREATE INDEX idx_product_card_tags_tag_id ON ziper.product_card_tags (tag_id);

-- 7. 사용자 상품 카드 조회·스와이프 이벤트 테이블 (user_card_events)
CREATE TABLE ziper.user_card_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL DEFAULT 'VIEW_DETAIL',
    viewed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_card_events_user_id FOREIGN KEY (user_id)
        REFERENCES ziper.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_card_events_card_id FOREIGN KEY (card_id)
        REFERENCES ziper.product_cards (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_card_events_type CHECK (
        event_type IN (
            'VIEW_DETAIL',
            'OPEN_ORIGINAL_URL',
            'REDISCOVERY_KEEP',
            'REDISCOVERY_PURCHASE',
            'REDISCOVERY_DELETE',
            'REDISCOVERY_UNDO'
        )
    )
);

CREATE INDEX idx_user_card_events_user_viewed_at ON ziper.user_card_events (user_id, viewed_at DESC);
CREATE INDEX idx_user_card_events_card_viewed_at ON ziper.user_card_events (card_id, viewed_at DESC);
