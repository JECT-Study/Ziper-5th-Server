# 배포와 장애 전환 계약

이 문서는 목표 배포 구조와 장애 전환 계약을 다룹니다. 첫 홈서버 배포는 [ADR 0006](adr/0006-initial-production-deployment.md)에 따라 별도로 진행합니다. Worker, Durable Object, GCP 리소스, 정식 배포 워크플로와 Secret Manager 로더는 아직 구현하거나 적용하지 않았습니다. 목표 구조의 결정 근거는 [ADR 0004](adr/0004-deployment-and-failover.md)이며 [ADR 0002](adr/0002-infrastructure-contract.md)를 대체합니다. 아래 값은 첫 운영값이며 실제 연동에서 검증한 뒤 조정합니다. 한도와 가격은 연동 직전에 다시 확인합니다.

## 구성

| 구성 요소 | 역할 |
| --- | --- |
| Cloudflare Worker | API 호스트명의 route. 회로 상태에 따라 origin 선택. route 실패 모드는 fail open |
| Durable Object | SQLite 기반 단일 객체. 회로 상태·수동 전환의 유일한 기준. alarm으로 health check |
| 홈서버 | 주 origin. Docker Compose로 release 이미지 실행. 공유기 포트를 열지 않고 Cloudflare Tunnel로만 노출 |
| Cloud Run | 보조 origin. 최소 인스턴스 0, 요청 기반 과금. 회로가 열릴 때만 사용자 요청 처리 |
| Supabase PostgreSQL | 두 origin의 공용 DB |
| GHCR | 공개 release 이미지 저장소. 태그는 전체 Git SHA |

환경은 운영 하나와 로컬만 둡니다. 두 origin은 같은 이미지, `prod` 프로필, 설정 번들 버전, JWT 발급자·대상·키를 사용합니다. 앱은 무상태를 유지합니다.

API 호스트명의 DNS는 홈서버 Tunnel을 가리킵니다. Worker는 `CLOSED`일 때 같은 호스트명으로 요청을 전달해 Tunnel 뒤의 홈서버에 닿습니다. Workers 일일 한도를 넘으면 fail open으로 Worker를 건너뛰고 홈서버로 직접 갑니다. Cloud Run은 공개 호출을 막고 Worker가 `X-Serverless-Authorization`에 ID 토큰을 넣어 호출합니다. 클라이언트의 `Authorization`은 그대로 전달합니다.

## 회로 상태

| 상태 | 사용자 요청 | 홈서버 확인 |
| --- | --- | --- |
| `CLOSED` | 홈서버 | 10초마다 `/readyz` |
| `OPEN` | Cloud Run | 대기 시간이 끝날 때까지 하지 않음 |
| `HALF_OPEN` | Cloud Run | 10초마다 `/readyz` |

| 항목 | 값 |
| --- | --- |
| health check | Durable Object alarm이 홈서버 `/readyz`에 GET. 200만 성공. 제한 시간 3초 |
| 판단 기준 | 연속 결과만 사용. 최소 표본 3회 |
| `CLOSED` → `OPEN` | 홈서버 연속 3회 실패(약 30초)이고 Cloud Run `/readyz`가 200 |
| Cloud Run 깨우기 | 홈서버 첫 실패 때 Cloud Run `/readyz` 호출. cold start를 고려해 제한 시간 30초 |
| 양쪽 모두 실패 | 전환하지 않고 `CLOSED` 유지. 공용 DB 장애일 가능성이 큼 |
| `OPEN` 대기 시간 | 60초에서 시작해 `HALF_OPEN`에서 다시 실패할 때마다 2배, 최대 10분. `CLOSED`가 되면 초기화 |
| `HALF_OPEN` → `CLOSED` | 홈서버 연속 3회 성공 |
| `HALF_OPEN` → `OPEN` | 홈서버 1회 실패 |
| `HALF_OPEN` 동시성 | alarm이 직렬로 실행하는 health check 1개. 사용자 요청은 홈서버로 보내지 않음 |
| Worker 상태 캐시 | isolate 메모리에 최대 10초. 영속 저장이 아니며 기준은 Durable Object |

`/livez`가 아닌 `/readyz`를 쓰는 이유는 홈서버와 DB 사이의 연결 문제도 감지하기 위해서입니다. 공용 DB 장애처럼 두 origin이 함께 실패하면 전환해도 소용이 없으므로 보조 origin의 준비 상태를 전환 조건에 넣었습니다.

