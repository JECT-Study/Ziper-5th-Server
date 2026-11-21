# Ziper 백엔드 제품 요구사항 정의서 (Backend PRD)

- **한 줄 정의**: 여러 쇼핑몰의 관심 상품을 **저장한 이유(맥락)와 함께 한곳에 모아 비교·정리하는 위시리스트 서비스**
- **문서 구성**:
  - **Part 1 (사람을 위한 요약)**: 목적·가치·핵심 정책을 간결한 개조식으로 정리
  - **Part 2 (AI를 위한 상세 명세)**: 구현 시 모호함이 없도록 불변식·상태 전이·쿼리 조건·예외 규칙을 엄밀하게 정의

---

# Part 1. 사람을 위한 제품·정책 요약 (개조식)

## 1. 프로젝트 목적과 핵심 가치

### 1.1 타겟 사용자와 해결하려는 문제
- **타겟 사용자**: 무신사·에이블리·지그재그·29CM·인스타그램 등 여러 플랫폼을 넘나들며 쇼핑하는 2030 여성
- **핵심 문제 1 — 찜의 파편화와 방치**: 쇼핑몰마다 찜·장바구니·스크린샷이 흩어져 있어 비교가 어렵고 저장 후 잊혀짐
- **핵심 문제 2 — 선택 맥락의 부재**: 나중에 다시 봤을 때 *"왜 사고 싶었는지, 언제 입으려 했는지"* 기억나지 않아 구매 결정 실패

### 1.2 제품의 3대 핵심 가치
1. **쉬운 모아보기 (Frictionless Archiving)**
   - 외부 앱 '공유하기' 또는 앱 내 링크 붙여넣기로 원하는 보드에 즉시 저장
   - 상품명·가격·대표 이미지 자동 추출 (실패 시에도 흐름 이탈 없이 보정·저장)
2. **맥락 보존 (Context Preservation)**
   - **한 줄 메모**(담은 이유·희망 옵션·착용 상황) 기록
   - **해시태그**(봄·여름·가을·겨울 기본 칩 + 사용자 `#직접입력`)로 분류
3. **재발견 루프 (Rediscovery Loop)**
   - 저장 후 방치된 상품을 스와이프(`유지` / `구매 완료` / `삭제`)로 가볍게 정리
   - 스와이프 종료 후 정리한 상품 수와 금액 리포트로 구매 의사결정 지원

---

## 2. 도메인별 핵심 정책 (개조식)

### 2.1 회원 · 인증 · 마이페이지
- **지원 로그인**: 카카오, 네이버, 애플(OAuth 2.0), 이메일·비밀번호
- **소셜 최초 가입 자동화**:
  - 닉네임 자동 생성: `Ziper` + 5자리 난수 (`Ziper00000`~`Ziper99999`, 중복 불가, 추후 변경 가능)
  - 기본 보드 자동 생성: `'기본 보드'` (이름 수정·삭제 불가)
  - 이메일 미제공 소셜 계정(비비즈 카카오 등)도 가입 허용
- **이메일 인증 규칙**:
  - 6자리 인증번호 발송 (유효시간 3분, 동일 이메일 재발송 3분 간격)
  - 이메일 기준 오입력 최대 5회 제한 → 5회 실패 시 30분간 인증·발급 잠금 (30분 경과 시 초기화)
- **마이페이지 및 계정 관리**:
  - 내 정보 조회(닉네임, 이메일, 가입 유형 등) 및 **닉네임(프로필) 수정** (한글·영문·숫자 2~10자, 특수문자/공백 불가, 중복 불가)
  - **비밀번호 변경**: 이메일 가입 계정 대상 (현재 비밀번호 검증 + 영문/숫자/특수문자 8자 이상 새 비밀번호 설정, 소셜 전용 계정은 거부)
  - **회원 탈퇴**: 탈퇴 사유 선택 후 즉시 모든 기기 세션 종료 및 `WITHDRAWN` 소프트 삭제 처리 (탈퇴 계정 재로그인·토큰 재발급 차단)
  - *1차 MVP 제외*: ID/PW 찾기

### 2.2 상품 등록 및 메타데이터 추출
- **진입 경로**:
  - **경로 A (외부 공유)**: 타 앱에서 공유하기 → Ziper 선택 → 바텀시트 즉시 노출
  - **경로 B (앱 내 등록)**: 홈 또는 보드 내 `(+)` 클릭 → URL 붙여넣기 (특정 보드 내 진입 시 해당 보드 기본 선택)
- **지원 쇼핑몰 · 클라이언트 격리 추출 · URL 정규화 및 폴백(Fallback)** ([ADR 0019](adr/0019-client-side-product-extraction-and-url-policy.md)):
  - **1차 자동 추출 대상 쇼핑몰**: 무신사, 에이블리, 지그재그
  - **클라이언트 격리 추출 (서버 HTML 크롤링 금지)**: 1차 지원몰 자동 추출은 모바일 앱(`ziper-mobile`)의 격리된 오프스크린 WebView가 수행한다. 서버는 상품 페이지 HTML을 요청하지 않으며, User-Agent 위장·프록시·헤드리스 브라우저 우회를 하지 않는다. 브라우저 단독 `ziper-web`은 교차 출처 제약으로 자동 추출을 수행하지 않고 수기 입력(`MANUAL`)으로 저장한다.
  - **단축·추적 URL 정규화**: 단축·추적 URL 리다이렉트는 앱 추출 WebView가 따라가 최종 URL(`finalUrl`)을 전달하며, 서버는 외부 HTTP 요청 없이 문자열 규칙(호스트 소문자화, `m.`/`www.` 통일, 몰별 상품 ID 추출, `utm_*`·`fbclid` 등 추적 파라미터 제거)으로 정규화(`canonicalUrl`) 및 쇼핑몰(`shop`)을 판정한다.
  - **서버 제공 API**: 인증 필수의 `GET /api/v1/products/extraction-rules`(`ETag`/`304` 캐시, 앱 배포 없이 몰별 선택자·정규화·`imageHosts`·`imageStrategy` 갱신)와 `POST /api/v1/products/url-check`(외부 요청 없이 정규화·지원몰 판정·기존 저장 카드 `existingCardId` 반환)를 제공한다.
  - **폴백 및 저장 시 검증**: 유효한 `http(s)` URL이면 추출 실패·미지원 쇼핑몰 모두 차단하지 않고 `MANUAL` 수기 입력으로 저장을 허용한다(잘못된 URL 형식·비허용 스킴만 `400` 거부). 앱 추출 값(`CLIENT`)도 직접 입력(`MANUAL`)과 동일한 신뢰 수준으로 저장 시 서버가 검증하며(상품명 HTML 제거·200자 제한, 지원몰 `shop` 서버 판정, `imageUrl` `https` 및 지원몰 `imageHosts` 허용 목록 검증), 서버는 외부 페이지 요청 없이 저장을 확정한다.
