package com.ject.ziper.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

class NaverOAuthProviderStrategyTest {
	private static final String USER_INFO_URI = "https://openapi.naver.com/v1/nid/me";

	@Test
	void 유효한Naver액세스토큰_사용자정보를조회하면_식별자와선택이메일을추출한다() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		NaverOAuthProviderStrategy strategy = new NaverOAuthProviderStrategy(builder.build(), USER_INFO_URI);

		server.expect(requestTo(USER_INFO_URI)).andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer naver-access-token")).andRespond(withSuccess("""
						{
						  "resultcode": "00",
						  "message": "success",
						  "response": {
						    "id": "naver-user-001",
						    "email": "user@naver.com"
						  }
						}
						""", MediaType.APPLICATION_JSON));

		OAuthUserPrincipal principal = strategy.authenticate("naver-access-token");

		assertThat(principal.provider()).isEqualTo(OAuthProvider.NAVER);
		assertThat(principal.providerId()).isEqualTo("naver-user-001");
		assertThat(principal.email()).isEqualTo("user@naver.com");
		server.verify();

		server.reset();
		server.expect(requestTo(USER_INFO_URI)).andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer naver-access-token-no-email"))
				.andRespond(withSuccess("""
						{
						  "resultcode": "00",
						  "message": "success",
						  "response": {
						    "id": "naver-user-002"
						  }
						}
						""", MediaType.APPLICATION_JSON));

		OAuthUserPrincipal principalWithoutEmail = strategy.authenticate("naver-access-token-no-email");

		assertThat(principalWithoutEmail.provider()).isEqualTo(OAuthProvider.NAVER);
		assertThat(principalWithoutEmail.providerId()).isEqualTo("naver-user-002");
		assertThat(principalWithoutEmail.email()).isNull();
		server.verify();
	}

	@Test
	void 빈토큰이나오류응답또는식별자누락_Naver인증을요청하면_UNAUTHORIZED예외를던진다() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		NaverOAuthProviderStrategy strategy = new NaverOAuthProviderStrategy(builder.build(), USER_INFO_URI);

		assertThatThrownBy(() -> strategy.authenticate(" ")).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
		server.verify();

		server.expect(requestTo(USER_INFO_URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
		assertThatThrownBy(() -> strategy.authenticate("invalid-token")).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
		server.verify();

		server.reset();
		server.expect(requestTo(USER_INFO_URI)).andRespond(withSuccess("""
				{
				  "resultcode": "024",
				  "message": "Authentication failed"
				}
				""", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> strategy.authenticate("failed-result")).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
		server.verify();

		server.reset();
		server.expect(requestTo(USER_INFO_URI)).andRespond(withSuccess("""
				{
				  "resultcode": "00",
				  "message": "success",
				  "response": {
				    "id": " "
				  }
				}
				""", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> strategy.authenticate("blank-id")).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
		server.verify();
	}
}
