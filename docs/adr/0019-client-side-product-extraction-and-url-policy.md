# 0019 클라이언트 격리 WebView 상품 추출, URL 정규화 및 링크 상태 경계

- 상태: 채택 — 정책 및 API·스키마 경계 확정; API·마이그레이션 구현(`JEC-53`, `JEC-54`, `JEC-90`)과 주기적 동기화 워커(`JEC-9`)는 후속 작업
- 날짜: 2026-11-21

## 배경

1차 지원 쇼핑몰(무신사·에이블리·지그재그)의 상품 링크를 저장할 때 초기 PRD는 서버 `POST /api/v1/products/lookup` API가 쇼핑몰 상품 페이지 HTML을 직접 요청해 Open Graph 메타데이터와 리다이렉트 최종 URL을 추출하는 구조를 전제했다. 그러나 2026-11-21 국내 가정용 IP 기준 `curl` 실측 결과, 서버 측 HTML 크롤링은 구조적으로 지속 불가능함이 확인되었다.

| 쇼핑몰 | 상품 페이지 HTML 실측 | 비고 |
| --- | --- | --- |
| 무신사 | `403 Cloudflare` | iOS Safari·링크 미리보기 봇·`curl` 포함 모든 User-Agent 차단 |
| 에이블리 | `403` 자체 캡차 | Slackbot User-Agent만 통과 |
| 지그재그 | `200` (봇 User-Agent에는 `404`) | `product:price:amount` 메타 태그 부재, 가격은 페이지 내 JSON |

또한 상품 카드에 "품절 / 판매 종료" 배지를 표시하고 재발견 큐에서 접근 불가 상품을 제외하려면 링크 상태를 추적해야 하는데, 기존 `V6` 스키마의 `product_cards.is_link_valid` boolean([ADR 0015](0015-product-card-and-rediscovery-schema.md))만으로는 서버 요청 차단(`403`/캡차)에 의한 "확인 불가"와 실제 "품절·판매 종료"를 구분할 수 없고, 마지막 확인 시점과 출처도 표현할 수 없다.

## 결정

1. **서버 HTML 크롤링 폐기 및 클라이언트 격리 WebView 추출(`ziper-mobile`) · 수기 입력(`ziper-web`)**:
   - 서버는 외부 쇼핑몰 상품 페이지 HTML을 요청하지 않는다. 타 서비스 봇 User-Agent 위장, 서버 측 헤드리스 브라우저, 프록시 우회도 사용하지 않으며, 기존 `POST /api/v1/products/lookup` 설계는 폐기한다.
   - 1차 지원몰(무신사·에이블리·지그재그) 자동 추출은 모바일 앱(`ziper-mobile`)의 격리된 오프스크린 WebView가 수행한다. 단축·추적 URL 리다이렉트도 앱 추출 WebView가 따라가 원본(`sharedUrl`)과 최종 URL(`finalUrl`)을 서버에 전달한다.
   - 브라우저 단독 `ziper-web`(데스크톱·모바일 웹)은 교차 출처(CORS) 제약으로 타 도메인 상품 페이지를 읽을 수 없으므로 수기 입력(`MANUAL`)으로 저장한다.
2. **인증 필수 `extraction-rules` 및 외부 요청 없는 `url-check` API (`JEC-53`)**:
   - **`GET /api/v1/products/extraction-rules`** (인증 필수, `ETag`/`If-None-Match` → `304 Not Modified`): 앱 배포 없이 쇼핑몰 마크업·CDN 변경에 대응할 수 있도록 버전, 1차 지원몰별 `hosts`, `canonical` 정규화 패턴/템플릿, 추출 `fields`(`title`, `price`, `image`, `availability`), `imageHosts`, `imageStrategy`(`SERVER_MIRROR` | `CLIENT_UPLOAD`, [ADR 0017](0017-product-image-storage-and-thumbnail-policy.md)), 그리고 미지원 몰용 `generic` 규칙을 내려준다.
   - **`POST /api/v1/products/url-check`** (인증 필수, **외부 HTTP 요청 0건**): 요청 URL(`finalUrl` 또는 직접 입력 URL)에 대해 구문·`http(s)` 스킴을 검증하고, 문자열 규칙(호스트 소문자화, `m.`/`www.` 통일, 추적 파라미터 `utm_*`·`fbclid` 등 제거, 지원몰 상품 ID 패턴 추출)으로 `canonicalUrl`, `shop`, `supported`를 판정한 뒤 `(user_id, canonical_url)` 기준 `existingCardId`를 반환한다.
   - **지원몰 판정 경계**: 호스트가 지원몰이어도 상품 ID 패턴(경로 시작부터 상품번호 끝까지의 경계를 포함하며, 퍼센트 디코딩 후 매칭)과 일치하지 않으면 `supported = false`, `shop = null`로 판정한다. 이때 미지원 몰 규칙을 적용한다.
   - **정규화 경계**: 지원몰은 `m.`/`www.`을 통일하고 추적 파라미터를 제거한다. 미지원 몰은 URL 의미를 바꾸지 않는 정규화(퍼센트 인코딩 16진수 대소문자 통일, 비예약 문자의 퍼센트 인코딩 해제, 추적 파라미터 제거, 남은 파라미터의 값·순서 유지)만 적용하며 스킴과 `www.`은 바꾸지 않는다(원본 이동 링크 보존).
   - **미결정(사용자 확인 중)**: 휴지통(`is_deleted = TRUE`) 카드를 `existingCardId`에 포함할지 여부는 정해지지 않았다. 확정되면 이 결정을 갱신한다.
   - 잘못된 URL 형식이나 비허용 스킴(`javascript:`, `file:` 등)만 `400 Bad Request`로 거부하며, 미지원 몰의 유효한 `http(s)` URL은 `supported = false`로 통과시켜 수기 입력을 허용한다.
