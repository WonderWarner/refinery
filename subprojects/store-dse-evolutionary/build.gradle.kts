/*
 * SPDX-FileCopyrightText: 2025 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */

plugins {
	id("tools.refinery.gradle.java-library")
}

mavenArtifact {
	name = "Store DSE Evolutionary"
	description = "Multi-objective evolutionary design-space explorer for the model store"
}

dependencies {
	api(project(":refinery-store-dse"))
	api(project(":refinery-store-dse-visualization"))
	api(project(":refinery-store-reasoning"))
	api(libs.moea)
	testImplementation(project(":refinery-store-query-interpreter"))
}
