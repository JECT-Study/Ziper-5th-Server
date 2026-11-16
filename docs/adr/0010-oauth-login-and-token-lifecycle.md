# 0010 소셜 OAuth 로그인과 자체 JWT·세션 수명 주기

- 상태: 채택 — Apple JWKS 통합 검증 완료, 실제 운영 Apple 클라이언트 ID 연동은 배포 시 확인
- 날짜: 2026-11-13

## 배경

iOS 네이티브 앱(Expo)과 웹·REST API 클라이언트가 동일한 백엔드 인증 계약을 사용해야 합니다. Apple은 별도 UserInfo API를 제공하지 않고 RS256으로 서명된 ID Token에 사용자 식별자(`sub`)와 이메일을 담습니다. 향후 카카오·구글 등 다른 OAuth 프로바이더도 같은 로그인 진입점으로 확장할 수 있어야 하며, 최초 소셜 인증 이후에는 자체 Access Token과 Refresh Token으로 인가와 세션 갱신을 처리해야 합니다.

## 결정

소셜 로그인 진입점은 `POST /api/v1/auth/oauth/{provider}`로 단일화하고 요청 본문의 `token` 필드로 프로바이더 토큰을 받습니다. `OAuthProviderStrategy` 인터페이스로 프로바이더별 검증 방식을 추상화합니다. Apple 구현체는 Apple JWKS(`https://appleid.apple.com/auth/keys`) 공개키로 ID Token의 RS256 서명, 발급자(`https://appleid.apple.com`), 만료 시각, 허용된 클라이언트 ID 목록(`app.security.apple-client-ids`, iOS Bundle ID 및 웹 Service ID)을 검증해 `(provider, providerId, email)`을 추출합니다.

미가입 소셜 사용자는 첫 로그인 요청에서 자동으로 회원가입 처리합니다. 초기 닉네임은 별도 입력 API 없이 `Ziper`와 5자리 난수(`Ziper00000`~`Ziper99999`)로 중복 없이 생성하고, 기본 상품 보드를 함께 생성한 뒤 `isNewUser=true`를 반환합니다.

자체 토큰은 HS256으로 서명하되 `type` 클레임으로 용도를 구분합니다(`access`, `refresh`). 리소스 서버의 Bearer 인증 디코더는 `type=refresh` 토큰을 거부해 Refresh Token이 일반 API 호출에 오용되지 않도록 합니다. Refresh Token 원본은 저장하지 않고 SHA-256 해시(`token_hash`)만 `ziper.sessions` 테이블에 저장합니다. `POST /api/v1/auth/reissue` 호출 시 기존 세션 해시를 대조한 뒤 새 Access Token과 Refresh Token을 발급하고 세션 해시를 즉시 교체(Refresh Token Rotation)합니다. `POST /api/v1/auth/logout`은 검증된 세션 행을 삭제합니다.

## 대안

- 서버에서 Apple Authorization Code를 받아 `/auth/token`으로 교환하는 단일 방식: Apple Private Key(`.p8`) 관리가 필수이고 iOS 네이티브 SDK가 이미 검증 가능한 `identityToken`을 반환하므로, JWKS 기반 ID Token 검증을 기본으로 채택해 네이티브와 웹을 함께 수용한다.
- Refresh Token을 불투명(Opaque) 문자열로만 발급하고 DB에 만료 컬럼 추가: V3 스키마(`ziper.sessions`)를 변경하지 않고 JWT 자체 `exp`·서명 검증과 DB 해시 대조를 결합해 무결성과 회전 검증을 동시에 달성한다.

## 영향과 한계

외부 OAuth 검증과 JWT 파싱·해싱 예외 변환은 `global.security` 경계에 두어 도메인 서비스의 `BusinessCatch` 및 웹 의존성 금지 규칙을 유지합니다. 이미 탈취된 Refresh Token이 정상 사용자보다 먼저 재발급에 사용되면 기존 세션이 회전되므로, 향후 이상 징후 탐지 시 사용자 전체 세션 폐기 정책을 추가로 검토할 수 있습니다.