회로 판단은 health check만 사용하므로 업무 4xx와 5xx는 회로에 영향을 주지 않습니다. Worker가 origin에 연결하지 못하면 GET·HEAD·OPTIONS만 다른 origin으로 한 번 다시 보냅니다. 결과가 불명확한 POST 등 안전하지 않은 요청은 다시 보내지 않고 `Retry-After: 30`과 함께 503을 반환합니다.

## 수동 전환

| 경로 | 용도 | 동작 |
| --- | --- | --- |
| Durable Object 수동 전환 | 배포·점검 | `auto`·`home`·`cloudrun`. `auto` 외 값은 최대 15분 뒤 자동으로 `auto`로 돌아감. Worker 관리 경로와 관리자 토큰으로만 변경 |
| Worker 환경 변수 `ORIGIN_OVERRIDE` | Durable Object를 쓸 수 없을 때 | `home`·`cloudrun`. Worker 재배포로 적용하며 Durable Object보다 우선 |

## 무료 한도와 소진 시 동작

| 한도 (Free, 00:00 UTC 초기화) | 예상 사용량 | 소진 시 동작 |
| --- | --- | --- |
| Workers 요청 100,000/일 | API 요청 수와 같음 | fail open으로 홈서버에 직접 연결. 장애 전환 없음 |
| Durable Objects 요청 100,000/일 (alarm 포함) | alarm 8,640 + isolate당 최대 8,640 | Worker는 마지막으로 캐시한 상태, 없으면 홈서버 사용. 필요하면 `ORIGIN_OVERRIDE` |
| Durable Objects 실행 시간 13,000 GB-s/일 | 객체 1개가 하루 종일 실행돼도 10,800 GB-s | 위와 같음 |
| SQLite 쓰기 100,000행/일 | health check마다 1행, 약 8,640행 | 위와 같음 |

