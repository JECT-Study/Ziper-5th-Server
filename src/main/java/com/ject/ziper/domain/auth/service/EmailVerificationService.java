package com.ject.ziper.domain.auth.service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.ject.ziper.domain.auth.repository.EmailVerificationRepository;
import com.ject.ziper.domain.auth.repository.EmailVerificationRepository.AuthCode;
import com.ject.ziper.domain.auth.repository.EmailVerificationRepository.EmailFailures;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.security.EmailCodeHmac;

@Service
@ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "true")
public class EmailVerificationService {
	private static final Duration CODE_LIFETIME = Duration.ofMinutes(3);
	private static final Duration LIMIT_WINDOW = Duration.ofHours(1);
	private static final int REQUESTER_HOURLY_LIMIT = 20;
	private static final int INPUT_LIMIT = 5;
	// 이메일별 오입력 5회에 도달하면 이 시간 동안 발송·재발송·확인을 막고, 지나면 횟수를 초기화한다.
	// 잠기지 않은 실패 기록도 마지막 실패 후 이 시간이 지나면 초기화한다.
	private static final Duration LOCK_DURATION = Duration.ofMinutes(30);

	private final EmailVerificationRepository repository;
	private final JavaMailSender mailSender;
	private final TransactionTemplate transactions;
	private final EmailCodeHmac hmac;
	private final String from;
	private final SecureRandom random = new SecureRandom();

	public EmailVerificationService(EmailVerificationRepository repository, JavaMailSender mailSender,
			TransactionTemplate transactions, @Value("${app.mail.code-secret-base64:}") String secretBase64,
			@Value("${app.mail.from:}") String from) {
		if (from.isBlank()) {
			throw new IllegalArgumentException("Email sender address is required");
		}
		this.repository = repository;
		this.mailSender = mailSender;
		this.transactions = transactions;
		this.hmac = new EmailCodeHmac(secretBase64);
		this.from = from;
	}

	public UUID issue(String address, String requester) {
		String email = normalize(address);
		String requesterKey = hmac.hash("requester:" + requester);
		UUID flowId = UUID.randomUUID();
		String code = code();
		transactions.executeWithoutResult(status -> {
			repository.lock("email:" + email);
			repository.lock("requester:" + requesterKey);
			rejectLocked(email, Instant.now());
			checkLimits(email, requesterKey);
			insert(flowId, email, requesterKey, code);
		});
		send(email, code);
		return flowId;
	}

	public void resend(UUID flowId, String requester) {
		String requesterKey = hmac.hash("requester:" + requester);
		String code = code();
		String email = transactions.execute(status -> {
			repository.lock("flow:" + flowId);
			AuthCode latest = activeFlow(flowId);
			repository.lock("email:" + latest.email());
			repository.lock("requester:" + requesterKey);
			rejectLocked(latest.email(), Instant.now());
			checkLimits(latest.email(), requesterKey);
			insert(flowId, latest.email(), requesterKey, code);
			return latest.email();
		});
		send(email, code);
	}

	public void verify(UUID flowId, String code) {
		ErrorCode failure = transactions.execute(status -> {
			repository.lock("flow:" + flowId);
			AuthCode latest = activeFlow(flowId);
			repository.lock("email:" + latest.email());
			Instant now = Instant.now();
			FailureState state = failureState(latest.email(), now);
			if (state.locked()) {
				return ErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED;
			}
			if (!latest.expiredAt().isAfter(now)) {
				return ErrorCode.EMAIL_CODE_EXPIRED;
			}
			byte[] expected = HexFormat.of().parseHex(latest.codeHash());
			byte[] actual = HexFormat.of().parseHex(hmac.hash("code:" + flowId + ":" + code));
			if (!MessageDigest.isEqual(expected, actual)) {
				repository.incrementFailure(latest.id());
				int failures = state.failedAttempts() + 1;
				Instant lockedUntil = failures >= INPUT_LIMIT ? now.plus(LOCK_DURATION) : null;
				repository.saveFailures(latest.email(), failures, now, lockedUntil);
				return lockedUntil != null ? ErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED : ErrorCode.EMAIL_CODE_INVALID;
			}
			repository.markVerified(latest.id(), now);
			repository.clearFailures(latest.email());
			return null;
		});
		if (failure != null) {
			throw new BusinessException(failure);
		}
	}

