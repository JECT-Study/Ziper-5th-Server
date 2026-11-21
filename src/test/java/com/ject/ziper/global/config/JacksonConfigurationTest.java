package com.ject.ziper.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

class JacksonConfigurationTest {
	private final ObjectMapper mapper = mapper();

	record Product(String name, String memo, Long price, int count) {
	}

	record Tags(List<String> tags, Map<String, String> extra) {
	}

	private static ObjectMapper mapper() {
		Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
		new JacksonConfiguration().strictJsonInputCustomizer().customize(builder);
		return builder.build();
	}

	@ParameterizedTest
	@ValueSource(strings = {"\\u0000", "a\\u0000b", "\\u0001", "\\u0008", "\\u000b", "\\u000c", "\\u001f"})
	void 허용하지않는제어문자가담긴문자열_역직렬화하면_거부한다(String escaped) {
		String json = "{\"name\":\"" + escaped + "\",\"price\":1,\"count\":1}";

		assertThatThrownBy(() -> mapper.readValue(json, Product.class)).isInstanceOf(JsonMappingException.class);
	}

	@Test
	void 컬렉션과맵안의NUL_역직렬화하면_거부한다() {
		assertThatThrownBy(() -> mapper.readValue("{\"tags\":[\"a\\u0000\"]}", Tags.class))
				.isInstanceOf(JsonMappingException.class);
		assertThatThrownBy(() -> mapper.readValue("{\"extra\":{\"k\":\"\\u0000\"}}", Tags.class))
				.isInstanceOf(JsonMappingException.class);
	}

	@Test
	void 줄바꿈과탭이담긴메모_역직렬화하면_그대로유지한다() throws Exception {
		Product product = mapper.readValue(
				"{\"name\":\"가방\",\"memo\":\"1줄\\n2줄\\r\\n\\t끝\",\"price\":29000,\"count\":2}", Product.class);

		assertThat(product.memo()).isEqualTo("1줄\n2줄\r\n\t끝");
		assertThat(product.price()).isEqualTo(29000L);
		assertThat(product.count()).isEqualTo(2);
	}

	@Test
	void 일반문자열과null_역직렬화하면_그대로유지한다() throws Exception {
		Product product = mapper.readValue("{\"name\":\"이모지 😀\",\"memo\":null,\"price\":null,\"count\":0}",
				Product.class);

		assertThat(product.name()).isEqualTo("이모지 😀");
		assertThat(product.memo()).isNull();
		assertThat(product.price()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"29000.9", "29000.0", "1e3", "\"29000\"", "true", "\"\"", "[]"})
	void 정수필드에실수문자열불리언이주어졌을때_역직렬화하면_거부한다(String value) {
		assertThatThrownBy(() -> mapper.readValue("{\"price\":" + value + ",\"count\":1}", Product.class))
				.isInstanceOf(JsonMappingException.class);
		assertThatThrownBy(() -> mapper.readValue("{\"price\":1,\"count\":" + value + "}", Product.class))
				.isInstanceOf(JsonMappingException.class);
	}

	@Test
	void 정수필드에정수가주어졌을때_역직렬화하면_허용한다() throws Exception {
		Product product = mapper.readValue("{\"price\":9223372036854775807,\"count\":-3}", Product.class);

		assertThat(product.price()).isEqualTo(Long.MAX_VALUE);
		assertThat(product.count()).isEqualTo(-3);
	}
}