한도를 넘은 작업은 자동 유료 전환 없이 실패합니다. 소진되면 다음 00:00 UTC(09:00 KST)까지 자동 전환이 멈춥니다. Worker 요청이 일일 한도에 자주 가까워지면 Workers Paid 전환을 검토합니다. Cloud Run, 네트워크, DB를 합친 전체 무료 운영은 보장하지 않습니다. Cloud Run 무료 사용량과 리전별 적용 여부는 아직 확인하지 않았습니다. [Durable Objects 가격](https://developers.cloudflare.com/durable-objects/platform/pricing/), [Workers 한도](https://developers.cloudflare.com/workers/platform/limits/)

## 배포

배포는 수동 실행 워크플로 하나로 합니다. 입력 `version`은 `main`에 포함된 커밋의 전체 Git SHA이고, 실행 이름에 이 SHA를 표시합니다. 동시 실행은 하나로 제한하며 진행 중인 배포를 취소하지 않습니다.

```sh
gh workflow run deploy.yml -f version=<git-sha>
```

1. SHA가 `main`에 포함되고 해당 커밋의 CI가 성공했는지 확인합니다.
2. GHCR에 SHA 태그가 없으면 빌드·Sentry 소스 업로드·`verify-release-artifact.py` 검증을 거쳐 게시합니다. 있으면 다시 빌드하지 않습니다. 기존 `Upload Sentry source context` 워크플로의 단계를 재사용합니다.
3. Cloud Run에 새 리비전을 배포하고 `/readyz` 200을 확인합니다. 실패하면 이전 리비전을 유지하고 중단합니다.
4. Durable Object 수동 전환을 `cloudrun`으로 바꿉니다.
5. 홈서버에서 배포 스크립트가 이미지를 받아 컨테이너를 교체하고 3분 안에 `/readyz` 200을 기다립니다. 실패하면 직전 이미지로 되돌리고 워크플로를 실패 처리합니다.
6. 수동 전환을 `auto`로 되돌립니다.

롤백은 이전 SHA로 같은 명령을 실행합니다. 이전 배포 SHA는 `gh run list --workflow deploy.yml --status success`로 찾습니다. 이미지와 Sentry 릴리스를 그대로 다시 사용합니다. 롤백할 수 있는 조건은 다음과 같습니다.

- GHCR에 해당 SHA 이미지가 남아 있어야 합니다. 최근 20개 이미지를 보존합니다.
- 앱은 부팅 시 Flyway를 실행합니다. 이전 버전이 새 스키마에서 동작하도록 마이그레이션을 직전 버전과 호환되게 작성합니다. 컬럼 삭제처럼 호환을 깨는 변경은 이후 배포로 나눕니다.
- 비밀 번들 버전은 롤백과 별개로 유지합니다.

권한은 다음과 같습니다.
- GitHub Environment `production`이 홈서버 SSH 키, Cloudflare Access 서비스 토큰, Worker 관리자 토큰을 보관합니다.
- GCP는 GitHub OIDC와 Workload Identity Federation으로 접근하며 키 파일을 두지 않습니다.
- 홈서버 SSH는 Cloudflare Tunnel과 Access 서비스 토큰을 거칩니다. 배포 키는 `authorized_keys`의 강제 명령으로 배포 스크립트만 실행합니다.
- self-hosted runner는 쓰지 않습니다. GitHub는 공개 저장소에서 self-hosted runner를 거의 쓰지 말라고 안내합니다. [GitHub 보안 안내](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions)

Cloud Run은 공개 GHCR 이미지를 바로 배포할 수 있습니다. 이미지를 받지 못하는 일이 반복되면 Artifact Registry 원격 저장소를 둡니다. [Cloud Run 배포](https://docs.cloud.google.com/run/docs/deploying)

## 비밀 관리

개발자는 개인 ADC를 사용합니다. 공유 서비스 계정 JSON을 파일로 나누지 않습니다. 기존 secret에는 개발자 accessor와 versionManager, 런타임 accessor만 부여하는 계약이며 최초 생성은 별도 관리자 작업입니다. 현재 IAM을 적용했다는 의미는 아닙니다.

향후 사용자 정의 `ConfigDataLoader`가 고정 버전의 JSON 번들을 부팅 시 한 번 읽어 속성으로 풉니다. 표준 `sm@` 원문 문자열 가져오기를 JSON 펼침이나 속성별 캐시로 오해하지 않습니다. 핫 리로드 없이 두 origin을 같은 버전으로 재시작합니다. 실패·재시작·검증·수동 조회·재시도와 Cloud Run cold start도 접근량에 포함합니다.

| 실행 위치 | 자격 증명 |
| --- | --- |
| Cloud Run | 연결된 런타임 서비스 계정. 키 파일 없음 |
| 홈서버 | 번들 accessor 권한만 가진 전용 서비스 계정 키. 저장소 밖에 권한 `0600`으로 보관하고 수동 교체 |
| Worker | `wrangler secret`으로 관리자 토큰과 Cloud Run 호출용 서비스 계정 키(`run.invoker`만) 보관 |

Secret Manager 무료 한도는 결제 계정 합산 월 활성 버전 6개와 접근 작업 10,000회입니다. enabled와 disabled 모두 활성 버전으로 계산하며 destroyed만 제외합니다. 정상 버전 5개와 교체 중 1개를 예산으로 삼고 교체 발행은 직렬화합니다. 검증 후 이전 버전을 destroy하면 즉시 롤백할 수 없으므로 교체 시 보존 기간과 비용·복구 절충을 검토합니다. 이 예산만으로 항상 비용이 0이라고 약속하지 않습니다. [공식 가격](https://cloud.google.com/secret-manager/pricing), [권장 운영 방식](https://docs.cloud.google.com/secret-manager/docs/best-practices)

## 모니터링 연결 경계

로컬·홈서버 앱의 Sentry 지표 수집 설정과 Discord 연결 절차는 [운영 문서](operations.md#애플리케이션-지표)를 따릅니다. Cloud Run 지표 전송, 회로 전이 알림과 공개 URL의 장애·복구 검증은 아직 구현하지 않았습니다. 모니터링을 위해 Cloud Run을 상시 실행하거나 깨우지 않습니다.

## 연동 PR에서 정할 것

- Cloudflare에 등록할 도메인과 API 호스트명
- Cloud Run과 Supabase 리전. 같은 리전을 권장하며 기본안은 서울 `asia-northeast3`
- 회로 전이 알림 채널
- GCP 예산 알림 금액
- 위 운영값을 실제 장애 주입으로 검증한 결과
