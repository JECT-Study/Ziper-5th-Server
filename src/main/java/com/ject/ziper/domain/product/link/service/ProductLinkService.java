package com.ject.ziper.domain.product.link.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
		ProductCardLinkSnapshot card = productCardLinkJdbcRepository.findOwnedActiveCard(cardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND));

		Instant now = clock.instant();
		Long linkId = card.linkId();
		if (linkId == null) {
			linkId = resolveOrCreateLink(card.productUrl(), null).getId();
			productCardLinkJdbcRepository.attachLinkId(card.cardId(), linkId, now);
		}

		locks.lock("link-id:" + linkId);

		ProductCardLinkSnapshot lockedCard = productCardLinkJdbcRepository.findOwnedActiveCard(cardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND));

		if (reportCooldownSeconds > 0
				&& productLinkReportRepository.existsByUserIdAndLinkIdAndCreatedAtGreaterThanEqual(userId, linkId,
						now.minusSeconds(reportCooldownSeconds))) {
			throw new BusinessException(ErrorCode.LINK_STATUS_REPORT_RATE_LIMITED);
		}

		if (observedAt.isAfter(now.plusSeconds(FUTURE_CLOCK_SKEW_SECONDS))
				|| (lockedCard.linkCheckedAt() != null && !observedAt.isAfter(lockedCard.linkCheckedAt()))) {
			return toResponse(lockedCard);
		}

		productCardLinkJdbcRepository.updateCardLinkStatus(cardId, userId, linkId, reportedStatus, observedAt,
				ProductLinkCheckSource.CLIENT, now);
		productLinkReportRepository
				.saveAndFlush(ProductLinkReport.create(linkId, userId, reportedStatus, observedAt, now));

		long distinctReporters = productLinkReportRepository.countDistinctReportersSince(linkId, reportedStatus,
				now.minus(CONSENSUS_WINDOW));
		if (distinctReporters >= CONSENSUS_THRESHOLD) {
			productLinkRepository.findById(linkId).ifPresent(link -> {
				if (link.getCheckedAt() == null || !observedAt.isBefore(link.getCheckedAt())) {
					link.applyCheckResult(reportedStatus, observedAt, ProductLinkCheckSource.CLIENT, now);
				}
			});
			productCardLinkJdbcRepository.propagateLinkStatus(linkId, cardId, reportedStatus, observedAt,
					ProductLinkCheckSource.CLIENT, now);
		}

		return new ProductLinkStatusResponse(reportedStatus, observedAt, ProductLinkCheckSource.CLIENT);
	}

	@Transactional
	public ProductLink resolveOrCreateLink(String canonicalUrl, String shop) {
		if (canonicalUrl == null || canonicalUrl.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		String normalizedUrl = canonicalUrl.trim();
		locks.lock(linkUrlLockKey(normalizedUrl));
		Instant now = clock.instant();
		return productLinkRepository.findByCanonicalUrl(normalizedUrl).map(existing -> {
			existing.updateShopIfBlank(shop, now);
			return existing;
		}).orElseGet(() -> productLinkRepository.saveAndFlush(ProductLink.create(normalizedUrl, shop, now)));
	}

	@Transactional
	public void applyServerCheck(Long linkId, ProductLinkStatus status, Instant checkedAt) {
		if (linkId == null || status == null || checkedAt == null || status == ProductLinkStatus.UNKNOWN) {
			return;
		}
		locks.lock("link-id:" + linkId);
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

	private static String linkUrlLockKey(String normalizedUrl) {
		return "link:" + UUID.nameUUIDFromBytes(normalizedUrl.getBytes(StandardCharsets.UTF_8));
	}

	private static ProductLinkStatusResponse toResponse(ProductCardLinkSnapshot card) {
		return new ProductLinkStatusResponse(card.linkStatus(), card.linkCheckedAt(), card.linkCheckSource());
	}
}
