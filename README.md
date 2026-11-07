# Ziper 서버

JECT 5기 6팀의 Java 21 · Spring Boot 3.5.16 API 서버입니다. 현재는 인증 검증, 오류 처리, 데이터베이스 연결과 운영 점검을 위한 초기 기반만 구현했습니다. 소셜 로그인, 회원 기능과 업무 API는 아직 없습니다.

소스와 테스트는 JDK 21을 사용하고 Docker 실행 이미지는 JRE 25를 사용합니다. 소스 이미지의 컴파일 단계도 JDK 21을 사용합니다.

## 로컬에서 시작하기

JDK 21, Docker Compose v2, Python 3, curl을 준비합니다. Gradle은 저장소의 [Wrapper 설정](gradle/wrapper/gradle-wrapper.properties)을 사용합니다.

```sh
./scripts/local-setup.sh
docker compose up --build -d
curl -i http://localhost:8080/livez
curl -i http://localhost:8080/readyz
```

두 점검 경로가 `200`과 `Cache-Control: no-store`를 반환하면 준비된 상태입니다. 앱과 PostgreSQL 포트는 로컬 호스트에만 열립니다. 첫 빌드는 의존성 다운로드로 시간이 걸립니다.

`local-setup.sh`는 임의 비밀번호와 JWT 서명 키가 들어 있는 `.env`를 권한 `0600`으로 생성합니다. 기존 파일은 덮어쓰지 않습니다. Git 훅도 설치하며(첫 Gradle 빌드나 IDE 가져오기에서도 자동 설치됩니다), 커밋 전에 스타일을 검사하고 푸시 전에 전체 검사를 실행합니다. `.env`는 커밋하지 않습니다.

```sh
./scripts/check.sh
./scripts/smoke-test.sh
```

`check.sh`는 문서 정책과 검사기 자체 테스트, Java 스타일·아키텍처 검사와 테스트를 실행합니다. 변경 경로별 문서 검토와 PR 작성 방법은 [저장소 규칙](docs/repository.md)을 따릅니다. PostgreSQL 17.6 Testcontainers 테스트에 Docker가 필요하며 H2로 대체하지 않습니다. `smoke-test.sh`는 별도 Compose 프로젝트, 임시 자격 증명과 임의 포트를 사용합니다. JAR를 한 번 빌드해 release 이미지에 넣고 이미지 내부 JAR의 해시와 Sentry 소스 번들 ID 일치도 확인합니다. DB 중단 시 준비 상태 `503`, 생존 상태 `200`, DB 재시작 후 복구를 검사하고 컨테이너와 볼륨을 정리합니다.

로컬 앱을 중지하려면 `docker compose down`을 실행합니다. `docker compose down -v`는 로컬 DB 데이터도 삭제하므로 초기화할 때만 사용합니다.

## 개발 안내

쿼리·서비스 성능 계측은 [Sentry 성능 검증](docs/operations.md#쿼리와-서비스-성능-추적)을 따릅니다. `scripts/verify-sentry-performance.sh`는 비공개 환경 변수로 공급한 Sentry DSN을 사용해 테스트 전용 HTTP·서비스·PostgreSQL 경로를 실행하고 trace와 profiler ID를 출력합니다.

- [문서 지도 — 작업 전 먼저 읽기](docs/context-map.md)
- [구조와 개발 규칙](docs/development.md)
- [데이터베이스와 Supabase 준비](docs/database.md)
- [보안·상태 점검·Sentry 운영](docs/operations.md)
- [리뷰와 저장소 설정](docs/repository.md)
- [에이전트 작업 규칙](AGENTS.md)
- [초기화 검증 기록](docs/verification.md)

Spring Boot 4에 대한 팀의 경험이 부족해 3.5 계열로 시작했습니다. 3.5.16은 마지막 OSS 지원 릴리스이므로, 2026-11-01 기준으로 이 선택을 장기 지원으로 간주하면 안 됩니다. 후속 지원 또는 상위 버전 전환 계획을 별도로 정해야 합니다. [Spring 공식 안내](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)

배포 환경과 CD는 아직 정하지 않았습니다. Supabase 프로젝트도 아직 생성하지 않았습니다. OpenAPI 생성 계약은 명시적으로 선택한 로컬 환경에서만 확인합니다. 기본값과 서버 환경에서는 비활성입니다. 장애 전환과 비밀 관리는 [향후 인프라 계약](docs/infrastructure.md)만 정했으며 아직 구현하지 않았습니다. 라이선스는 소유자가 선택하기 전까지 임의로 추가하지 않습니다.
