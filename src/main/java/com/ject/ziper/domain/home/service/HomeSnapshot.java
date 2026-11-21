package com.ject.ziper.domain.home.service;

import java.util.List;

import com.ject.ziper.domain.home.repository.HomeQueryRepository.BoardPreviewImageRow;
import com.ject.ziper.domain.home.repository.HomeQueryRepository.BoardSummaryRow;
import com.ject.ziper.domain.home.repository.HomeQueryRepository.RecentProductRow;

/**
 * 읽기 트랜잭션 안에서 조회한 홈 화면 원본 행. 서명 URL은 아직 해석되지 않았다.
 */
public record HomeSnapshot(long rediscoveryCount, List<BoardSummaryRow> boardRows,
		List<BoardPreviewImageRow> previewRows, List<RecentProductRow> recentRows) {
}
