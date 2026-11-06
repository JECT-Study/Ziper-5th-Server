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

공개 readiness의 제한된 준비 시간 정책은 Infra 저장소의 `docs/adr/0006-bounded-public-readiness.md`에서 관리한다. Server의 기존 health CLI가 Accept 협상·계약 검사·일시적 오류 분류를 구현하고 Infra workflow가 180초 준비 시간과 연속 정상 3회를 지정한다. 두 저장소 CI와 고정 참조 갱신 후 실제 배포 결과를 별도로 검증한다. HTTP 530의 과거 원인을 기동 지연으로 확정하지 않는다.

## 대안

- Server에 배포 본체 복제: 정책과 구현의 중복을 피하려고 제외한다.
- 개인 SSH 키를 CI에 복사: 전용 키와 제한된 수신기를 사용한다.
- merge 시 자동 배포: 수동 운영이라는 사용자 선택에 따라 제외한다.
- 로컬 통과를 운영 성공으로 기록: 실제 HTTP·Worker 분기·Discord 수신 증거가 없으므로 제외한다.

## 영향과 한계

source와 Infra의 main 병합·CI 및 실제 수동 보류 실행과 Discord 보류 수신을 확인했다. 실제 앱 배포·Worker·Cloud Run·복구 FSM은 별도로 검증한다. 수신 경로 수정은 Infra ADR 0004를 따르며 release 전 1MiB 바이너리 검증을 추가했다. GCP·Cloudflare 접속 자격·Discord Secret의 존재와 실제 동작은 별개로 확인한다. 운영 데이터 마이그레이션과 이미지 롤백의 호환성은 실행 전에 검토한다.
