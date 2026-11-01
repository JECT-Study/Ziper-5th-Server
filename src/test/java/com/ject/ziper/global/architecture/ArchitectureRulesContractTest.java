package com.ject.ziper.global.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ject.ziper.domain.harnessfixture.controller.nested.InvalidController;
import com.ject.ziper.domain.harnessfixture.controller.nested.ValidController;
import com.ject.ziper.domain.harnessfixture.errors.InvalidDomainException;
import com.ject.ziper.domain.harnessfixture.nested.DiscardedSessionCall;
import com.ject.ziper.domain.harnessfixture.nested.SessionDependency;
import com.ject.ziper.domain.harnessfixture.nested.ValidRequest;
import com.ject.ziper.domain.harnessfixture.service.nested.MvcService;
import com.ject.ziper.domain.harnessfixture.service.nested.ServletService;
import com.ject.ziper.domain.harnessfixture.service.nested.ValidService;
import com.ject.ziper.global.error.BusinessException;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

class ArchitectureRulesContractTest {
	@Test
	void controllerRuleRejectsRepositoryDependencyInNestedPackage() {
		assertRejected(ArchitectureRules.CONTROLLERS_DO_NOT_ACCESS_REPOSITORIES, InvalidController.class);
		assertAccepted(ArchitectureRules.CONTROLLERS_DO_NOT_ACCESS_REPOSITORIES, ValidController.class);
	}

	@Test
	void serviceRuleRejectsServletAndMvcDependenciesInNestedPackages() {
		assertRejected(ArchitectureRules.SERVICES_DO_NOT_DEPEND_ON_WEB, ServletService.class);
		assertRejected(ArchitectureRules.SERVICES_DO_NOT_DEPEND_ON_WEB, MvcService.class);
		assertAccepted(ArchitectureRules.SERVICES_DO_NOT_DEPEND_ON_WEB, ValidService.class);
	}

	@Test
	void sessionRuleRejectsDiscardedRequestCallsAndSessionDependencies() {
		assertRejected(ArchitectureRules.PRODUCTION_DOES_NOT_USE_SESSIONS, DiscardedSessionCall.class);
		assertRejected(ArchitectureRules.PRODUCTION_DOES_NOT_USE_SESSIONS, SessionDependency.class);
		assertAccepted(ArchitectureRules.PRODUCTION_DOES_NOT_USE_SESSIONS, ValidRequest.class);
	}

	@Test
	void exceptionRuleRejectsIndirectRuntimeSubclassesAndAllowsTechnicalPropagation() {
		assertRejected(ArchitectureRules.DOMAIN_DOES_NOT_DECLARE_RUNTIME_EXCEPTIONS, InvalidDomainException.class);
		assertAccepted(ArchitectureRules.DOMAIN_DOES_NOT_DECLARE_RUNTIME_EXCEPTIONS, ValidService.class,
				BusinessException.class);
	}

	@Test
	void absentDomainLayersAreExplicitlyAllowed() {
		assertAccepted(ArchitectureRules.CONTROLLERS_DO_NOT_ACCESS_REPOSITORIES, BusinessException.class);
		assertAccepted(ArchitectureRules.SERVICES_DO_NOT_DEPEND_ON_WEB, BusinessException.class);
		assertAccepted(ArchitectureRules.DOMAIN_DOES_NOT_DECLARE_RUNTIME_EXCEPTIONS, BusinessException.class);
	}

	private static void assertRejected(ArchRule rule, Class<?> fixture) {
		var result = rule.evaluate(new ClassFileImporter().importClasses(fixture));
		assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isTrue();
		assertThat(result.getFailureReport().getDetails()).anyMatch(detail -> detail.contains(fixture.getName()));
	}

	private static void assertAccepted(ArchRule rule, Class<?>... fixtures) {
		var result = rule.evaluate(new ClassFileImporter().importClasses(fixtures));
		assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
	}
}