	public VerificationStatus status(UUID flowId) {
		return transactions.execute(status -> {
			repository.lock("flow:" + flowId);
			AuthCode latest = repository.latestByFlow(flowId);
			if (latest == null) {
				throw new BusinessException(ErrorCode.EMAIL_FLOW_INVALID);
			}
			FailureState failures = failureState(latest.email(), Instant.now());
			int failedAttempts = failures.failedAttempts();
			long remainingSeconds = latest.verified() || latest.consumed()
					? 0
					: Math.max(0, (Duration.between(Instant.now(), latest.expiredAt()).toMillis() + 999) / 1000);
			return new VerificationStatus(failedAttempts, Math.max(0, INPUT_LIMIT - failedAttempts), latest.expiredAt(),
					remainingSeconds, latest.verified(), failures.lockedUntil());
		});
	}

	private AuthCode activeFlow(UUID flowId) {
		AuthCode latest = repository.latestByFlow(flowId);
		if (latest == null || latest.verified() || latest.consumed()) {
			throw new BusinessException(ErrorCode.EMAIL_FLOW_INVALID);
		}
		return latest;
	}

	private void rejectLocked(String email, Instant now) {
		if (failureState(email, now).locked()) {
			throw new BusinessException(ErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED);
		}
	}

	private FailureState failureState(String email, Instant now) {
		EmailFailures failures = repository.failuresByEmail(email);
		if (failures == null) {
			return FailureState.NONE;
		}
		if (failures.lockedUntil() != null) {
			return failures.lockedUntil().isAfter(now)
					? new FailureState(failures.failedAttempts(), failures.lockedUntil())
					: FailureState.NONE;
		}
		return failures.lastFailedAt().plus(LOCK_DURATION).isAfter(now)
				? new FailureState(failures.failedAttempts(), null)
				: FailureState.NONE;
	}

	private void checkLimits(String email, String requesterKey) {
		Instant now = Instant.now();
		Instant previous = repository.lastRequestedAt(email);
		if ((previous != null && previous.isAfter(now.minus(CODE_LIFETIME)))
				|| repository.countByRequester(requesterKey, now.minus(LIMIT_WINDOW)) >= REQUESTER_HOURLY_LIMIT) {
			throw new BusinessException(ErrorCode.EMAIL_REQUEST_LIMITED);
		}
	}

	private void insert(UUID flowId, String email, String requesterKey, String code) {
		Instant now = Instant.now();
		repository.insert(flowId, email, hmac.hash("code:" + flowId + ":" + code), requesterKey, now,
				now.plus(CODE_LIFETIME));
	}

	private String code() {
		return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
	}

	private void send(String email, String code) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(email);
		message.setSubject("집어 이메일 인증번호");
		message.setText("인증번호: " + code + "\n3분 안에 입력해 주세요.\n요청하지 않았다면 이 메일을 무시해 주세요.");
		mailSender.send(message);
	}

	private String normalize(String address) {
		String email = address.trim().toLowerCase(Locale.ROOT);
		if (email.length() > 255 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return email;
	}

	public record VerificationStatus(int failedAttempts, int remainingAttempts, Instant expiresAt,
			long remainingSeconds, boolean verified, Instant lockedUntil) {
	}

	private record FailureState(int failedAttempts, Instant lockedUntil) {
		static final FailureState NONE = new FailureState(0, null);

		boolean locked() {
			return lockedUntil != null;
		}
	}
}
