package com.ject.ziper.domain.trash.batch;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TrashCleanupProperties.class)
public class TrashCleanupConfiguration {
}
