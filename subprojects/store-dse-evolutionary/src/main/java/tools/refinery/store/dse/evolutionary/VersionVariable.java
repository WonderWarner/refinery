/*
 * SPDX-FileCopyrightText: 2025 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.store.dse.evolutionary;

import org.moeaframework.core.variable.AbstractVariable;
import org.moeaframework.core.variable.Variable;
import tools.refinery.store.map.Version;

import java.util.Objects;

class VersionVariable extends AbstractVariable {
	private final transient RefineryProblem problem;
	private transient Version version;

	public VersionVariable(RefineryProblem problem, Version version) {
		super("version");
		this.problem = problem;
		this.version = version;
	}

	public Version getVersion() {
		return version;
	}

	public void setVersion(Version version) {
		this.version = version;
	}

	@Override
	public void randomize() {
		var randomSolution = problem.newSolution();
		var mutation = problem.getMutation();
		for (int i = 0; i < problem.getRandomizeDepth(); i++) {
			randomSolution = mutation.mutate(randomSolution);
		}
		version = RefineryProblem.getVersion(randomSolution);
	}

	@Override
	public String encode() {
		throw new UnsupportedOperationException("Can't encode Refinery version variable");
	}

	@Override
	public void decode(String s) {
		throw new UnsupportedOperationException("Can't decode Refinery version variable");
	}

	@Override
	public Variable copy() {
		return new VersionVariable(problem, version);
	}

	@Override
	public String getDefinition() {
		return getName();
	}

	@Override
	public boolean equals(Object obj) {
		if (!super.equals(obj)) {
			return false;
		}
		var otherVersionVariable = (VersionVariable) obj;
		return Objects.equals(version, otherVersionVariable.version);
	}

	@Override
	public int hashCode() {
		return 31 * super.hashCode() + Objects.hashCode(version);
	}
}
