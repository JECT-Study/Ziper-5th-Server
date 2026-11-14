# 0010 OAuth 무이메일 회원을 위한 users.email Nullable 완화

- 상태: 채택 — Flyway V4 마이그레이션 및 Kakao/Apple OAuth 통합 테스트 검증
- 날짜: 2026-11-14

## 배경

Kakao 로그인에서 `account_email` 동의 항목은 비즈 앱 전환 완료 이후에만 수집할 수 있으며, 개인/개발 단계의 일반 Kakao 앱은 사용자 정보 조회(`GET /v2/user/me`) 시 `id`·프로필 정보만 반환하고 `kakao_account.email`을 생략합니다.

기존 `V3__create_user_and_initial_tables.sql`의 `ziper.users.email` 컬럼이 `NOT NULL`로 정의되어 있었고 `AuthService`가 `principal.email() == null`이면 `401 UNAUTHORIZED`를 반환했기 때문에, 유효한 Kakao Access Token을 발급받아도 신규 가입 및 로그인이 차단되었습니다.

## 결정

1. **스키마 완화 (`V4__allow_nullable_user_email.sql`)**: 이미 적용된 `V3`는 Flyway checksum 불변 원칙을 위해 수정하지 않고, 신규 `V4` 마이그레이션으로 `ziper.users.email`의 `NOT NULL` 제약을 해제(`DROP NOT NULL`)합니다.
2. **무결성 보완 제약**:
   - PostgreSQL `UNIQUE` 제약(`NULLS DISTINCT`)은 그대로 유지해 `NULL` 이메일 회원은 복수로 가입할 수 있되, 실제 이메일 값이 있는 경우에는 중복을 계속 차단합니다.
   - `ck_users_email_not_blank`(`email IS NULL OR btrim(email) <> ''`)로 공백 문자열 이메일 저장을 금지합니다.
   - `ck_users_login_identity`(`email IS NOT NULL OR oauth_connect IS NOT NULL`)로 이메일도 OAuth 연결도 없는 고아 회원 행 생성을 DB 차원에서 차단합니다.
3. **도메인 모델 및 인증 서비스**:
   - `User.email` JPA 매핑을 `nullable = true`로 완화합니다.
   - `AuthService.oauthLogin`은 공백·누락 이메일을 `null`로 정규화해 허용하고, `email != null`일 때만 `existsByEmail(email)` 중복 검사를 수행합니다. 재로그인은 `(provider, providerId)` 기반 `oauth_users` 및 `oauth_connect` 매핑으로 식별하므로 이메일 유무와 무관하게 동일 회원으로 로그인됩니다.

## 대안

- **가상/임시 이메일 문자열 생성 (`kakao_{id}@kakao.invalid` 등)**: `users.email` 컬럼의 `NOT NULL`을 유지할 수 있으나, 실제 미인증 주소를 유효 이메일처럼 저장해 향후 이메일 기반 계정 통합·알림 발송 시 혼선과 오인 발송 위험을 만듭니다. 미제공 상태는 도메인상 `NULL`로 정확히 표현하는 편이 안전합니다.
- **비즈 앱 전환 전까지 Kakao 로그인 차단**: 개발·QA·개인 계정 테스트 단계부터 Kakao 로그인을 검증할 수 없어 개발 및 PR 검증 흐름을 막습니다.

## 영향과 한계

- `users.email`이 `NULL`일 수 있으므로, 향후 이메일을 필수로 사용하는 기능(이메일 알림, 이메일 기반 계정 찾기/연동)을 추가할 때는 `email IS NULL` 분기 처리 또는 별도 이메일 등록·인증 절차를 설계해야 합니다.
- 이메일·비밀번호 회원가입 경로에서는 여전히 유효한 이메일이 제공되며, `ck_users_login_identity`에 의해 소셜 연결(`oauth_connect`) 없이 `email IS NULL`로 저장되는 행은 허용하지 않습니다.
