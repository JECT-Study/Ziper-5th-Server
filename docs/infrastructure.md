# 향후 인프라 운영 계약

이 문서는 설계만 다룹니다. Worker, Durable Object, GCP 리소스, IAM과 Secret Manager 로더는 아직 구현하거나 적용하지 않았습니다. 결정 근거는 [ADR 0002](adr/0002-infrastructure-contract.md)입니다. 실제 배포 전에 플랜과 최신 한도를 다시 확인합니다.

## 장애 전환

Workers Free의 SQLite Durable Object를 권위 있는 회로 상태 저장소로 사용합니다. 상태는 `CLOSED`·`OPEN`·`HALF_OPEN`입니다. KV는 설정 전용입니다. KV 변경은 다른 위치에서 60초 이상 늦게 보일 수 있고 원자적 카운터를 제공하지 않으므로 장애 횟수나 전이의 정합성을 맡기지 않습니다.

업무 4xx는 장애율에서 제외합니다. 결과가 불명확한 POST 등 안전하지 않은 쓰기를 다른 origin으로 자동 재전송하지 않습니다. 두 origin이 같은 DB를 쓰면 DB 장애는 양쪽에 영향을 줍니다. 앱은 무상태로 유지하고 같은 이미지·설정 버전·JWT 발급자·대상·키를 사용합니다. Cloud Run 연동 시 플랫폼 인증에는 `X-Serverless-Authorization`을 검토해 클라이언트의 `Authorization`을 보존합니다.

측정 창, 최소 표본 수, 실패 임계치, probe 간격과 회복 조건, HALF_OPEN 동시성, 한도 소진 시 응답과 복구는 아직 정하지 않았습니다. 이 값을 확정하기 전에는 자동 장애 전환이 운영 가능하다고 간주하지 않습니다.

Durable Objects Free 한도는 일일 100,000 요청, 13,000 GB-s, SQLite 일일 5,000,000 행 읽기·100,000 행 쓰기, 계정당 저장 공간 5GB입니다. Free 한도 초과는 자동 유료 전환이 아니라 작업 실패를 일으키며 Paid는 사용량 요금이 있습니다. 실제 계정 플랜은 확인되지 않았습니다. Workers·네트워크·origin·DB 등의 비용을 합친 전체 무료 운영은 보장하지 않습니다. [공식 가격](https://developers.cloudflare.com/durable-objects/platform/pricing/), [KV 동작](https://developers.cloudflare.com/kv/concepts/how-kv-works/)

## 비밀 관리

개발자는 개인 ADC를 사용합니다. 공유 서비스 계정 JSON을 파일로 나누지 않습니다. 기존 secret에는 개발자 accessor와 versionManager, 런타임 accessor만 부여하는 계약이며 최초 생성은 별도 관리자 작업입니다. 현재 IAM을 적용했다는 의미는 아닙니다.

향후 사용자 정의 `ConfigDataLoader`가 고정 버전의 JSON 번들을 부팅 시 한 번 읽어 속성으로 풉니다. 표준 `sm@` 원문 문자열 가져오기를 JSON 펼침이나 속성별 캐시로 오해하지 않습니다. 핫 리로드 없이 두 origin을 같은 버전으로 재시작합니다. 실패·재시작·검증·수동 조회·재시도도 접근량에 포함합니다.

Secret Manager 무료 한도는 결제 계정 합산 월 활성 버전 6개와 접근 작업 10,000회입니다. enabled와 disabled 모두 활성 버전으로 계산하며 destroyed만 제외합니다. 정상 버전 5개와 교체 중 1개를 예산으로 삼고 교체 발행은 직렬화합니다. 검증 후 이전 버전을 destroy하면 즉시 롤백할 수 없으므로 교체 시 보존 기간과 비용·복구 절충을 검토합니다. 이 예산만으로 항상 비용이 0이라고 약속하지 않습니다. [공식 가격](https://cloud.google.com/secret-manager/pricing), [권장 운영 방식](https://docs.cloud.google.com/secret-manager/docs/best-practices)