3. **저장 단계 서버 검증 경계 (`JEC-54`)**:
   - 앱 추출 값(`metadataSource = 'CLIENT'`)도 직접 입력(`'MANUAL'`)과 동일한 비신뢰 입력으로 간주해 `POST /api/v1/products`에서 서버가 모든 필드를 검증한다(외부 HTTP 요청 없음).
   - `JEC-53`과 동일한 문자열 정규화 컴포넌트를 재사용해 `canonical_url`과 `shop`을 확정한다. **1차 지원몰의 `shop`은 서버 판정값만 채택**하고 요청의 `shop`은 무시하며, 미지원 몰만 사용자 입력을 허용한다. 중복 `(user_id, canonical_url)`은 `409 Conflict`로 차단한다.
   - 상품명은 HTML 태그 제거·공백 정규화·최대 200자, 가격은 `0` 이상 KRW 정수 및 상한, 메모는 최대 100자를 검증한다.
   - `imageUrl`은 `https` 스킴만 허용한다. 1차 지원몰은 해당 몰의 `imageHosts` 허용 목록에 속해야 하며 위반 시 `400`으로 거부한다. 미지원 몰은 저장을 허용하되 실제 다운로드 시 `JEC-76`([ADR 0017](0017-product-image-storage-and-thumbnail-policy.md))의 SSRF 방어(DNS 공인 IP 검증·hop/용량/MIME 제한)를 통과해야 미러링한다.
   - `product_cards` 후속 마이그레이션에서 `shop VARCHAR(100)`, `metadata_source IN ('CLIENT', 'MANUAL')`, `image_status IN ('NONE', 'PENDING', 'READY', 'FAILED')`, `price NOT NULL`, 카드 이미지 3종(`image_url`, `image_file_id`, `fallback_icon`) 중 최소 1종 필수 `CHECK` 제약을 적용한다.
4. **공유 상품 링크 상태 모델 및 2인 합의 전파 (`JEC-90`)**:
   - 정규화 URL 단위 공유 상태 테이블 `ziper.product_links`(`id`, `canonical_url UNIQUE`, `shop`, `status IN ('AVAILABLE', 'SOLD_OUT', 'REMOVED', 'UNKNOWN')` 기본 `UNKNOWN`, `checked_at` nullable, `check_source IN ('SERVER', 'CLIENT')` nullable, `updated_at`)와 클라이언트 관측 기록 테이블 `ziper.product_link_reports`(`link_id`, `user_id`, `status IN ('AVAILABLE', 'SOLD_OUT', 'REMOVED')`, `observed_at`, `created_at`)를 신설한다.
   - `product_cards.is_link_valid`를 `link_id`(FK → `product_links`), `link_status`, `link_checked_at`, `link_check_source`로 교체한다(기존 데이터는 `TRUE` → `UNKNOWN`, `FALSE` → `REMOVED` 이관). 모든 카드 응답(상세·보드 상세·홈·재발견·구매 완료)은 `link: { status, checkedAt, source }`를 반환하며, 경량 조회 `GET /api/v1/products/{cardId}/link-status`와 관측 보고 `POST /api/v1/products/{cardId}/link-status/reports`를 제공한다.
   - 서버 측 관측(`JEC-9` 배치 등)의 `403`·캡차·타임아웃은 상태를 바꾸지 않고 `UNKNOWN`(또는 기존 상태)을 유지한다. 서버 요청에만 `404`를 돌려주는 봇 차단 응답(예: 지그재그의 봇 User-Agent `404`)이 정상 상품을 삭제로 오판하지 않도록, **서버 측 관측의 `404`/접근 차단은 삭제 확정 근거가 아니며 기존 상태를 유지한다.**
   - 삭제 확정(`REMOVED`)은 실사용자 WebView(클라이언트)가 관측해 보고한 `404`/`410` 또는 서로 다른 사용자 2명 이상의 합의로만 판정한다. 서버 측 `200` + 품절 표시(`SOLD_OUT`)와 가용 확인(`AVAILABLE`)은 서버 관측으로 반영할 수 있다.
   - 클라이언트 관측 보고(`UNKNOWN` 보고 불가, 미래 시각·기존 `link_checked_at` 이전 시각 무시, 사용자·링크 단위 빈도 제한 `429`)는 **보고한 사용자 본인 카드에만 즉시 반영**한다. 다른 사용자 카드에 영향을 주는 공유 상태(`product_links`)는 **서로 다른 사용자 2명 이상이 24시간 이내에 같은 상태를 보고**한 경우에만 갱신·전파(더 최근의 본인 보고가 있는 카드는 유지)하며, 서버 배치(`JEC-9`) 확인 결과는 합의 없이 즉시 반영·전파한다.
   - 재발견 큐는 `link_status NOT IN ('REMOVED', 'SOLD_OUT')` 대상만 노출하며, 기존 `idx_product_cards_rediscovery_queue` 부분 인덱스도 같은 조건으로 교체한다.