- **이미지 정책** ([ADR 0017](adr/0017-product-image-storage-and-thumbnail-policy.md)):
  - **공유 외부 Object Storage(Supabase Storage 확정) + B-2 단일 중해상도 WebP 썸네일**: 무상태(홈서버+Cloud Run) 환경에서 **Supabase Storage**를 1차 저장소로 확정하되 Free Plan 용량·전송량 제약을 고려해 S3 호환 추상화 인터페이스를 유지한다(트래픽·Egress 증가 시 Cloudflare R2 전환 시임 보존). 1차 MVP(`JEC-76`)부터 유저 직접 업로드 이미지와 외부 OG 이미지 미러링 모두 `480~600px` WebP 썸네일(4:3 크롭, 장당 25~45KB)로 저장해 전 화면에서 공용 서빙한다(원본 `image_url`도 메타데이터·폴백용으로 병행 보관, 고도화·고아 파일 정리는 `JEC-10`).
  - **저장 직후 1회 비동기 미러링 (`@Async` 미사용)**: 외부 쇼핑몰 이미지는 카드 저장 직후 1회만 비동기 미러링하며 주기적으로 재수집하지 않는다. 프로세스 메모리 내 `@Async`는 사용하지 않고, 카드 저장과 같은 트랜잭션에서 DB 작업 테이블에 작업을 등록한 뒤 커밋 직후 빠른 경로(fast-path) 워커 + `FOR UPDATE SKIP LOCKED` 폴링으로 처리한다(미러링 실패가 카드 저장 실패로 이어지지 않음).
  - **응답 `imageStatus` 및 표시 우선순위**: 카드 응답에 `imageStatus`(`NONE` | `PENDING` | `READY` | `FAILED`)를 포함하며, 표시 우선순위는 `imageFileUrl`(자체 Object Storage) → `imageUrl`(원본 외부 URL) → **카테고리 대체 아이콘 5종(`TOP`, `PANTS`, `SKIRT`, `ACCESSORY`, `BEAUTY`)** 순으로 고정한다. 클라이언트 로드 실패(`onError`) 시 재요청 대기 없이 즉시 대체 아이콘으로 폴백한다.
  - **CDN 차단 및 컴플라이언스 퇴각 경로**: 호스트별 동시·초당 요청 제한 및 `403`/`429` 연속 시 자동 일시 중단을 적용하며, 특정 몰 CDN의 서버 IP 차단이 지속되면 `extraction-rules`의 `imageStrategy`를 `CLIENT_UPLOAD`로 전환해 앱이 직접 이미지를 받아 기존 업로드 API로 올린다. 또한 도메인별/전역 플래그로 미러링을 즉시 중단(원본 URL 참조 + 대체 아이콘 폴백)할 수 있고, 미러링 썸네일은 소유자 인증 기반 비공개 접근 및 삭제/탈퇴 시 즉시 파기를 원칙으로 한다.
- **입력 항목 필수 여부 및 길이 정책**:
  - 필수: 상품 URL, 상품명(최대 200자), 저장할 보드(1개 이상 다중 선택), 해시태그(1개 이상 다중 선택·입력), 가격(0원 이상 KRW 정수), 카드 이미지(OG URL / 직접 업로드 파일 / 카테고리 대체 아이콘 5종 중 1종 필수), 메타데이터 출처(`metadataSource`: `CLIENT` | `MANUAL`)
  - 선택: 쇼핑몰명(`shop`, 지원몰은 서버 판정값 사용), 한 줄 메모(최대 100자)
- **해시태그 정책**:
  - 기본 계절 태그 4종(`봄`, `여름`, `가을`, `겨울`, `is_default = TRUE`)은 모든 유저에게 제공되며 삭제 불가
  - 사용자가 직접 추가한 커스텀 태그(최대 6자 권장)는 **유저별 소유권**을 두고 목록 조회·생성·삭제 가능
  - **커스텀 태그 삭제 규칙**: 커스텀 태그 삭제 시 다른 상품 카드에서도 사용 중이면 현재 카드와의 관계(`product_card_tags`)만 해제하고, 해당 카드에서만 유일하게 사용 중인 태그는 `ON DELETE CASCADE`로 태그 자체 삭제를 허용
- **표시 규격**:
  - 이미지: 4:3 세로 비율 고정 (크롭 표시)
  - 상품명: 보드 목록에서는 최대 20자 말줄임(`…`), 상세 조회·수정 시에는 전체 상품명 노출

### 2.3 홈 · 보드 및 상품 관리
- **홈 요약 (`B-1`)**:
  - 재발견 대기 상품 수 배너, 내 보드 미리보기(각 보드별 대표 상품 썸네일 최대 3장 + 상품 수), 최근 저장 상품 목록 제공
