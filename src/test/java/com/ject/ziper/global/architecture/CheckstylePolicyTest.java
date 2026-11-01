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

class CheckstylePolicyTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void 파일위치와무관한업무서비스와컨트롤러패키지의catch문_검사하면_BusinessCatch로거부한다() throws Exception {
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
	void 업무서비스의중첩클래스와익명클래스의catch문_검사하면_모두거부한다() throws Exception {
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
	void 정리용try와catch를언급한문자열과주석_검사하면_허용한다() throws Exception {
		assertThat(inspect("com.ject.ziper.domain.order.service", """
				// catch (RuntimeException exception) is only documentation.
				String description = "catch (Exception exception)";
				void execute() throws Exception {
					try (AutoCloseable resource = System::nanoTime) {
						resource.close();
					} finally { System.nanoTime(); }
					try { System.nanoTime(); } finally { System.nanoTime(); }
				}
				""")).isEmpty();
	}

	@Test
	void 전역과외부IO경계와대상밖패키지의catch문_검사하면_허용한다() throws Exception {
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
	void SuppressWarnings로억제한업무catch문_검사하면_여전히거부한다() throws Exception {
		assertThat(inspect("com.ject.ziper.domain.order.service", """
				@SuppressWarnings({"checkstyle:BusinessCatch", "checkstyle:MatchXpath"})
				void execute() {
					try { System.nanoTime(); }
					catch (RuntimeException exception) { throw exception; }
				}
				""")).singleElement().satisfies(event -> assertThat(event.getModuleId()).isEqualTo("BusinessCatch"));
	}

	@Test
	void 승인된파일과줄의허용목록_catch문을검사하면_정확한파일과줄의BusinessCatch만허용한다() throws Exception {
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

	@Test
	void import밖의패키지한정이름_검사하면_FullyQualifiedName으로거부한다() throws Exception {
		for (String source : List.of("@org.springframework.stereotype.Service class Sample {}",
				"class Sample extends java.util.ArrayList<String> {}",
				"class Sample implements java.io.Serializable {}", "class Sample { java.util.Set<String> field; }",
				"class Sample { Iterable<java.time.Instant> field; }",
				"class Sample { Object field = new java.util.ArrayList<String>(); }",
				"class Sample { Object field = java.nio.charset.StandardCharsets.UTF_8; }",
				"class Sample { Class<?> type = java.util.UUID.class; }",
				"class Sample { java.util.function.Function<Object, String> mapper = String::valueOf; }",
				"class Sample { Runnable task = java.lang.System::gc; }",
				"class Sample { void execute() throws java.io.IOException {} }",
				"class Sample { void execute(Object value) { java.util.Objects.requireNonNull(value); } }",
				"class Sample { Object execute(Object value) { return (java.lang.Runnable) value; } }",
				"class Sample { boolean execute(Object value) { return value instanceof java.lang.CharSequence; } }")) {
			assertThat(inspect("package com.ject.ziper.domain.order.service;\n" + source + "\n")).singleElement()
					.satisfies(event -> {
						assertThat(event.getModuleId()).isEqualTo("FullyQualifiedName");
						assertThat(event.getMessage()).contains("import");
					});
		}
	}

	@Test
	void import와멤버접근체인_검사하면_허용한다() throws Exception {
		assertThat(inspect("""
				package com.ject.ziper.domain.order.service;

				import static java.util.Objects.requireNonNull;

				import java.util.Map;

				class Sample {
					// java.util.List in comments is documentation.
					String text = "java.util.List";
					Map.Entry<String, String> entry;
					Sample other;
					String name;

					void execute() {
						requireNonNull(this.name);
						System.out.println(other.name.length());
						Runnable task = Sample.this::execute;
					}
				}
				""")).isEmpty();
	}

	@Test
	void 한국어GivenWhenThen형식이아닌테스트메서드이름_검사하면_TestMethodName으로거부한다() throws Exception {
		for (String method : List.of("@Test void rejectsInvalidName() {}", "@Test void 잘못된이름_거부한다() {}",
				"@Test void 너무_많은_구분이_있다() {}", "@Test void 상황_행동_() {}", "@Test void given_when_then() {}",
				"@ParameterizedTest void parameterizedName() {}", "@RepeatedTest(2) void repeatedName() {}")) {
			assertThat(inspect("com.ject.ziper.domain.order.service", method + "\n")).singleElement()
					.satisfies(event -> {
						assertThat(event.getModuleId()).isEqualTo("TestMethodName");
						assertThat(event.getMessage()).contains("given_when_then");
					});
		}
	}

	@Test
	void 한국어세단계테스트이름과일반메서드이름_검사하면_허용한다() throws Exception {
		assertThat(inspect("com.ject.ziper.domain.order.service", """
				@Test
				void 유효한JWT토큰_요청하면_200을반환한다() {}

				@ParameterizedTest
				void 여러입력값_검증하면_모두거부한다() {}

				void helperMethod() {}
				""")).isEmpty();
	}

	private List<AuditEvent> inspect(String packageName, String members) throws Exception {
		// Deliberately keep the physical path outside domain/service to test
		// package-based scope.
		return inspect("package " + packageName + ";\nclass Sample {\n" + members + "}\n");
	}

	private List<AuditEvent> inspect(String content) throws Exception {
		Path source = temporaryDirectory.resolve("UnrelatedLocation.java");
		Files.writeString(source, content);
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
