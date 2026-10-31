# 보안과 관측 설정

## JWT 인증

인증은 `Authorization: Bearer <token>` 헤더만 받는 무상태 방식입니다. 쿠키와 서버 세션은 사용하지 않습니다. HS256 키는 Base64로 인코딩한 최소 32바이트 값이며 `JWT_SECRET_BASE64`로 전달합니다. `iss=ziper-server`, `aud=ziper-api`, 비어 있지 않은 `sub`, 유효한 `exp`를 검증합니다. 발급자와 대상은 환경 변수로 변경할 수 있습니다.

CSRF 보호는 이 헤더 인증 전제를 바탕으로 꺼져 있습니다. 향후 인증을 쿠키로 옮기면 CSRF 설정도 다시 설계해야 합니다. CORS 허용 출처는 `CORS_ALLOWED_ORIGINS`에 명시하며 웹·모바일 배포 환경에 맞춰 관리합니다.

소셜 로그인과 자체 JWT 발급 API는 아직 없습니다. Refresh token 저장소는 실제 기능 구현 시 DB로 설계하며 프로세스 메모리를 영속 저장소처럼 사용하지 않습니다.

## 상태 점검

| 경로 | 확인 대상 | 정상 | 비정상 |
| --- | --- | --- | --- |
| `/livez` | 앱 생존 상태, DB 제외 | 200 | 503 |
| `/readyz` | 요청 처리 준비 상태와 DB | 200 | 503 |

응답은 상세 DB 정보 없이 상태만 제공하며 `Cache-Control: no-store`를 반환합니다. DB 장애 중에도 `/livez`가 성공해야 합니다. 다른 Actuator 경로는 공개하지 않습니다. 로컬 Compose는 호스트 포트를 `127.0.0.1`에 바인딩합니다.

향후 Cloudflare Worker에서 외부 점검을 구현한다면 60초 간격, 10초 타임아웃, 연속 3회 실패 후 알림을 시작 기준으로 검토합니다. 현재 Worker나 외부 알림 배포는 없습니다.

## Sentry

프로젝트는 [JECT-5th-TEAM6-API-SERVER](https://ject-5th-team6.sentry.io/settings/projects/ject-5th-team6-api-server/)입니다. 조직 표시명은 `JECT-5th-TEAM6`, 조직·팀 slug는 `ject-5th-team6`, 프로젝트 slug는 `ject-5th-team6-api-server`입니다.

SDK 8.62.0과 Gradle 플러그인 6.25.0을 사용합니다. HTTP 오류와 트레이스를 수집하며, `SENTRY_ENABLED=true`와 유효한 `SENTRY_DSN`을 모두 설정해야 활성화됩니다. 로컬 기본값은 비활성입니다. 트레이스 샘플링 기본값은 `0.1`입니다. 배포 시 `SENTRY_ENVIRONMENT`를 환경 이름으로, `SENTRY_RELEASE`를 빌드한 Git SHA로 설정합니다.

기본 개인정보 전송, 요청 본문, 쿠키, 헤더와 쿼리 등 요청 메타데이터 수집을 제한합니다. 상태 점검 요청은 트레이스 수집에서 제외합니다. 업무 코드에서도 토큰, 개인정보와 비밀번호를 예외 메시지나 태그에 넣지 않습니다. 실제 이벤트 수신 검증 상태는 초기화 검증 기록에서 확정하며, 설정 파일 존재만으로 수신 성공을 판단하지 않습니다.

### 소스 문맥 업로드

`bootJar`는 업로드 여부와 관계없이 소스 번들을 생성합니다. 업로드에는 `SENTRY_UPLOAD=true`와 `SENTRY_AUTH_TOKEN`이 모두 필요합니다. 토큰은 GitHub Actions secret에 저장하고 로컬 파일이나 로그에 남기지 않습니다.

수동 워크플로 `Upload Sentry source context`는 `develop`과 `main`에서만 실행됩니다. GitHub 환경 이름은 `sentry-source-upload`입니다. 결과 아티팩트 `ziper-server-<SHA>`에는 JAR, 소스 번들, `SENTRY_RELEASE` 파일이 함께 들어갑니다.

배포할 때 이 아티팩트의 `ziper-server.jar`를 그대로 사용합니다. 소스를 다시 컴파일하면 번들 ID가 달라져 업로드한 소스와 스택 트레이스 연결이 깨질 수 있습니다. 이 워크플로는 소스 업로드만 수행하며 배포하지 않습니다.