- **동일 URL 상품 원칙**:
  - 한 유저 내 동일 정규화 URL 상품은 **단 1개의 상품 카드**로 관리하며, 여러 보드에 동시 연결(M:N) 가능
  - 이미 저장한 정규화 URL을 다시 신규 등록하면 중복 차단(`409 Conflict`)
- **보드 생성 · 수정 및 정렬**:
  - 바텀시트 이탈 없이 `[+ 새 보드 만들기]` 후 자동 체크하여 즉시 저장 가능
  - 보드명 최대 **10자(공백 포함)**, 동일 유저의 활성 보드 간 이름 중복 불가
  - 보드 목록 정렬: 순수 최신 생성순 (`product_boards.created_at DESC`, `'기본 보드'` 최상단 고정 없음) + 특수 `'구매 완료 목록'` 진입 지원
  - 보드 내 상품 목록(`D-2`): **4종 정렬(`최근 등록순`, `오래된 순`, `가격 낮은 순`, `가격 높은 순`)** 및 **해시태그 필터** 지원
- **원본 링크 상태 모델 (`JEC-90`, [ADR 0019](adr/0019-client-side-product-extraction-and-url-policy.md))**:
  - 저장 시점 가격을 기준으로 유지하며 가격 실시간 갱신은 제공하지 않음
  - 기존 `is_link_valid` boolean을 정규화 URL 단위 공유 상태(`product_links`)·클라이언트 관측 보고(`product_link_reports`)와 카드 단위 `link_status`(`AVAILABLE` | `SOLD_OUT` | `REMOVED` | `UNKNOWN`)·`link_checked_at`·`link_check_source`(`SERVER` | `CLIENT`)로 확장·대체
  - `403`·캡차·타임아웃 등 확인 불가 응답은 상태를 변경하지 않고 `UNKNOWN`(또는 기존 상태)을 유지하며, 확인 시점과 출처를 카드 응답(`link: { status, checkedAt, source }`)에 함께 제공
  - 클라이언트 관측 보고는 보고자 본인 카드에 즉시 반영하고, 서로 다른 사용자 2명 이상이 24시간 이내 동일 상태를 보고하거나 서버 배치(`JEC-9`)가 직접 확인한 경우에만 공유 상태(`product_links`) 갱신 및 전파
  - `REMOVED`·`SOLD_OUT` 상태 상품은 품절·접근 불가 상태로 표시하고 재발견 대상에서 제외 (`link_status NOT IN ('REMOVED', 'SOLD_OUT')` 대상만 노출)
- **구매 완료 처리**:
  - 상세·목록·재발견에서 `[구매 완료]` 선택 시 모든 일반 보드와 재발견 대상에서 제외
  - 별도 보드 행 생성 없이 전용 **'구매 완료 목록'**에서 상품당 1회만 노출
- **삭제 범위 구분**:
  - **개별 보드에서만 상품 제거**: 해당 보드 연결만 해제 (다른 보드에는 유지, 어느 보드에도 안 남은 **고아 상품**이 되면 30일 휴지통 이동)
  - **보드 자체 삭제**: 해당 보드 연결 해제 후, 어느 보드에도 안 남은 **고아 상품만 30일 휴지통 이동** (다른 보드에도 담긴 상품은 유지)
  - **상품 상세 또는 재발견에서 삭제**: 담긴 **모든 보드에서 일괄 비노출**되고 즉시 30일 휴지통 이동

### 2.4 휴지통 및 복구
- **접근 경로 및 대상**: `[마이페이지] -> [휴지통]` (일반 보드 목록에는 미노출). 복구 대상은 **삭제된 상품 카드**로 한정 (삭제된 보드 자체 복구는 미제공)
- **보관 및 파기**:
  - 보드 구분 없이 상품 카드 단위로 노출하며 30일 D-Day 표시
  - 삭제 후 30일 경과 시 자동 영구 삭제 / 휴지통 내 `[즉시 영구 삭제]` 시 즉시 DB 파기
- **지정 복구**:
  - 복구 클릭 시 현재 존재하는 활성 보드 중 **원하는 보드(1개 이상)를 새로 지정**하여 복구
  - 상세·재발견 전체 삭제로 `product_board_card` 매핑이 보존된 카드에 한해, 해당 보드가 아직 활성 상태이면 기본 선택 힌트(`previousBoardIds`)를 함께 반환 (고아 삭제 카드는 빈 배열)

### 2.5 방치 상품 재발견(Rediscovery) 스와이프
- **노출 대상**: 구매 예정 상태이면서 링크 상태가 정상/미확인(`link_status NOT IN ('REMOVED', 'SOLD_OUT')`)이고, 대기 주기(최초 14일 / 연장 30일·90일)가 경과한 상품 (동일 URL은 세션당 1회만 노출)
- **주기 연장 및 리셋**:
  - 최초 저장 후 **14일** 경과 시 진입
  - 스와이프에서 `[유지]` 선택 시: **1차 30일 → 2차 이후 90일 단위**로 대기 기간 연장
  - 일반 보드에서 **상품 상세 조회 또는 `[원본 이동]` 클릭 시**: 연장 단계와 무관하게 **즉시 최초 14일 주기로 리셋**
- **스와이프 액션 및 3초 되돌리기**:
  - `[구매했어요]`: 모든 보드에서 일괄 구매 완료 처리
  - `[삭제]`: 모든 보드에서 일괄 삭제(30일 휴지통 이동)
  - `[일단 유지]`: 30일(1차) → 90일(2차+) 재발견 주기 연장
  - **3초 `[되돌리기]`**: 기본 전략은 **클라이언트 3초 지연 전송**(연속 스와이프·화면 이탈·완료 리포트 진입 시 대기 중인 요청 즉시 flush 전제). 서버 `UNDO` API(`REDISCOVERY_UNDO`)는 우선순위가 낮은 예비 확장으로 분리
