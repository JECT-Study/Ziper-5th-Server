CREATE TABLE ziper.image_mirror_jobs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source_url TEXT NOT NULL,
    source_url_hash VARCHAR(64) NOT NULL,
    host VARCHAR(255) NOT NULL,
    shop VARCHAR(100),
    priority SMALLINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    locked_at TIMESTAMP WITH TIME ZONE,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_image_mirror_jobs_card_id FOREIGN KEY (card_id)
        REFERENCES ziper.product_cards (id) ON DELETE CASCADE,
    CONSTRAINT fk_image_mirror_jobs_user_id FOREIGN KEY (user_id)
        REFERENCES ziper.users (id) ON DELETE CASCADE,
    CONSTRAINT ck_image_mirror_jobs_status CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT ck_image_mirror_jobs_attempt_count_non_negative CHECK (attempt_count >= 0)
);

CREATE INDEX idx_image_mirror_jobs_pending
    ON ziper.image_mirror_jobs (priority DESC, next_attempt_at ASC, id ASC)
    WHERE status = 'PENDING';
CREATE INDEX idx_image_mirror_jobs_card_id
    ON ziper.image_mirror_jobs (card_id);
CREATE INDEX idx_image_mirror_jobs_url_hash
    ON ziper.image_mirror_jobs (source_url_hash, status);
