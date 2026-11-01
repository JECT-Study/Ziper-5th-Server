package com.ject.ziper.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OpenApiConfigurationTest {
	@Test
	void 프로필과실행모드조합_Swagger엔드포인트를켜면_로컬단독프로필과로컬실행모드에서만허용한다() {
		for (String[] profiles : new String[][]{{}, {"prod"}, {"dev"}, {"test"}, {"local", "prod"}}) {
			for (String property : new String[]{"springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled"}) {
				var environment = new MockEnvironment().withProperty(property, "true").withProperty("app.runtime-mode",
						"local");
				environment.setActiveProfiles(profiles);
				assertThatIllegalStateException().isThrownBy(() -> new OpenApiConfiguration(environment));
			}
		}
		var environment = new MockEnvironment().withProperty("springdoc.api-docs.enabled", "true");
		environment.setActiveProfiles("local");
		assertThatIllegalStateException().isThrownBy(() -> new OpenApiConfiguration(environment));
		environment.withProperty("app.runtime-mode", "local").withProperty("springdoc.swagger-ui.enabled", "true");
		assertThat(new OpenApiConfiguration(environment).enabled()).isTrue();
		assertThat(new OpenApiConfiguration(new MockEnvironment()).enabled()).isFalse();
	}
}
