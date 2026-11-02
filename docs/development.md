# 구조와 개발 규칙

문서 정책은 `db/` 변경에 DB 문서 검토를, `.githooks/` 변경에 개발 규칙 검토를 요구합니다. catch 억제 테스트는 실제 파일의 정규 경로 패턴으로 20줄 제한의 거부 사유까지 확인합니다.

Groovy Gradle을 사용하는 단일 모듈 프로젝트입니다. 기본 패키지는 `com.ject.ziper`입니다.

업무 기능은 `domain/<feature>` 아래에 `controller`, `service`, `repository`, `entity`, `dto` 패키지로 나눕니다. 실제 기능이 생길 때 필요한 패키지만 만듭니다. 공통 관심사는 `global` 아래 `config`, `security`, `persistence`, `error`, `observability`에 둡니다. 현재 구현된 공통 코드는 보안, 오류 처리, 관측 기능과 로컬 OpenAPI 설정입니다.

컨트롤러는 HTTP 입출력을 처리하고 서비스는 업무 규칙과 트랜잭션을 담당합니다. 엔티티를 API 응답으로 직접 노출하지 않습니다. 웹과 모바일 클라이언트가 같은 API를 사용할 예정입니다.

## Java 스타일

[NAVER Java 코딩 컨벤션](https://github.com/naver/hackday-conventions-java/blob/master/src/java-rules.adoc)을 기준으로 하드 탭을 사용하며 표시 폭은 4입니다. `.editorconfig`를 적용합니다. Spotless는 Eclipse 4.32와 프로젝트에서 직접 작성한 최소 XML 설정을 사용합니다. 외부 포매터 XML 전체를 복제한 설정이 아닙니다. Checkstyle 경고도 실패로 처리합니다.

```sh
./scripts/check.sh
```

자동 포맷은 변경 범위를 확인한 뒤 실행합니다. 이번 초기화 작업에서는 모든 구현·문서·통합 수정이 끝난 후 조정 담당자만 최종 포맷을 한 번 실행합니다. 다른 작업자는 포매터나 `spotlessApply`를 실행하지 않습니다.

Checkstyle API 계약 테스트는 `testImplementation`으로 검사기를 사용합니다. 이 의존성에서만 `slf4j-simple`을 제외해 테스트 애플리케이션의 Logback과 충돌하지 않도록 합니다. Gradle Checkstyle 작업은 별도 의존성 구성을 사용합니다.

## 자동 검사 범위

`check.sh`는 `scripts/test-harness-policy.py`, `scripts/check-harness-policy.py --repo .`, `scripts/test-pr-policy.py`를 실행한 뒤 Gradle `check`를 실행합니다. 커밋 훅과 푸시 훅도 문서 정책 검사를 포함합니다. 훅은 `./scripts/local-setup.sh` 외에도 Gradle `compileJava`가 의존하는 `installGitHooks` 태스크가 `core.hooksPath`를 `.githooks`로 설정해 설치합니다. 첫 Gradle 빌드나 IDE 가져오기에서 실행되며 `.git`이 없는 Docker 빌드에서는 건너뜁니다. 훅은 우회할 수 있고 로컬 설정이므로 CI와 리뷰를 함께 사용합니다.

Checkstyle의 `MatchXpath` 규칙 `BusinessCatch`는 `com.ject.ziper.domain` 아래 `service`, `controller`와 그 하위 패키지의 실제 `catch` 구문을 금지합니다. 문자열·주석의 단어는 대상이 아니며, 정리용 `try/finally`와 `try-with-resources`는 허용합니다. 전역 오류 처리와 외부 I/O 경계의 예외 처리는 이 규칙의 대상이 아닙니다. 예외가 필요하면 `config/checkstyle/business-catch-suppressions.xml`에 정확한 파일과 한정된 줄 범위만 등록하고 PR에 이유를 기록해 소유자 승인을 받습니다. 기본 허용 목록은 비어 있습니다.

Checkstyle의 `MatchXpath` 규칙 `FullyQualifiedName`은 운영·테스트 코드에서 `import`, `package` 선언 밖의 패키지 한정 이름(`java.util.Locale.ROOT`, `@org.springframework.stereotype.Service` 등)을 금지합니다. 타입과 정적 멤버는 항상 `import` 또는 `import static`으로 가져옵니다. 소문자로 시작하는 이름 뒤에 대문자로 시작하는 이름이 이어지는 점 표기를 위반으로 판단하므로 인스턴스로 정적 상수에 접근하는 코드도 함께 거부됩니다. 문자열·주석은 대상이 아니며 허용 목록에 등록할 수 없습니다.

ArchUnit은 실제 운영 클래스에서 컨트롤러의 저장소 의존, 업무 서비스의 Servlet·Spring Web 의존, 세션 의존·요청 세션 API 호출, 업무 패키지의 사용자 정의 `RuntimeException`을 검사합니다. 예상한 업무 4xx에는 공통 `BusinessException`을 사용합니다. 현재 업무 클래스가 없으므로 별도 정상·위반 fixture 계약 테스트로 규칙의 탐지 동작도 확인합니다. 이 검사는 모든 메모리 상태나 모든 동적 호출의 안전성을 보장하지 않습니다.

## 변경 검증

테스트는 관찰 가능한 동작을 확인합니다. 테스트 메서드 이름은 `주어진상황_행동하면_기대결과`처럼 given·when·then 세 부분을 `_`로만 구분해 한국어로 씁니다. 각 부분 안에서는 `_`를 쓰지 않으며 `JWT`, `BusinessCatch` 같은 고유 식별자는 섞어 쓸 수 있습니다. Checkstyle 규칙 `TestMethodName`이 JUnit 테스트 애너테이션이 붙은 메서드의 형식을 검사하고, 이 메서드에는 일반 메서드의 `MethodName` 규칙을 적용하지 않습니다. PostgreSQL 기능은 실제 PostgreSQL 17.6으로 검증합니다. DB 스키마 변경은 Flyway 마이그레이션으로 기록합니다. Hibernate는 스키마를 검증하며 자동 생성하거나 수정하지 않습니다. OSIV는 꺼져 있습니다.

공개 API의 오류는 Spring `ProblemDetail` 형식을 사용합니다. 4xx와 5xx 응답에 SQL, 스택 트레이스, 비밀 값이나 내부 예외 메시지를 노출하지 않습니다.

## 공통 계약

[ADR 0001](adr/0001-runtime-contract.md)의 오류 계약을 따릅니다. `BusinessException`은 예상한 업무 4xx 전용입니다. 업무 계층에서 기술 예외를 잡아 무시하거나 이 예외로 감싸지 않습니다. 공개 `detail`은 정적인 한국어 오류 카탈로그에서 선택합니다. `code`와 서버가 생성한 `requestId`를 포함하고 JWT 401·403에도 같은 팩토리를 적용합니다. 예상 4xx는 Sentry에서 제외하고 예상하지 못한 5xx는 요청당 한 번 수집합니다.

### ADR 작성 기준

되돌리기 어렵거나 변경 범위와 의도를 분명히 남겨야 하는 결정은 구현과 같은 PR에서 `docs/adr/`에 ADR로 기록하고 [문서 지도](context-map.md)의 결정 이력에 연결합니다. 다음 중 하나라도 해당하면 ADR을 작성합니다.

1. DB 스키마의 파괴적 변경(컬럼·테이블 삭제, 타입 변경)이나 되돌리기 어려운 마이그레이션
2. 새 외부 시스템·인프라 도입(메시지 큐, 캐시, 외부 API, 저장소 등)
3. 인증·인가 방식 변경
4. 패키지 구조나 계층 경계 변경
5. 트랜잭션·재시도·멱등성 같은 일관성 모델 변경

해당하지 않는 기능 구현 상세는 ADR이 아니라 PR에 기록합니다. 기준에 해당하는지 애매하면 ADR을 쓰거나 PR에서 사용자에게 확인합니다.

작성 방법입니다.

- 파일 이름은 `docs/adr/NNNN-<주제>.md`이며 `NNNN`은 기존 ADR의 다음 번호입니다.
- 첫 줄은 `# NNNN 제목`, 다음 줄에 `- 상태:`와 `- 날짜:`를 적습니다. 상태는 `제안`, `채택`, `대체됨(NNNN)`, `폐기` 중 하나로 쓰고 아직 검증하지 않은 부분은 상태에 함께 밝힙니다.
- 본문은 `## 배경`, `## 결정`, `## 대안`, `## 영향과 한계` 순서로 씁니다. `대안`에는 검토했지만 채택하지 않은 선택지와 이유를 적습니다.
- 결정이 바뀌면 기존 ADR을 지우거나 고치지 않고 새 ADR을 쓰고, 기존 ADR의 상태를 `대체됨`으로 바꿔 새 ADR에 연결합니다.

기능 구현 상세는 PR에 기록하고 기능별 영구 문서를 중복 생성하지 않습니다. 기능 PR은 사용자 이야기의 E2E 동작 증거를 포함합니다. 구조·인증·데이터·일관성·장애 전환·아티팩트 결정이 달라지면 ADR과 [문서 지도](context-map.md)를 함께 갱신합니다.
