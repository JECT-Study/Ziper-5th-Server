-- 유저별 커스텀 해시태그 소유권 확장 및 부분 유니크 인덱스 전환 (JEC-75)
ALTER TABLE ziper.tags
    ADD COLUMN user_id BIGINT NULL,
    ADD CONSTRAINT fk_tags_user_id FOREIGN KEY (user_id)
        REFERENCES ziper.users (id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_tags_default_owner CHECK (
        (is_default = TRUE AND user_id IS NULL)
        OR (is_default = FALSE AND user_id IS NOT NULL)
    );

ALTER TABLE ziper.tags
    DROP CONSTRAINT IF EXISTS tags_title_key;

CREATE UNIQUE INDEX uq_tags_default_title
    ON ziper.tags (title)
    WHERE user_id IS NULL;

CREATE UNIQUE INDEX uq_tags_user_title
    ON ziper.tags (user_id, title)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_tags_user_id
    ON ziper.tags (user_id)
    WHERE user_id IS NOT NULL;