- **세션 완료 리포트**: Stateless 집계 API — 클라이언트가 세션 동안 처리한 `(cardId, action)` 목록을 전달하면 액션별(유지·구매완료·삭제·합계) 상품 개수와 금액 합계(해당 액션 0건 시 0원) 계산 반환

---

## 3. MVP 범위 요약

- **1차 MVP 포함**:
  - 소셜(카카오·네이버·애플) 및 이메일 회원가입·로그인·자동 로그인·로그아웃
  - 마이페이지 조회, 프로필(닉네임) 수정, 비밀번호 변경(이메일 계정), 회원 탈퇴(`WITHDRAWN` 소프트 삭제)
  - 외부 공유하기 및 앱 내 링크 등록 + 1차 지원몰(무신사·에이블리·지그재그) 앱 격리 WebView 자동 추출 + 서버 추출 규칙(`extraction-rules`)·문자열 기반 URL 확인(`url-check`) + 수기 입력(`MANUAL`) 폴백 ([ADR 0019](adr/0019-client-side-product-extraction-and-url-policy.md))
  - 외부 Object Storage(Supabase Storage 확정, S3 호환 추상화 유지) 기반 B-2 단일 중해상도 WebP 썸네일 저장(`files`, 유저 직접 업로드 + 저장 직후 1회 DB 작업 테이블 기반 비동기 OG 이미지 미러링) + `imageStatus` + 원본 `image_url` 폴백 + 카테고리 대체 아이콘 5종 ([ADR 0017](adr/0017-product-image-storage-and-thumbnail-policy.md))
  - 한 줄 메모 및 다중 해시태그(기본 4종 보호 + 유저별 커스텀 태그 조회·생성·삭제)
  - 상품 링크 상태 모델(`product_links`·`product_link_reports`, `link_status`·`link_checked_at`·`link_check_source`, `JEC-90`)
  - 홈 요약(재발견 대기 수·보드별 대표 썸네일 최대 3장·최근 저장), 다중 보드 관리(보드 상세 4종 정렬·태그 필터), 상품 상세/수정, 구매 완료 목록, 30일 휴지통 및 지정 복구(상품 카드)
  - 방치 상품 재발견 스와이프 (14/30/90일 주기, 클라 3초 지연 전송 기반 되돌리기, Stateless 완료 금액 리포트)
- **1차 MVP 제외 / 별도 분리 (후속·개선)**:
  - 별도 백그라운드/개선 이슈: OG 이미지 비동기 리사이징 워커 고도화·고아 파일 정리(`JEC-10`), 외부 쇼핑몰 주기적 링크 동기화(`JEC-9`), 재발견 서버 `UNDO` API(예비)
  - 1차 제외: ID/PW 찾기, 삭제된 보드 복구
  - 2차 MVP: 보드/단품 카드 링크 공유(선택적 비공개 지원), 타 유저 탐색·댓글, 가격 하락 알림
  - 3차 MVP: 제휴 파트너스 링크 자동 수익화, 캡처 이미지 OCR 인식, 웹 브라우저 확장 프로그램

---

## 4. 화면별 요구사항 요약표 (RQ-ID)

| 분류 | RQ-ID | 피그마 | 구분 | 핵심 요구사항 요약 |
| --- | --- | --- | --- | --- |
| 인증 | `RQ-AUTH-10`~`12` | 시작/소셜 | F/B | 소셜·이메일 시작 화면, 소셜 자동 가입(`Ziper`+5자리 난수 닉네임, `'기본 보드'` 생성) |
| 인증 | `RQ-AUTH-13`~`15` | 이메일가입 | F/B | 이메일·비밀번호(영문/숫자/특수문자 8자+) 입력, 6자리 번호(3분 유효, 5회 실패 시 30분 잠금) |
| 인증 | `RQ-AUTH-16`~`17`, `6` | 로그인/유지 | F/B | 이메일 로그인, Refresh Token 회전 기반 자동 로그인, 기기별 세션 로그아웃 |
| 마이페이지 | `RQ-MYP-01`~`04` | 마이페이지 | F/B | 내 정보 조회, 프로필(닉네임) 수정, 비밀번호 변경(이메일 계정), 회원 탈퇴(`WITHDRAWN` 소프트 삭제) |
| 외부공유 | `RQ-SAV-A1`~`A4` | A1~A4 | F/B | 외부 공유 URL 수신 → 앱 격리 WebView 리다이렉트 추적·자동 추출 + 서버 `url-check`, 실패·미지원 시 `MANUAL` 수기 보정 전환 |
| 앱내등록 | `RQ-SAV-B1`~`B6` | B1~B6 | F/B | 홈/보드 내 `(+)` 진입(보드 내 진입 시 기본 체크), URL 붙여넣기·추출·수기 보정·필수값 검증 |
| 공통시트 | `RQ-SAV-C1`~`C6` | C1~C5, C-IMG | F/B | 보드 다중 선택, 바텀시트 내 새 보드 즉석 생성·자동 체크, 유저별 커스텀 태그, 직접 이미지 업로드·대체 아이콘 5종 |
| 홈/보드 | `RQ-BOR-D1`~`D4` | D1~D4 | F/B | 홈 요약(재발견 수·보드 3장 썸네일·최근 저장), 보드 목록(순수 최신 생성순 `created_at DESC`·10자 제한·중복 금지), 카드 4:3 비율 및 20자 말줄임 |
| 보드상세 | `RQ-BOR-D5`~`D7` | D5~D7 | F/B | 정렬 4종·태그 필터, 상세/원본 이동 시 14일 주기 리셋, 수동 구매완료 일괄 동기화, 보드 삭제 시 고아 상품만 휴지통 이동 |
| 상품상세 | `RQ-DTL-51`~`56` | 5-1~5-4, MOD, DEL | F/B | 전체 상품명·쇼핑몰명(`shop`)·링크 상태·메모·태그·소속 보드 조회 및 수정, 구매 완료 이동, 상세 삭제 시 모든 보드에서 휴지통 이동 |
| 재발견 | `RQ-RED-E1`~`E8` | E-1~E-7, END | F/B | 14/30/90일 경과 상품 URL당 1회 노출(`REMOVED`·`SOLD_OUT` 제외), 유지·구매완료·삭제 동기화, 클라 3초 지연 되돌리기, Stateless 완료 금액 리포트 |
| 휴지통 | `RQ-TRH-F1`~`F2` | F1, F2 | F/B | 휴지통 30일 D-Day 표시 및 만료 시 자동 영구 삭제, 유저가 지정한 활성 보드로 상품 카드 선택 복구(`previousBoardIds` 힌트 포함) |

