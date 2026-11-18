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

### 2.1 회원 및 인증
- **지원 로그인**: 카카오, 네이버, 애플(OAuth 2.0), 이메일·비밀번호
- **소셜 최초 가입 자동화**:
  - 닉네임 자동 생성: `Ziper` + 5자리 난수 (`Ziper00000`~`Ziper99999`, 중복 불가, 추후 변경 가능)
  - 기본 보드 자동 생성: `'기본 보드'` (이름 수정·삭제 불가)
  - 이메일 미제공 소셜 계정(비비즈 카카오 등)도 가입 허용
- **이메일 인증 규칙**:
  - 6자리 인증번호 발송 (유효시간 3분, 동일 이메일 재발송 3분 간격)
  - 이메일 기준 오입력 최대 5회 제한 → 5회 실패 시 30분간 인증·발급 잠금 (30분 경과 시 초기화)

### 2.2 상품 등록 및 메타데이터 추출
- **진입 경로**:
  - **경로 A (외부 공유)**: 타 앱에서 공유하기 → Ziper 선택 → 바텀시트 즉시 노출
  - **경로 B (앱 내 등록)**: 홈 또는 보드 내 `(+)` 클릭 → URL 붙여넣기 (특정 보드 내 진입 시 해당 보드 기본 선택)
- **3단계 메타데이터 추출 및 폴백(Fallback)**:
  1. **1차 (서버 파싱)**: 백엔드 API가 URL의 Open Graph 태그(첫 번째 대표 이미지 1장, 전체 상품명, KRW 가격) 추출
  2. **2차 (클라이언트 1차 폴백 파싱)**: 쇼핑몰의 서버 IP/Bot 차단 시 클라이언트(앱/웹뷰)가 직접 파싱하여 입력 폼 자동 채움
  3. **3차 (수기 보정 + 대체 아이콘)**: 파싱 불가·미지원 쇼핑몰일 경우 수기 입력 폼 전환 및 카테고리 아이콘 5종(`상의`, `바지`, `스커트`, `패션잡화`, `뷰티`) 선택
- **입력 항목 필수 여부**:
  - 필수: 상품 URL, 상품명, 저장할 보드(1개 이상 다중 선택)
  - 선택: 가격, 한 줄 메모, 해시태그(0개 이상 다중 선택·입력), 이미지/대체 아이콘
- **표시 규격**:
  - 이미지: 4:3 세로 비율 고정 (크롭 표시)
  - 상품명: 보드 목록에서는 최대 20자 말줄임(`…`), 상세 조회·수정 시에는 전체 상품명 노출

### 2.3 보드 및 상품 관리
- **동일 URL 상품 원칙**:
  - 한 유저 내 동일 URL 상품은 **단 1개의 상품 카드**로 관리하며, 여러 보드에 동시 연결(M:N) 가능
  - 이미 저장한 URL을 다시 신규 등록하면 중복 차단(`409 Conflict`)
- **보드 생성 및 정렬**:
  - 바텀시트 이탈 없이 `[+ 새 보드 만들기]` 후 자동 체크하여 즉시 저장 가능
  - 동일 유저의 활성 보드 간 이름 중복 불가
  - 보드 목록 정렬: `'기본 보드'` 최상단 고정 → 최신 생성순
  - 보드 내 상품 정렬: 해당 보드에 담은 최신순
- **구매 완료 처리**:
  - 상세·목록·재발견에서 `[구매 완료]` 선택 시 모든 일반 보드와 재발견 대상에서 제외
  - 별도 보드 행 생성 없이 전용 **'구매 완료 목록'**에서 상품당 1회만 노출
- **삭제 범위 구분**:
  - **개별 보드에서만 상품 제거**: 해당 보드 연결만 해제 (다른 보드에는 유지, 어느 보드에도 안 남은 **고아 상품**이 되면 30일 휴지통 이동)
  - **보드 자체 삭제**: 해당 보드 연결 해제 후, 어느 보드에도 안 남은 **고아 상품만 30일 휴지통 이동** (다른 보드에도 담긴 상품은 유지)
  - **상품 상세 또는 재발견에서 삭제**: 담긴 **모든 보드에서 일괄 비노출**되고 즉시 30일 휴지통 이동

