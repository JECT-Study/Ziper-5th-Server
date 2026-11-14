# 문서 지도

에이전트는 작업 시작 시 이 문서를 먼저 읽습니다. 표에서 변경 대상의 문서로 이동하고 실제 코드·스크립트와 대조합니다. `config/harness/doc-policy.json`은 유지 문서와 변경 경로별 검토 대상을 정의하며, 정적 검사는 이 표의 링크·누락·중복·경로 이탈을 확인합니다. 검사를 통과해도 문서 독해나 내용의 정확성이 입증되지는 않습니다. 동작 변경이 기존 설명에 영향을 주면 같은 PR에서 문서를 수정합니다. 문서 영향이 없으면 PR에 이유를 남깁니다.

| 문서 | 목적 | 변경 계기와 수정 방향 |
| --- | --- | --- |
| [README](../README.md) | 현재 구현 범위와 첫 실행 | 진입 명령·준비물·구현 상태 변경 시 실제 실행 경로 반영 |
| [AGENTS](../AGENTS.md) | 작업 경계와 금지 사항 | 협업·검증·문서 정책 변경 시 강제 계약 반영 |
| [개발](development.md) | 코드 구조·오류·스타일·검증 | 공통 경계나 명령 변경 시 현재 코드 기준 갱신 |
| [DB](database.md) | 역할·마이그레이션·연결 | 스키마·권한·DB 연결 변경 시 최소 권한과 이행 절차 갱신 |
| [운영](operations.md) | JWT·probe·Sentry·아티팩트 | 인증·관측·배포물 변경 시 런타임 계약 갱신 |
| [인프라 연결](infrastructure.md) | 외부 Infra 정책의 소유 위치·앱 계약 | 두 저장소의 연결·책임·검증 증거 위치 변경 시 실제 계약 반영 |
| [저장소](repository.md) | PR·CI·리뷰 운영 | 워크플로·리뷰 정책 변경 시 실제 상태 반영 |
| [검증 이력](verification.md) | 날짜별 실제 검사 증거 | 새 실행 결과만 날짜·범위·제한과 추가; 과거 결과를 재실행처럼 쓰지 않음 |

기능 구현 상세는 PR에만 남깁니다. 기능 PR은 사용자 이야기의 E2E 검증을 포함합니다. 생성된 Swagger 계약은 이 제한의 예외이며 로컬 명시적 선택으로만 노출합니다.

## 결정 이력


장기적인 구조·인증·데이터·일관성·장애 전환·아티팩트 결정은 `docs/adr/`에 배경·결정·영향·상태를 기록하고 이 목록에서 연결합니다. 결정이 바뀌면 과거 기록을 지우지 않고 대체 ADR을 연결합니다.

- [0001 공통 실행·오류·아티팩트 경계](adr/0001-runtime-contract.md)
- [0002 향후 장애 전환과 비밀 번들](adr/0002-infrastructure-contract.md) — 0004로 대체

- [0003 검사 정책의 신뢰 경계와 적용 범위](adr/0003-harness-contract.md)

- [0004 홈서버 우선 배포와 Cloud Run 장애 전환](adr/0004-deployment-and-failover.md) — 수동 진입점 0007과 연결된 Infra ADR로 대체
- [0005 공유 Education 조직의 프로젝트별 모니터링 접근](adr/0005-monitoring-project-access.md)
- [0006 홈서버 첫 운영 배포와 Uptime 검증](adr/0006-initial-production-deployment.md)
- [0007 수동 운영 배포 진입점과 응답 식별](adr/0007-manual-production-entrypoint.md)
  - 실제 SSH 입력 경로·1MiB 검증·이식 가능한 이미지 식별자는 Infra 저장소가 관리합니다. 외부 결정 위치와 운영 실행 증거는 0007과 저장소 문서에서 연결합니다.
  - 공개 readiness의 제한된 준비 시간 정책은 Infra ADR 0006이 소유합니다. Server CLI와 고정 참조의 관계는 0007에서 연결합니다.

- 0007의 수동 진입점은 GHCR 발행·WIF와 비공개 Cloud Run 쌍 배포를 호출합니다. 세부 정책과 운영 증거는 Infra가 소유합니다.

- 0007의 고정 Infra workflow는 불변 OIDC subject와 복구 lease 중 배포 차단도 포함합니다. 실제 인증·독립 timer·장애 실험 증거는 Infra PR #1에서 구분합니다.

- 0007이 호출하는 종료 후 재기동 정책은 Infra ADR 0009에서 관리합니다. 운영 컨테이너 정책과 전체 다음 BIOS 부팅의 검증 범위를 구분합니다.
- [0008 서비스·쿼리 추적과 성능 데이터 경계](adr/0008-service-and-query-tracing.md)
- [0009 이메일 인증번호 발송과 검증 경계](adr/0009-email-verification-delivery.md) — 요청 한도·오입력 제한은 0012로 대체
- [0010 소셜 OAuth 로그인과 자체 JWT·세션 수명 주기](adr/0010-oauth-login-and-token-lifecycle.md)
- [0011 OAuth 무이메일 회원을 위한 users.email Nullable 완화](adr/0011-nullable-user-email-for-oauth.md)
- [0012 이메일 단위 인증번호 오입력 잠금](adr/0012-email-verification-lockout.md)
