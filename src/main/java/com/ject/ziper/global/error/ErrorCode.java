package com.ject.ziper.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."), UNAUTHORIZED(HttpStatus.UNAUTHORIZED,
			"인증이 필요합니다."), FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."), NOT_FOUND(HttpStatus.NOT_FOUND,
					"요청한 리소스를 찾을 수 없습니다."), METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED,
							"지원하지 않는 요청 방식입니다."), CONFLICT(HttpStatus.CONFLICT,
									"현재 상태에서는 요청을 처리할 수 없습니다."), CLIENT_ERROR(HttpStatus.BAD_REQUEST,
											"요청을 처리할 수 없습니다."), INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR,
													"서버 오류가 발생했습니다.");

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
