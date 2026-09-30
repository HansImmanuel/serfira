plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.sonarqube") version "7.5.0.8588"
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
