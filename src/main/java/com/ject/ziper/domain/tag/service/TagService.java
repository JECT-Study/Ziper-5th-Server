package com.ject.ziper.domain.tag.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.tag.dto.TagListResponse;
import com.ject.ziper.domain.tag.dto.TagResponse;
import com.ject.ziper.domain.tag.entity.Tag;
import com.ject.ziper.domain.tag.repository.TagCardLinkRepository;
import com.ject.ziper.domain.tag.repository.TagRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;

@Service
public class TagService {
	public static final int MAX_TAG_LENGTH = 6;
	private static final String LOCK_PREFIX = "tag:";

	private final TagRepository tagRepository;
	private final TagCardLinkRepository tagCardLinkRepository;
	private final AdvisoryLockRepository advisoryLockRepository;

	public TagService(TagRepository tagRepository, TagCardLinkRepository tagCardLinkRepository,
			AdvisoryLockRepository advisoryLockRepository) {
		this.tagRepository = tagRepository;
		this.tagCardLinkRepository = tagCardLinkRepository;
		this.advisoryLockRepository = advisoryLockRepository;
	}

	@Transactional(readOnly = true)
	public TagListResponse listTags(Long userId) {
		List<TagResponse> tags = tagRepository.findAccessibleByUserId(userId).stream().map(TagResponse::from).toList();
		return new TagListResponse(tags);
	}

	@Transactional
	public TagResponse createTag(Long userId, String rawTitle) {
		String normalizedTitle = normalizeTitle(rawTitle);
		advisoryLockRepository.lock(LOCK_PREFIX + userId);
		if (tagRepository.existsAccessibleTitle(userId, normalizedTitle)) {
			throw new BusinessException(ErrorCode.DUPLICATE_TAG);
		}
		Tag saved = tagRepository.save(Tag.createCustom(userId, normalizedTitle, Instant.now()));
		return TagResponse.from(saved);
	}

	@Transactional
	public void deleteTag(Long userId, Long tagId, Long cardId) {
		advisoryLockRepository.lock(LOCK_PREFIX + userId);
		Tag tag = tagRepository.findById(tagId).orElseThrow(() -> new BusinessException(ErrorCode.TAG_NOT_FOUND));
		if (tag.isDefault()) {
			throw new BusinessException(ErrorCode.DEFAULT_TAG_DELETE_FORBIDDEN);
		}
		if (!userId.equals(tag.getUserId())) {
			throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
		}
		if (cardId != null) {
			if (!tagCardLinkRepository.existsCardOwnedByUser(cardId, userId)) {
				throw new BusinessException(ErrorCode.NOT_FOUND);
			}
			int otherLinkedCount = tagCardLinkRepository.countOtherLinkedCards(tagId, cardId);
			if (otherLinkedCount > 0) {
				tagCardLinkRepository.unlinkCardTag(cardId, tagId);
				return;
			}
		}
		tagRepository.delete(tag);
	}

	@Transactional
	public List<Tag> resolveOrCreateForCard(Long userId, List<String> rawTitles) {
		if (rawTitles == null || rawTitles.isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_TAG_TITLE);
		}
		Set<String> normalizedTitles = new LinkedHashSet<>();
		for (String rawTitle : rawTitles) {
			normalizedTitles.add(normalizeTitle(rawTitle));
		}
		advisoryLockRepository.lock(LOCK_PREFIX + userId);
		Map<String, Tag> existingByTitle = tagRepository.findAccessibleByUserIdAndTitleIn(userId, normalizedTitles)
				.stream().collect(Collectors.toMap(Tag::getTitle, Function.identity(),
						(first, second) -> first.isDefault() ? first : second));
		Instant now = Instant.now();
		List<Tag> resolved = new ArrayList<>(normalizedTitles.size());
		for (String title : normalizedTitles) {
			Tag existing = existingByTitle.get(title);
			if (existing != null) {
				resolved.add(existing);
			} else {
				Tag created = tagRepository.save(Tag.createCustom(userId, title, now));
				existingByTitle.put(title, created);
				resolved.add(created);
			}
		}
		return List.copyOf(resolved);
	}

	public static String normalizeTitle(String rawTitle) {
		if (rawTitle == null) {
			throw new BusinessException(ErrorCode.INVALID_TAG_TITLE);
		}
		String stripped = rawTitle.strip();
		int index = 0;
		while (index < stripped.length() && stripped.charAt(index) == '#') {
			index++;
		}
		String normalized = stripped.substring(index).strip();
		int length = normalized.codePointCount(0, normalized.length());
		if (normalized.isEmpty() || length > MAX_TAG_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_TAG_TITLE);
		}
		return normalized;
	}
}