---

# Part 2. AI 에이전트를 위한 상세 구현 명세 (AI Specification)

이 섹션은 백엔드 코드·쿼리·마이그레이션 구현 시 AI 에이전트가 준수해야 할 엄밀한 데이터 불변식, 상태 전이 수식, 쿼리 조건을 정의합니다.

## 5. 스키마 구조 및 데이터 불변식 (Data Invariants)

1. **`ziper.users`**
   - 상태 값 도메인: `status IN ('ACTIVE', 'WITHDRAWN')`.
   - 회원 탈퇴 시 즉시 해당 유저의 모든 `sessions`를 삭제하고 `status = 'WITHDRAWN'`으로 소프트 삭제 처리한다. `WITHDRAWN` 상태 계정은 로그인 및 토큰 재발급을 거부한다.
2. **`ziper.product_boards`**
   - 기본 보드 불변식: 회원가입 시 `(user_id, name='기본 보드', modifiable=false, is_deleted=false)` 1개 행 필수 생성. `modifiable=false`인 행은 이름 변경·삭제 요청 시 `BusinessException`으로 거부한다.
   - 보드명 길이 규칙: 공백 포함 최대 **10자** (`btrim(name) <> ''`, DTO 및 도메인 검증).
   - 생성 시각 컬럼 및 정렬: 최신 생성순 정렬을 위해 `created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP`를 추가(후속 Flyway 마이그레이션)하여 사용하며, 보드 목록·홈 미리보기 모두 `'기본 보드'` 최상단 고정 없이 순수 `created_at DESC, id DESC`로 정렬한다.
   - 활성 보드 이름 고유성: `uq_product_boards_active_user_name (user_id, name) WHERE is_deleted = FALSE`로 동일 유저 내 활성 보드 이름 중복을 차단한다.
3. **`ziper.product_cards`**
   - 소유권 및 URL 고유성: `user_id NOT NULL`, `CONSTRAINT uq_product_cards_user_url UNIQUE (user_id, product_url)`. 서버 문자열 정규화 결과(`canonical_url`)를 `product_url`로 저장하며, 동일 유저가 기존 정규화 URL을 신규 등록 요청하면 `409 Conflict`를 반환한다([ADR 0019](adr/0019-client-side-product-extraction-and-url-policy.md)).
   - 쇼핑몰명 컬럼: `shop VARCHAR(100)` (nullable, 후속 Flyway 마이그레이션 `JEC-54`에서 추가). 1차 지원몰은 서버 정규화 판정값으로 강제 저장하고 미지원 몰만 사용자 입력 또는 `NULL`을 허용한다.
   - 메타데이터 출처 및 이미지 미러링 상태: `metadata_source IN ('CLIENT', 'MANUAL')`, `image_status IN ('NONE', 'PENDING', 'READY', 'FAILED')` (`JEC-54` 마이그레이션 추가). 외부 `image_url` 없이 직접 업로드·대체 아이콘만 등록하면 `image_status = 'NONE'`, 외부 `image_url` 미러링 작업을 등록하면 `'PENDING'`으로 저장한다.
   - 상태 값 도메인: `status IN ('PLANNED', 'PURCHASED')`.
   - 소프트 삭제 일관성: `(is_deleted = FALSE AND deleted_at IS NULL) OR (is_deleted = TRUE AND deleted_at IS NOT NULL)`.
   - 카드 이미지 필수 불변식 및 대체 아이콘 도메인: `image_url`(OG URL), `image_file_id`(직접 업로드/미러링 파일), `fallback_icon`(대체 아이콘 5종) 중 **최소 1개 이상 필수**이며 `fallback_icon IS NULL OR fallback_icon IN ('TOP', 'PANTS', 'SKIRT', 'ACCESSORY', 'BEAUTY')`를 만족해야 한다. (`V6` 스키마에는 이미지 필수 `CHECK` 제약이 없으므로 API 검증 및 후속 Flyway 마이그레이션 `JEC-54`에서 강제한다.)
   - 가격 도메인: **필수 입력**(`NOT NULL`, `price >= 0`, `BIGINT`, KRW 원 단위). (`V6` 스키마에서는 nullable로 생성되었으므로 API 필수 검증 및 후속 Flyway 마이그레이션 `JEC-54`의 `NOT NULL` 전환으로 일치시킨다.)
   - 링크 상태 컬럼(`JEC-90` 마이그레이션에서 `is_link_valid` 대체): `link_id` (FK → `ziper.product_links.id`), `link_status IN ('AVAILABLE', 'SOLD_OUT', 'REMOVED', 'UNKNOWN')`, `link_checked_at` (nullable), `link_check_source IN ('SERVER', 'CLIENT')` (nullable). 기존 데이터는 `is_link_valid = TRUE` → `UNKNOWN`, `FALSE` → `REMOVED`로 이관한다.
   - 재발견 단계 도메인: `rediscovery_stage BETWEEN 0 AND 2`.
