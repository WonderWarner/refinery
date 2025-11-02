package tools.refinery.store.dse.evolutionary;

import org.jetbrains.annotations.Nullable;
import org.moeaframework.core.Solution;
import org.moeaframework.core.constraint.LessThanOrEqual;
import org.moeaframework.core.operator.Mutation;
import org.moeaframework.core.operator.Variation;
import org.moeaframework.problem.AbstractProblem;
import tools.refinery.store.dse.propagation.PropagationAdapter;
import tools.refinery.store.dse.transition.DesignSpaceExplorationAdapter;
import tools.refinery.store.dse.transition.ObjectiveValue;
import tools.refinery.store.dse.transition.ObjectiveValues;
import tools.refinery.store.map.Version;
import tools.refinery.store.model.Interpretation;
import tools.refinery.store.model.Model;
import tools.refinery.store.model.ModelStore;
import tools.refinery.store.reasoning.ReasoningAdapter;
import tools.refinery.store.reasoning.interpretation.PartialInterpretation;
import tools.refinery.store.reasoning.literal.Concreteness;
import tools.refinery.store.reasoning.representation.PartialSymbol;
import tools.refinery.store.representation.Symbol;
import tools.refinery.visualization.statespace.VisualizationStore;
import tools.refinery.visualization.statespace.internal.VisualizationStoreImpl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RefineryProblem extends AbstractProblem {
	private static long measurementTimeNanos = 0L;
	public static synchronized void addMeasurementTimeNanos(long nanos) {
		measurementTimeNanos += nanos;
	}
	public static synchronized void setMeasurementTimeNanos(long nanos) {
		measurementTimeNanos = nanos;
	}
	public static synchronized long getMeasurementTimeNanos() {
		return measurementTimeNanos;
	}


	public record EvaluationRecord(long elapsedSinceFirstEvalNanos, double[] objectives, double constraintValue) {
		public EvaluationRecord {
			objectives = objectives == null ? new double[0] : Arrays.copyOf(objectives, objectives.length);
		}
	}
	private static long firstEvaluationTimeNanos = -1L;
	private final List<EvaluationRecord> evaluationRecords = new ArrayList<>();
	private double[] bestObjectiveValues;
	private double bestConstraintValue = Double.POSITIVE_INFINITY;

	private synchronized void collectEvaluation(Solution solution) {
		long startTime = System.nanoTime();
		try {

		if (solution == null) return;

		long now = System.nanoTime();
		if (firstEvaluationTimeNanos < 0) {
			firstEvaluationTimeNanos = now;
		}
		long elapsed = now - firstEvaluationTimeNanos;

		double[] objectives = new double[numberOfObjectives];
		for (int i = 0; i < numberOfObjectives; i++) {
			objectives[i] = solution.getObjective(i).getValue();
		}
		double constraintVal = solution.getConstraint(0).getValue();

		if (bestObjectiveValues == null || bestObjectiveValues.length != numberOfObjectives) {
			bestObjectiveValues = new double[numberOfObjectives];
			for (int i = 0; i < numberOfObjectives; i++) bestObjectiveValues[i] = Double.POSITIVE_INFINITY;
			bestConstraintValue = Double.POSITIVE_INFINITY;
		}

		boolean improved = false;
		for (int i = 0; i < numberOfObjectives; i++) {
			if (objectives[i] < bestObjectiveValues[i]) {
				improved = true;
				break;
			}
		}
		if (!improved && constraintVal < bestConstraintValue) {
			improved = true;
		}

		if (improved) {
			for (int i = 0; i < numberOfObjectives; i++) {
				if (objectives[i] < bestObjectiveValues[i]) {
					bestObjectiveValues[i] = objectives[i];
				}
			}
			if (constraintVal < bestConstraintValue) {
				bestConstraintValue = constraintVal;
			}

			evaluationRecords.add(new EvaluationRecord(elapsed, objectives, constraintVal));
		}
		} finally {
			addMeasurementTimeNanos(System.nanoTime() - startTime);
		}
	}

	public synchronized EvaluationRecord[] getEvaluationRecords() {
		return evaluationRecords.toArray(new EvaluationRecord[0]);
	}

	public synchronized void resetEvaluationRecords() {
		evaluationRecords.clear();
		firstEvaluationTimeNanos = -1L;
		bestConstraintValue = Double.POSITIVE_INFINITY;
		if (bestObjectiveValues != null) {
			Arrays.fill(bestObjectiveValues, Double.POSITIVE_INFINITY);
		}
	}

	private final Model model;
    private final Version initialVersion;
    private final VisualizationStore visualizationStore;
    private final int randomizeDepth;
	private int maxViolations = 10;
    private final DesignSpaceExplorationAdapter dseAdapter;
    private final @Nullable PropagationAdapter propagationAdapter;
    private final RuleBasedMutation ruleBasedMutation;
    private final DeltaCrossover deltaCrossover ;

	private final List<PartialInterpretation<?,?>> minObjectiveInterpretations = new ArrayList<>();
	private final List<PartialInterpretation<?,?>> maxObjectiveInterpretations = new ArrayList<>();
	private final List<PartialInterpretation<?,?>> violationInterpretations = new ArrayList<>();

    public RefineryProblem(ModelStore store, Version initialVersion, List<PartialSymbol<?,?>> crossoverSymbols,
						   List<PartialSymbol<?,?>> minObjectiveSymbols, List<PartialSymbol<?,?>> maxObjectiveSymbols,
						   List<PartialSymbol<?,?>> violationSymbols, int randomizeDepth,
						   boolean isVisualizationEnabled) {
        super(1, minObjectiveSymbols.size()+maxObjectiveSymbols.size(), 1);
        if (randomizeDepth < 0) {
            throw new IllegalArgumentException("randomizeDepth must be positive or zero");
        }
        model = store.createEmptyModel();
        this.initialVersion = initialVersion;

        if (isVisualizationEnabled) visualizationStore = new VisualizationStoreImpl();
        else visualizationStore = null;
        this.randomizeDepth = randomizeDepth;

		dseAdapter = model.getAdapter(DesignSpaceExplorationAdapter.class);
        propagationAdapter = model.tryGetAdapter(PropagationAdapter.class).orElse(null);

		var reasoningAdapter = model.getAdapter(ReasoningAdapter.class);
		for (var minObjectiveSymbol : minObjectiveSymbols) {
			this.minObjectiveInterpretations.add(reasoningAdapter.getPartialInterpretation(Concreteness.CANDIDATE, minObjectiveSymbol));
		}
		for (var maxObjectiveSymbol : maxObjectiveSymbols) {
			this.maxObjectiveInterpretations.add(reasoningAdapter.getPartialInterpretation(Concreteness.CANDIDATE, maxObjectiveSymbol));
		}
		for (var violationSymbol : violationSymbols) {
			this.violationInterpretations.add(reasoningAdapter.getPartialInterpretation(Concreteness.CANDIDATE, violationSymbol));
		}

		var selectedSymbols = toSymbols(store, crossoverSymbols);

		ruleBasedMutation = new RuleBasedMutation(this);
		deltaCrossover = new DeltaCrossover(this, 0.3, selectedSymbols);
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
        var version = getVersion(solution);
        if (version == null) {
            setInfeasible(solution);
            return;
        }
        model.restore(version);

		int constraintCount = getConstraintCount();
		if (constraintCount > maxViolations) {
			setInfeasible(solution);
			return;
		}

        if (propagationAdapter != null && propagationAdapter.concretizationRequested()) {
            var concretizationResult = propagationAdapter.concretize();
			constraintCount = getConstraintCount();
            if (concretizationResult.isRejected() || constraintCount > maxViolations) {
                setInfeasible(solution);
                return;
            }
        }

		setObjectiveValues(solution);
		solution.setConstraintValue(0, constraintCount);

		collectEvaluation(solution);

//        if (visualizationStore != null) {
//            visualizationStore.addSolution(version);
//        }
    }

    private void setInfeasible(Solution solution) {
		for (int i = 0; i < numberOfObjectives; i++) {
			solution.setObjectiveValue(i, Double.POSITIVE_INFINITY);
		}
        solution.setConstraintValue(0, Double.POSITIVE_INFINITY);
    }

	private int getConstraintCount() {
		int totalViolations = 0;
		for (var violationInterpretation : violationInterpretations) {
			totalViolations += countPred(violationInterpretation);
		}
		return totalViolations;
	}

	private void setObjectiveValues(Solution solution) {
		int i = 0;
		for (; i < minObjectiveInterpretations.size(); i++) {
			int value = countPred(minObjectiveInterpretations.get(i));
			solution.setObjectiveValue(i, value);
		}
		for (int j = 0; j < maxObjectiveInterpretations.size(); j++) {
			int value = countPred(maxObjectiveInterpretations.get(j));
			value *= -1;
			solution.setObjectiveValue(i+j, value);
		}
	}

	private int countPred(PartialInterpretation<?,?> partialInterpretation) {
		var cursor = partialInterpretation.getAll();
		int count = 0;
		while(cursor.move()) {
			count++;
		}
		return count;
	}

    @Override
    public Solution newSolution() {
        var solution = new Solution(numberOfVariables, numberOfObjectives, numberOfConstraints);
        solution.setVariable(0, new VersionVariable(this, initialVersion));
        solution.setConstraint(0, new LessThanOrEqual(maxViolations));

//        if (visualizationStore != null) {
//            visualizationStore.addState(getVersion(solution), dseAdapter.getObjectiveValue().toString());
//        }

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

	public void setShouldCrossoverNodes(boolean  shouldCrossoverNodes) {
		deltaCrossover.setShouldCrossoverNodes(shouldCrossoverNodes);
	}

	public void setMaxViolations(int maxViolations) {
		this.maxViolations = maxViolations;
	}

	public void setProbabilityOfCrossover(double probabilityOfCrossover) {
		deltaCrossover.setProbabilityOfCrossover(probabilityOfCrossover);
	}

	public static List<Symbol<?>> toSymbols(ModelStore store, List<PartialSymbol<?,?>> partialSymbols) {
		List<Symbol<?>> symbols = new ArrayList<>();
		for(var partialSymbol: partialSymbols) {
			Symbol<?> symbol = tryExtractSymbol(partialSymbol);
			if (symbol == null) {
				String name = tryExtractName(partialSymbol);
				if (name != null) {
					symbol = (Symbol<?>) store.getSymbolByName(name);
				}
			}
			if (symbol != null) {
				symbols.add(symbol);
			}
		}
		return symbols;
	}

	private static Symbol<?> tryExtractSymbol(PartialSymbol<?,?> p) {
		try {
			Method m = p.getClass().getMethod("getSymbol");
			Object o = m.invoke(p);
			if (o instanceof Symbol) return (Symbol<?>) o;
		} catch (Exception ignored) {}
		return null;
	}

	private static String tryExtractName(PartialSymbol<?,?> p) {
		try {
			Method m = p.getClass().getMethod("getName");
			Object o = m.invoke(p);
			if (o instanceof String string) return string;
		} catch (Exception ignored) {}
		try {
			Method m = p.getClass().getMethod("name");
			Object o = m.invoke(p);
			if (o instanceof String string) return string;
		} catch (Exception ignored) {}
		String s = p.toString();
		return (s == null || s.isBlank()) ? null : s;
	}

	public void displayVersion(Version version) {
		model.restore(version);
		System.out.println("Displaying model version");
		for(var symbol : model.getStore().getSymbols()) {
			System.out.println("\tSymbol: "+ symbol.name());
			var any = model.getInterpretation(symbol);
			var inter = (Interpretation<?>) any;
			var cur = inter.getAll();
			while(cur.move()) {
				var k = cur.getKey();
				var v = cur.getValue();
				System.out.println("\t\t" + k + " -> " + v);
			}
		}
		System.out.println();
	}
}
