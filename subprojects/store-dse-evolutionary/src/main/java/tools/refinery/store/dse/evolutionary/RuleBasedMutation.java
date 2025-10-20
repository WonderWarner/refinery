/*
 * SPDX-FileCopyrightText: 2025 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.store.dse.evolutionary;

import org.moeaframework.core.Solution;
import org.moeaframework.core.operator.Mutation;
import tools.refinery.store.dse.transition.Transformation;
import tools.refinery.store.map.Version;
import tools.refinery.visualization.statespace.VisualizationStore;

import java.util.Random;

class RuleBasedMutation implements Mutation {
	private final RefineryProblem problem;
	private final Random random = new Random();

	private final VisualizationStore visualizationStore;
	private final boolean isVisualizationEnabled;

	public RuleBasedMutation(RefineryProblem problem) {
		this.problem = problem;
		visualizationStore = problem.getVisualizationStore();
		isVisualizationEnabled = visualizationStore != null;
	}

	@Override
	public Solution mutate(Solution parent) {
		var child = parent.copy();
		var version = RefineryProblem.getVersion(parent);
		if (version == null) {
			return child;
		}

		problem.getModel().restore(version);
		var dseAdapter = problem.getDSEAdapter();

		var transformations = dseAdapter.getTransformations();
		var weights = new double[transformations.size()];
		double totalWeight = 0;
		int totalActivationCount = 0;
		for (int i = 0; i < weights.length; i++) {
			var transformation = transformations.get(i);
			int activationCount = transformation.getActivationCount();
			double weight = transformation.getDefinition().getWeight(activationCount);
			weights[i] = weight;
			totalWeight += weight;
			totalActivationCount += activationCount;
		}

		Version childVersion = null;
		if (totalActivationCount > 0) {
			double offset = random.nextDouble(totalWeight);
			for (int i = 0; i < weights.length; i++) {
				double weight = weights[i];
				if (weight > 0 && offset < weight) {
					var transformation = transformations.get(i);
					childVersion = fireRandomActivation(transformation);
					break;
				}
				offset -= weight;
			}
		}

		RefineryProblem.setVersion(child, childVersion);

		//TODO add to visualizationStore
		// DSE Adapter might be useful, see in BestFirstWorker
		if(isVisualizationEnabled) {

		}

		return child;
	}

	private Version fireRandomActivation(Transformation transformation) {
		int activation = random.nextInt(transformation.getActivationCount());
		if (!transformation.fireActivation(transformation.getActivation(activation))) {
			return null;
		}
		var propagationAdapter = problem.getPropagationAdapter();
		if (propagationAdapter != null) {
			var propagationResult = propagationAdapter.propagate();
			if (propagationResult.isRejected()) {
				return null;
			}
		}
		return problem.getModel().commit();
	}

	@Override
	public String getName() {
		return this.getClass().getSimpleName();
	}
}
