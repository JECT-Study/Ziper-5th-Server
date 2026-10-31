# 초기화 검증 기록

2026-11-01 로컬 환경에서 Java 21과 실제 PostgreSQL 17.6을 사용해 검증했습니다.

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
