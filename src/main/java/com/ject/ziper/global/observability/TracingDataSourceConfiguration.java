package com.ject.ziper.global.observability;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.p6spy.engine.spy.P6SpyDriver;
import com.zaxxer.hikari.HikariDataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sentry.enabled", havingValue = "true")
@EnableConfigurationProperties(DataSourceProperties.class)
class TracingDataSourceConfiguration {

	@Bean
	@ConditionalOnMissingBean(DataSource.class)
	@ConfigurationProperties("spring.datasource.hikari")
	HikariDataSource dataSource(DataSourceProperties properties) {
		String url = properties.determineUrl();
		if (!url.startsWith("jdbc:")) {
			throw new IllegalArgumentException("Tracing requires a JDBC datasource");
		}
		String tracedUrl = url.startsWith("jdbc:p6spy:") ? url : "jdbc:p6spy:" + url.substring("jdbc:".length());
		return properties.initializeDataSourceBuilder().type(HikariDataSource.class)
				.driverClassName(P6SpyDriver.class.getName()).url(tracedUrl).build();
	}
}