### 2.4 휴지통 및 복구
- **접근 경로**: `[마이페이지] -> [휴지통]` (일반 보드 목록에는 미노출)
- **보관 및 파기**:
  - 보드 구분 없이 상품 카드 단위로 노출하며 30일 D-Day 표시
  - 삭제 후 30일 경과 시 자동 영구 삭제 / 휴지통 내 `[즉시 영구 삭제]` 시 즉시 DB 파기
- **지정 복구**:
  - 복구 클릭 시 현재 존재하는 활성 보드 중 **원하는 보드(1개 이상)를 새로 지정**하여 복구

### 2.5 방치 상품 재발견(Rediscovery) 스와이프
- **노출 대상**: 구매 예정 상태이면서 원본 링크가 정상이고, 대기 주기(최초 14일 / 연장 30일·90일)가 경과한 상품 (동일 URL은 세션당 1회만 노출)
- **주기 연장 및 리셋**:
  - 최초 저장 후 **14일** 경과 시 진입
  - 스와이프에서 `[유지]` 선택 시: **1차 30일 → 2차 이후 90일 단위**로 대기 기간 연장
  - 일반 보드에서 **상품 상세 조회 또는 `[원본 이동]` 클릭 시**: 연장 단계와 무관하게 **즉시 최초 14일 주기로 리셋**
- **스와이프 액션 및 3초 되돌리기**:
  - `[구매했어요]`: 모든 보드에서 일괄 구매 완료 처리
  - `[삭제]`: 모든 보드에서 일괄 삭제(30일 휴지통 이동)
  - **3초 `[되돌리기]`**: 액션 직후 3초 내 클릭 시 이전 상태 및 원래 담긴 보드들로 즉시 원복
- **세션 완료 리포트**: 스와이프 완료 시 액션별(유지·구매완료·삭제) 상품 개수와 금액 합계 제공

---

## 3. MVP 범위 요약

- **1차 MVP 포함**:
  - 소셜(카카오·네이버·애플) 및 이메일 회원가입·로그인·자동 로그인·로그아웃
  - 외부 공유하기 및 앱 내 링크 등록 + 3단계 메타데이터 추출/폴백
  - 한 줄 메모 및 다중 해시태그(기본 4종 + 커스텀)
  - 다중 보드 관리, 상품 상세/수정, 구매 완료 목록, 30일 휴지통 및 지정 복구
  - 방치 상품 재발견 스와이프 (14/30/90일 주기, 3초 되돌리기, 완료 금액 리포트)
- **1차 MVP 제외 (2·3차 로드맵)**:
  - 2차 MVP: 보드/단품 카드 링크 공유(선택적 비공개 지원), 타 유저 탐색·댓글, 가격 하락 알림
  - 3차 MVP: 제휴 파트너스 링크 자동 수익화, 캡처 이미지 OCR 인식, 웹 브라우저 확장 프로그램

---

## 4. 화면별 요구사항 요약표 (RQ-ID)

