-- 1. 소셜 로그인 연동 테이블 (oauth_users)
CREATE TABLE ziper.oauth_users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider VARCHAR(255) NOT NULL,
    provider_id VARCHAR(255) NOT NULL,
    CONSTRAINT uq_oauth_provider_id UNIQUE (provider, provider_id)
);

-- 2. 회원 기본 테이블 (users)
CREATE TABLE ziper.users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    nickname VARCHAR(10) NOT NULL UNIQUE,
    status VARCHAR(64) NOT NULL DEFAULT 'ACTIVE',
    marketing_agreed BOOLEAN NOT NULL DEFAULT FALSE,
    marketing_agreed_at TIMESTAMP NOT NULL DEFAULT now(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_logged_at TIMESTAMP WITH TIME ZONE,
    oauth_connect BIGINT,
    CONSTRAINT fk_users_oauth_connect FOREIGN KEY (oauth_connect)
        REFERENCES ziper.oauth_users (id) ON DELETE SET NULL
);

CREATE INDEX idx_users_status ON ziper.users (status);

-- 3. 리프레시 토큰 기기별 관리 테이블 (sessions)
CREATE TABLE ziper.sessions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    user_agent VARCHAR(255),
    token_hash VARCHAR(255) NOT NULL,
    CONSTRAINT fk_sessions_user_id FOREIGN KEY (user_id)
        REFERENCES ziper.users (id) ON DELETE CASCADE
);

CREATE INDEX idx_sessions_user_id ON ziper.sessions (user_id);
CREATE INDEX idx_sessions_token_hash ON ziper.sessions (token_hash);

-- 4. 이메일 인증번호 관리 테이블 (auth_codes)
CREATE TABLE ziper.auth_codes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    flow_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    requester_key VARCHAR(64) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    consumed_at TIMESTAMP WITH TIME ZONE,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_auth_codes_failed_attempts CHECK (failed_attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_auth_codes_expiry CHECK (expired_at > requested_at),
    CONSTRAINT ck_auth_codes_consumed CHECK (consumed_at IS NULL OR verified_at IS NOT NULL)
);

CREATE INDEX idx_auth_codes_email_requested_at ON ziper.auth_codes (email, requested_at);
CREATE INDEX idx_auth_codes_requester_requested_at ON ziper.auth_codes (requester_key, requested_at);
CREATE INDEX idx_auth_codes_flow_requested_at ON ziper.auth_codes (flow_id, requested_at DESC, id DESC);

-- 5. 상품 보드 테이블 (product_boards)
CREATE TABLE ziper.product_boards (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    modifiable BOOLEAN NOT NULL DEFAULT TRUE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_product_boards_user_id FOREIGN KEY (user_id)
        REFERENCES ziper.users (id) ON DELETE CASCADE
);

CREATE INDEX idx_product_boards_user_id ON ziper.product_boards (user_id);
