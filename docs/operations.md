# 보안과 관측 설정

5xx 예외는 Sentry 활성화 여부와 관계없이 서버 오류 로그에 요청 ID와 스택 트레이스를 기록합니다. 응답은 정적인 오류 정보만 제공합니다. 예외 메시지에 비밀 값을 넣지 않으며 JWT 설정의 문자열 표현은 비밀 키를 마스킹합니다.

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

향후 장애 전환과 비밀 관리는 [인프라 계획](infrastructure.md)을 따릅니다. Worker나 외부 알림 배포는 없으며 probe 간격·임계치·회복 조건은 미정입니다.

## Sentry

프로젝트는 [JECT-5th-TEAM6-API-SERVER](https://ject-5th-team6.sentry.io/settings/projects/ject-5th-team6-api-server/)입니다. 조직 표시명은 `JECT-5th-TEAM6`, 조직·팀 slug는 `ject-5th-team6`, 프로젝트 slug는 `ject-5th-team6-api-server`입니다.

SDK 8.62.0과 Gradle 플러그인 6.25.0을 사용합니다. HTTP 오류와 트레이스를 수집하며, `SENTRY_ENABLED=true`와 유효한 `SENTRY_DSN`을 모두 설정해야 활성화됩니다. 로컬 기본값은 비활성입니다. 트레이스 샘플링 기본값은 `0.1`입니다. 배포 시 `SENTRY_ENVIRONMENT`를 환경 이름으로, `SENTRY_RELEASE`를 빌드한 Git SHA로 설정합니다.

기본 개인정보 전송, 요청 본문, 쿠키, 헤더와 쿼리 등 요청 메타데이터 수집을 제한합니다. 상태 점검 요청은 트레이스 수집에서 제외합니다. 업무 코드에서도 토큰, 개인정보와 비밀번호를 예외 메시지나 태그에 넣지 않습니다. 실제 이벤트와 소스 문맥 수신은 [초기화 검증 기록](verification.md)에서 확인할 수 있습니다.

### 소스 문맥 업로드

`bootJar`는 업로드 여부와 관계없이 소스 번들을 생성합니다. 업로드에는 `SENTRY_UPLOAD=true`와 `SENTRY_AUTH_TOKEN`이 모두 필요합니다. 토큰은 GitHub Actions secret에 저장하고 로컬 파일이나 로그에 남기지 않습니다.

수동 워크플로 `Upload Sentry source context`는 `develop`과 `main`에서만 실행됩니다. GitHub 환경 이름은 `sentry-source-upload`입니다. 결과 아티팩트 `ziper-server-<SHA>`에는 JAR, 소스 번들, `SENTRY_RELEASE`와 검증한 `image.tar.gz`가 함께 들어갑니다.

배포할 때 이 아티팩트의 `ziper-server.jar`를 그대로 사용합니다. 소스를 다시 컴파일하면 번들 ID가 달라져 업로드한 소스와 스택 트레이스 연결이 깨질 수 있습니다. 이 워크플로는 소스 업로드만 수행하며 배포하지 않습니다.

## 공개 오류와 로컬 API 문서

공개 오류는 `ProblemDetail`의 `status`, `detail`, `code`, `requestId`를 사용합니다. 서버가 생성한 요청 ID를 `X-Request-Id`로 반환하고 Sentry 이벤트와 연결합니다. JWT 401·403도 같은 팩토리를 사용합니다. 예상한 업무 4xx는 Sentry에 보내지 않고 예상하지 못한 5xx만 요청당 한 번 수집합니다. 원본 예외 메시지를 공개 응답에 넣지 않습니다. 입력 검증 실패도 정적인 400 안내만 반환하며 필드 오류 배열, 바인딩 경로와 거부한 값을 노출하지 않습니다. 클라이언트가 보낸 요청 ID는 신뢰하지 않고 서버에서 새 UUID를 생성합니다. 로그 MDC의 `requestId`를 요청 종료 시 복원·제거합니다.

Swagger는 기본값과 서버 환경에서 꺼집니다. 활성 프로필이 `local` 하나뿐이고 `APP_RUNTIME_MODE=local`, `SWAGGER_ENABLED=true`를 함께 명시한 경우만 사용합니다. 로컬에서도 기본값은 꺼짐이며 업무 API가 구현됐다는 의미는 아닙니다. `PORT` 기본값은 8080입니다. 런타임 가드는 서버 모드나 혼합 프로필에서 API 문서 활성화를 거부합니다. 로컬 Compose는 프로필과 실행 모드를 이미 명시하므로 다음과 같이 선택합니다.

```sh
SWAGGER_ENABLED=true docker compose up --build -d app
```

브라우저에서 `http://localhost:8080/swagger-ui.html`을 열고 JSON은 `/v3/api-docs`, YAML은 `/v3/api-docs.yaml`에서 확인합니다. JSON과 UI가 모두 활성화된 유효한 로컬 환경에서만 문서 GET·HEAD를 인증 없이 허용하며 업무 API의 JWT 인증은 유지합니다. 생성 범위는 `domain` 아래의 `controller` 패키지로 제한하고 현재 업무 경로는 비어 있습니다. Bearer JWT 보안 스키마를 제공하며 서버 환경에 문서를 공개하지 않습니다.

배포 시 업로드된 JAR를 그대로 이미지에 복사하는 경로를 사용하고 재컴파일하지 않습니다. 일반 소스 빌드 이미지는 로컬 개발용이며 업로드한 소스 번들과 일치하는 배포물로 간주하지 않습니다. 다음 명령은 기존 JAR를 재컴파일하지 않고 패키징한 뒤 이미지 내부 JAR의 SHA256과 현재 JAR가 요구하는 Sentry debug ID에 대응하는 소스 번들을 대조합니다. 증분 빌드 디렉터리의 무관한 과거 번들은 무시하지만 현재 ID 누락·중복과 이미지 JAR 변조는 실패합니다.

```sh
docker build --target release --tag ziper-release:local .
python3 scripts/verify-release-artifact.py --image ziper-release:local
```

입력은 `build/libs/ziper-server.jar`와 `build/intermediates/sentry/java/source-bundle/*.zip`입니다. `--target source`는 로컬 소스 빌드 경로이며 기본 Docker target도 `source`입니다. CI의 `test`가 `tested-release`를 업로드하고 `container-smoke`가 내려받아 `ZIPER_PREBUILT_ARTIFACT=true`로 같은 JAR를 검사합니다. 로컬 smoke는 이 변수가 없으면 JAR를 한 번 빌드합니다. 수동 Sentry 워크플로는 한 번 빌드·업로드한 JAR를 release 이미지에 넣고 검증한 이미지를 보관하며 배포는 하지 않습니다.
