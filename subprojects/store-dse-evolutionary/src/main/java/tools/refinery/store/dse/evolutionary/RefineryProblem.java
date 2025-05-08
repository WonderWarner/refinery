/*
 * SPDX-FileCopyrightText: 2025 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.store.dse.evolutionary;

import org.jetbrains.annotations.Nullable;
import org.moeaframework.core.Solution;
import org.moeaframework.core.constraint.LessThanOrEqual;
import org.moeaframework.core.operator.Mutation;
import org.moeaframework.problem.AbstractProblem;
import tools.refinery.store.dse.propagation.PropagationAdapter;
import tools.refinery.store.dse.transition.DesignSpaceExplorationAdapter;
import tools.refinery.store.dse.transition.DesignSpaceExplorationStoreAdapter;
import tools.refinery.store.map.Version;
import tools.refinery.store.model.Model;
import tools.refinery.store.model.ModelStore;

public class RefineryProblem extends AbstractProblem {
	public static final int DEFAULT_RANDOMIZE_DEPTH = 1;

	private static final int VERSION_VARIABLE_INDEX = 0;
	private static final int ACCEPT_CONSTRAINT_INDEX = 0;
	private static final double ACCEPT_FEASIBLE = 0.0;
	private static final double ACCEPT_INFEASIBLE = 0.0;

	private final Model model;
	private final Version initialVersion;
	private final int randomizeDepth;
	private final DesignSpaceExplorationAdapter dseAdapter;
	private final @Nullable PropagationAdapter propagationAdapter;
	private final RuleBasedMutation ruleBasedMutation;

	public RefineryProblem(ModelStore store, Version initialVersion) {
		this(store, initialVersion, DEFAULT_RANDOMIZE_DEPTH);
	}

	public RefineryProblem(ModelStore store, Version initialVersion, int randomizeDepth) {
		super(1, store.getAdapter(DesignSpaceExplorationStoreAdapter.class).getObjectives().size(), 1);
		if (randomizeDepth < 0) {
			throw new IllegalArgumentException("randomizeDepth must be positive or zero");
		}
		model = store.createEmptyModel();
		this.initialVersion = initialVersion;
		this.randomizeDepth = randomizeDepth;
		dseAdapter = model.getAdapter(DesignSpaceExplorationAdapter.class);
		propagationAdapter = model.tryGetAdapter(PropagationAdapter.class).orElse(null);
		ruleBasedMutation = new RuleBasedMutation(this);
	}

	public int getRandomizeDepth() {
		return randomizeDepth;
	}

	Model getModel() {
		return model;
	}

	DesignSpaceExplorationAdapter getDSEAdapter() {
		return dseAdapter;
	}

	@Nullable PropagationAdapter getPropagationAdapter() {
		return propagationAdapter;
	}

	@Override
	public void evaluate(Solution solution) {
		var version = getVersion(solution);
		if (version == null) {
			setInfeasible(solution);
			return;
		}
		model.restore(version);
		if (dseAdapter.checkExclude()) {
			setInfeasible(solution);
            return;
		}
		if (propagationAdapter != null && propagationAdapter.concretizationRequested()) {
			var concretizationResult = propagationAdapter.concretize();
			if (concretizationResult.isRejected() || dseAdapter.checkExclude()) {
                setInfeasible(solution);
                return;
            }
		}
		var objectiveValue = dseAdapter.getObjectiveValue();
		for (int i = 0; i < numberOfObjectives; i++) {
			solution.setObjectiveValue(i, objectiveValue.get(i));
		}
		solution.setConstraintValue(ACCEPT_CONSTRAINT_INDEX,
				dseAdapter.checkAccept() ? ACCEPT_FEASIBLE : ACCEPT_INFEASIBLE);
	}

	private void setInfeasible(Solution solution) {
		for (int i = 0; i < numberOfObjectives; i++) {
			// Objectives correspond to a minimization problem, so positive infinity is as bad as possible.
			solution.setObjectiveValue(i, Double.POSITIVE_INFINITY);
		}
		solution.setConstraintValue(ACCEPT_CONSTRAINT_INDEX, ACCEPT_INFEASIBLE);
	}

	@Override
	public Solution newSolution() {
		var solution = new Solution(numberOfVariables, numberOfObjectives, numberOfConstraints);
		solution.setVariable(VERSION_VARIABLE_INDEX, new VersionVariable(this, initialVersion));
		solution.setConstraint(ACCEPT_CONSTRAINT_INDEX, new LessThanOrEqual(0.0));
		return solution;
	}

	@Override
	public void close() {
		model.close();
		super.close();
	}

	public Mutation getMutation() {
		return ruleBasedMutation;
	}

	public static @Nullable Version getVersion(Solution solution) {
		return ((VersionVariable) solution.getVariable(VERSION_VARIABLE_INDEX)).getVersion();
	}

	static void setVersion(Solution solution, @Nullable Version version) {
		((VersionVariable) solution.getVariable(VERSION_VARIABLE_INDEX)).setVersion(version);
	}
}
