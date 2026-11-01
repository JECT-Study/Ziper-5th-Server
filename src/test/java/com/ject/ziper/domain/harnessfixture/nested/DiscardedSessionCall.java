package com.ject.ziper.domain.harnessfixture.nested;

public class DiscardedSessionCall {
	public void execute(jakarta.servlet.http.HttpServletRequest request) {
		request.getSession();
	}
}
