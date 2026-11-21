package com.ject.ziper.domain.file.repository;

public interface ProductCardImageUpdater {
	void markMirrorReady(Long cardId, Long fileId);

	void markMirrorFailed(Long cardId);
}
