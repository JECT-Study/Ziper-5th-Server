-- 이메일별 인증번호 오입력 횟수와 30분 잠금 상태. 인증 흐름(flow_id)과 무관하게 이메일 단위로 합산한다.
CREATE TABLE ziper.email_verification_failures (
    email VARCHAR(255) PRIMARY KEY,
    failed_attempts INTEGER NOT NULL,
    last_failed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_until TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_email_verification_failures_attempts CHECK (failed_attempts BETWEEN 1 AND 5),
    CONSTRAINT ck_email_verification_failures_lock CHECK (locked_until IS NULL OR failed_attempts = 5)
);