4. **`ziper.product_board_card`**
   - 매핑 고유성: `UNIQUE (board_id, card_id)`.
   - **매핑 보존 원칙**: 상품 전체 삭제(`is_deleted = TRUE`)나 구매 완료(`status = 'PURCHASED'`) 전환 시 `product_board_card` 행을 물리 삭제하지 않고 유지한다. 일반 보드 상품 조회 쿼리에서 반드시 `pc.is_deleted = FALSE AND pc.status = 'PLANNED'` 조건을 결합해 필터링한다. (휴지통 목록에서 전체 삭제 카드의 활성 `previousBoardIds` 힌트 제공 및 예비 서버 Undo 확장에 활용)
5. **`ziper.tags` & `ziper.product_card_tags`**
   - `tags.title`: 유니코드 NFC로 정규화하고 선행 공백·`#` 접두부를 반복 제거한 뒤 앞뒤 공백을 제거하고 내부 연속 공백을 한 칸으로 줄인 비공백 문자열(제어·서식 문자 포함 시 `INVALID_TAG_TITLE`, 커스텀 태그 권장 최대 6자). 대소문자는 구분한다.
   - 기본 시드: `('봄', TRUE), ('여름', TRUE), ('가을', TRUE), ('겨울', TRUE)` (`user_id IS NULL`, 삭제 불가).
   - **유저별 커스텀 태그 소유권** (후속 Flyway 마이그레이션 확장): `tags.user_id` (nullable, 기본 4종 `NULL` / 커스텀 태그는 소유 `user_id` 보유, 기본 태그 `title` 전역 고유 + 커스텀 태그 `(user_id, title)` 고유)로 유저별 커스텀 태그 목록 조회·생성·삭제를 지원한다.
   - **커스텀 태그 삭제 불변식**: 커스텀 태그 삭제 시 다른 상품 카드에서도 사용 중이면 현재 카드와의 관계(`product_card_tags`)만 해제하고, 해당 카드에서만 유일하게 사용 중인 태그는 `ON DELETE CASCADE`로 태그 행 삭제를 허용한다.
   - 카드당 태그 수: **`1..N`개 필수** (상품 등록·수정 입력 시점에 최소 1개 이상의 해시태그 연결을 애플리케이션 계층에서 검증한다). 이 조건은 입력 시점 검증일 뿐 저장 후 불변식이 아니며, 태그 삭제(해당 카드에서만 쓰던 태그의 `ON DELETE CASCADE` 삭제 포함)로 카드의 태그가 0개가 되는 것은 허용한다.
6. **`ziper.files`**
   - 유저 직접 업로드 이미지 및 B-2 공용 WebP 썸네일(`480~600px`) 메타데이터 저장 (`storage_key UNIQUE`, `file_size >= 0`, Supabase Storage 확정·S3 호환 추상화 유지, [ADR 0017](adr/0017-product-image-storage-and-thumbnail-policy.md)). 카드 영구 삭제·회원 탈퇴 시 연결된 Object Storage 객체도 함께 파기한다.
   - 외부 OG 이미지 미러링 비동기 작업은 프로세스 메모리 `@Async`가 아닌 DB 작업 테이블로 영속 관리한다(`PENDING` → 커밋 후 빠른 경로 워커 + `FOR UPDATE SKIP LOCKED` 폴링).
7. **`ziper.user_card_events`**
   - `event_type IN ('VIEW_DETAIL', 'OPEN_ORIGINAL_URL', 'REDISCOVERY_KEEP', 'REDISCOVERY_PURCHASE', 'REDISCOVERY_DELETE', 'REDISCOVERY_UNDO')`.
8. **`ziper.product_links` & `ziper.product_link_reports`** (`JEC-90`, [ADR 0019](adr/0019-client-side-product-extraction-and-url-policy.md))
   - `ziper.product_links`: 정규화 URL(`canonical_url UNIQUE`) 단위 공유 링크 상태(`shop`, `status IN ('AVAILABLE', 'SOLD_OUT', 'REMOVED', 'UNKNOWN')` 기본 `UNKNOWN`, `checked_at` nullable, `check_source IN ('SERVER', 'CLIENT')` nullable, `updated_at`).
   - `ziper.product_link_reports`: 클라이언트 관측 기록(`link_id`, `user_id`, `status IN ('AVAILABLE', 'SOLD_OUT', 'REMOVED')`, `observed_at`, `created_at`). 동일 사용자·링크 단위 보고 빈도를 제한(`429`)하고, 서로 다른 사용자 2명 이상이 24시간 이내 동일 상태를 보고하면 `product_links` 공유 상태를 갱신해 하위 카드(더 최근의 본인 보고가 없는 카드)에 전파한다.

---

## 6. 액션별 상태 전이 규칙 (State Transition Rules)

