# 0015 상품·보드·해시태그·재발견 스키마 경계

- 상태: 채택 — Flyway V6 마이그레이션 반영, 도메인 API 구현은 후속 작업
- 날짜: 2026-11-18

## 배경

사용자는 하나의 상품 URL을 여러 보드에 동시에 저장하고, 상품 상세·목록·재발견 스와이프에서 구매 완료나 삭제를 수행할 때 동일 URL 상품의 상태가 모든 보드에 일괄 동기화되어야 한다. 또한 스와이프 직후 3초 이내 `[되돌리기]`를 누르면 원래 담겨 있던 보드 구성으로 즉시 복구되어야 하며, 방치 상품 재발견은 최초 14일 이후 유지 선택 시 30일·90일로 연장되고 상세 조회·원본 이동 시 즉시 14일로 리셋되어야 한다.

초안 ERD DDL에는 `product_cards.user_id` 소유자가 누락되어 있었고, 해시태그가 `product_cards.tag_id` 단일 컬럼 및 역방향 외래 키로 정의되어 카드당 다중 해시태그 선택 요구사항과 맞지 않았다.

## 결정

1. **유저별 동일 URL 단일 `product_cards`와 `product_board_card` 다대다 매핑**:
   - `ziper.product_cards`에 `user_id NOT NULL`과 `UNIQUE (user_id, product_url)` 제약을 두어 한 사용자의 동일 URL 상품을 단일 엔티티로 관리하고, 중복 URL 신규 등록은 `409 Conflict`로 차단한다.
   - `ziper.product_board_card (board_id, card_id)`로 여러 보드에 연결한다.
   - `ziper.product_boards`에는 `WHERE is_deleted = FALSE` 조건의 `(user_id, name)` 부분 고유 인덱스를 추가해 동일 사용자 내 활성 보드 이름 중복을 막는다.
2. **구매 완료 및 전체 삭제 시 보드 매핑 보존과 조회 필터링**:
   - 구매 완료(`status = 'PURCHASED'`)는 별도 보드 행 없이 상태 값으로 표현하고 전용 목록에서 상품당 1회 노출한다.
   - 상품 전체 삭제(`is_deleted = TRUE`)나 구매 완료 전환 시에도 3초 내 `[되돌리기]`로 원래 소속 보드들을 즉시 복원할 수 있도록 `product_board_card` 행은 물리 삭제하지 않고 유지하며, 일반 보드 조회 시 `status = 'PLANNED' AND is_deleted = FALSE`로 필터링한다.
   - 개별 보드에서만 상품을 빼거나 보드 자체를 삭제할 때만 해당 `product_board_card` 행을 지우고, 남은 활성 보드 연결이 0개(고아 상품)가 된 경우에만 `product_cards`를 휴지통(`is_deleted = TRUE`)으로 이동한다. 휴지통에서 지정 복구할 때는 기존 보드 매핑을 사용자가 새로 선택한 활성 보드 목록으로 교체한다.
3. **다중 해시태그와 이미지·대체 아이콘 분리**:
   - `ziper.tags (id, title UNIQUE, is_default)`에 기본 계절 태그 4종(`봄`, `여름`, `가을`, `겨울`)을 시드하고, `ziper.product_card_tags (card_id, tag_id)`로 카드당 0개 이상의 다중 태그를 매핑한다.
   - 상품 이미지는 외부 OG 이미지 직접 참조(`image_url`), 직접 업로드·서버 리사이징 파일 참조(`image_file_id` → `ziper.files.id`), 파싱 실패 시 카테고리 대체 아이콘(`fallback_icon`: `TOP`, `PANTS`, `SKIRT`, `ACCESSORY`, `BEAUTY`)을 모두 수용한다.
4. **재발견 쿨다운 상태와 이벤트 이력**:
   - `ziper.product_cards`에 `rediscovery_stage` (`0`: 14일, `1`: 30일, `2`: 90일), `last_interacted_at`, `next_rediscovery_at`, `is_link_valid`를 두어 재발견 대상 판정과 14일 즉시 리셋을 인덱스 기반으로 수행한다.
   - 조회·원본 이동·재발견 스와이프 이력은 `ziper.user_card_events`에 `event_type`과 `viewed_at`으로 기록한다.

## 대안

- **동일 URL이라도 보드마다 별도 `product_cards` 행 복제**: 상품명·메모·태그 수정 및 구매 완료·전체 삭제 시 URL 문자열 기준 일괄 갱신이 필요해 정합성 유지 비용이 크고, 재발견 1회 노출 시 중복 제거 쿼리가 복잡해진다.
- **구매 완료·전체 삭제 시 `product_board_card` 즉시 물리 삭제**: 3초 내 `[되돌리기]` 수행 시 원래 담겨 있던 보드 목록을 복원하기 위해 별도 백업 테이블이나 스냅샷 저장이 추가로 필요하다.
- **재발견 주기를 `user_card_events` 로그 집계만으로 매번 계산**: 카드 수가 늘어날수록 스와이프 큐 조회 시 이벤트 전체 스캔 비용이 커진다.

## 영향과 한계

- 일반 보드 내 상품 목록 쿼리는 반드시 `product_cards.status = 'PLANNED' AND product_cards.is_deleted = FALSE` 조건을 함께 걸어야 구매 완료·휴지통 상품이 노출되지 않는다.
- 휴지통에 보관 중인 상품(`is_deleted = TRUE`)도 `(user_id, product_url)` 고유 제약의 적용을 받으므로, 휴지통에 있는 동일 URL 상품을 다시 등록하려면 휴지통 복구 또는 영구 삭제가 선행되어야 한다.