| 분류 | RQ-ID | 피그마 | 구분 | 핵심 요구사항 요약 |
| --- | --- | --- | --- | --- |
| 인증 | `RQ-AUTH-10`~`12` | 시작/소셜 | F/B | 소셜·이메일 시작 화면, 소셜 자동 가입(`Ziper`+5자리 난수 닉네임, `'기본 보드'` 생성) |
| 인증 | `RQ-AUTH-13`~`15` | 이메일가입 | F/B | 이메일·비밀번호(영문/숫자/특수문자 8자+) 입력, 6자리 번호(3분 유효, 5회 실패 시 30분 잠금) |
| 인증 | `RQ-AUTH-16`~`17`, `6` | 로그인/유지 | F/B | 이메일 로그인, Refresh Token 회전 기반 자동 로그인, 기기별 세션 로그아웃 |
| 외부공유 | `RQ-SAV-A1`~`A4` | A1~A4 | F/B | 외부 공유 URL 수신 → 자동 추출 바텀시트 노출, 실패 시 수기 보정 폼 전환 |
| 앱내등록 | `RQ-SAV-B1`~`B6` | B1~B6 | F/B | 홈/보드 내 `(+)` 진입(보드 내 진입 시 기본 체크), URL 붙여넣기·추출·수기 보정·필수값 검증 |
| 공통시트 | `RQ-SAV-C1`~`C6` | C1~C5, C-IMG | F/B | 보드 다중 선택, 바텀시트 내 새 보드 즉석 생성·자동 체크, 상품 수정, 실패 시 카테고리 아이콘 대체 |
| 홈/보드 | `RQ-BOR-D1`~`D4` | D1~D4 | F/B | 홈·보드 목록, 재발견 진입 칩, 보드 이름 중복 금지, 카드 4:3 비율 및 상품명 20자 말줄임 |
| 보드상세 | `RQ-BOR-D5`~`D7` | D5~D7 | F/B | 상세/원본 이동 시 14일 주기 리셋, 수동 구매완료 일괄 동기화, 보드 삭제 시 고아 상품만 휴지통 이동 |
| 상품상세 | `RQ-DTL-51`~`56` | 5-1~5-4, MOD, DEL | F/B | 전체 상품명·메모·태그·소속 보드 조회 및 수정, 구매 완료 이동, 상세 삭제 시 모든 보드에서 휴지통 이동 |
| 재발견 | `RQ-RED-E1`~`E8` | E-1~E-7, END | F/B | 14/30/90일 경과 상품 URL당 1회 노출, 유지·구매완료·삭제 동기화, 3초 되돌리기, 완료 금액 리포트 |
| 휴지통 | `RQ-TRH-F1`~`F2` | F1, F2 | F/B | 휴지통 30일 D-Day 표시 및 만료 시 자동 영구 삭제, 유저가 지정한 활성 보드로 선택 복구 |

---

# Part 2. AI 에이전트를 위한 상세 구현 명세 (AI Specification)

이 섹션은 백엔드 코드·쿼리·마이그레이션 구현 시 AI 에이전트가 준수해야 할 엄밀한 데이터 불변식, 상태 전이 수식, 쿼리 조건을 정의합니다.

## 5. 스키마 구조 및 데이터 불변식 (Data Invariants)

1. **`ziper.product_boards`**
   - 기본 보드 불변식: 회원가입 시 `(user_id, name='기본 보드', modifiable=false, is_deleted=false)` 1개 행 필수 생성. `modifiable=false`인 행은 이름 변경·삭제 요청 시 `BusinessException`으로 거부한다.
   - 활성 보드 이름 고유성: `uq_product_boards_active_user_name (user_id, name) WHERE is_deleted = FALSE`로 동일 유저 내 활성 보드 이름 중복을 차단한다.
2. **`ziper.product_cards`**
   - 소유권 및 URL 고유성: `user_id NOT NULL`, `CONSTRAINT uq_product_cards_user_url UNIQUE (user_id, product_url)`. 동일 유저가 기존 `product_url`을 신규 등록 요청하면 `409 Conflict`를 반환한다.
   - 상태 값 도메인: `status IN ('PLANNED', 'PURCHASED')`.
   - 소프트 삭제 일관성: `(is_deleted = FALSE AND deleted_at IS NULL) OR (is_deleted = TRUE AND deleted_at IS NOT NULL)`.
   - 대체 아이콘 도메인: `fallback_icon IS NULL OR fallback_icon IN ('TOP', 'PANTS', 'SKIRT', 'ACCESSORY', 'BEAUTY')`.
   - 가격 도메인: `price IS NULL OR price >= 0` (`BIGINT`, KRW 원 단위).
   - 재발견 단계 도메인: `rediscovery_stage BETWEEN 0 AND 2`.
3. **`ziper.product_board_card`**
   - 매핑 고유성: `UNIQUE (board_id, card_id)`.
   - **매핑 보존 원칙 (3초 Undo 지원)**: 상품 전체 삭제(`is_deleted = TRUE`)나 구매 완료(`status = 'PURCHASED'`) 전환 시 `product_board_card` 행을 물리 삭제하지 않고 유지한다. 일반 보드 상품 조회 쿼리에서 반드시 `pc.is_deleted = FALSE AND pc.status = 'PLANNED'` 조건을 결합해 필터링한다.
