/*
 * SPDX-FileCopyrightText: 2021-2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.gradle.internal

plugins {
	`java-library`
	id("tools.refinery.gradle.internal.java-basic-conventions")
	id("tools.refinery.gradle.maven-publish")
}

dependencies {
	api(platform(project(":refinery-bom-dependencies")))
}

publishing.publications.named<MavenPublication>("mavenJava") {
	from(components["java"])
}
