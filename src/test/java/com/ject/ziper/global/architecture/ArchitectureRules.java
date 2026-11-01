package com.ject.ziper.global.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.JavaClass;

final class ArchitectureRules {
	static final ArchRule CONTROLLERS_DO_NOT_ACCESS_REPOSITORIES = noClasses().that()
			.resideInAPackage("com.ject.ziper.domain..controller..").should().dependOnClassesThat()
			.resideInAPackage("com.ject.ziper.domain..repository..").allowEmptyShould(true);

	static final ArchRule SERVICES_DO_NOT_DEPEND_ON_WEB = noClasses().that()
			.resideInAPackage("com.ject.ziper.domain..service..").should().dependOnClassesThat()
			.resideInAnyPackage("jakarta.servlet..", "javax.servlet..", "org.springframework.web..")
			.allowEmptyShould(true);

	static final ArchRule DOMAIN_DOES_NOT_DECLARE_RUNTIME_EXCEPTIONS = noClasses().that()
			.resideInAPackage("com.ject.ziper.domain..").should().beAssignableTo(RuntimeException.class)
			.allowEmptyShould(true);

	static final ArchRule PRODUCTION_DOES_NOT_USE_SESSIONS = classes()
			.should(new ArchCondition<JavaClass>("avoid servlet session dependencies and request session calls") {
				@Override
				public void check(JavaClass item, ConditionEvents events) {
					item.getDirectDependenciesFromSelf().stream()
							.filter(dependency -> dependency.getTargetClass().isAssignableTo(HttpSession.class))
							.forEach(dependency -> events
									.add(SimpleConditionEvent.violated(item, dependency.getDescription())));
					item.getMethodCallsFromSelf().stream()
							.filter(call -> call.getTargetOwner().isAssignableTo(HttpServletRequest.class))
							.filter(call -> call.getName().toLowerCase(java.util.Locale.ROOT).contains("session"))
							.forEach(call -> events.add(SimpleConditionEvent.violated(item, call.getDescription())));
				}
			});

	private ArchitectureRules() {
	}
}
