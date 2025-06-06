/*
 * SPDX-FileCopyrightText: 2021-2023 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.gradle

import org.gradle.accessors.dm.LibrariesForLibs
import tools.refinery.gradle.utils.SonarPropertiesUtils

plugins {
	id("tools.refinery.gradle.internal.java-basic-library")
	id("tools.refinery.gradle.sonarqube")
}

val libs = the<LibrariesForLibs>()

dependencies {
	testImplementation(libs.junit4)
	testRuntimeOnly(libs.junit.engine.vintage)
	testRuntimeOnly(libs.slf4j.simple)
}

tasks {
	withType(Jar::class) {
		// Make sure we include external project notices.
		from(layout.projectDirectory.file("about.html"))
		from(layout.projectDirectory.file("NOTICE.md"))
	}
}

publishing.publications.named<MavenPublication>("mavenJava") {
	pom.developers {
		developer {
			name = "The VIATRA™ Authors"
			url = "https://eclipse.dev/viatra/"
		}
	}
}

sonarqube.properties {
	// Code copied from the VIATRA project is maintained by the VIATRA contributors.
	// Our own modifications are verified by tests in our own subprojects.
	// Therefore, we disable coverage checking for vendor subprojects.
	SonarPropertiesUtils.addToList(properties, "sonar.coverage.exclusions", "src/main/**")
}
