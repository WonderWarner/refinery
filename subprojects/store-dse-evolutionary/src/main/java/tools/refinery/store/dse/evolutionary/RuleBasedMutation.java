package tools.refinery.store.dse.evolutionary;

import org.moeaframework.core.Solution;
import org.moeaframework.core.operator.Mutation;
import tools.refinery.store.dse.transition.Transformation;
import tools.refinery.store.map.Version;
import tools.refinery.visualization.statespace.VisualizationStore;

import java.util.Random;

public class RuleBasedMutation implements Mutation {

	private static long mutationTimeNanos = 0L;
	public static synchronized void addMutationTimeNanos(long nanos) {
		mutationTimeNanos += nanos;
	}
	public static synchronized void setMutationTimeNanos(long nanos) {
		mutationTimeNanos = nanos;
	}
	public static synchronized long getMutationTimeNanos() {
		return mutationTimeNanos;
	}

	private final RefineryProblem problem;
    private final Random random = new Random();

	public RuleBasedMutation(RefineryProblem problem) {
        this.problem = problem;
        //visualizationStore = problem.getVisualizationStore();
        //isVisualizationEnabled = visualizationStore != null;
    }

	public void setRandomSeed(long seed) {
		this.random.setSeed(seed);
	}

    @Override
    public Solution mutate(Solution parent) {
		long start = System.nanoTime();
		try {
			var child = parent.copy();
			var version = RefineryProblem.getVersion(parent);
			if (version == null) {
				return child;
			}

			problem.getModel().restore(version);
			var transformations = problem.getDSEAdapter().getTransformations();
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
			//int transformationNum = -1;

			if (totalActivationCount > 0) {
				double offset = random.nextDouble(totalWeight);
				for (int i = 0; i < weights.length; i++) {
					double weight = weights[i];
					if (weight > 0 && offset < weight) {
						var transformation = transformations.get(i);
						//transformationNum = i;
						childVersion = fireRandomActivation(transformation);
						break;
					}
					offset -= weight;
				}
			}

//			if (isVisualizationEnabled && childVersion != null) {
//				visualizationStore.addState(childVersion, problem.getObjectiveValue().toString());
//				visualizationStore.addTransition(version, childVersion,
//						"fire: " + transformationNum + ", " + activation);
//			}

			RefineryProblem.setVersion(child, childVersion);
			return child;
		} finally {
			addMutationTimeNanos(System.nanoTime() - start);
		}
    }

    private Version fireRandomActivation(Transformation transformation) {
		//private final VisualizationStore visualizationStore;
		//private final boolean isVisualizationEnabled;
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
