# 0008 서비스·쿼리 추적과 성능 데이터 경계
- 상태: 채택; 로컬 실제 Sentry 수신 검증, 운영 배포는 미실행
- 날짜: 2026-11-07

## 배경

기존 Sentry는 HTTP 전체 시간과 오류를 수집하지만 실제 서비스 처리와 SQL의 시간·호출 관계를 분석할 계측이 부족했다. 업무 API는 아직 없으므로 운영용 가짜 API를 만들지 않고 애플리케이션 설정과 테스트 전용 경로로 기반을 검증한다.

## 결정

기존 Sentry Spring Boot Starter를 단일 초기화·HTTP 계측 소유자로 유지한다. 같은 BOM의 JDBC·async-profiler 모듈과 Spring AOP를 추가한다. 주요 Spring Bean 메서드에 선택적으로 `@SentrySpan`을 적용하며 실제 쿼리는 P6Spy로 측정한다.

Sentry 활성화 시 런타임 Hikari 풀의 드라이버·내부 URL prefix만 바꾸고 원래 `DataSourceProperties`와 풀 속성 바인딩을 사용한다. 환경 변수 URL·Flyway 자격을 유지하며 비활성일 때는 Boot 기본 DataSource 구성을 따른다.

DB span은 prepared SQL의 SHA-256 앞 16자리 fingerprint만 남긴다. SQL 원문·값·DB 이름·추가 태그를 제거하고 시간·식별자·부모·상태를 유지한다. SDK 8.62.0의 직렬화 span 복사 API는 경계 어댑터에 모으고 SDK 변경 시 envelope 계약 테스트로 확인한다. `database-query-data=false`만으로 JDBC 설명이 제거된다고 가정하지 않는다.

HTTP sampling은 기본 0.1, profile session sampling은 0.0이며 profiling은 opt-in이다. TRACE lifecycle로 활성 span 구간을 수집한다. 허용된 CORS 출처에서 sentry-trace·baggage를 받으며 기존 동일 빌드의 source bundle 업로드 계약을 유지한다.

## 대안

- OpenTelemetry agent 전체 적용: 기존 Starter와 계측 소유권·중복 span·배포 옵션을 함께 이전해야 하므로 도입하지 않는다.
- 모든 서비스 자동 AOP: 관측량과 프록시 영향을 줄이기 위해 의미 있는 구간의 명시적 annotation을 선택한다.
- SQL 원문 전송·P6Spy 파일 로그: 민감 정보가 섞일 수 있어 사용하지 않는다.
- DataSource 프록시 교체: 기존 Hikari 메타데이터·풀 관측을 유지하기 위해 풀 내부의 driver를 계측한다.

## 영향과 한계

실제 PostgreSQL·HTTP·AOP 경로에서 서비스→저장소→DB span을 검증한다. 외부 Sentry 검증은 opt-in 명령이며 일반 CI는 기록용 transport를 사용한다. 검증용 API와 부하는 테스트 소스에만 둔다.

Fingerprint는 반복·소요 시간을 비교하게 하지만 SQL 텍스트 기반의 자동 N+1 탐지나 실행 계획은 제공하지 않는다. 호출 위치와 코드의 SQL을 대조해야 한다. 프로파일은 샘플링 기반이며 모든 함수의 정확한 실행 시간을 기록하지 않는다. 같은 객체 내부 호출·비동기 전파는 별도 검증이 필요하다. 로컬 결과는 운영 배포·실제 업무 병목·개선 효과를 증명하지 않는다.
