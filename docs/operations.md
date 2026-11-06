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

## Java 빌드와 실행 환경

Gradle 컴파일과 테스트의 toolchain은 Java 21입니다. Docker의 `build` 단계는 JDK 21을 사용하고 `runtime-base`는 JRE 25를 사용합니다. `source`와 `release`는 같은 실행 기반을 공유합니다. 컴파일용 JDK를 단독으로 올리면 Java 21 toolchain을 찾지 못하므로 빌드 JDK와 Gradle toolchain은 함께 검토합니다.

CI의 `container-smoke`는 기존 JAR를 사용하는 release 이미지의 DB 중단·복구 검증 후 `source` 이미지도 빌드해 로컬 소스 빌드 경로를 확인합니다.

## Sentry

프로젝트는 [ziper-server](https://joonamin-project.sentry.io/settings/projects/ziper-server/)입니다. Education 조직 slug는 `joonamin-project`, 집어 전용 팀은 `ziper`, 이전 전후 프로젝트 ID는 `4512351718408192`입니다.

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

## 애플리케이션 지표

`APP_MONITORING_METRICS_ENABLED=true`는 Sentry Application Metrics 전송을 켭니다. 기본값은 `false`이며 `SENTRY_ENABLED=true`와 유효한 HTTPS DSN이 필요합니다. `APP_MONITORING_ORIGIN`은 로컬에서 `local`, 홈서버에서 `home`으로 설정합니다. 현재 주기 수집은 Cloud Run을 지원하지 않습니다. 요청 기반 CPU 할당에서는 백그라운드 전송을 보장할 수 없으므로 배포 연동 때 별도로 설계합니다.

요청마다 `ziper.http.requests` counter와 `ziper.http.duration` distribution(밀리초), 5xx는 1이고 나머지는 0인 `ziper.http.failure` distribution을 기록합니다. `avg(ziper.http.failure)`는 수신된 요청의 5xx 비율입니다. 이 값은 트레이스 샘플링과 독립적이지만 SDK 큐 제한, 네트워크 장애, 서버 한도에 따라 유실될 수 있습니다. 과금·감사 집계로 사용하지 않습니다. 상태 점검, Actuator, Swagger 경로는 제외합니다. 경로는 Spring의 매칭 패턴이며 매칭되지 않은 요청은 `unmatched`로 묶습니다. 401과 업무 4xx도 지표에는 포함하고 예상한 4xx 오류 이벤트는 기존처럼 전송하지 않습니다.

자원 수집 간격은 60초입니다. 힙 사용량·최대량·비율, 프로세스 CPU, 살아 있는 스레드, GC 횟수·중단 시간, Hikari 활성·유휴·최대 연결·대기·타임아웃을 수집합니다. GC와 타임아웃은 첫 관측을 기준으로 이후 증가량만 전송하며, 카운터 초기화 시 기준을 다시 잡습니다. 누락되거나 유효하지 않은 값은 보내지 않습니다. 수집기의 메모리에는 이 일시적인 계측 기준만 보관합니다.

속성은 `origin`, 임의 실행 식별자 `instance`, HTTP `method`, `route`, `status`, DB `pool`과 SDK의 환경·릴리스·버전 정보로 제한합니다. 수집은 SDK의 비동기 큐를 사용하며 별도의 전송 재시도나 readiness 의존성을 추가하지 않습니다. 기존 오류 이벤트 필터와 별개로 메트릭 전송 필터가 사용자 정보 등 나머지 속성을 제거합니다. `sentry.metrics.enabled`는 SDK 8.59.0부터 직접 호출을 차단하지 않으므로 앱의 활성화 설정과 전송 필터를 사용합니다. [SDK 문서](https://docs.sentry.io/platforms/java/guides/spring-boot/metrics/)

### 외부 수신 검증

일반 검사에서는 외부 전송 테스트를 건너뜁니다. `SENTRY_DSN`을 비밀 환경 변수로 주입한 세션에서 다음 명령을 실행합니다. 테스트는 실제 PostgreSQL 17.6과 임시 HTTP 서버를 사용하며 테스트 전용 경로는 배포 JAR에 포함되지 않습니다.

```sh
SENTRY_LIVE_VERIFICATION=true ./gradlew test --tests '*SentryLiveVerificationTest' --rerun-tasks
```

환경은 `local-monitoring`으로 고정하고 트레이스를 100% 수집합니다. `SENTRY_RELEASE`에 검증 빌드 식별자를 지정합니다. 명령 성공은 HTTP 실행과 SDK flush 완료만 뜻합니다. Sentry 화면에서 오류 한 건, 요청 지표와 자원 지표, 트레이스를 별도로 확인해야 외부 수신 검증이 끝납니다.

테스트의 `Sentry verification run=<UUID>` 출력은 해당 실행의 오류 태그 `verificationRun`과 같습니다. 테스트 전용 fingerprint가 실행마다 새 이슈를 만들므로 새 오류 알림을 재검증할 수 있습니다. 이 콜백은 운영 JAR에 포함되지 않으며 기존 개인정보 필터를 먼저 적용합니다.

[Ziper Server 대시보드](https://joonamin-project.sentry.io/dashboard/10650540/)의 재현 정의는 `config/monitoring/sentry-dashboard.json`입니다. 환경은 대시보드의 Envs 필터, 실행 인스턴스는 `instance`로 구분합니다. 정의에는 비밀 값이 없으며 조직이나 프로젝트를 옮기면 프로젝트 ID를 함께 변경합니다.

### Discord와 운영 알림

대상은 [서버 알림 채널](https://discord.com/channels/1537397802366144524/1566809692779323402)입니다. Sentry 조직의 Integrations에서 Discord를 설치한 뒤 채널 ID `1566809692779323402`를 지정합니다. Sentry 조직 관리자와 Discord 서버 관리 권한이 필요합니다. 실제 메시지 수신을 확인한 뒤 연결 완료로 기록합니다. [공식 설치 안내](https://docs.sentry.io/integrations/notification-incidents/discord/)

2026-11-03 현재 계정에는 대상 Discord 서버의 관리 권한이 없어 설치하지 못했습니다. 기존 Discord 연결은 다른 서버이므로 대상 채널에 사용할 수 없습니다. Discord 서버 관리자에게 설치 작업 동안 현재 사용자에게 **서버 관리(Manage Server)** 권한을 부여하도록 요청합니다. 권한을 받은 사용자가 [Discord 연동 설정](https://joonamin-project.sentry.io/settings/integrations/discord/)에서 **Add Installation**을 누르고 서버 `1537397802366144524`를 선택해 승인합니다. 봇에 대상 채널을 보고 메시지를 보낼 권한을 준 뒤 임시 사용자 권한을 회수할 수 있습니다. 이 절차를 위해 다른 사람을 Sentry 조직 Owner로 초대하지 않습니다.

설치 후 `ziper-server`만 선택한 알림에 Discord 서버와 채널을 지정합니다. 테스트 알림과 새 오류에 의한 실제 메시지를 각각 확인하고 검증용 규칙을 제거합니다. 현재 운영 알림과 검증용 Discord 알림은 생성하지 않았습니다.

2026-11-02 집어 프로젝트를 Developer 구독의 `ject-5th-team6`에서 기존 Education 조직으로 이전했습니다. 교육 혜택은 계정 연결 표시가 아니라 대상 조직의 구독 화면에서 확인합니다. 조직의 만료일, 메트릭·Uptime 한도와 추가 과금 제한을 함께 확인합니다.

운영 활성화 시 환경 필터는 `prod`로 제한합니다. 새 오류·재발 오류의 동일 이슈 재알림은 30분 간격으로 제한합니다. 자원 경고 초깃값은 인스턴스별 힙 비율 0.85 초과 5분 지속, DB 풀 대기 연결 0 초과 5분 지속입니다. 요청량·5xx 비율·p50/p95 지연·GC·CPU·DB 풀은 대시보드에서 먼저 관측합니다. 운영값은 첫 배포 후 실제 부하에 맞춰 조정합니다. 로컬 검증용 알림 규칙은 수신 확인 후 제거합니다.

첫 배포 때 Uptime 모니터 하나를 최종 API 주소의 `/readyz`에 연결합니다. 간격 1분, 제한 시간 10초, 연속 실패 3회, 연속 성공 1회를 초깃값으로 사용합니다. 공개 URL과 장애 전환, Discord 장애·복구 수신은 배포 후 검증 대상입니다.

### 프로젝트별 접근

[ADR 0005](adr/0005-monitoring-project-access.md)에 따라 조직의 Open Membership은 끄고 기본 역할은 Member로 유지합니다. 기존 tocktock의 `client`와 `server`는 `fakedev` 팀, 집어의 `ziper-server`는 `ziper` 팀에만 연결합니다. 초대할 때 조직 역할 Member와 담당 팀만 명시합니다. 집어 담당자에게 `fakedev` 팀이나 조직 전체 접근이 가능한 Owner·Manager 역할을 부여하지 않습니다.

이 설정은 프로젝트 데이터 접근을 분리하며 이슈 조작까지 금지하는 읽기 전용 설정은 아닙니다. 현재 추가 초대 명단은 전달되지 않았습니다. 실제 초대 구성원이 로그인했을 때 담당 프로젝트만 접근할 수 있는지도 별도로 확인합니다.

## 첫 홈서버 운영 배포

[ADR 0006](adr/0006-initial-production-deployment.md)의 첫 배포는 Windows 홈서버의 Ubuntu WSL에서 수행합니다. `/opt/ziper/private`에 런타임 환경 파일, DB 루트 CA와 전용 Tunnel 토큰을 둡니다. 실제 비밀 값은 저장소에 넣지 않습니다. 공개 주소는 `https://api-ziper.joonamin.dev`이며 별도 `ziper-production` Compose 프로젝트만 관리합니다.

작업자가 검증한 release 이미지를 전달하고 `ziper-server:<전체 SHA>`로 로드합니다. `/opt/ziper/releases/<전체 SHA>/`에는 `compose.yml`, `deploy.sh`와 검사한 이미지 ID 파일 `image-id`를 둡니다. 스크립트가 이미지 ID를 다시 대조하며 JAR를 다시 빌드하지 않습니다.

```sh
bash /opt/ziper/releases/<전체-SHA>/deploy.sh <전체-SHA>
python3 scripts/verify-public-health.py --url https://api-ziper.joonamin.dev --output build/reports/public-health.json
```

공개 검사는 10초 간격으로 세 차례 실행하며 두 probe의 직접 200 응답, `{"status":"UP"}`, `Cache-Control: no-store`와 세션 미생성을 요구합니다. HTTP 리다이렉트와 캐시된 응답도 거부합니다. 이 성공은 Sentry Uptime의 외부 점검 성공을 대신하지 않습니다. Uptime에는 같은 호스트의 `/readyz`를 등록하고 실제 체크·장애·복구를 별도로 확인합니다.

원격 SSH 세션에서는 Docker Desktop의 대화형 credential store를 사용하지 않습니다. 배포 스크립트가 전용 `DOCKER_CONFIG=/opt/ziper/docker-client`를 사용하며 그 안의 `config.json`은 `{"auths":{}}`입니다. 기존 Docker 클라이언트 설정과 다른 프로젝트의 로그인은 변경하지 않습니다.

## 운영 E2E 응답 식별

기존 probe 응답(`/livez`, `/readyz`, Actuator probe)에 `X-Ziper-Origin`과 `X-Ziper-Release`를 추가합니다. origin은 `home|cloudrun|local`만, release는 소문자 40자리 소스 SHA 또는 로컬 표식만 출력합니다. 다른 값은 헤더에 넣지 않습니다. 새 업무 API·비밀 값·인스턴스 주소는 노출하지 않습니다.

예약 전환·배포·E2E 정책과 실제 운영 증거는 [Infra 저장소](https://github.com/JECT-Study/Ziper-5th-Infra/pull/1)에서 관리합니다. 헤더 존재만으로 동일 이미지 배포를 증명하지 않으며 배포 manifest·이미지 digest·제공업체 revision과 대조합니다. 실제 운영 검증은 아직 수행하지 않았습니다.

2026-11-06 운영 Actuator는 Accept 미지정 시 `application/vnd.spring-boot.actuator.v3+json`을 반환했습니다. 공개 배포 검사는 `Accept: application/json`을 명시하고 `application/json`만 허용합니다. 협상을 무시한 vendor JSON과 HTML은 실패합니다. 기존 User-Agent, 200, 직접 응답, no-store, 세션 쿠키 미생성, 캐시 HIT·STALE·UPDATING 거부, 본문 4096바이트 한도와 정확한 UP 상태 검사를 유지합니다.

`verify-public-health.py`의 기본 모드는 기존처럼 첫 오류에서 실패하며 `--checks`는 정상 검사 쌍의 횟수입니다. `--startup-timeout`을 양수로 지정한 경우만 제한된 준비 시간 동안 재시도합니다. Infra workflow는 `--checks 3 --interval 10 --timeout 10 --startup-timeout 180 --retry-interval 5`를 전달합니다. `/livez`와 `/readyz`가 모두 통과한 검사 쌍 3회가 연속으로 필요합니다. 일시적 실패는 연속 횟수와 성공 표본을 초기화합니다.

재시도 대상은 HTTP 502·503·504·520·521·522·523·524·530, 연결 거부·중단·재설정·도달 불가·시간 초과, 일시적 DNS 오류 EAI_AGAIN입니다. 401·403을 포함한 나머지 HTTP 상태, TLS 검증 오류, 영구 DNS 오류, 리다이렉트와 200 응답의 계약 위반은 즉시 실패합니다. 응답 본문에서 Cloudflare 원인을 추정하거나 오류 코드를 추출하지 않습니다.

요청 하나는 POSIX runner의 타이머로 DNS·TLS·헤더·본문 읽기를 합쳐 최대 10초로 제한하며 남은 전체 시간보다 짧게 조정합니다. 실패 후 5초, 정상 검사 쌍 뒤 10초를 기다리되 모두 180초에 포함합니다. 한도에 도달하면 정상 횟수와 관계없이 실패하고 배포 명령은 재실행하지 않습니다. CI의 Linux와 로컬 macOS가 대상이며 POSIX 타이머가 없는 환경에서는 차단합니다.

`--output` JSON과 stdout에는 경로별 시도 번호, HTTP 상태 또는 미수신 시 null, 성공·실패, 재시도 여부, 정적 오류 이유, 경과 시간과 요청 제한을 기록합니다. 최종 종료 이유와 연속 정상 횟수도 구분합니다. 비밀·전체 응답 본문·원본 네트워크 예외는 출력하지 않습니다. 전체 한도는 검사 시작부터 측정하며 CLI 인자 처리와 결과 파일 저장 시간은 제외합니다.

Linux Infra workflow는 검사 명령 전체를 `timeout --signal=KILL 180s`로 제한합니다. Python의 신호 처리가 지연되는 네이티브 호출에도 상한을 적용하기 위한 외부 제한입니다. JSON은 요청 전후 `.next` 파일을 원자적으로 교체해 갱신합니다. 강제 종료 시 마지막 요청이 `requesting`, 최종 이유가 `running`인 기록을 남길 수 있으며 Actions의 비정상 종료와 함께 실패로 해석합니다.
