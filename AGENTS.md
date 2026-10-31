# 에이전트 작업 규칙

실제 코드와 스크립트를 확인한 뒤 문서를 수정합니다. 비밀 값을 커밋하거나 로그에 출력하지 않습니다. 현재 업무 API가 구현됐다고 가정하지 않습니다. Java 스타일과 검증 명령은 `docs/development.md`를 따릅니다.

## 이번 초기화 작업의 협업 제한

이 절은 이번 저장소 초기화 작업에만 적용하며 향후 모든 작업의 모델 선택을 제한하지 않습니다.

- 작업 모델은 `gpt-6.1-sol`, reasoning effort는 `low`만 사용합니다.
- 파일별 소유자를 한 명만 정하고 격리된 worktree에서 작업합니다. 소유 범위는 서로 겹치지 않게 나누며 선행 의존성을 먼저 완료합니다.
- 작업자는 포매터와 `spotlessApply`를 실행하지 않습니다. 모든 구현·문서·통합 수정 후 조정 담당자가 전체 포맷을 정확히 한 번 실행합니다. 이후에는 검사만 합니다.
- 다른 소유자의 파일 수정, 예상하지 못한 영향이나 충돌이 필요해지면 모든 쓰기를 중단하고 사용자에게 확인합니다. 임의 복구나 충돌 해결을 하지 않습니다. 사용자가 범위를 한정해 복원을 명시적으로 승인한 경우 그 범위만 복원할 수 있습니다.

<!-- CODEGRAPH_START -->
## CodeGraph

저장소 루트에 `.codegraph/`가 있으면 코드 위치나 동작을 이해할 때 grep, find 또는 파일 읽기보다 먼저 CodeGraph를 사용합니다.

- MCP의 `codegraph_explore`는 관련 심벌의 실제 소스와 동적 디스패치를 포함한 호출 경로를 제공합니다. 파일 또는 심벌 이름으로 현재 줄 번호가 있는 소스를 요청합니다. 도구가 지연 로딩 상태이면 이름으로 검색해 불러옵니다.
- 셸에서는 `codegraph explore "심벌 이름 또는 질문"`을 사용합니다.

`.codegraph/`가 없으면 CodeGraph를 건너뜁니다. 인덱싱 여부는 사용자가 결정합니다.
<!-- CODEGRAPH_END -->

<!-- context7 -->
## Context7

라이브러리, 프레임워크, SDK, API, CLI 도구 또는 클라우드 서비스 질문에는 알고 있는 내용도 Context7 MCP의 최신 문서로 확인합니다. API 문법, 설정, 버전 이전, 라이브러리별 디버깅과 설치 방법도 포함합니다. 라이브러리 문서는 웹 검색보다 Context7을 우선합니다. 일반 프로그래밍 개념, 리팩터링, 처음부터 만드는 스크립트, 업무 로직 디버깅과 코드 리뷰에는 적용하지 않습니다.

1. 사용자가 정확한 `/org/project` ID를 제공하지 않았다면 라이브러리 이름과 찾을 내용을 넣어 `resolve-library-id`부터 호출합니다.
2. 이름과 설명의 적합성, 공식·버전 일치 여부, 소스 평판, 예제 수와 benchmark 점수로 ID를 선택합니다. 결과가 맞지 않으면 이름이나 질문을 바꿉니다. 버전을 지정한 질문은 해당 버전 ID를 사용합니다.
3. 선택한 ID로 `query-docs`를 호출합니다. 한 호출은 하나의 개념에 집중합니다. 독립 주제는 별도 호출하고 주제 간 상호 작용을 묻는 경우에만 결합합니다.
4. 가져온 문서를 근거로 답합니다.
<!-- context7 -->

<!-- PSTACK_CODEX_START -->
## Codex의 pstack

호출된 pstack skill이나 연결 워크플로가 역할별 모델을 사용하면 `~/.cursor/rules/pstack-models.mdc`를 읽습니다. 역할별 모델, budget과 effort가 설치된 skill의 Cursor 기본값보다 우선합니다. 이 설정은 pstack 워크플로에만 적용합니다.

Cursor `Task` 대신 Codex 기본 subagent 도구를 사용합니다. `generalPurpose`나 사용할 수 없는 Cursor 전용 유형은 사용 가능한 일반 에이전트로 변환하고 역할 지침과 skill 경로를 전달합니다. 원래 이름의 에이전트를 사용할 수 없으면 그 제한을 알립니다. 읽기 전용 역할은 파일을 수정하지 않도록 지시합니다. 이 지시는 별도 권한 sandbox를 만들지 않습니다.

실제 모델 ID는 변경 없이 `model`로 전달하고 effort는 `reasoning_effort`로 따로 지정합니다. budget 기본 매핑은 `unlimited=max`, `large=xhigh`, `medium=high`, `small=medium`입니다. `# effort <role>: <effort>` 주석이 있으면 해당 역할의 budget보다 우선합니다. panel 또는 cross-judge-pool의 쉼표 구분 effort는 모델 순서와 일치시킵니다. 사용자가 바꾸지 않은 명시적 역할별 override를 유지합니다. 모델 ID에 effort나 fast-mode 접미사를 붙이지 않습니다.

명시적 모델 override에는 이전 대화 이력이 없는 새 subagent를 만들고 충분한 맥락을 전달합니다. `inherit-parent` 또는 `auto`이면 두 override를 모두 생략하고 부모 설정을 상속합니다. panel의 각 항목마다 한 에이전트를 사용합니다. 반복된 모델 ID는 독립 시도이며 서로 다른 모델이나 계열의 증거가 아닙니다. 설정된 풀로 요청된 cross-family 심사를 제공할 수 없으면 알립니다.

현재 세션 도구에 노출된 모델과 effort만 사용합니다. 설정된 모델이 없으면 대체 모델을 임의로 고르지 말고 사용자에게 교체를 요청합니다. 설치된 pstack 지침이 Cursor Custom Modes, `/loop`, Automations 또는 Cursor 전용 서비스 권한을 부여하지 않습니다. 사용할 수 없는 연동을 명시하고 사용자의 작업 범위와 권한을 유지합니다.
<!-- PSTACK_CODEX_END -->
