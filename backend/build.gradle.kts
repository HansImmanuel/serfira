plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.sonarqube") version "7.5.0.8588"
	// Dependency-vulnerability scan (T27). The plugin registers its own tasks only — it never attaches to
	// `check`, so `./gradlew check` stays exactly the gate T27a defined. CI runs `dependencyCheckAnalyze`
	// in its own job; locally it is an explicit, manual command.
	id("org.owasp.dependencycheck") version "13.0.0"
}

group = "com.serfira"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

sonar {
	properties {
		property("sonar.projectKey", "serfira")
    	property("sonar.host.url", "http://localhost:9000")
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// --- Core ---
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// --- Security & JWT ---
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	// BouncyCastle for Argon2id: Spring Security's Argon2PasswordEncoder uses the lightweight
	// Argon2 KDF from bcprov. Only bcprov is needed (bcpkix/bcutil are certificate helpers). The
	// explicit coordinate pins the version so the dependency is auditable (ADR-022, T21a).
	implementation("org.bouncycastle:bcprov-jdk18on:1.80")

	// --- Database ---
	runtimeOnly("org.postgresql:postgresql")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	// Spring Boot 4 modularized auto-configs: Flyway startup migration lives in spring-boot-flyway.
	implementation("org.springframework.boot:spring-boot-flyway")

	// --- Scheduling: ShedLock (multi-instance safe job lock) ---
	// 7.x is the line tested with Spring Boot 4 (T23, review CR-02). The PostgreSQL table schema is unchanged from 6.x.
	implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")

	// --- API Docs ---
	// springdoc majors move in lockstep with Boot; 3.1.x is built against Boot 4.1 (T23, review CR-03).
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

	// --- Boilerplate reduction ---
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	// --- Test ---
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	// Boot 4 modularized test slices: MockMvc support lives in the webmvc test module.
	testImplementation("org.springframework.boot:spring-boot-webmvc-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
	testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Dependency-vulnerability gate (T27). Policy: fail on High or Critical (CVSS >= 7.0). Anything suppressed
// in dependency-check-suppressions.xml must state a reason, and a suppression that stops matching fails the
// scan instead of lingering. The plugin is deliberately not wired into `check`: this scans the NVD feed, so
// it is too slow and too network-dependent to sit in the local/CI test gate (T27a).
dependencyCheck {
	failBuildOnCVSS.set(7.0f)
	formats.set(listOf("HTML"))
	suppressionFile.set("dependency-check-suppressions.xml")
	failBuildOnUnusedSuppressionRule.set(true)
	// Required for the pinned scanner (13.0.0), not optional: the NVD API rejects unkeyed/empty requests, so
	// a scan without a valid key fails with `NvdApiException: Invalid API Key` instead of returning results.
	// CI supplies it from the repository secret `NVD_API_KEY`; local scans must export the same environment
	// variable. The value is only set when non-blank, so a missing key is never papered over with an empty
	// string — the scan fails, which is the intended behaviour (no silent bypass of the dependency gate).
	providers.environmentVariable("NVD_API_KEY").orNull?.takeIf(String::isNotBlank)?.let { nvd.apiKey.set(it) }
	// OSS Index enriches findings with upstream metadata; a rate limit or outage there must not red the CVE
	// gate, whose source of truth is the NVD feed.
	analyzers.ossIndex.warnOnlyOnRemoteErrors.set(true)
}
