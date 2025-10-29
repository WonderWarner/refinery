package tools.refinery.store.dse.evolutionary;

import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.Nullable;
import org.moeaframework.core.Solution;
import org.moeaframework.core.constraint.LessThanOrEqual;
import org.moeaframework.core.operator.Mutation;
import org.moeaframework.core.operator.Variation;
import org.moeaframework.problem.AbstractProblem;
import tools.refinery.language.semantics.ProblemTrace;
import tools.refinery.store.dse.propagation.PropagationAdapter;
import tools.refinery.store.dse.transition.DesignSpaceExplorationAdapter;
import tools.refinery.store.dse.transition.DesignSpaceExplorationStoreAdapter;
import tools.refinery.store.dse.transition.ObjectiveValue;
import tools.refinery.store.map.Version;
import tools.refinery.store.model.Model;
import tools.refinery.store.model.ModelStore;
import tools.refinery.visualization.statespace.VisualizationStore;
import tools.refinery.visualization.statespace.internal.VisualizationStoreImpl;

public class RefineryProblem extends AbstractProblem {
    public static final int DEFAULT_RANDOMIZE_DEPTH = 1;
    public static final boolean DEFAULT_VISUALIZATION_ENABLED = false;

    private final Model model;
	private final ProblemTrace problemTrace;
    private final Version initialVersion;
    private final VisualizationStore visualizationStore;
    private final int randomizeDepth;
    private final DesignSpaceExplorationAdapter dseAdapter;
    private final @Nullable PropagationAdapter propagationAdapter;
    private RuleBasedMutation ruleBasedMutation;
    private DeltaCrossover deltaCrossover ;

    public RefineryProblem(ModelStore store, ProblemTrace problemTrace, Version initialVersion) {
        this(store, problemTrace, initialVersion, DEFAULT_RANDOMIZE_DEPTH, DEFAULT_VISUALIZATION_ENABLED);
    }

    public RefineryProblem(ModelStore store,  ProblemTrace problemTrace, Version initialVersion,
						   boolean isVisualizationEnabled) {
        this(store, problemTrace, initialVersion, DEFAULT_RANDOMIZE_DEPTH, isVisualizationEnabled);
    }

    public RefineryProblem(ModelStore store,  ProblemTrace problemTrace, Version initialVersion, int randomizeDepth) {
        this(store, problemTrace, initialVersion, randomizeDepth, DEFAULT_VISUALIZATION_ENABLED);
    }

    public RefineryProblem(ModelStore store, ProblemTrace problemTrace, Version initialVersion, int randomizeDepth,
						   boolean isVisualizationEnabled) {
        super(1, store.getAdapter(DesignSpaceExplorationStoreAdapter.class).getObjectives().size(), 1);
        if (randomizeDepth < 0) {
            throw new IllegalArgumentException("randomizeDepth must be positive or zero");
        }
        model = store.createEmptyModel();
		this.problemTrace = problemTrace;
        this.initialVersion = initialVersion;
        if (isVisualizationEnabled) visualizationStore = new VisualizationStoreImpl();
        else visualizationStore = null;
        this.randomizeDepth = randomizeDepth;
        dseAdapter = model.getAdapter(DesignSpaceExplorationAdapter.class);
        propagationAdapter = model.tryGetAdapter(PropagationAdapter.class).orElse(null);
        ruleBasedMutation = new RuleBasedMutation(this);
        deltaCrossover = new DeltaCrossover(this, 0.3);

		//TODO collect objective function names, constraint function names, crossover predicate names
		// create getters and accept method
		// set objective and constraint values according to it

    }

    public VisualizationStore getVisualizationStore() {
        return visualizationStore;
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

    @Nullable
    PropagationAdapter getPropagationAdapter() {
        return propagationAdapter;
    }

    @Override
    public void evaluate(Solution solution) {
		//TODO exchange to
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

        if (visualizationStore != null && dseAdapter.checkAccept()) {
            visualizationStore.addSolution(version);
        }

        solution.setConstraintValue(0,
                dseAdapter.checkAccept() ? 0.0 : 0.0); //TODO
    }

    private void setInfeasible(Solution solution) {
        for (int i = 0; i < numberOfObjectives; i++) {
            solution.setObjectiveValue(i, Double.POSITIVE_INFINITY);
        }
        solution.setConstraintValue(0, 0.0);
    }

    @Override
    public Solution newSolution() {
        var solution = new Solution(numberOfVariables, numberOfObjectives, numberOfConstraints);
        solution.setVariable(0, new VersionVariable(this, initialVersion));
        solution.setConstraint(0, new LessThanOrEqual(0.0));

        if (visualizationStore != null) {
            visualizationStore.addState(getVersion(solution), dseAdapter.getObjectiveValue().toString());
        }

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
        return ((VersionVariable) solution.getVariable(0)).getVersion();
    }

    static void setVersion(Solution solution, @Nullable Version version) {
        ((VersionVariable) solution.getVariable(0)).setVersion(version);
    }

    public Variation getCrossover() { return deltaCrossover; }

	public void setRandomSeed(long randomSeed) {
		if (ruleBasedMutation != null) {
			ruleBasedMutation.setRandomSeed(randomSeed);
		}
		if (deltaCrossover != null) {
			deltaCrossover.setRandomSeed(randomSeed);
		}
	}

	public void setDeltaSelectionRatio(double deltaSelectionRatio) {
		if (deltaCrossover == null) {
			return;
		}
		deltaCrossover.setDeltaSelectionRatio(deltaSelectionRatio);
	}

	public ObjectiveValue getObjectiveValue() {
		throw new NotImplementedException("TODO");
	}
}