## 대안 및 트레이드오프

| 대안 | 핵심 방식 | 기각 사유 |
| --- | --- | --- |
| **1. 서버 UA 위장(Slackbot 등) 또는 헤드리스·프록시 우회** | 백엔드가 봇 UA 위장·Playwright·주거용 프록시로 쇼핑몰 HTML 수집 | 쇼핑몰 차단 정책 변경 시 즉시 파손되고 이용약관·법적 리스크 및 프록시·컨테이너 비용이 크다. 무신사는 봇 UA도 모두 `403`으로 차단한다. |
| **2. 서버 HTTP 리다이렉트 추적 기반 단축 URL 해석** | `url-check`에서 서버가 단축 URL에 직접 HTTP 요청을 보내 `Location` 추적 | 단축 URL 도메인 역시 봇 차단·지연·SSRF 표면이 생기며, 앱 공유 흐름에서는 추출 WebView가 이미 최종 페이지로 이동하므로 서버 외부 요청이 중복된다. |
| **3. 기존 `is_link_valid` boolean 유지** | 링크 유효성만 `TRUE`/`FALSE`로 기록 | 서버 `403` 차단과 실제 판매 종료(`404`/`410`)·품절(`SOLD_OUT`)을 구분할 수 없어 오탐으로 정상 상품이 재발견에서 누락되거나 확인 시점을 안내할 수 없다. |
| **4. 단일 사용자 클라이언트 보고 즉시 전체 공유 전파** | 1명의 보고로 `product_links` 공유 상태를 바로 갱신 | 악의적 조작이나 일시적 네트워크·로그인 화면 오탐 한 건으로 동일 URL을 저장한 모든 사용자의 카드가 잘못 `REMOVED`/`SOLD_OUT` 처리된다. |

## 영향과 한계

- **클라이언트 계약 전환**: 기존 `ProductLookup` 계약(`POST /api/v1/products/lookup`)을 `GET /api/v1/products/extraction-rules` + `POST /api/v1/products/url-check` + 앱 격리 WebView 추출 흐름으로 교체해야 하며, 카드 응답 5종에 `link { status, checkedAt, source }`와 `imageStatus`([ADR 0017](0017-product-image-storage-and-thumbnail-policy.md))가 추가된다.
- **웹 단독 환경의 한계**: `ziper-web` 단독 브라우저에서는 자동 추출과 단축 URL 리다이렉트 추적이 동작하지 않으므로 수기 입력(`MANUAL`)이 기본 경로다. 향후 브라우저 확장 프로그램(PRD 3차 MVP) 도입 시 동일 규칙(`extraction-rules`)을 재사용할 수 있다.
- **남은 조사 및 후속 결정 항목**: 무신사·에이블리 상품 페이지의 브라우저 기준 가격 위치(JSON-LD/인라인 JSON), 3개 지원몰의 품절·판매 종료 판정 규칙(`JEC-9`), 앱 공유 단축 링크 최종 URL 패턴은 `JEC-53`·`JEC-9` 구현 전 실측으로 규칙 JSON에 반영한다. 또한 품절(`SOLD_OUT`)의 재발견 제외 여부(현재는 `REMOVED`·`SOLD_OUT` 모두 제외)와 배지 문구는 PM 확정에 따라 조정할 수 있다.
