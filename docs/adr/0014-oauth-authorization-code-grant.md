# 0014 Spring Security RestClient 기반 무상태 OAuth Authorization Code 교환

- 상태: 채택
- 날짜: 2026-11-16
- 관계: [ADR 0010](0010-oauth-login-and-token-lifecycle.md)의 토큰 검증 계약과 결합해 인가 코드 교환 경로를 추가한다.

## 배경

[ADR 0010](0010-oauth-login-and-token-lifecycle.md)은 모바일 네이티브 SDK와 웹 클라이언트가 이미 확보한 소셜 토큰(`id_token` 또는 `access_token`)을 전달하는 `POST /api/v1/auth/oauth/{provider}`를 정의했습니다. 그러나 Kakao JavaScript SDK v2(`Kakao.Auth.authorize`)와 표준 OAuth 2.0 Authorization Code 흐름(Naver·Apple 웹 로그인 포함)은 브라우저에 인가 코드(`code`)만 내려주며, `client_secret`이나 Apple `.p8` 개인키를 브라우저에 노출하지 않고 토큰으로 교환하려면 백엔드의 서버 측 코드 교환 경로가 필요합니다.

## 결정

기존 토큰 직접 전달 엔드포인트 `POST /api/v1/auth/oauth/{provider}`는 하위 호환을 위해 유지하고, Authorization Code 교환 전용 엔드포인트 `POST /api/v1/auth/oauth/{provider}/code`를 추가합니다.

- **무상태(Stateless) RestClient 교환기**: `spring-boot-starter-oauth2-client`의 `RestClientAuthorizationCodeTokenResponseClient`를 `global.security.oauth.OAuthAuthorizationCodeExchanger` 내부에서 사용합니다. `http.oauth2Login()` 필터는 활성화하지 않아 `HttpSession`과 서버 세션을 생성하지 않으며 기존 아키텍처의 세션 금지 규칙을 유지합니다.
- **Sentry 트레이스 계측 유지**: Spring이 관리하는 `RestClient.Builder`를 복제해 `FormHttpMessageConverter`, 커스텀 `OAuth2AccessTokenResponseHttpMessageConverter`, `OAuth2ErrorResponseErrorHandler`를 구성하므로 외부 `/oauth/token` 호출에도 Sentry 트레이스 전파가 유지됩니다.
- **프로바이더별 공통 파이프라인**:
  - **Apple**: `AppleClientSecretGenerator`가 PKCS#8 EC P-256 개인키(`APPLE_PRIVATE_KEY`)로 단기(5분) ES256 `client_secret` JWT를 동적으로 서명해 `https://appleid.apple.com/auth/token`에 전달하고, 응답의 `id_token`을 추출해 기존 `AppleOAuthProviderStrategy`의 JWKS 검증을 그대로 통과시킵니다.
  - **Naver**: `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`, 선택 `state`를 전달해 `https://nid.naver.com/oauth2.0/token`에서 `access_token`을 교환하고, `token_type` 누락/소문자 응답과 HTTP 200 오류 JSON(`access_token` 누락)을 방어해 `NaverOAuthProviderStrategy`로 넘깁니다.
  - **Kakao**: `KAKAO_CLIENT_ID`, 선택 `KAKAO_CLIENT_SECRET`, `redirect_uri`를 전달해 `https://kauth.kakao.com/oauth/token`에서 `access_token`을 교환하고 `KakaoOAuthProviderStrategy`로 넘깁니다.
- **비밀 보호**: `SecurityProperties.toString()`에서 `applePrivateKey`, `naverClientSecret`, `kakaoClientSecret`을 `[REDACTED]`로 마스킹하고, 운영 값은 Secret Manager 고정 버전에 저장합니다.

## 대안

- Spring Security 기본 `http.oauth2Login()` 필터 도입: 기본 `AuthorizationRequestRepository`가 `HttpSession`에 의존하고 백엔드 리다이렉트·쿠키 상태 설계가 필요해 무상태 REST API 및 `PRODUCTION_DOES_NOT_USE_SESSIONS` 규칙과 맞지 않으므로 채택하지 않았습니다.
- 구형 `DefaultAuthorizationCodeTokenResponseClient`(`RestTemplate` 기반): Spring Security 6.4부터 Deprecated 되었으므로 최신 `RestClientAuthorizationCodeTokenResponseClient`를 채택했습니다.
- 기존 `POST /api/v1/auth/oauth/{provider}` 제거 후 코드 방식으로 단일화: 이미 연동된 iOS 네이티브 SDK(`identityToken` 직접 전달)가 추가 왕복 없이 동작해야 하므로 두 엔드포인트를 병행합니다.

## 영향과 한계

- `OAuth2AuthorizationException`, `RestClientException`, 개인키 파싱 오류 등 모든 기술 예외는 `global.security.oauth` 경계에서 `BusinessException(ErrorCode.UNAUTHORIZED)`로 변환해 `domain.auth` 서비스·컨트롤러의 `BusinessCatch` 금지 규칙을 유지합니다.
- 운영 환경에서 `POST /api/v1/auth/oauth/{provider}/code`를 호출하려면 Secret Manager의 `ziper-runtime-properties`에 `APPLE_AUTH_CLIENT_ID`, `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY`, `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`이 포함된 버전이 고정되어야 합니다.
