# 0007 수동 운영 배포 진입점과 응답 식별

- 상태: 채택 — 실행별 성공·실패·미검증은 Infra PR #1에 기록
- 날짜: 2026-11-06

## 배경

운영자는 main CI 성공 뒤 GitHub Actions에서 수동 배포를 실행하고 실제 Worker의 정상·오류 경로와 Discord 배포 알림을 확인하려 한다. 배포 본체와 예약 운영·검증 증거의 소유 저장소는 Ziper-5th-Infra다. ADR 0006의 초기 개인 SSH 부트스트랩은 아직 앱 기동까지 실행하지 않았다.

## 결정

Server에는 workflow_dispatch 진입점만 두고 공개 Infra의 고정 SHA workflow를 재사용한다. main 외 참조는 배포하지 않으며 source와 Infra의 성공한 main CI를 모두 요구한다. Source의 tested-release JAR와 Sentry source bundle을 재사용하고 새 JAR를 빌드하지 않는다. 초기에는 홈 단독 배포와 공개 readiness부터 검증하고 Cloud Run과 실제 장애 전환은 별도로 기록한다.

예약 시간, 접속 수신기, Discord 전송과 운영 E2E의 상세 계약은 [Infra ADR 0002](https://github.com/JECT-Study/Ziper-5th-Infra/blob/codex/home-circuit-breaker/docs/adr/0002-scheduled-operation-and-e2e.md)에 둔다. 사용자가 요청한 관리자 bypass를 허용하되 force push·브랜치 삭제 금지와 일반 PR 리뷰 정책은 유지한다. 최초 연결에서는 CI 통과 후 관리자 병합을 사용하며 이를 리뷰 승인으로 기록하지 않는다. Infra PR #1의 main 병합 SHA `974c6b74b0e0d52c2250e5a0dbfd1138151566e5`와 해당 main CI 성공을 확인해 두 pin에 반영했다.

기존 probe는 X-Ziper-Origin을 home/cloudrun/local로, X-Ziper-Release를 소문자 40자리 SHA 또는 local로만 노출한다. 이 값은 비밀·실제 인스턴스 주소를 포함하지 않는다. 응답 헤더만 신뢰해 배포를 완료 처리하지 않고 이미지 digest·제공업체 revision·실제 readiness와 대조한다. 업무 API는 추가하지 않는다.

서로 다른 Docker 저장소 간 이미지 동일성은 [Infra ADR 0005](https://github.com/JECT-Study/Ziper-5th-Infra/blob/main/docs/adr/0005-portable-image-identity.md)의 OCI config digest로 검증한다. 호스트 엔진 Id와 registry manifest digest는 같은 종류의 값으로 비교하지 않는다.

공개 readiness의 제한된 준비 시간 정책은 [Infra ADR 0006](https://github.com/JECT-Study/Ziper-5th-Infra/blob/b44518bcf31a9c6eba57765ac4f1c610cbb7099f/docs/adr/0006-bounded-public-readiness.md)에서 관리한다. Server의 기존 health CLI가 Accept 협상·계약 검사·일시적 오류 분류를 구현하고 Infra workflow가 180초 준비 시간과 연속 정상 3회를 지정한다. 성공한 Infra main CI의 실제 SHA `b44518bcf31a9c6eba57765ac4f1c610cbb7099f`로 두 참조를 함께 갱신한다. Server main CI와 실제 배포 결과는 별도로 검증한다. HTTP 530의 과거 원인을 기동 지연으로 확정하지 않는다.

## 대안

- Server에 배포 본체 복제: 정책과 구현의 중복을 피하려고 제외한다.
- 개인 SSH 키를 CI에 복사: 전용 키와 제한된 수신기를 사용한다.
- merge 시 자동 배포: 수동 운영이라는 사용자 선택에 따라 제외한다.
- 로컬 통과를 운영 성공으로 기록: 실제 HTTP·Worker 분기·Discord 수신 증거가 없으므로 제외한다.

## 영향과 한계

source와 Infra의 main 병합·CI 및 실제 수동 보류 실행과 Discord 보류 수신을 확인했다. 실제 앱 배포·Worker·Cloud Run·복구 FSM은 별도로 검증한다. 초기 archive 수신 경로와 1MiB 진단 이력은 Infra ADR 0004·0005에 남기고, 현재 전달은 Infra ADR 0007의 GHCR digest pull을 따른다. GCP·Cloudflare 접속 자격·Discord Secret의 존재와 실제 동작은 별개로 확인한다. 운영 데이터 마이그레이션과 이미지 롤백의 호환성은 실행 전에 검토한다.

GHCR 쌍 배포는 [Infra PR #8](https://github.com/JECT-Study/Ziper-5th-Infra/pull/8)의 ADR 0007·0008을 따른다. caller에 packages:write와 id-token:write를 허용하고 called workflow의 publish·backup·home job이 필요한 범위로 다시 제한한다. CI용 장기 GCP 키 없이 main·production·저장소 ID로 한정한 WIF를 사용한다. 앱 runtime은 02:30–06:00에 보류하되 이미지 발행·미러 준비는 가능하다. 실제 동작은 새 Infra main SHA를 고정하고 운영 실행 증거로 검증한다.

WIF는 저장소의 불변 owner/repository ID가 포함된 정확한 production subject와 main ref를 요구한다. 알려진 구형 subject만 검토된 bootstrap으로 이행하고 다른 trust drift는 중단한다. 복구 lease가 있으면 공통 잠금 아래 배포를 거부하는 정책과 독립 timer의 검증 절차는 Infra ADR 0008에서 관리한다.

예약 종료 뒤 재기동 정책은 [Infra ADR 0009](https://github.com/JECT-Study/Ziper-5th-Infra/blob/bb929769d23b60ca2990812c6a2f80e22b444d53/docs/adr/0009-resume-after-scheduled-boot.md)를 따릅니다. Docker의 수동 stop과 daemon 재시작의 차이를 격리 검증하고 새 Source SHA의 운영 컨테이너에서 정책을 대조하며, 실제 다음 장비 부팅을 대신했다고 기록하지 않습니다.
