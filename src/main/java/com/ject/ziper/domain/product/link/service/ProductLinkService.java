package com.ject.ziper.domain.product.link.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.product.link.dto.ProductLinkStatusResponse;
import com.ject.ziper.domain.product.link.entity.ProductLink;
import com.ject.ziper.domain.product.link.entity.ProductLinkCheckSource;
import com.ject.ziper.domain.product.link.entity.ProductLinkReport;
import com.ject.ziper.domain.product.link.entity.ProductLinkStatus;
import com.ject.ziper.domain.product.link.repository.ProductCardLinkJdbcRepository;
import com.ject.ziper.domain.product.link.repository.ProductCardLinkJdbcRepository.ProductCardLinkSnapshot;
import com.ject.ziper.domain.product.link.repository.ProductLinkReportRepository;
import com.ject.ziper.domain.product.link.repository.ProductLinkRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;

@Service
public class ProductLinkService {
	private static final long FUTURE_CLOCK_SKEW_SECONDS = 30L;
	private static final Duration CONSENSUS_WINDOW = Duration.ofHours(24);
	private static final long CONSENSUS_THRESHOLD = 2L;

	private final ProductLinkRepository productLinkRepository;
	private final ProductLinkReportRepository productLinkReportRepository;
	private final ProductCardLinkJdbcRepository productCardLinkJdbcRepository;
	private final AdvisoryLockRepository locks;
	private final long reportCooldownSeconds;
	private final Clock clock;

	@Autowired
	public ProductLinkService(ProductLinkRepository productLinkRepository,
			ProductLinkReportRepository productLinkReportRepository,
			ProductCardLinkJdbcRepository productCardLinkJdbcRepository, AdvisoryLockRepository locks,
			@Value("${app.product.link-report-cooldown-seconds:60}") long reportCooldownSeconds) {
		this(productLinkRepository, productLinkReportRepository, productCardLinkJdbcRepository, locks,
				reportCooldownSeconds, Clock.systemUTC());
	}

	public ProductLinkService(ProductLinkRepository productLinkRepository,
			ProductLinkReportRepository productLinkReportRepository,
			ProductCardLinkJdbcRepository productCardLinkJdbcRepository, AdvisoryLockRepository locks,
			long reportCooldownSeconds, Clock clock) {
		this.productLinkRepository = productLinkRepository;
		this.productLinkReportRepository = productLinkReportRepository;
		this.productCardLinkJdbcRepository = productCardLinkJdbcRepository;
		this.locks = locks;
		this.reportCooldownSeconds = reportCooldownSeconds;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ProductLinkStatusResponse getLinkStatus(Long userId, Long cardId) {
		ProductCardLinkSnapshot card = productCardLinkJdbcRepository.findOwnedActiveCard(cardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND));
		return toResponse(card);
	}