| 액션 (Action) | 선행 조건 (Pre-condition) | DB 상태 변경 (Mutation) | 후행 보장 (Post-condition) |
| --- | --- | --- | --- |
| **1. 상품 신규 등록** | • 필수값 검증 통과: `productUrl`, `productTitle`(HTML 제거·최대 200자), `price`(0 이상), 카드 이미지(`imageUrl`/`imageFileId`/`fallbackIcon` 중 1개 이상; `imageUrl`은 `https` 및 지원몰 `imageHosts` 검증), `boardIds`(1개 이상), 해시태그(1개 이상), `metadataSource`(`CLIENT`\|`MANUAL`)<br>• 요청한 모든 `boardIds`가 해당 `user_id`의 활성 보드(`is_deleted = FALSE`)<br>• `(user_id, canonical_url)` 미존재 (존재 시 `409`) | • `product_links` 조회/생성 후 `product_cards` 생성 (`product_url = canonical_url`, `shop`, `price`, 카드 이미지, `metadata_source`, `image_status`, `link_id`, `link_status`, `status='PLANNED'`, `rediscovery_stage=0`, `last_interacted_at=now()`, `next_rediscovery_at=now()+14d`, `is_deleted=FALSE`)<br>• 외부 `imageUrl` 존재 시 같은 트랜잭션에서 DB 이미지 미러링 작업 등록(`image_status='PENDING'`). 예외: (a) 직접 업로드 파일(`imageFileId`)이 함께 있으면 미러링하지 않으며, (b) 도메인별·전역 미러링 플래그가 OFF이면 작업을 등록하지 않고 원본 `imageUrl` 참조 + `fallbackIcon` 폴백으로 두며 `image_status='NONE'`으로 기록<br>• 선택한 모든 보드에 `product_board_card` 삽입<br>• 입력된 태그(1개 이상, 기본 태그 또는 해당 유저의 커스텀 태그) Upsert 후 `product_card_tags` 삽입 | 선택한 모든 보드에서 상품 조회 가능, 커밋 후 빠른 경로 워커가 미러링 수행 |
| **2. 상품 상세 조회 / 원본 이동** (`RQ-BOR-D5`) | • `card.user_id == currentUserId`<br>• `card.is_deleted == FALSE` | • `card.rediscovery_stage = 0`<br>• `card.last_interacted_at = now()`<br>• `card.next_rediscovery_at = now() + INTERVAL '14 days'`<br>• `user_card_events`에 `VIEW_DETAIL` 또는 `OPEN_ORIGINAL_URL` 삽입 | 기존 30일/90일 연장 상태였더라도 즉시 14일 주기로 리셋됨 |
| **3. 개별 보드에서 상품 제거** (`3.2.3`) | • 해당 `board_id`와 `card_id`가 현재 유저 소유 | • `DELETE FROM ziper.product_board_card WHERE board_id = :boardId AND card_id = :cardId`<br>• 남은 활성 보드(`pb.is_deleted = FALSE`) 연결 수를 카운트하여 **`0`개이면**: `card.is_deleted = TRUE`, `card.deleted_at = now()` | 다른 보드에 연결이 남아 있으면 유지, 0개(고아 상품)면 휴지통 이동 |
| **4. 보드 자체 삭제** (`RQ-BOR-D7`) | • `board.modifiable == TRUE`<br>• `board.is_deleted == FALSE` | • 해당 보드의 `product_board_card` 연결 삭제 및 보드 삭제 처리 (`board.is_deleted = TRUE, deleted_at = now()`)<br>• 해당 보드에 속했던 카드들 중 남은 활성 보드 연결이 **`0`개인 고아 카드들만** 일괄 `is_deleted = TRUE, deleted_at = now()` 갱신 | 다른 활성 보드에도 속한 카드는 유지되고 고아 카드만 휴지통 이동 |
| **5. 상품 전체 삭제 (상세/재발견)** (`RQ-DTL-56`, `RQ-RED-E5`) | • `card.user_id == currentUserId`<br>• `card.is_deleted == FALSE` | • `card.is_deleted = TRUE`, `card.deleted_at = now()`, `card.updated_at = now()`<br>• **`product_board_card` 행은 삭제하지 않고 유지** | 모든 일반 보드 및 재발견에서 즉시 제외, 휴지통 복구 시 활성 원래 보드 힌트(`previousBoardIds`) 제공 가능 |
| **6. 구매 완료 전환 (상세/목록/재발견)** (`RQ-DTL-54`, `RQ-RED-E4`) | • `card.user_id == currentUserId`<br>• `card.is_deleted == FALSE` | • `card.status = 'PURCHASED'`, `card.updated_at = now()`<br>• **`product_board_card` 행은 삭제하지 않고 유지** | 일반 보드 및 재발견에서 제외, 구매 완료 목록에만 단일 카드로 노출 |
| **7. 재발견 `[유지]` 선택** (`RQ-RED-E3`) | • `card.status == 'PLANNED'` AND `card.is_deleted == FALSE` | • `oldStage == 0` 이면 `newStage = 1`, `interval = 30 days`<br>• `oldStage >= 1` 이면 `newStage = 2`, `interval = 90 days`<br>• `card.rediscovery_stage = newStage`, `card.last_interacted_at = now()`, `card.next_rediscovery_at = now() + interval`<br>• `user_card_events`에 `REDISCOVERY_KEEP` 기록 | 30일 또는 90일 동안 재발견 큐에서 제외 |
| **8. 휴지통 지정 복구** (`RQ-TRH-F2`) | • `card.is_deleted == TRUE`<br>• 요청한 `targetBoardIds`가 모두 활성 보드(1개 이상) | • 기존 `product_board_card`에서 해당 `card_id` 행 모두 삭제 후 `targetBoardIds`로 새로 삽입<br>• `card.is_deleted = FALSE`, `card.deleted_at = NULL`, `card.status = 'PLANNED'`, `card.rediscovery_stage = 0`, `card.next_rediscovery_at = now() + 14d` | 지정한 보드들로 상품 복구 완료 |
| **9. 휴지통 영구 삭제 (수동 / 30일 만료)** (`RQ-TRH-F1`) | • 수동: `card.is_deleted == TRUE`<br>• 배치: `card.is_deleted == TRUE AND card.deleted_at <= now() - INTERVAL '30 days'` | • `DELETE FROM ziper.product_cards WHERE id = :cardId` (`ON DELETE CASCADE`로 매핑·이벤트 함께 삭제) | DB에서 영구 파기 |
| **10. 회원 탈퇴** (`RQ-MYP-04`) | • `user.status == 'ACTIVE'` | • `DELETE FROM ziper.sessions WHERE user_id = :userId`<br>• `user.status = 'WITHDRAWN'` (및 탈퇴 사유 기록) | 즉시 세션 종료 및 탈퇴 계정 재로그인·토큰 재발급 차단 |

