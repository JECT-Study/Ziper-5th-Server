package com.ject.ziper.domain.harnessfixture.nested;

import jakarta.servlet.http.HttpServletRequest;

public class DiscardedSessionCall {
	public void execute(HttpServletRequest request) {
		request.getSession();
	}
}
