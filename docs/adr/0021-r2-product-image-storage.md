# 0021 상품 이미지 저장소로 Cloudflare R2 Standard 채택

- 상태: 채택 — 운영 버킷(`ziper-product-images-production`) 생성·Secret Manager `ziper-runtime-properties` v5 등록·서버 R2 어댑터(`R2ObjectStorageClient`) 구현 완료, Infra 버전 핀 갱신·운영 배포 및 연결 검증은 후속 작업
- 날짜: 2026-11-22
- 관계: [ADR 0017](0017-product-image-storage-and-thumbnail-policy.md)의 운영 저장소 선택을 부분 대체합니다.

## 배경

홈·보드·상세는 같은 WebP 썸네일을 반복해서 읽습니다. Supabase Free에서는 이미지 다운로드도 DB와 함수가 사용하는 조직의 대역폭 한도에 합산됩니다. 2026-11-22 운영 Supabase를 읽기 전용으로 확인했을 때 등록된 상품 카드와 파일 메타데이터는 각 0건이었습니다. 첫 상품 이미지 운영 배포 전에 저장소를 선택할 수 있는 시점입니다. [Supabase 대역폭](https://supabase.com/docs/guides/storage/serving/bandwidth)

## 결정

- 상품 이미지는 Cloudflare R2 Standard의 비공개 버킷에 저장합니다. 홈서버와 Cloud Run이 같은 운영 버킷을 사용합니다. WebP 변환, DB 작업 큐와 기존 `imageFileUrl` 응답 계약은 ADR 0017을 따릅니다.
- 버킷 이름은 `ziper-<용도>-<환경>` 형태로 지정합니다. 기존 Infra의 `ziper-` 접두어와 `production` 표기를 따라 운영 버킷 이름으로 **`ziper-product-images-production`**을 권장합니다. 프로비저닝 시 계정 내 이름 중복을 확인합니다. [R2 버킷 이름 규칙](https://developers.cloudflare.com/r2/buckets/create-buckets/)
- 버킷의 `r2.dev` 공개 접근을 끄고 공개 커스텀 도메인도 연결하지 않습니다. 서버 자격 증명은 해당 버킷의 객체 읽기·쓰기 권한으로 범위를 제한하고 [ADR 0013](0013-secret-manager-single-source.md)의 방식으로 공급합니다. [R2 공개 접근](https://developers.cloudflare.com/r2/buckets/public-buckets/) · [R2 토큰 권한](https://developers.cloudflare.com/r2/api/tokens/)
- 초기에는 서버가 카드 소유권을 확인한 뒤 R2 S3 API 도메인의 15분 유효 GET 서명 URL을 발급합니다. 사용자 ID 기준으로 URL을 발급하는 API 요청을 제한하고, 한 응답에서 발급하는 URL 수는 목록의 조회 범위로 제한합니다. 앱은 유효한 링크를 재사용합니다. 발급된 서명 URL은 만료 전까지 재사용될 수 있으므로 전체 서명 URL을 로그에 기록하지 않습니다. [R2 서명 URL](https://developers.cloudflare.com/r2/api/s3/presigned-urls/)

## 대안

- Supabase Storage는 DB와 같은 공급자에서 운영할 수 있습니다. 상품 썸네일 조회가 늘면 이미지 다운로드와 DB 사용량이 동일한 대역폭 한도를 소비합니다.
- 이미지 조회를 Worker로 통과시키면, 공개 버킷 없이 Worker의 R2 바인딩을 사용해 인증과 요청 제한을 실제 객체 조회 전에 적용할 수 있습니다. R2 읽기에 한도를 강제해야 한다면 직접 서명 URL 발급을 종료하고 이 경로를 사용합니다. 사용자별 제한과 별도로 Durable Object 같은 일관된 공용 카운터에서 조회 허용량을 먼저 차감합니다. 소진 시 R2에 접근하기 전에 차단하고 앱은 대체 아이콘을 표시합니다. 일반 Worker Rate Limiting 바인딩은 위치별 근삿값이므로 전체 한도를 집행하는 카운터로 사용하기 어렵습니다. [Worker R2 바인딩](https://developers.cloudflare.com/r2/api/workers/) · [Worker 요청 제한 동작](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)

## 영향과 한계

R2 Standard의 인터넷 전송은 무료이고 `GetObject`는 Class B로 계산됩니다. Standard의 월 무료 한도는 저장 10 GB-month, Class A 100만 회, Class B 1,000만 회이며 같은 계정의 다른 사용량도 고려합니다. 직접 서명 URL의 반복 다운로드는 앱의 발급 API를 거치지 않습니다. 발급 API의 요청 제한은 새 링크 발급을 줄입니다. 유효한 링크의 재사용은 R2 S3 도메인으로 직접 들어오므로 앱 도메인의 WAF와 별개입니다. [R2 요금](https://developers.cloudflare.com/r2/pricing/)

운영 연결 후 대시보드에서 버킷의 읽기 요청·저장량 추세와 계정의 청구 사용량을 확인합니다. 운영 배포 전 목표 예산보다 낮은 금액에 계정 예산 알림을 설정하고, 정상 트래픽을 관측한 뒤 임계치를 조정합니다. 예산 알림은 메일을 보내며 제품 사용은 계속됩니다. 예상치를 넘는 읽기가 반복되거나 월간 R2 조회 한도가 필요해지면 Worker 경유를 재검토합니다. 조회를 차단해도 저장량·쓰기나 Worker 자체 비용에는 각각의 한도가 필요합니다. [R2 사용량](https://developers.cloudflare.com/r2/platform/metrics-analytics/) · [Cloudflare 예산 알림](https://developers.cloudflare.com/billing/manage/budget-alerts/)