4. **`ziper.tags` & `ziper.product_card_tags`**
   - `tags.title`: `#` 기호를 제거하고 앞뒤 공백을 제거(`btrim`)한 비공백 고유 문자열(`VARCHAR(64) UNIQUE`).
   - 기본 시드: `('봄', TRUE), ('여름', TRUE), ('가을', TRUE), ('겨울', TRUE)`.
   - 카드당 태그 수: `0..N`개 허용 (`product_card_tags`에 행이 없어도 유효).
5. **`ziper.files`**
   - 유저 직접 업로드 또는 백그라운드 이미지 리사이징 결과물 메타데이터 저장 (`storage_key UNIQUE`, `file_size >= 0`).
6. **`ziper.user_card_events`**
   - `event_type IN ('VIEW_DETAIL', 'OPEN_ORIGINAL_URL', 'REDISCOVERY_KEEP', 'REDISCOVERY_PURCHASE', 'REDISCOVERY_DELETE', 'REDISCOVERY_UNDO')`.

---

## 6. 액션별 상태 전이 규칙 (State Transition Rules)

| 액션 (Action) | 선행 조건 (Pre-condition) | DB 상태 변경 (Mutation) | 후행 보장 (Post-condition) |
| --- | --- | --- | --- |
| **1. 상품 신규 등록** | • 요청한 모든 `boardIds`가 해당 `user_id`의 활성 보드(`is_deleted = FALSE`)<br>• `(user_id, product_url)` 미존재 (존재 시 `409`) | • `product_cards` 생성 (`status='PLANNED'`, `rediscovery_stage=0`, `last_interacted_at=now()`, `next_rediscovery_at=now()+14d`, `is_deleted=FALSE`)<br>• 선택한 모든 보드에 `product_board_card` 삽입<br>• 입력된 태그 Upsert 후 `product_card_tags` 삽입 | 선택한 모든 보드에서 상품 조회 가능 |
| **2. 상품 상세 조회 / 원본 이동** (`RQ-BOR-D5`) | • `card.user_id == currentUserId`<br>• `card.is_deleted == FALSE` | • `card.rediscovery_stage = 0`<br>• `card.last_interacted_at = now()`<br>• `card.next_rediscovery_at = now() + INTERVAL '14 days'`<br>• `user_card_events`에 `VIEW_DETAIL` 또는 `OPEN_ORIGINAL_URL` 삽입 | 기존 30일/90일 연장 상태였더라도 즉시 14일 주기로 리셋됨 |
| **3. 개별 보드에서 상품 제거** (`3.2.3`) | • 해당 `board_id`와 `card_id`가 현재 유저 소유 | • `DELETE FROM ziper.product_board_card WHERE board_id = :boardId AND card_id = :cardId`<br>• 남은 활성 보드(`pb.is_deleted = FALSE`) 연결 수를 카운트하여 **`0`개이면**: `card.is_deleted = TRUE`, `card.deleted_at = now()` | 다른 보드에 연결이 남아 있으면 유지, 0개(고아 상품)면 휴지통 이동 |
| **4. 보드 자체 삭제** (`RQ-BOR-D7`) | • `board.modifiable == TRUE`<br>• `board.is_deleted == FALSE` | • 해당 보드의 `product_board_card` 연결 삭제 및 보드 삭제 처리 (`board.is_deleted = TRUE, deleted_at = now()`)<br>• 해당 보드에 속했던 카드들 중 남은 활성 보드 연결이 **`0`개인 고아 카드들만** 일괄 `is_deleted = TRUE, deleted_at = now()` 갱신 | 다른 활성 보드에도 속한 카드는 유지되고 고아 카드만 휴지통 이동 |
| **5. 상품 전체 삭제 (상세/재발견)** (`RQ-DTL-56`, `RQ-RED-E5`) | • `card.user_id == currentUserId`<br>• `card.is_deleted == FALSE` | • `card.is_deleted = TRUE`, `card.deleted_at = now()`, `card.updated_at = now()`<br>• **`product_board_card` 행은 삭제하지 않고 유지** | 모든 일반 보드 및 재발견에서 즉시 제외, 3초 내 Undo 시 원래 보드들로 즉시 복원 가능 |
| **6. 구매 완료 전환 (상세/목록/재발견)** (`RQ-DTL-54`, `RQ-RED-E4`) | • `card.user_id == currentUserId`<br>• `card.is_deleted == FALSE` | • `card.status = 'PURCHASED'`, `card.updated_at = now()`<br>• **`product_board_card` 행은 삭제하지 않고 유지** | 일반 보드 및 재발견에서 제외, 구매 완료 목록에만 단일 카드로 노출, 3초 내 Undo 가능 |
| **7. 재발견 `[유지]` 선택** (`RQ-RED-E3`) | • `card.status == 'PLANNED'` AND `card.is_deleted == FALSE` | • `oldStage == 0` 이면 `newStage = 1`, `interval = 30 days`<br>• `oldStage >= 1` 이면 `newStage = 2`, `interval = 90 days`<br>• `card.rediscovery_stage = newStage`, `card.last_interacted_at = now()`, `card.next_rediscovery_at = now() + interval`<br>• `user_card_events`에 `REDISCOVERY_KEEP` 기록 | 30일 또는 90일 동안 재발견 큐에서 제외 |
| **8. 휴지통 지정 복구** (`RQ-TRH-F2`) | • `card.is_deleted == TRUE`<br>• 요청한 `targetBoardIds`가 모두 활성 보드(1개 이상) | • 기존 `product_board_card`에서 해당 `card_id` 행 모두 삭제 후 `targetBoardIds`로 새로 삽입<br>• `card.is_deleted = FALSE`, `card.deleted_at = NULL`, `card.status = 'PLANNED'`, `card.rediscovery_stage = 0`, `card.next_rediscovery_at = now() + 14d` | 지정한 보드들로 상품 복구 완료 |
| **9. 휴지통 영구 삭제 (수동 / 30일 만료)** (`RQ-TRH-F1`) | • 수동: `card.is_deleted == TRUE`<br>• 배치: `card.is_deleted == TRUE AND card.deleted_at <= now() - INTERVAL '30 days'` | • `DELETE FROM ziper.product_cards WHERE id = :cardId` (`ON DELETE CASCADE`로 매핑·이벤트 함께 삭제) | DB에서 영구 파기 |

