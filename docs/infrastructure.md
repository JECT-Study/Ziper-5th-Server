# 배포와 장애 전환 연결 계약

실제 인프라 코드·설정·정책과 운영 검증 증거는 [Ziper-5th-Infra](https://github.com/JECT-Study/Ziper-5th-Infra)가 소유합니다. 이 문서는 Server가 제공하고 소비하는 계약만 다룹니다. 과거의 인프라 초안은 [ADR 0004](adr/0004-deployment-and-failover.md)에 이력으로 남깁니다. 구현 유무나 운영 성공 여부는 초안이 아니라 Infra의 실제 코드와 실행별 증거로 판단합니다.

## 책임과 연결 위치

| 대상 | 기준 |
| --- | --- |
| 분 단위 health 표본·슬라이딩 윈도우·FSM | [Infra ADR 0001](https://github.com/JECT-Study/Ziper-5th-Infra/blob/main/docs/adr/0001-home-circuit-breaker.md) |
| 03–06시 예약 운영과 E2E 판정 | [Infra ADR 0002](https://github.com/JECT-Study/Ziper-5th-Infra/blob/main/docs/adr/0002-scheduled-operation-and-e2e.md) |
| GHCR digest pull·이미지 식별·되돌리기 | [Infra ADR 0007](https://github.com/JECT-Study/Ziper-5th-Infra/blob/main/docs/adr/0007-ghcr-home-delivery.md) |
| 비공개 Cloud Run·WIF·비밀 파일·복구 lease | [Infra ADR 0008](https://github.com/JECT-Study/Ziper-5th-Infra/blob/main/docs/adr/0008-private-cloud-run-and-production-e2e.md) |
| 배포 준비·비밀 등록·운영 절차 | [Infra 운영 문서](https://github.com/JECT-Study/Ziper-5th-Infra/blob/main/docs/operations.md) |
| 실제 통과·실패·미실행 범위 | [Infra PR #1](https://github.com/JECT-Study/Ziper-5th-Infra/pull/1) |

이 저장소의 `.github/workflows/deploy-production.yml`은 [ADR 0007](adr/0007-manual-production-entrypoint.md)의 수동 진입점입니다. `uses`와 `infra_sha`를 같은 실제 Infra main SHA로 고정하고 두 저장소의 해당 main CI 성공을 요구합니다. 배포는 다음 명령으로 main에서 수동 시작하며 과거 SHA를 선택하는 입력은 구현하지 않았습니다.

```sh
gh workflow run deploy-production.yml --repo JECT-Study/Ziper-5th-Server --ref main
```

## 앱이 보장하는 계약

로컬 Compose는 `SENTRY_PROFILE_SESSION_SAMPLE_RATE`를 앱으로 전달하며 기본 0.0입니다. 운영에서 profiling을 선택하면 해당 런타임 환경에도 명시해야 합니다. 기존 PostgreSQL URL·Flyway 자격은 바꾸지 않습니다. 성능 계측과 실제 수신 검증은 [운영](operations.md#쿼리와-서비스-성능-추적)을 따릅니다.

앱은 무상태이며 두 origin에서 같은 검증 이미지와 JWT 발급자·대상·키를 사용합니다. `/livez`는 DB를 제외한 생존 상태를, `/readyz`는 DB를 포함한 요청 준비 상태를 반환합니다. 공개 상태 계약과 180초 제한 준비 검사는 [운영](operations.md#상태-점검)을 따릅니다. 사용자 요청 실패를 회로 표본으로 합산하거나 결과가 불명확한 쓰기를 다른 origin에 재전송하지 않는 정책은 Infra에서 구현합니다.

민감 설정은 이미지와 artifact에 포함하지 않습니다. 홈은 저장소 밖의 private runtime 파일을, Cloud Run은 전용 runtime SA가 읽는 고정 Secret Manager 파일 마운트를 사용합니다. 사용자 정의 Secret Manager `ConfigDataLoader`는 구현하지 않았습니다. CI는 WIF를 사용하며 Worker의 전용 Cloud Run invoker 키와 구분합니다. 운영 DB와 스키마의 책임은 [DB 문서](database.md)를 따릅니다. 공통 DB 장애는 origin 전환으로 해결되지 않습니다.

## 모니터링과 검증 범위

앱의 Sentry 설정·지표·공개 probe·프로젝트별 접근은 [운영 문서](operations.md)를 따릅니다. Discord 배포 알림, Worker의 회로 경로, 실제 두 origin의 readiness와 복구 결과는 Infra PR #1에서 각각 판정합니다. 배포 알림 수신을 Sentry 오류 알림 수신으로, 공개 HTTP 200을 Sentry Uptime 모니터 성공으로 간주하지 않습니다.

비용 정책과 native spend cap의 현재 상태는 Infra의 운영 문서와 증거를 따릅니다. min0/max1이나 무료 한도가 추가 과금 0원을 보장하지는 않습니다. 운영 결과를 로컬 테스트·계획·현재 설정만으로 완료 처리하지 않습니다.
