-- Kakao 개인/비비즈 앱 등 이메일 미제공 OAuth 가입을 허용하되 공백 이메일 및 무식별자 행을 방지한다.
ALTER TABLE ziper.users
    ALTER COLUMN email DROP NOT NULL,
    ADD CONSTRAINT ck_users_email_not_blank
        CHECK (email IS NULL OR btrim(email) <> ''),
    ADD CONSTRAINT ck_users_login_identity
        CHECK (email IS NOT NULL OR oauth_connect IS NOT NULL);
