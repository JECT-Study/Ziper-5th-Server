# 0004 홈서버 우선 배포와 Cloud Run 장애 전환

- 상태: 대체됨 — 수동 진입점은 [0007](0007-manual-production-entrypoint.md), 상세 운영 정책은 [인프라 연결 계약](../infrastructure.md)이 연결한 Infra ADR 0001·0002·0007·0008을 따른다. 아래 본문은 과거 설계이며 현재 구현·운영 성공을 뜻하지 않는다.
- 날짜: 2026-11-01

## 배경

[ADR 0002](0002-infrastructure-contract.md)는 Worker의 Durable Object가 두 origin의 회로 상태를 소유한다고만 정했습니다. origin의 정체, 배포 환경과 절차, 회로 운영값, 무료 한도 소진 시 동작은 비어 있었습니다. 사이드 프로젝트이므로 비용과 운영 부담을 낮추는 것이 안정성보다 우선합니다. Cloudflare는 Workers Free 플랜을 전제로 합니다.

## 결정

주 origin은 홈서버, 보조 origin은 Cloud Run입니다. 회로가 `CLOSED`이면 홈서버로, `OPEN`·`HALF_OPEN`이면 Cloud Run으로 요청을 보냅니다. 회로 판단은 Durable Object의 alarm이 홈서버 `/readyz`를 확인한 결과만 사용하고 사용자 요청의 성공·실패는 세지 않습니다. 보조 origin이 준비되지 않았으면 전환하지 않습니다. 운영값은 [인프라 계획](../infrastructure.md)에 둡니다.

KV는 사용하지 않습니다. 운영값은 Worker 환경 변수, 수동 전환은 Durable Object에 저장하고 Durable Object를 쓸 수 없을 때만 Worker 환경 변수로 강제합니다. API 호스트명의 원래 origin은 Cloudflare Tunnel 뒤의 홈서버이고 Worker route는 fail open입니다. Workers 일일 한도를 넘으면 요청이 Worker를 건너뛰어 홈서버로 직접 갑니다.

배포 환경은 운영 하나와 로컬만 둡니다. 배포는 GitHub Actions의 수동 실행만 사용하며 입력은 `main`에 포함된 커밋의 전체 Git SHA입니다. 이미지는 GHCR에 SHA 태그로 한 번만 게시하고 두 origin이 같은 이미지를 실행합니다. 롤백은 이전 SHA로 같은 워크플로를 실행하는 것이며 이미 게시한 이미지를 다시 빌드하지 않습니다.

홈서버 배포는 GitHub 호스팅 runner가 Cloudflare Tunnel의 SSH 경로로 접속해 배포 스크립트만 실행하는 강제 명령 키를 사용합니다. GCP 배포는 GitHub OIDC와 Workload Identity Federation을 사용합니다. 비밀 번들은 ADR 0002의 고정 버전 JSON 번들 계약을 유지합니다. Cloud Run은 연결된 서비스 계정으로, 홈서버는 번들 접근 권한만 가진 전용 런타임 서비스 계정 키로 읽습니다.

## 대안

- **origin 하나로 시작**: 가장 단순하지만 홈서버 장애가 곧 서비스 중단이 되므로 채택하지 않았습니다.
- **Cloud Run 두 리전**: 운영은 쉽지만 상시 비용이 홈서버보다 크고 공용 DB 장애는 똑같이 막지 못합니다.
- **dev·prod 분리**: 안전하지만 사이드 프로젝트에서는 환경 관리와 비밀 버전 예산 부담이 커서 로컬과 CI 검증으로 대신합니다.
- **사용자 요청 실패율로 회로 판단**: 요청마다 Durable Object를 호출해 Free 한도를 빠르게 소모하고 업무 오류를 걸러내야 하므로 채택하지 않았습니다.
- **KV에 설정과 수동 전환 저장**: 변경 전파가 60초 이상 늦고 읽기도 일일 한도에 포함되어 제외했습니다.
- **self-hosted runner로 홈서버 배포**: GitHub는 공개 저장소에서 self-hosted runner를 거의 쓰지 말라고 안내합니다. 포크 PR이 runner 환경을 오염시킬 수 있어 제외했습니다.
- **배포 실패 시 자동 롤백 전체 적용**: 홈서버 컨테이너 교체 실패만 직전 이미지로 되돌리고, 버전 롤백은 사람이 SHA를 지정합니다.

## 영향과 한계

- 공용 DB 장애는 두 origin 모두에 영향을 줍니다. 이때 회로는 전환하지 않습니다.
- 회로 상태는 Worker isolate가 최대 10초 캐시하므로 전환이 그만큼 늦게 반영될 수 있습니다. 권위 있는 상태는 Durable Object에만 있습니다.
- 전환이 감지되기까지 약 30초, Cloud Run cold start까지 더해 최대 1분가량 요청이 실패할 수 있습니다.
- 일일 한도를 소진하면 다음 00:00 UTC(09:00 KST)까지 자동 전환이 멈춥니다.
- 운영 환경이 하나이므로 배포 전 실제 인프라 검증 단계가 없습니다.
- 앱이 부팅 시 Flyway를 실행하므로 마이그레이션은 직전 버전과 호환되게 작성해야 롤백할 수 있습니다.
- Cloud Run 호출용 서비스 계정 키와 홈서버 런타임 키는 수명이 긴 비밀입니다. 권한을 하나로 제한하고 수동 교체합니다.
- 한도와 가격은 2026-11-01 공식 문서 기준이며 연동 직전에 다시 확인합니다.
