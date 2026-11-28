package com.ject.ziper.domain.trash.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.trash.repository.TrashQueryRepository;
import com.ject.ziper.domain.trash.repository.TrashQueryRepository.TrashProductRow;

@Service
public class TrashQueryService {
	private final TrashQueryRepository repository;

	public TrashQueryService(TrashQueryRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public List<TrashProductRow> loadProducts(Long userId) {
		return repository.findUnexpiredProducts(userId);
	}
}