	@Transactional
	public ProductLinkStatusResponse reportLinkStatus(Long userId, Long cardId, String statusValue,
			Instant observedAt) {
		ProductLinkStatus reportedStatus = ProductLinkStatus.fromReportValue(statusValue)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_LINK_STATUS_REPORT));
		if (observedAt == null) {
			throw new BusinessException(ErrorCode.INVALID_LINK_STATUS_REPORT);
		}
		Instant now = clock.instant();
		// 합의 창(24시간)보다 오래된 관측은 어떤 합의에도 쓰일 수 없으므로 DB 쓰기 전에 400으로 거부한다.
		if (observedAt.isBefore(now.minus(CONSENSUS_WINDOW))) {
			throw new BusinessException(ErrorCode.INVALID_LINK_STATUS_REPORT);
		}
		ProductCardLinkSnapshot card = productCardLinkJdbcRepository.findOwnedActiveCard(cardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND));

		Long linkId = card.linkId();
		if (linkId == null) {
			linkId = resolveOrCreateLink(card.productUrl(), null).getId();
			productCardLinkJdbcRepository.attachLinkId(card.cardId(), linkId, now);
		}

		locks.lock(linkIdLockKey(linkId));

		ProductCardLinkSnapshot lockedCard = productCardLinkJdbcRepository.findOwnedActiveCard(cardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND));

		if (reportCooldownSeconds > 0
				&& productLinkReportRepository.existsByUserIdAndLinkIdAndCreatedAtGreaterThanEqual(userId, linkId,
						now.minusSeconds(reportCooldownSeconds))) {
			throw new BusinessException(ErrorCode.LINK_STATUS_REPORT_RATE_LIMITED);
		}

		// 허용 오차를 넘는 미래 관측은 무시한다. 오차 이내 미래 시각은 서버 현재 시각으로 보정해
		// 미래 시각이 저장되어 이후 정상 관측이 "더 오래됨"으로 무시되는 일을 막는다.
		if (observedAt.isAfter(now.plusSeconds(FUTURE_CLOCK_SKEW_SECONDS))) {
			return toResponse(lockedCard);
		}
		Instant effectiveObservedAt = observedAt.isAfter(now) ? now : observedAt;
		if (lockedCard.linkCheckedAt() != null && !effectiveObservedAt.isAfter(lockedCard.linkCheckedAt())) {
			return toResponse(lockedCard);
		}

		productCardLinkJdbcRepository.updateCardLinkStatus(cardId, userId, linkId, reportedStatus, effectiveObservedAt,
				ProductLinkCheckSource.CLIENT, now);
		productLinkReportRepository
				.saveAndFlush(ProductLinkReport.create(linkId, userId, reportedStatus, effectiveObservedAt, now));

		// 합의는 사용자별 최신 보고 기준이며, 전파 시각은 합의에 쓰인 관측 중 가장 최신 시각이다.
		// 링크 상태는 상품 URL 단위 공유 사실이므로 휴지통 카드에도 전파한다(복구 시 최신 상태 노출, 의도된 동작).
		List<Instant> agreedObservedAts = productLinkReportRepository.findLatestObservedAtPerReporter(linkId,
				reportedStatus, now.minus(CONSENSUS_WINDOW));
		if (agreedObservedAts.size() >= CONSENSUS_THRESHOLD) {
			Instant consensusAt = Collections.max(agreedObservedAts);
			productLinkRepository.findById(linkId).ifPresent(link -> {
				if (link.getCheckedAt() == null || !consensusAt.isBefore(link.getCheckedAt())) {
					link.applyCheckResult(reportedStatus, consensusAt, ProductLinkCheckSource.CLIENT, now);
				}
			});
			productCardLinkJdbcRepository.propagateLinkStatus(linkId, cardId, reportedStatus, consensusAt,
					ProductLinkCheckSource.CLIENT, now);
		}

		return new ProductLinkStatusResponse(reportedStatus, effectiveObservedAt, ProductLinkCheckSource.CLIENT);
	}

	/**
	 * 정규화 URL의 링크를 찾거나 만들고, 상태
	 * 갱신({@link #reportLinkStatus}·{@link #applyServerCheck})과 같은
	 * {@code link-id:{id}} 잠금을 호출 트랜잭션이 끝날 때까지 잡은 채 최신 상태로 반환한다. 이 잠금 때문에 호출자가 반환된
	 * 상태로 카드를 저장하면, 그 사이에 커밋된 상태 전파를 놓치지 않는다(저장 이후의 전파는 새 카드까지 갱신한다). 잠금 순서는
	 * {@code link:{uuid}} → {@code link-id:{id}}이며, 호출자는 이 뒤에 {@code board:}·{@code
	 * product-card:} 잠금을 잡지 않는다.
	 */
	@Transactional
	public ProductLink resolveOrCreateLink(String canonicalUrl, String shop) {
		if (canonicalUrl == null || canonicalUrl.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		String normalizedUrl = canonicalUrl.trim();
		locks.lock(linkUrlLockKey(normalizedUrl));
		Instant now = clock.instant();
		Optional<Long> existingId = productLinkRepository.findIdByCanonicalUrl(normalizedUrl);
		if (existingId.isPresent()) {
			// 잠금을 잡은 뒤에 엔티티를 처음 읽어야 다른 트랜잭션이 방금 커밋한 상태를 본다.
			locks.lock(linkIdLockKey(existingId.get()));
			ProductLink existing = productLinkRepository.findById(existingId.get())
					.orElseThrow(() -> new IllegalStateException("product_links row disappeared: " + existingId.get()));
			existing.updateShopIfBlank(shop, now);
			return existing;
		}
		ProductLink created = productLinkRepository.saveAndFlush(ProductLink.create(normalizedUrl, shop, now));
		locks.lock(linkIdLockKey(created.getId()));
		return created;
	}

	@Transactional
	public void applyServerCheck(Long linkId, ProductLinkStatus status, Instant checkedAt) {
		// 서버 요청은 봇 차단 등으로 정상 상품도 404를 받을 수 있으므로 REMOVED(삭제 확정)는
		// 실사용자 WebView 보고·합의로만 전파한다.
		if (linkId == null || status == null || checkedAt == null || status == ProductLinkStatus.UNKNOWN
				|| status == ProductLinkStatus.REMOVED) {
			return;
		}
		locks.lock(linkIdLockKey(linkId));
		Instant now = clock.instant();
		ProductLink link = productLinkRepository.findById(linkId).orElse(null);
		if (link == null) {
			return;
		}
		if (link.getCheckedAt() == null || !checkedAt.isBefore(link.getCheckedAt())) {
			link.applyCheckResult(status, checkedAt, ProductLinkCheckSource.SERVER, now);
		}
		productCardLinkJdbcRepository.propagateLinkStatus(linkId, null, status, checkedAt,
				ProductLinkCheckSource.SERVER, now);
	}

	private static String linkIdLockKey(Long linkId) {
		return "link-id:" + linkId;
	}

	private static String linkUrlLockKey(String normalizedUrl) {
		return "link:" + UUID.nameUUIDFromBytes(normalizedUrl.getBytes(StandardCharsets.UTF_8));
	}

	private static ProductLinkStatusResponse toResponse(ProductCardLinkSnapshot card) {
		return new ProductLinkStatusResponse(card.linkStatus(), card.linkCheckedAt(), card.linkCheckSource());
	}
}
