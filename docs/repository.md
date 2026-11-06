# 리뷰와 저장소 설정

CI checkout은 자격 증명을 남기지 않습니다. PR 문서 검토 대상은 병합 기준점과 head의 세 점 diff로 계산하며, 기본 브랜치만 변경한 경로는 포함하지 않습니다. `No documentation impact:`의 구체적인 이유는 같은 줄에 작성합니다. `.githooks/`와 `.coderabbit.yaml`도 `@hj-k66`과 `@joonamin`의 공동 소유자 검토 대상입니다.

CI는 `harness`, `quality`, `test`, `container-smoke` 작업으로 문서 정책, 스타일, 테스트와 컨테이너 동작을 검사합니다. 저장소 관리자는 실제 워크플로가 등록되고 성공한 뒤 `develop`과 `main`의 브랜치 보호에 필요한 상태 검사를 지정합니다. 이 문서는 보호 설정이 이미 적용됐다는 증거가 아닙니다.

`container-smoke`는 같은 JAR를 사용하는 release 이미지의 동작을 검증한 뒤 로컬 `source` 이미지도 빌드합니다. 실행 이미지와 컴파일용 JDK가 달라도 소스 빌드가 가능한지 함께 확인합니다.

PR은 feature·bug·chore 의도를 구분하고 변경 목적, 동작 변화와 실행한 검사를 기록합니다. 기능 PR에는 사용자 이야기의 E2E 검증을 포함합니다. 문서 동기화 체크박스를 확인하고 문서 영향이 없으면 이유를 남깁니다. 기능별 구현 설명은 PR에만 기록하며 장기 결정은 ADR과 문서 지도에 연결합니다. 스타일 설정은 NAVER 문서에서 필요한 규칙을 직접 작성한 것이며 외부 XML의 라이선스를 추정하지 않습니다. Dependabot 변경도 일반 변경과 동일하게 CI와 리뷰를 통과해야 합니다.

