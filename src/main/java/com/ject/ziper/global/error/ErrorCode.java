package com.ject.ziper.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."), UNAUTHORIZED(HttpStatus.UNAUTHORIZED,
			"인증이 필요합니다."), FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."), NOT_FOUND(HttpStatus.NOT_FOUND,
					"요청한 리소스를 찾을 수 없습니다."), METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED,
							"지원하지 않는 요청 방식입니다."), CONFLICT(HttpStatus.CONFLICT,
									"현재 상태에서는 요청을 처리할 수 없습니다."), CLIENT_ERROR(HttpStatus.BAD_REQUEST,
											"요청을 처리할 수 없습니다."), INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR,
													"서버 오류가 발생했습니다."), INVALID_NICKNAME(HttpStatus.BAD_REQUEST,
															"닉네임은 한글, 영문, 숫자만 사용해 2~10자로 입력해 주세요."), DUPLICATE_NICKNAME(
																	HttpStatus.CONFLICT,
																	"이미 사용 중인 닉네임입니다."), EMAIL_REQUEST_LIMITED(
																			HttpStatus.TOO_MANY_REQUESTS,
																			"잠시 후 다시 요청해 주세요."), EMAIL_FLOW_INVALID(
																					HttpStatus.BAD_REQUEST,
																					"인증을 다시 시작해 주세요."), EMAIL_CODE_INVALID(
																							HttpStatus.BAD_REQUEST,
																							"인증번호가 일치하지 않습니다."), EMAIL_CODE_EXPIRED(
																									HttpStatus.GONE,
																									"인증번호가 만료되었습니다."), EMAIL_CODE_ATTEMPTS_EXCEEDED(
																											HttpStatus.TOO_MANY_REQUESTS,
																											"인증을 완료하지 못했어요.");

	private final HttpStatus status;
	private final String detail;

	ErrorCode(HttpStatus status, String detail) {
		this.status = status;
		this.detail = detail;
	}

	public HttpStatus status() {
		return status;
	}
	public String detail() {
		return detail;
	}

	public static ErrorCode forStatus(int status) {
		for (ErrorCode code : values()) {
			if (code.status.value() == status) {
				return code;
			}
		}
		return status >= 500 ? INTERNAL_ERROR : CLIENT_ERROR;
	}
}
