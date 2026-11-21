package com.ject.ziper.domain.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ject.ziper.domain.tag.service.TagService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

class TagServiceUnitTest {
	@ParameterizedTest
	@CsvSource({"'##봄', '봄'", "' #데이트 ', '데이트'", "'# 데이트 ', '데이트'", "'가나다라마바', '가나다라마바'", "'  #가나다라마바  ', '가나다라마바'",
			"'🌸봄여름가을', '🌸봄여름가을'"})
	void 유효한해시태그문자열_정규화를수행하면_선행샵과공백이제거된문자열을반환한다(String rawTitle, String expected) {
		assertThat(TagService.normalizeTitle(rawTitle)).isEqualTo(expected);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "#", "###", " # ", "  ##  ", "가나다라마바사", "#가나다라마바사", "🌸봄여름가을겨"})
	void 공백또는길이초과해시태그_정규화를수행하면_잘못된태그제목예외를발생시킨다(String rawTitle) {
		assertThatThrownBy(() -> TagService.normalizeTitle(rawTitle)).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).errorCode()).isEqualTo(ErrorCode.INVALID_TAG_TITLE);
	}
}