Spring Boot 3.5를 사용하는 동안 springdoc-openapi는 호환되는 2.x를 유지합니다. Dependabot은 Spring Boot와 springdoc의 메이저 업데이트를 제외하며, 프레임워크 메이저 이전은 두 라이브러리의 호환성을 함께 확인하는 변경으로 진행합니다. [springdoc 호환성 안내](https://springdoc.org/faq.html)

## CodeRabbit 연결

`.coderabbit.yaml`은 한국어 리뷰, `develop`·`main` 대상 PR과 초안 PR 제외를 설정합니다. 저장소의 `AGENTS.md`도 리뷰 기준으로 제공합니다. 유료 전용 기능은 요구하지 않습니다.

조직에 CodeRabbit 앱은 설치돼 있지만 이 저장소 연결은 보류돼 있습니다. 조직 소유자 권한 요청과 연결은 사용자가 추후 진행합니다. 설정 파일만으로 앱 설치나 저장소 권한이 생기지 않습니다.

2026-11-01 기준 공개 저장소의 OSS 제공 정책과 일반 Free 플랜의 요약 기능은 서로 다릅니다. 현재 별 0개인 저장소는 연결 후 `@coderabbitai review` 수동 요청 방식으로 시작합니다. 공개 OSS 저장소에는 Team 기능이 무료로 제공될 수 있지만 무제한 리뷰는 아닙니다. 개발자·저장소 범위에 따라 시간당 1~10회 리뷰 제한이 적용되므로 실제 계정 상태와 정책을 확인합니다. [플랜 안내](https://docs.coderabbit.ai/management/plans), [요청 제한 안내](https://docs.coderabbit.ai/management/rate-limits)

배포 대상과 호스팅이 확정되면 별도 CD를 설계합니다. 공개 저장소라는 이유만으로 라이선스를 임의로 선택하지 않습니다.

## PR 작성 계약

제목은 `feature: 설명`, `bug: 설명`, `chore: 설명` 중 하나이며 본문의 `## Type`과 일치해야 합니다. 공통 필수 절은 `## Intent`, `## Validation`, `## Documentation`, `## Durable decision`, `## Checklist`입니다. Type은 `feature|bug|chore`, Durable decision은 `yes|no` 중 정확히 하나입니다.

- feature: `## User story`, `## Observable behavior`, `## E2E evidence`, `## Implementation details`
- bug: `## Reproduction`, `## Before and after`
- chore: `## Purpose and impact`

Documentation에는 `config/harness/doc-policy.json`에서 변경 경로에 연결한 각 문서의 Markdown 링크를 적습니다. 문서 내용이 그대로 유효하면 `No documentation impact: 구체적인 이유`와 `Reviewed:` 뒤에 검토한 문서 링크를 적습니다. 여러 경로가 겹치면 연결된 문서를 모두 검토합니다. Durable decision이 yes면 실제로 변경한 `docs/adr/*.md`와 `docs/context-map.md` 링크가 필요합니다. 일반 수정이나 리팩터링마다 ADR을 만들지는 않습니다. Checklist의 다음 두 항목은 실제 증거를 확인한 뒤 체크합니다.

```markdown
- [x] Documentation synchronized or no-impact reason recorded
- [x] Validation evidence recorded
```

`.github/PULL_REQUEST_TEMPLATE.md`와 유형별 템플릿에서 시작합니다. `PR policy` 워크플로는 본문 수정 시에도 검사합니다. 기본 브랜치의 신뢰한 검사기와 경로별 문서 정책이 후보 트리와 변경 파일 JSON, 이벤트 본문을 데이터로 읽으며 후보 브랜치의 정책 스크립트를 실행하지 않습니다. 검사기는 문서 검토 링크와 실제 ADR 변경을 확인하지만 문서 내용과 검증 증거의 진실성은 사람이 확인합니다.

첫 도입 PR의 기본 브랜치에는 새 검사기가 없습니다. 이 경우 `4aa71645b050c95a9dcdd3d072af9cba4ca6daa6`의 기존 PR 구조 검사기를 사용합니다. 새 검사는 후보 코드의 자체 테스트와 사람의 승인으로 확인하고, 첫 병합 이후 기본 브랜치의 새 검사가 자동 적용됩니다. 이 초기 검사를 새 정책의 자동 강제 증거로 기록하지 않습니다.

`.github/CODEOWNERS`는 AGENTS, 문서 지도, CODEOWNERS, 워크플로, 검사기, 스타일·빌드 설정과 아키텍처 테스트를 `@hj-k66`과 `@joonamin`의 공동 검토 대상으로 지정합니다. 2026-11-01 GitHub API로 `@hj-k66`의 Write 권한과 `develop`·`main`의 필수 소유자 승인, 승인 1명, 오래된 승인 무효화, 관리자 적용 설정을 확인했습니다. CODEOWNERS는 기본 브랜치에 병합된 뒤 적용됩니다. 첫 PR은 별도 사람 검토가 필요하며, 기본 브랜치 병합 전에는 소유자 지정이 강제된다고 간주하지 않습니다. 2026-11-02 GitHub API로 `@joonamin`의 관리자 권한을 확인해 공동 소유자로 추가했습니다. 여러 소유자를 같은 줄에 지정하므로 PR 작성자가 아닌 두 소유자 중 한 명의 승인으로 소유자 검토 요건을 충족합니다.

워크플로는 비밀 값과 쓰기 토큰 없이 `pull_request`로 실행합니다. 워크플로 자체를 바꾸는 PR의 안전성은 사람의 소유자 검토에 의존합니다. 기본 브랜치 스크립트를 사용해도 워크플로 변경까지 자체적으로 보호하지는 않습니다. 상세한 신뢰 경계는 [ADR 0003](adr/0003-harness-contract.md)에 기록합니다.

홈서버 운영 배포는 [ADR 0007](adr/0007-manual-production-entrypoint.md)의 main 수동 진입점을 사용합니다. 초기 개인 SSH 부트스트랩과 실제 Actions 배포 검증은 구분해 기록합니다. 공개 상태 검사기는 로컬 `check.sh`와 CI `harness`에서 검사합니다. Sentry 소스 업로드 대상은 `joonamin-project/ziper-server`이며 `sentry-source-upload` 환경의 조직 토큰을 사용합니다.

## main 수동 배포 진입점

`Deploy production manually`는 workflow_dispatch로만 시작하고 main만 허용합니다. 실제 배포 코드는 공개 Infra 저장소의 고정 SHA workflow를 재사용합니다. 홈·후속 검증은 같은 production concurrency를 사용하고 실행 중인 배포를 취소하지 않습니다. PR은 운영 Secret이나 배포 권한을 사용하지 않습니다.

현재 이미지는 [Infra ADR 0007](https://github.com/JECT-Study/Ziper-5th-Infra/blob/main/docs/adr/0007-ghcr-home-delivery.md)에 따라 GHCR에 발행하고 홈서버가 정확한 registry digest를 pull합니다. JAR 해시·OCI config digest를 검증한 뒤 로컬 release tag로 실행하며 SSH에는 8KiB 이하 제어 정보만 전달합니다. 과거 archive·1MiB 진단 이력은 Infra ADR 0004·0005에 남아 있습니다. workflow의 uses와 infra_sha는 같은 실제 Infra main SHA로 함께 갱신하고, 두 저장소의 해당 main CI 성공 후 main에서 수동 실행합니다. develop에도 진입 workflow가 있어 GitHub UI에서 수동 실행을 발견할 수 있습니다.

CF_ACCESS_CLIENT_ID/SECRET은 Server Repository secrets에서 읽을 수 있으며, private SSH key·host key pin·Discord webhook은 production 환경에 둡니다. production 환경은 main만 허용합니다. Secret 등록 여부와 인증·채널 수신의 실제 성공은 별개로 검증합니다. 정책·배포 구현·운영 E2E 결과는 [Infra PR #1](https://github.com/JECT-Study/Ziper-5th-Infra/pull/1)에 모읍니다.

공개 probe 검사는 JSON content negotiation을 명시합니다. 실제 서버의 기본 Actuator 미디어 타입과 검사기의 `application/json` 기대가 달라 정상 상태도 실패했던 문제를 수정했습니다. 읽기 기반 Tunnel 준비 대기와 실패 진단은 Infra workflow에 둡니다.

제한된 공개 준비 검사는 Server의 `scripts/verify-public-health.py` 한 곳에서 계약·오류 분류·시간 한도를 구현합니다. Infra 재사용 workflow는 180초 전체 한도와 연속 정상 3회를 지정하며 별도 계약 import/retry 루프를 두지 않습니다. Server main CI와 실제 수동 운영 배포의 성공은 별도로 확인합니다.

쌍 배포 caller의 packages:write와 id-token:write는 재사용 workflow를 위한 상한입니다. publish만 packages:write, home·backup은 packages:read이며 WIF가 필요한 backup만 id-token:write를 사용합니다. Source production의 HOME_ACCESS_CLIENT_ID/SECRET은 보호 origin 사전 검사에 쓰며 기존 SSH 자격과 구분합니다. 운영 비밀은 이미지·artifact에 포함하지 않습니다.

GHCR·비공개 Cloud Run 쌍 배포의 두 pin은 `efc751f0cb72837261f913274a27a621e72b094d`입니다. [Infra main CI](https://github.com/JECT-Study/Ziper-5th-Infra/actions/runs/42278011398)의 성공을 확인한 실제 병합 SHA이며 검사기·KST 보류·실행별 검증 범위는 Infra ADR 0007·0008과 PR #1을 따릅니다. 기존 pin 설명은 그 변경 시점의 이력입니다.
