-- 1. 상품 보드 생성 시각 컬럼 추가 (RQ-BOR-D2)
ALTER TABLE ziper.product_boards
    ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- 2. 사용자 활성 보드 최신순 조회 인덱스 (RQ-BOR-D1)
CREATE INDEX idx_product_boards_user_created_at
    ON ziper.product_boards (user_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;
