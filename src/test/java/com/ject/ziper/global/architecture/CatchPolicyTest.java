package com.ject.ziper.global.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;

class CatchPolicyTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void rejectsCatchInBusinessPackagesRegardlessOfFileLocation() throws Exception {
		for (String packageName : List.of("com.ject.ziper.domain.order.service",
				"com.ject.ziper.domain.order.service.internal", "com.ject.ziper.domain.order.controller",
				"com.ject.ziper.domain.order.controller.internal")) {
			List<AuditEvent> violations = inspect(packageName, """
					void execute() {
						try { System.nanoTime(); }
						catch (RuntimeException exception) { throw exception; }
					}
					""");
			assertThat(violations).singleElement().satisfies(event -> {
				assertThat(event.getModuleId()).isEqualTo("BusinessCatch");
				assertThat(event.getMessage()).contains("BusinessException", "boundary adapter");
			});
		}
	}

	@Test
	void rejectsCatchInNestedAndAnonymousClasses() throws Exception {
		List<AuditEvent> violations = inspect("com.ject.ziper.domain.order.service", """
				class Nested {
					void execute() {
						try { System.nanoTime(); }
						catch (RuntimeException exception) { throw exception; }
					}
				}
				Runnable task = new Runnable() {
					public void run() {
						try { System.nanoTime(); }
						catch (RuntimeException exception) { throw exception; }
					}
				};
				""");
		assertThat(violations).hasSize(2)
				.allSatisfy(event -> assertThat(event.getModuleId()).isEqualTo("BusinessCatch"));
	}

	@Test
	void allowsCleanupAndCatchTextWithoutCatchStatements() throws Exception {
		assertThat(inspect("com.ject.ziper.domain.order.service", """
				// catch (RuntimeException exception) is only documentation.
				String description = "catch (Exception exception)";
				void execute() throws Exception {
					try (var resource = new java.io.StringReader("")) {
						resource.read();
					} finally { System.nanoTime(); }
					try { System.nanoTime(); } finally { System.nanoTime(); }
				}
				""")).isEmpty();
	}

	@Test
	void allowsCatchesAtGlobalAndExternalIoBoundaries() throws Exception {
		for (String packageName : List.of("com.ject.ziper.global.error", "com.ject.ziper.domain.order.adapter",
				"com.ject.ziper.domain.order.repository", "com.ject.ziper.domain.order.services",
				"com.other.domain.order.service", "com.ject.ziper.domain.extra.order.service",
				"com.ject.ziper.domain.order.adapter.service", "org.com.ject.ziper.domain.order.service")) {
			assertThat(inspect(packageName, """
					void execute() {
						try { System.nanoTime(); }
						catch (RuntimeException exception) { throw exception; }
					}
					""")).isEmpty();
		}
	}

	@Test
	void inlineSuppressWarningsCannotDisableCatchPolicy() throws Exception {
		assertThat(inspect("com.ject.ziper.domain.order.service", """
				@SuppressWarnings({"checkstyle:BusinessCatch", "checkstyle:MatchXpath"})
				void execute() {
					try { System.nanoTime(); }
					catch (RuntimeException exception) { throw exception; }
				}
				""")).singleElement().satisfies(event -> assertThat(event.getModuleId()).isEqualTo("BusinessCatch"));
	}

	@Test
	void approvedSuppressionMatchesOnlyExactFileLineAndBusinessCatch() throws Exception {
		Path source = temporaryDirectory.resolve("src/main/java/com/ject/ziper/Example.java");
		Files.createDirectories(source.getParent());
		String content = """
				package com.ject.ziper.domain.order.service;
				class Sample {
					void execute() {
						try { System.nanoTime(); }
						catch (RuntimeException exception) { throw exception; }
					}
				}
				""";
		Files.writeString(source, content);
		Path suppression = temporaryDirectory.resolve("approved-suppressions.xml");
		Files.writeString(suppression,
				"""
						<?xml version="1.0"?>
						<!DOCTYPE suppressions PUBLIC
						    "-//Checkstyle//DTD SuppressionFilter Configuration 1.2//EN"
						    "https://checkstyle.org/dtds/suppressions_1_2.dtd">
						<suppressions>
						    <suppress id="BusinessCatch" files="[\\\\/]src[\\\\/]main[\\\\/]java[\\\\/]com[\\\\/]ject[\\\\/]ziper[\\\\/]Example\\.java$" lines="5"/>
						</suppressions>
						""");
		assertThat(inspect(source, suppression)).isEmpty();

		Path otherSource = source.resolveSibling("Other.java");
		Files.writeString(otherSource, content);
		assertThat(inspect(otherSource, suppression)).singleElement().satisfies(event -> {
			assertThat(event.getModuleId()).isEqualTo("BusinessCatch");
			assertThat(event.getLine()).isEqualTo(5);
		});

		Files.writeString(source,
				content.replace("catch (RuntimeException exception) { throw exception; }",
						"catch (RuntimeException exception) {}\n" + "		try { System.nanoTime(); }\n"
								+ "		catch (RuntimeException exception) { throw exception; }"));
		List<AuditEvent> remaining = inspect(source, suppression);
		assertThat(remaining).hasSize(2);
		assertThat(remaining.stream().filter(event -> "BusinessCatch".equals(event.getModuleId()))).singleElement()
				.satisfies(event -> assertThat(event.getLine()).isEqualTo(7));
		assertThat(remaining.stream().filter(event -> event.getSourceName().endsWith("EmptyCatchBlockCheck")))
				.singleElement().satisfies(event -> assertThat(event.getLine()).isEqualTo(5));
	}

	private List<AuditEvent> inspect(String packageName, String members) throws Exception {
		// Deliberately keep the physical path outside domain/service to test
		// package-based scope.
		Path source = temporaryDirectory.resolve("UnrelatedLocation.java");
		Files.writeString(source, "package " + packageName + ";\nclass Sample {\n" + members + "}\n");
		return inspect(source, Path.of("config/checkstyle/business-catch-suppressions.xml"));
	}

	private List<AuditEvent> inspect(Path source, Path suppression) throws Exception {
		Properties properties = new Properties();
		properties.setProperty("catchSuppressionFile", suppression.toAbsolutePath().toString());
		var configuration = ConfigurationLoader.loadConfiguration("config/checkstyle/checkstyle.xml",
				new PropertiesExpander(properties), ConfigurationLoader.IgnoredModulesOptions.OMIT);
		List<AuditEvent> violations = new ArrayList<>();
		Checker checker = new Checker();
		try {
			checker.setModuleClassLoader(Checker.class.getClassLoader());
			checker.configure(configuration);
			checker.addListener(new AuditListener() {
				@Override
				public void auditStarted(AuditEvent event) {
				}

				@Override
				public void auditFinished(AuditEvent event) {
				}

				@Override
				public void fileStarted(AuditEvent event) {
				}

				@Override
				public void fileFinished(AuditEvent event) {
				}

				@Override
				public void addError(AuditEvent event) {
					violations.add(event);
				}

				@Override
				public void addException(AuditEvent event, Throwable throwable) {
					throw new AssertionError("Checkstyle could not inspect the fixture", throwable);
				}
			});
			int errors = checker.process(List.of(source.toFile()));
			assertThat(errors).isEqualTo(violations.size());
		} finally {
			checker.destroy();
		}
		return violations;
	}
}
