# 구조와 개발 규칙

Groovy Gradle을 사용하는 단일 모듈 프로젝트입니다. 기본 패키지는 `com.ject.ziper`입니다.

업무 기능은 `domain/<feature>` 아래에 `controller`, `service`, `repository`, `entity`, `dto` 패키지로 나눕니다. 실제 기능이 생길 때 필요한 패키지만 만듭니다. 공통 관심사는 `global` 아래 `config`, `security`, `persistence`, `error`, `observability`에 둡니다. 현재 구현된 공통 코드는 보안, 오류 처리, 관측 기능과 로컬 OpenAPI 설정입니다.

컨트롤러는 HTTP 입출력을 처리하고 서비스는 업무 규칙과 트랜잭션을 담당합니다. 엔티티를 API 응답으로 직접 노출하지 않습니다. 웹과 모바일 클라이언트가 같은 API를 사용할 예정입니다.

## Java 스타일

[NAVER Java 코딩 컨벤션](https://github.com/naver/hackday-conventions-java/blob/master/src/java-rules.adoc)을 기준으로 하드 탭을 사용하며 표시 폭은 4입니다. `.editorconfig`를 적용합니다. Spotless는 Eclipse 4.32와 프로젝트에서 직접 작성한 최소 XML 설정을 사용합니다. 외부 포매터 XML 전체를 복제한 설정이 아닙니다. Checkstyle 경고도 실패로 처리합니다.

```sh
./scripts/check.sh
```

자동 포맷은 변경 범위를 확인한 뒤 실행합니다. 이번 초기화 작업에서는 모든 구현·문서·통합 수정이 끝난 후 조정 담당자만 최종 포맷을 한 번 실행합니다. 다른 작업자는 포매터나 `spotlessApply`를 실행하지 않습니다.

## 변경 검증

테스트는 관찰 가능한 동작을 확인합니다. PostgreSQL 기능은 실제 PostgreSQL 17.6으로 검증합니다. DB 스키마 변경은 Flyway 마이그레이션으로 기록합니다. Hibernate는 스키마를 검증하며 자동 생성하거나 수정하지 않습니다. OSIV는 꺼져 있습니다.

공개 API의 오류는 Spring `ProblemDetail` 형식을 사용합니다. 4xx와 5xx 응답에 SQL, 스택 트레이스, 비밀 값이나 내부 예외 메시지를 노출하지 않습니다.

## 공통 계약

[ADR 0001](adr/0001-runtime-contract.md)의 오류 계약을 따릅니다. `BusinessException`은 예상한 업무 4xx 전용입니다. 업무 계층에서 기술 예외를 잡아 무시하거나 이 예외로 감싸지 않습니다. 공개 `detail`은 정적인 한국어 오류 카탈로그에서 선택합니다. `code`와 서버가 생성한 `requestId`를 포함하고 JWT 401·403에도 같은 팩토리를 적용합니다. 예상 4xx는 Sentry에서 제외하고 예상하지 못한 5xx는 요청당 한 번 수집합니다.

기능 구현 상세는 PR에 기록하고 기능별 영구 문서를 중복 생성하지 않습니다. 기능 PR은 사용자 이야기의 E2E 동작 증거를 포함합니다. 구조·인증·데이터·일관성·장애 전환·아티팩트 결정이 달라지면 ADR과 [문서 지도](context-map.md)를 함께 갱신합니다.