---

## 7. 핵심 조회 및 집계 쿼리 규약

1. **일반 보드 내 상품 목록 조회**:
   - 조건: `pbc.board_id = :boardId AND pc.user_id = :userId AND pc.is_deleted = FALSE AND pc.status = 'PLANNED'`
   - 정렬: `ORDER BY pbc.created_at DESC, pbc.id DESC`
2. **구매 완료 목록 조회**:
   - 조건: `pc.user_id = :userId AND pc.is_deleted = FALSE AND pc.status = 'PURCHASED'`
   - 정렬: `ORDER BY pc.updated_at DESC, pc.id DESC` (보드 조인 없이 `product_cards` 기준 1회 노출)
3. **휴지통 목록 조회**:
   - 조건: `pc.user_id = :userId AND pc.is_deleted = TRUE AND pc.deleted_at > now() - INTERVAL '30 days'`
   - D-Day 계산: `CEIL(EXTRACT(EPOCH FROM ((pc.deleted_at + INTERVAL '30 days') - now())) / 86400.0)`
   - 정렬: `ORDER BY pc.deleted_at DESC, pc.id DESC`
4. **재발견 스와이프 큐 조회 (`RQ-RED-E2`)**:
   - 조건: `pc.user_id = :userId AND pc.is_deleted = FALSE AND pc.status = 'PLANNED' AND pc.is_link_valid = TRUE AND pc.next_rediscovery_at <= now()`
   - 정렬: 사용자가 최근 자주 저장한 태그(`product_card_tags`) 가중치 우선 → `pc.next_rediscovery_at ASC, pc.id ASC`
5. **재발견 세션 완료 리포트 집계 (`RQ-RED-E8`)**:
   - 입력: 세션 동안 처리된 카드 ID 및 최종 액션(`KEEP`, `PURCHASE`, `DELETE`) 목록
   - 출력:
     - `keepCount`, `keepTotalPrice` (`COALESCE(SUM(price), 0)`)
     - `purchaseCount`, `purchaseTotalPrice` (`COALESCE(SUM(price), 0)`)
     - `deleteCount`, `deleteTotalPrice` (`COALESCE(SUM(price), 0)`)
     - `totalProcessedCount`, `totalProcessedPrice`
