package com.ject.ziper.global.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class ArchitectureRulesTest {
	@Test
	void productionClassesRespectArchitectureBoundaries() {
		var production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.ject.ziper");
		assertThat(production).isNotEmpty();
		assertThat(production.stream().map(type -> type.getName())).noneMatch(
				name -> name.contains("harnessfixture") || name.equals(ArchitectureRulesTest.class.getName()));
		ArchitectureRules.CONTROLLERS_DO_NOT_ACCESS_REPOSITORIES.check(production);
		ArchitectureRules.SERVICES_DO_NOT_DEPEND_ON_WEB.check(production);
		ArchitectureRules.DOMAIN_DOES_NOT_DECLARE_RUNTIME_EXCEPTIONS.check(production);
		ArchitectureRules.PRODUCTION_DOES_NOT_USE_SESSIONS.check(production);
	}
}
