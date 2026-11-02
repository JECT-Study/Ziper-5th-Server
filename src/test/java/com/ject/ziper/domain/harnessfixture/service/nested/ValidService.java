package com.ject.ziper.domain.harnessfixture.service.nested;

public class ValidService {
	public void execute() {
		throw new IllegalStateException("technical failure propagates");
	}
}
