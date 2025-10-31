package tools.refinery.store.dse.evolutionary;

import org.jetbrains.annotations.Nullable;
import org.moeaframework.core.Solution;
import org.moeaframework.core.constraint.LessThanOrEqual;
import org.moeaframework.core.operator.Mutation;
import org.moeaframework.core.operator.Variation;
import org.moeaframework.problem.AbstractProblem;
import tools.refinery.logic.term.intinterval.IntBound;
import tools.refinery.logic.term.intinterval.IntInterval;
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
import tools.refinery.store.reasoning.representation.PartialFunction;
import tools.refinery.store.reasoning.representation.PartialSymbol;
import tools.refinery.store.representation.Symbol;
import tools.refinery.visualization.statespace.VisualizationStore;
import tools.refinery.visualization.statespace.internal.VisualizationStoreImpl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class RefineryProblem extends AbstractProblem {
    public static final int DEFAULT_RANDOMIZE_DEPTH = 1;
    public static final boolean DEFAULT_VISUALIZATION_ENABLED = false;
	public static final int ALLOWED_NUMBER_OF_VIOLATIONS = 5;

    private final Model model;
    private final Version initialVersion;
    private final VisualizationStore visualizationStore;
    private final int randomizeDepth;
    private final DesignSpaceExplorationAdapter dseAdapter;
    private final @Nullable PropagationAdapter propagationAdapter;
    private final RuleBasedMutation ruleBasedMutation;
    private final DeltaCrossover deltaCrossover ;

	private final List<PartialInterpretation<?,?>> objectiveInterpretations = new ArrayList<>();
	private final PartialInterpretation<?,?> violationInterpretation;

    public RefineryProblem(ModelStore store, Version initialVersion, List<PartialSymbol<?,?>> crossoverSymbols,
						   List<PartialFunction<?,?>> objectiveFunctions, PartialFunction<?,?> violationFunction ) {
        this(store, initialVersion, crossoverSymbols, objectiveFunctions, violationFunction, DEFAULT_RANDOMIZE_DEPTH,
				DEFAULT_VISUALIZATION_ENABLED);
    }

    public RefineryProblem(ModelStore store, Version initialVersion, List<PartialSymbol<?,?>> crossoverSymbols,
						   List<PartialFunction<?,?>> objectiveFunctions, PartialFunction<?,?> violationFunction,
						   boolean isVisualizationEnabled) {
        this(store, initialVersion, crossoverSymbols, objectiveFunctions, violationFunction, DEFAULT_RANDOMIZE_DEPTH,
				isVisualizationEnabled);
    }

    public RefineryProblem(ModelStore store, Version initialVersion, List<PartialSymbol<?,?>> crossoverSymbols,
						   List<PartialFunction<?,?>> objectiveFunctions, PartialFunction<?,?> violationFunction,
						   int randomizeDepth) {
        this(store, initialVersion, crossoverSymbols, objectiveFunctions, violationFunction, randomizeDepth,
				DEFAULT_VISUALIZATION_ENABLED);
    }

    public RefineryProblem(ModelStore store, Version initialVersion, List<PartialSymbol<?,?>> crossoverSymbols,
						   List<PartialFunction<?,?>> objectiveFunctions, PartialFunction<?,?> violationFunction,
						   int randomizeDepth, boolean isVisualizationEnabled) {
        super(1, objectiveFunctions.size(), 1);
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
//		for(var objFun: objectiveFunctions) {
//			this.objectiveInterpretations.add(reasoningAdapter.getPartialInterpretation(Concreteness.CANDIDATE, objFun));
//		}
//		this.violationInterpretation = reasoningAdapter.getPartialInterpretation(Concreteness.PARTIAL,
//				violationFunction);
		violationInterpretation = null;

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

		Integer constraintCount = getConstraintCount();
		if (constraintCount == null || constraintCount > ALLOWED_NUMBER_OF_VIOLATIONS) {
			setInfeasible(solution);
			return;
		}

        if (propagationAdapter != null && propagationAdapter.concretizationRequested()) {
            var concretizationResult = propagationAdapter.concretize();
			constraintCount = getConstraintCount();
            if (concretizationResult.isRejected() || constraintCount == null || constraintCount > ALLOWED_NUMBER_OF_VIOLATIONS) {
                setInfeasible(solution);
                return;
            }
        }

		setObjectiveValues(solution);
		solution.setConstraintValue(0, constraintCount);

        if (visualizationStore != null && dseAdapter.checkAccept()) {
            visualizationStore.addSolution(version);
        }
    }

    private void setInfeasible(Solution solution) {
		for (int i = 0; i < numberOfObjectives; i++) {
			solution.setObjectiveValue(i, Double.POSITIVE_INFINITY);
		}
        solution.setConstraintValue(0, Double.POSITIVE_INFINITY);
    }

	private Integer getConstraintCount() {
		var cursor = violationInterpretation.getAll();
		if (cursor.move())
		{
			IntInterval interval = (IntInterval) cursor.getValue();
			return getLowerValueOrNull(interval);
		}
		return null;
	}

	private void setObjectiveValues(Solution solution) {
		for (int i = 0; i < numberOfObjectives; i++) {
			var objectiveInterpretation =  objectiveInterpretations.get(i);
			var cursor = objectiveInterpretation.getAll();
			if(cursor.move()) {
				IntInterval interval = (IntInterval) cursor.getValue();
				var value = getLowerValueOrNull(interval);
				if (value == null) solution.setObjectiveValue(i, Double.POSITIVE_INFINITY);
				else solution.setObjectiveValue(i, value);
			}
			else solution.setObjectiveValue(i, Double.POSITIVE_INFINITY);
		}
	}

	private Integer getLowerValueOrNull(IntInterval interval) {
		IntBound number = interval.lowerBound();
		if (number instanceof IntBound.Finite finite) {
			return finite.value();
		}
		return null;
	}

    @Override
    public Solution newSolution() {
        var solution = new Solution(numberOfVariables, numberOfObjectives, numberOfConstraints);
        solution.setVariable(0, new VersionVariable(this, initialVersion));
        solution.setConstraint(0, new LessThanOrEqual(ALLOWED_NUMBER_OF_VIOLATIONS));

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
		double[] values = new double[numberOfObjectives];
		for (int i = 0; i < numberOfObjectives; i++) {
			var objectiveInterpretation =  objectiveInterpretations.get(i);
			var cursor = objectiveInterpretation.getAll();
			if(cursor.move()) {
				IntInterval interval = (IntInterval) cursor.getValue();
				var value = getLowerValueOrNull(interval);
				if (value == null) values[i] = Double.POSITIVE_INFINITY;
				else values[i] = value;
			}
			else values[i] = Double.POSITIVE_INFINITY;
		}
		return new ObjectiveValues.ObjectiveValueN(values);
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

	public void displaySymbolValue(String name) {
		System.out.println("Displaying symbol: " + name);
		var any = model.getInterpretation(model.getStore().getSymbolByName(name));
		var inter = (Interpretation<?>) any;
		var cur = inter.getAll();
		while(cur.move()) {
			var k = cur.getKey();
			var v = cur.getValue();
			System.out.println("\t" + k + " -> " + v);
		}
		System.out.println();
	}

	public void displayVersion() {
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
