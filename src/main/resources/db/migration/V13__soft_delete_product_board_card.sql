ALTER TABLE ziper.product_board_card
    ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_product_board_card_deleted_consistency CHECK (
        (is_deleted = TRUE AND deleted_at IS NOT NULL)
        OR (is_deleted = FALSE AND deleted_at IS NULL)
    );

CREATE INDEX idx_product_board_card_active_card
    ON ziper.product_board_card (card_id)
    WHERE is_deleted = FALSE;
