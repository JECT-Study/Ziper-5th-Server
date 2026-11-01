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
