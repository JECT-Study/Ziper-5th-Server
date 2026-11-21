package com.ject.ziper.global.config;

import java.io.IOException;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.type.LogicalType;

/**
 * 모든 JSON 요청 본문에 적용하는 엄격한 입력 규칙이다. 문자열의 NUL·허용하지 않는 C0 제어 문자와 정수 필드의 실수·문자열·불리언
 * 강제 변환을 역직렬화 단계에서 거부해 전역 오류 처리기가 400 INVALID_REQUEST로 응답하게 한다.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfiguration {

	@Bean
	Jackson2ObjectMapperBuilderCustomizer strictJsonInputCustomizer() {
		return builder -> builder.deserializerByType(String.class, new StrictStringDeserializer())
				.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
				.postConfigurer(JacksonConfiguration::rejectIntegerCoercion);
	}

	static void rejectIntegerCoercion(ObjectMapper mapper) {
		mapper.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.EmptyArray, CoercionAction.Fail);
	}

	/**
	 * 탭·줄바꿈·캐리지 리턴은 메모 같은 여러 줄 입력에 쓰이고 PostgreSQL text가 받아들이므로 허용한다. NUL을 포함한 나머지
	 * C0 제어 문자(U+0000~U+001F)는 거부한다.
	 */
	static final class StrictStringDeserializer extends StdDeserializer<String> {
		private static final long serialVersionUID = 1L;

		StrictStringDeserializer() {
			super(String.class);
		}

		@Override
		public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
			String value = StringDeserializer.instance.deserialize(parser, context);
			if (value != null && hasForbiddenControl(value)) {
				return (String) context.handleWeirdStringValue(String.class, "", "허용하지 않는 제어 문자");
			}
			return value;
		}

		static boolean hasForbiddenControl(String value) {
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
					return true;
				}
			}
			return false;
		}
	}
}