---

## 7. 핵심 조회 및 집계 쿼리 규약

1. **일반 보드 내 상품 목록 조회 (`D-2`)**:
   - 조건: `pbc.board_id = :boardId AND pc.user_id = :userId AND pc.is_deleted = FALSE AND pc.status = 'PLANNED'`
   - 선택 필터: `tagIds`(또는 `tagId`) 전달 시 `product_card_tags` 조인으로 해당 태그 포함 카드만 필터링
   - 보드 상세의 `productCount`는 현재 태그 필터와 일치하는 활성 구매 예정 상품의 전체 개수이며 페이지 크기와 무관하다. 보드 목록의 개수는 태그 필터 없이 집계한다.
   - 다음 페이지는 같은 보드와 정렬에서 직전 응답의 `nextCursor`를 그대로 전달한다. 서버는 마지막 항목의 정렬 키와 보드 연결 ID를 기준으로 다음 항목을 조회한다.
   - 정렬 4종(`sort`):
     - `RECENT` (기본): `ORDER BY pbc.created_at DESC, pbc.id DESC`
     - `OLDEST`: `ORDER BY pbc.created_at ASC, pbc.id ASC`
     - `PRICE_ASC`: `ORDER BY pc.price ASC, pbc.created_at DESC, pbc.id DESC`
     - `PRICE_DESC`: `ORDER BY pc.price DESC, pbc.created_at DESC, pbc.id DESC`
2. **구매 완료 목록 조회 (`5-4`)**:
   - 조건: `pc.user_id = :userId AND pc.is_deleted = FALSE AND pc.status = 'PURCHASED'`
   - 정렬 4종(`sort`, 기본 `RECENT`): `ORDER BY pc.updated_at DESC, pc.id DESC` / `ASC` / `pc.price ASC|DESC` (보드 조인 없이 `product_cards` 기준 1회 노출)
3. **휴지통 목록 조회 (`RQ-TRH-F1`)**:
   - 조건: `pc.user_id = :userId AND pc.is_deleted = TRUE AND pc.deleted_at > now() - INTERVAL '30 days'`
   - D-Day 계산: `CEIL(EXTRACT(EPOCH FROM ((pc.deleted_at + INTERVAL '30 days') - now())) / 86400.0)`
   - 정렬: `ORDER BY pc.deleted_at DESC, pc.id DESC`
   - 기본 선택 힌트: 보존된 `product_board_card` 중 `pb.is_deleted = FALSE`인 보드 ID 목록을 `previousBoardIds`로 함께 반환 (고아 삭제 카드는 빈 배열)
4. **재발견 스와이프 큐 조회 (`RQ-RED-E2`)**:
   - 조건: `pc.user_id = :userId AND pc.is_deleted = FALSE AND pc.status = 'PLANNED' AND pc.link_status NOT IN ('REMOVED', 'SOLD_OUT') AND pc.next_rediscovery_at <= now()`
   - 정렬: 사용자가 최근 자주 저장한 태그(`product_card_tags`) 가중치 우선 → `pc.next_rediscovery_at ASC, pc.id ASC`
5. **재발견 세션 완료 리포트 집계 (`RQ-RED-E8`, Stateless)**:
   - 입력: 세션 동안 처리된 카드 ID 및 최종 액션(`KEEP`, `PURCHASE`, `DELETE`) 목록 (`[{cardId, action}]`)
   - 출력 (해당 액션 0건 시 `0`으로 반환):
     - `keepCount`, `keepTotalPrice` (`COALESCE(SUM(price), 0)`)
     - `purchaseCount`, `purchaseTotalPrice` (`COALESCE(SUM(price), 0)`)
     - `deleteCount`, `deleteTotalPrice` (`COALESCE(SUM(price), 0)`)
     - `totalProcessedCount`, `totalProcessedPrice`
6. **홈 요약 조회 (`B-1`)**:
   - 재발견 대기 상품 개수 (`§7.4` 조건 카운트)
   - 내 보드 미리보기 목록: 순수 최신 생성순 (`pb.created_at DESC, pb.id DESC`, `'기본 보드'` 최상단 고정 없음), 각 보드별 활성 `PLANNED` 상품 개수 + 최신 등록 상품 대표 썸네일 최대 3장(`[{ cardId, imageUrl, fallbackIcon }]`, `imageUrl`은 `imageFileUrl ?? image_url` 우선)
   - 최근 저장 상품 목록: `pc.user_id = :userId AND pc.is_deleted = FALSE AND pc.status = 'PLANNED' ORDER BY pc.created_at DESC, pc.id DESC` (상위 N개)
7. **보드 목록 조회 (`D-1`, 저장 바텀시트 공용)**:
   - 조건: `pb.user_id = :userId AND pb.is_deleted = FALSE`
   - 정렬: 순수 최신 생성순 (`ORDER BY pb.created_at DESC, pb.id DESC`, `'기본 보드'` 최상단 고정 없음)
   - 부가 정보: 각 보드별 활성 `PLANNED` 상품 개수 + 최신 등록 상품 대표 썸네일 최대 3장(`[{ cardId, imageUrl, fallbackIcon }]`, `imageUrl`은 `imageFileUrl ?? image_url` 우선), 및 특수 `'구매 완료 목록'` 요약(`purchasedSummary`: 개수 + 대표 썸네일 최대 3장)
