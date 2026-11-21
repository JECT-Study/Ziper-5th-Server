-- 유저별 커스텀 해시태그 소유권 확장 및 부분 유니크 인덱스 전환 (JEC-75)
ALTER TABLE ziper.tags
    ADD COLUMN user_id BIGINT NULL,
    ADD CONSTRAINT fk_tags_user_id FOREIGN KEY (user_id)
        REFERENCES ziper.users (id) ON DELETE CASCADE;

-- 사용자별 동일 제목 태그로 분리해야 하므로 전역 제목 유일성을 먼저 해제한다.
ALTER TABLE ziper.tags
    DROP CONSTRAINT IF EXISTS tags_title_key;

-- V6는 소유자 없는 커스텀 태그를 허용했으므로 소유자 제약 전에 기존 데이터를 이관한다.
-- 1) 커스텀 태그의 소유자를 연결된 카드의 user_id에서 가져온다(여러 사용자면 가장 작은 user_id가 원본을 유지).
UPDATE ziper.tags t
SET user_id = owner.user_id
FROM (
    SELECT pct.tag_id, MIN(pc.user_id) AS user_id
    FROM ziper.product_card_tags pct
    JOIN ziper.product_cards pc ON pc.id = pct.card_id
    GROUP BY pct.tag_id
) owner
WHERE t.id = owner.tag_id
  AND t.is_default = FALSE;

-- 2) 같은 커스텀 태그를 쓰는 나머지 사용자에게 사용자별 태그를 새로 만든다.
INSERT INTO ziper.tags (title, is_default, created_at, user_id)
SELECT DISTINCT t.title, FALSE, t.created_at, pc.user_id
FROM ziper.product_card_tags pct
JOIN ziper.product_cards pc ON pc.id = pct.card_id
JOIN ziper.tags t ON t.id = pct.tag_id
WHERE t.is_default = FALSE
  AND t.user_id <> pc.user_id;

-- 3) 나머지 사용자의 카드 연결을 사용자별 태그로 옮긴다.
UPDATE ziper.product_card_tags pct
SET tag_id = new_tag.id
FROM ziper.product_cards pc, ziper.tags old_tag, ziper.tags new_tag
WHERE pc.id = pct.card_id
  AND old_tag.id = pct.tag_id
  AND old_tag.is_default = FALSE
  AND old_tag.user_id <> pc.user_id
  AND new_tag.is_default = FALSE
  AND new_tag.user_id = pc.user_id
  AND new_tag.title = old_tag.title;

-- 4) 어떤 카드에도 연결되지 않은 커스텀 태그는 소유자를 알 수 없으므로 삭제한다. 기본 태그는 소유자 없이 유지한다.
DELETE FROM ziper.tags
WHERE is_default = FALSE
  AND user_id IS NULL;

ALTER TABLE ziper.tags
    ADD CONSTRAINT ck_tags_default_owner CHECK (
        (is_default = TRUE AND user_id IS NULL)
        OR (is_default = FALSE AND user_id IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_tags_default_title
    ON ziper.tags (title)
    WHERE user_id IS NULL;

CREATE UNIQUE INDEX uq_tags_user_title
    ON ziper.tags (user_id, title)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_tags_user_id
    ON ziper.tags (user_id)
    WHERE user_id IS NOT NULL;
