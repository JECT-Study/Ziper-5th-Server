# 초기화 검증 기록

아래는 기존 초기화 단계의 2026-11-01 검증 기록입니다. 후속 변경의 재실행 결과나 현재 전체 코드의 검증 증거가 아닙니다. 당시 로컬 환경에서 Java 21과 실제 PostgreSQL 17.6을 사용했습니다.

| 검사 | 확인 내용 |
| --- | --- |
| JUnit 15개 | JWT 서명·필수 claim·만료·CORS·세션 미생성, 안전한 오류 응답, Sentry 중복 방지·개인정보 필터 |
| PostgreSQL | 두 번의 앱 시작과 Flyway 재실행, 런타임 CRUD 허용과 DDL 거부 |
| 실제 HTTP | 상태만 반환하는 probe, DB 장애 중 readiness 503·liveness 200, 복구 후 readiness 200 |
| Docker Compose smoke | 격리된 프로젝트에서 이미지 빌드·기동·DB 중단·복구·정리 |
| 소스 업로드 | `bootJar`의 업로드 옵션으로 일치하는 JVM 소스 번들 업로드 |
| CodeRabbit 설정 | 공식 JSON schema에 대한 구성 검증 |

Sentry에는 실제 앱의 HTTP 요청으로 `IllegalStateException: Ziper Sentry setup verification 2026-11-01`을 발생시켰습니다. [확인된 이슈](https://ject-5th-team6.sentry.io/issues/JECT-5TH-TEAM6-API-SERVER-1)에 요청당 한 건씩 총 두 건이 기록됐고, 소스 업로드 후 재검증 이벤트에서 예외가 발생한 파일·메서드·12번 줄과 주변 소스가 표시되는 것을 확인했습니다. 검증용 컨트롤러는 제거했으며 최종 배포물에 포함하지 않습니다.

실제 Supabase 프로젝트 연결은 프로젝트 생성 후 확인해야 합니다. CodeRabbit 저장소 연결은 조직 소유자와 사용자가 추후 진행합니다. 배포와 Worker 구현은 포함하지 않았습니다.

최종 포맷은 모든 코드·문서·통합 수정이 끝난 뒤 코디네이터가 한 번만 실행하고, 이후 검사와 GitHub CI로 확인합니다. 브랜치 보호는 PR의 CI 검사 이름이 등록된 뒤 적용합니다.

## 2026-11-01 후속 통합 검증 — 최종 포맷 전

조정 담당자가 통합된 코드에서 다음 검사를 실행했습니다. 위의 초기화 15개 테스트 기록과는 별도 실행입니다.

| 검사 | 실제 결과와 범위 |
| --- | --- |
| `./gradlew test checkstyleMain checkstyleTest bootJar` | JUnit 21개, 실패·오류·건너뜀 0; Java 검사와 JAR 생성 성공 |
| PostgreSQL 17.6 | 런타임 history 조회·DML 거부, 업무 CRUD 허용·DDL 거부; 비슈퍼유저 CREATEROLE 관리자의 실제 bootstrap 템플릿 실행과 임시 SET 권한 회수 검증 |
| 공개 오류·보안·로컬 OpenAPI | 정적 한국어 오류·생성 요청 ID·JWT 401/403 공통 형식, 입력 검증 민감 정보 비노출, 로컬 UI·정적 자원·JSON·YAML 및 빈 업무 명세 검증 |
| Sentry 전송 테스트 | 실제 Boot 컨텍스트의 MockMvc 요청에서 예상하지 못한 5xx가 Sentry 테스트 전송기에 한 번 기록되고 응답 요청 ID와 이벤트 태그 일치; 외부 Sentry 수신 확인을 뜻하지 않음 |
| `python3 scripts/test-pr-policy.py` | 7개 테스트 그룹 성공 |
| `python3 scripts/test-release-artifact.py` | 1개 그룹의 정상·변조·현재 ID 누락·중복·다른 ID만 존재하는 사례 및 과거 번들 공존 허용 사례 성공 |
| `actionlint`, `git diff --check` | 성공 |
| 실제 Boot 출력 | 로그의 `requestId=` 상관관계 형식 확인 |
| `ZIPER_PREBUILT_ARTIFACT=true scripts/smoke-test.sh` | 현재 JAR 그대로 release 이미지 구성, 이미지 JAR SHA256·현재 소스 번들 ID 일치; 실제 HTTP liveness·readiness 200, DB 중단 시 readiness 503·liveness 200, 복구 후 readiness 200, 컨테이너·볼륨 정리 성공 |

실제 호스팅된 Supabase 연결, Worker·GCP 배포·IAM 적용은 검사하지 않았습니다. 최종 전체 포맷과 이후 검사 결과는 아직 실행 완료로 기록하지 않으며 PR의 최종 검증 증거로 남깁니다.

## 2026-11-01 하네스 강화 검증

후속 하네스 구현을 통합한 뒤 조정 담당자가 `./gradlew spotlessApply`를 한 번 실행하고 아래 검사를 수행했습니다. 이전 초기화 단계의 실행과 별도 기록입니다.

| 검사 | 실제 결과와 범위 |
| --- | --- |
| `./scripts/check.sh bootJar` | 문서 정책 1개 그룹(정상·위반 11개 사례), PR 정책 9개 그룹, JUnit 33개 성공; 실패·오류·건너뜀 0; Spotless·Checkstyle·JAR 생성 성공 |
| AST·ArchUnit 계약 | 업무 catch 거부, 주석·문자열·자원 정리 허용, 정확한 파일·줄 억제, 인라인 억제 거부; 저장소·웹·세션·업무 예외의 정상·위반 fixture 확인 |
| `python3 scripts/test-release-artifact.py` | 정상·변조·누락·중복·과거 번들 공존 사례 성공 |
| `actionlint`, `git diff --check` | 성공 |
| GitHub API 조회 | `@hj-k66` Write 권한, `develop`·`main` 코드 소유자 필수 승인·승인 1명·오래된 승인 무효화·관리자 적용 확인 |

기본 브랜치에는 CODEOWNERS가 아직 없으므로 소유자 지정은 첫 병합 이후 적용됩니다. 새 `harness` CI의 성공과 필수 검사 등록은 별도 확인 대상입니다. 이번 실행에서는 release 컨테이너 smoke나 외부 Sentry 수신, 호스팅 Supabase·클라우드 배포를 재검증하지 않았습니다.

## 2026-11-01 CodeRabbit 연결 확인

GitHub API로 커밋 `80678a5`의 check run과 PR #1 댓글을 조회했습니다. `coderabbitai` 앱의 `CodeRabbit` check run이 `skipped`로 완료됐고 `coderabbitai[bot]`이 "Draft PR not reviewed" 댓글을 남겼습니다. 앱 연결은 확인했지만 초안 PR이라 실제 리뷰 결과는 아직 확인하지 않았습니다.

## 2026-11-02 Sentry 애플리케이션 지표 연결

- `./scripts/check.sh bootJar` 성공. JUnit 44개 중 43개 성공, 외부 전송 opt-in 테스트 1개 건너뜀. 문서 정책, 검사기 자체 테스트, Spotless, Checkstyle과 아키텍처 검사도 통과했습니다.
- 실제 임시 HTTP 서버와 PostgreSQL 17.6에서 200·401·409·500 요청을 기록했습니다. 트레이스 샘플링 0에서도 요청별 count·duration·5xx 비율 표본을 수집하고, 5xx 오류 이벤트는 한 건만 기록했습니다. 상태 점검·Actuator·Swagger는 메트릭에서 제외했습니다. 메트릭 속성 필터·비활성화·설정 검증과 GC 카운터의 구간 증가량·초 단위 변환도 검사했습니다.
- `SENTRY_LIVE_VERIFICATION=true`로 테스트 전용 HTTP fixture와 실제 PostgreSQL에서 외부 SDK 전송을 초기 구현과 후속 5xx 비율 추가 후 각각 실행했습니다. [검증 오류](https://ject-5th-team6.sentry.io/issues/7824899803/) 한 건을 실제 Sentry에서 확인했습니다. 구독 사용량 화면은 이전 오류 2건·스팬 2개에서 오류 3건·스팬 6개로 바뀌었으며 메트릭 수신도 표시됐습니다. 테스트 전용 경로는 운영 소스에 없습니다.
- [Ziper Server 대시보드](https://ject-5th-team6.sentry.io/dashboard/10650059/)에 12개 패널을 저장했습니다. HTTP 지연과 JVM 힙·DB 풀 패널의 실제 수신 데이터를 확인했습니다. 후속 실행에서 요청 4개의 5xx 비율이 0.25로 표시되고 GC 증가량 패널에도 실행 식별자가 나타나는 것을 확인했습니다. 패널 생성만으로 수신 검증을 대신하지 않습니다. 재현용 정의는 `config/monitoring/sentry-dashboard.json`입니다.
- 실제 조직 구독은 Developer였습니다. 로그인 계정의 다른 조직에 Sponsored Team 혜택이 적용된 상태이며, `ject-5th-team6`의 Discord 설치는 플랜 제한으로 잠겨 있습니다. Discord 수신, 운영 알림 규칙 활성화, 공개 Uptime 점검과 Cloud Run 지표 전송은 완료하지 않았습니다. 교육 혜택의 복수 조직 적용·이전 정책은 공식 지원 AI의 자동 응답을 받았으나 사람 상담원의 확인을 추가 요청한 상태입니다.

최종 JAR의 `ZIPER_PREBUILT_ARTIFACT=true scripts/smoke-test.sh`도 성공했습니다. 이미지 내부 JAR 해시·Sentry debug ID 검증, DB 중단 중 readiness 실패·liveness 성공, 복구 후 readiness 성공과 컨테이너·볼륨 정리를 확인했습니다.

## 2026-11-03 Education 조직 이전과 실제 수신 검증

- `ject-5th-team6-api-server`를 기존 Sponsored Team 조직 `joonamin-project`로 이전하고 `ziper-server`로 변경했습니다. 프로젝트 ID `4512351718408192`와 수집 키는 유지됐으며 새 조직의 DSN 호스트를 무시된 로컬 `.env`에 반영했습니다. 삭제·재생성은 하지 않았습니다. 로컬 설정의 Sentry와 애플리케이션 지표는 활성화했고 `origin=local`로 지정했습니다.
- 이전 조직의 프로젝트 목록은 비어 있고 [이전 검증 이슈](https://joonamin-project.sentry.io/issues/7824899803/)는 새 조직의 `ziper-server`에서 기존 이벤트 3건을 유지한 것을 API로 확인했습니다.
- 실제 API 조회에서 `client`·`server`는 `fakedev` 팀, `ziper-server`는 `ziper` 팀에만 연결된 것을 확인했습니다. 조직 `openMembership=false`, 기본 역할 `member`도 확인했습니다. 추가 초대는 없으며 실제 Member 로그인에 의한 접근 거부 검증은 명단 전달 후 수행합니다.
- `./scripts/check.sh` 성공. JUnit 44개 중 43개 성공, 외부 전송 테스트 1개 건너뜀. 이후 외부 전송 테스트를 별도로 실행해 1개 성공, 실패·오류·건너뜀 0을 확인했습니다. 실제 PostgreSQL 17.6과 임시 HTTP 서버를 사용했습니다.
- 외부 검증 실행 `7e1288a4-13f7-4079-9bfa-3d49ebf38a4d`, 릴리스 `ziper-migration-20261103`, 환경 `local-monitoring`에 대해 [새 조직의 검증 오류](https://joonamin-project.sentry.io/issues/7825019863/)가 한 건 수신됐습니다. 해당 릴리스의 실제 `http.server` 스팬 4건도 조회했습니다.
- 같은 릴리스로 범위를 제한한 Sentry Application Metrics 조회에서 요청 수 4, 5xx 비율 0.25와 지연 분포 표본 4개를 확인했습니다. 힙 사용량·최대량·비율, CPU, 스레드, GC 횟수·시간, DB 활성·유휴·최대·대기·타임아웃 12종도 수신됐습니다. GC와 타임아웃의 이번 증가량은 0이었습니다. 표본이 적으므로 지연의 성능 판정에는 사용하지 않았습니다.
- [새 대시보드](https://joonamin-project.sentry.io/dashboard/10650540/)의 12개 패널과 실제 자원 지표를 확인했습니다. 이전 조직의 대시보드를 삭제하지 않았습니다.
- 새 조직의 `org:ci` 토큰으로 `SENTRY_UPLOAD=true` 소스 번들 업로드가 성공했습니다. 마지막 대조에서 현재 JAR의 소스 번들 `0df50053-7825-4c68-9609-42fb0bc9a28e`도 추가 업로드하고 대상 프로젝트 목록에서 같은 debug ID를 확인했습니다. 테스트 전용 예외 발생 파일은 배포 소스 번들 대상이 아니므로 이번 외부 테스트의 해당 프레임에는 소스 문맥이 없습니다. 업로드 성공을 그 프레임의 소스 표시 확인으로 간주하지 않습니다.
- GitHub 환경 `sentry-source-upload`, 환경 secret 메타데이터와 `main`·`develop` 배포 브랜치 정책을 확인했습니다. 로컬 `build.gradle`은 새 조직·프로젝트를 가리킵니다. 원격 워크플로 실행과 배포는 하지 않았으며 병합 후 별도 확인이 필요합니다.
- Discord 설치 목록에는 다른 서버 `1561280572100313188`만 연결돼 있습니다. 사용자는 대상 서버 `1537397802366144524`의 관리 권한이 없다고 확인했습니다. 따라서 지정 채널의 테스트 알림·실제 오류 알림 수신, 운영 알림 규칙과 공개 Uptime 검증은 완료하지 않았습니다. 서버 관리자가 설치 권한을 제공한 뒤 [운영 절차](operations.md#discord와-운영-알림)에 따라 이어서 확인합니다.

마지막 전체 검사에서 `build/resources/main/db/migration/`에 원본과 내용이 같은 ` 2.sql` 복사본 두 개가 남아 Flyway 중복 버전 오류가 났습니다. 소스에는 중복이 없었습니다. 생성 폴더의 복사본만 제거한 뒤 `./scripts/check.sh`를 재실행해 위의 43개 성공 결과를 확인했습니다. 현재 JAR에는 정상 마이그레이션 두 개만 있고 테스트 fixture 클래스는 없습니다.

현재 JAR로 `ZIPER_PREBUILT_ARTIFACT=true scripts/smoke-test.sh`를 다시 실행해 성공했습니다. 재컴파일 없이 이미지 내부 JAR 해시와 소스 번들 ID를 대조하고, 실제 HTTP 준비·생존 상태, DB 중단 중 readiness 실패·liveness 성공, DB 복구와 컨테이너·볼륨 정리를 확인했습니다.

## 2026-11-05 운영 연동 준비 검증

- 최신 원격 `develop`의 `23cd451`을 기준으로 별도 작업 디렉터리를 만들었습니다. 기존 작업공간의 미커밋 변경은 보존했습니다. 원격 `main`은 초기 README만 있는 상태로 확인했으며 브랜치 보호는 변경하지 않았습니다.
- `./scripts/check.sh bootJar` 성공. JUnit 44개 중 43개 성공, 외부 전송 opt-in 1개 건너뜀. 공개 상태 검사기 테스트 3개도 통과했고, 실제 HTTP fixture의 리다이렉트·오류·캐시·정보 노출 응답과 CLI의 자격 증명 반사 출력 거부를 확인했습니다.
- `ZIPER_PREBUILT_ARTIFACT=true scripts/smoke-test.sh` 성공. Java 21로 빌드한 JAR를 Java 25 런타임 이미지에 그대로 넣고 해시·소스 번들 ID, DB 중단 중 readiness 503·liveness 200, DB 복구와 정리를 확인했습니다. 운영 Compose 구문 검사와 `bash -n deployment/home/deploy.sh`도 성공했습니다.
- 실제 Supabase `Zip-er` 프로젝트는 PostgreSQL 17.11, Singapore 리전입니다. 기존 집어 역할과 스키마가 없는 것을 확인한 뒤 bootstrap을 한 번 실행했습니다. `ziper_app`·`ziper_migrator`의 로그인 허용과 역할 생성·DB 생성·RLS 우회 권한 비활성, `ziper` 스키마의 migrator 소유를 읽어 확인했습니다.
- 실제 Session pooler 5432에 런타임 역할로 접속했습니다. 공식 루트 CA와 `sslmode=verify-full`로 TLS 검증이 성공했고 스키마 USAGE 허용·스키마 CREATE 거부·DB CREATE 거부를 확인했습니다. 비밀 값은 저장소 밖에만 보관했습니다.
- 전용 `ziper-production` Cloudflare Tunnel과 `api-ziper.joonamin.dev` → `http://app:8080` 경로를 만들었습니다. Docker Desktop의 비대화형 credential store 오류는 프로젝트 전용 Docker 클라이언트 설정으로 해결했습니다. 기존 tocktock 컨테이너와 Tunnel 설정은 변경하지 않았습니다.

이 시점에는 공개 앱의 UP 응답과 Sentry Uptime 수신을 아직 완료로 기록하지 않습니다. 실제 운영 기동과 외부 점검 결과는 후속 실행 기록으로 남깁니다.
