package tools.refinery.store.dse.evolutionary;

import org.moeaframework.core.Solution;
import org.moeaframework.core.operator.Variation;
import tools.refinery.logic.term.cardinalityinterval.CardinalityInterval;
import tools.refinery.logic.term.truthvalue.TruthValue;
import tools.refinery.store.map.Version;
import tools.refinery.store.map.internal.delta.MapDelta;
import tools.refinery.store.model.*;
import tools.refinery.store.reasoning.translator.typehierarchy.InferredType;
import tools.refinery.store.representation.AnySymbol;
import tools.refinery.store.representation.Symbol;
import tools.refinery.store.tuple.Tuple;
import tools.refinery.visualization.statespace.VisualizationStore;

import java.util.*;

public class DeltaCrossover implements Variation {

	private static long crossoverTimeNanos = 0L;
	public static synchronized void addCrossoverTimeNanos(long nanos) {
		crossoverTimeNanos += nanos;
	}
	public static synchronized long getCrossoverTimeNanos() {
		return crossoverTimeNanos;
	}

	private static long totalCrossover = 0L;
	private static long successfulCrossover = 0L;
	public static long getTotalCrossover() {
		return totalCrossover;
	}
	public static long getSuccessfulCrossover() {
		return successfulCrossover;
	}
	public static synchronized void resetMeasurements() {
		crossoverTimeNanos = 0L;
		totalCrossover = 0L;
		successfulCrossover = 0L;
	}

	private static final String TYPE_SYMBOL_NAME = "TYPE";
	private static final String COUNT_SYMBOL_NAME = "COUNT";
	private static final Random random = new Random();

	private final RefineryProblem problem;
	private final Model model;
	private final ModelStore modelStore;
	//private final VisualizationStore visualizationStore;
	//private final boolean isVisualizationEnabled;
	private boolean shouldCrossoverNodes = false;
	private ModelDiffCursor diffCursor;
	private double deltaSelectionRatio;
	private double probabilityOfCrossover = 0.3;
	private final List<Symbol<?>> crossoverSymbols;

	public DeltaCrossover(RefineryProblem problem, double deltaSelectionRatio,
						  List<Symbol<?>> crossoverSymbols) {
		this.problem = problem;
		if(deltaSelectionRatio <= 0.0 || deltaSelectionRatio >= 1.0) {
			throw new IllegalArgumentException("Inclusion of differences ratio must be between 0 and 1");
		}
		this.deltaSelectionRatio = deltaSelectionRatio;
		this.crossoverSymbols = crossoverSymbols;
		this.model = problem.getModel();
		this.modelStore = model.getStore();
		//visualizationStore = problem.getVisualizationStore();
		//isVisualizationEnabled = visualizationStore != null;
		this.diffCursor = null; // Will be set in evolve method
	}

	public void setRandomSeed(long seed) {
		random.setSeed(seed);
	}

	public void setShouldCrossoverNodes(boolean shouldCrossoverNodes) {
		this.shouldCrossoverNodes = shouldCrossoverNodes;
	}

	public void setProbabilityOfCrossover(double probability) {
		if(probability < 0.0 || probability > 1.0) {
			throw new IllegalArgumentException("Probability of crossover must be between 0 and 1");
		}
		this.probabilityOfCrossover = probability;
	}

	public void setDeltaSelectionRatio(double ratio) {
		if(ratio <= 0.0 || ratio >= 1.0) {
			throw new IllegalArgumentException("Inclusion of differences ratio must be between 0 and 1");
		}
		this.deltaSelectionRatio = ratio;
	}

	@Override
	public String getName() {
		return this.getClass().getSimpleName();
	}

	@Override
	public int getArity() {
		return 2;
	}

	@Override
	public Solution[] evolve(Solution[] solutions) {
		long start = System.nanoTime();
		try {
			if (solutions.length != this.getArity()) {
				throw new IllegalArgumentException("This Crossover requires " + this.getArity() + " solutions");
			}
			if (random.nextDouble() > probabilityOfCrossover) {
				return new Solution[]{solutions[0].copy(), solutions[1].copy()};
			}

			totalCrossover++;
			var child = solutions[0].copy();
			var version1 = RefineryProblem.getVersion(solutions[0]);
			var version2 = RefineryProblem.getVersion(solutions[1]);
			if (version1 == null || version2 == null) {
				return new Solution[]{solutions[0].copy(), solutions[1].copy()};
			}

			var abstractIdsOfVersion1 = new HashSet<Integer>();
			var abstractIdsOfVersion2 = new HashSet<Integer>();
			var sizeOfVersion1 = getSizeOfVersionAndCollectAbstractIds(version1, abstractIdsOfVersion1);
			var sizeOfVersion2 = getSizeOfVersionAndCollectAbstractIds(version2, abstractIdsOfVersion2);

			// Count nodes for both versions and pick the one with fewer nodes as the base
			if (sizeOfVersion1 > sizeOfVersion2) {
				var tempVersion = version1;
				version1 = version2;
				version2 = tempVersion;

				var tempIds = abstractIdsOfVersion1;
				abstractIdsOfVersion1 = abstractIdsOfVersion2;
				abstractIdsOfVersion2 = tempIds;
			}

			// preserveIds will be kept from version1 and should be ignored when applying diffs
			var preserveIds = new HashSet<Integer>();
			// abstractIdsOfVersion2 that are not in version1 will be changed to the abstract version
			// it's edges also should be set to UNKNOWN
			for (Integer id : abstractIdsOfVersion1) {
				if (!abstractIdsOfVersion2.remove(id)) {
					preserveIds.add(id);
				}
			}

			model.restore(version1);
			this.diffCursor = model.getDiffCursor(version2);

			var childVersion = applyDeltasAndCommit(preserveIds, abstractIdsOfVersion2);
			if (childVersion == null) {
				return new Solution[]{solutions[0].copy(), solutions[1].copy()};
			}

			RefineryProblem.setVersion(child, childVersion);
			successfulCrossover++;

//			if (isVisualizationEnabled) {
//				visualizationStore.addState(childVersion, problem.getObjectiveValue().toString());
//				visualizationStore.addTransition(version1, childVersion, Double.toString(1 - deltaSelectionRatio));
//				visualizationStore.addTransition(version2, childVersion, Double.toString(deltaSelectionRatio));
//			}

			return new Solution[]{child};
		}
		finally {
			addCrossoverTimeNanos(System.nanoTime() - start);
		}
	}

	private long getSizeOfVersionAndCollectAbstractIds(Version version, Set<Integer> abstractIds) {
		model.restore(version);
		var countSymbol = modelStore.getSymbolByName(COUNT_SYMBOL_NAME);
		if (countSymbol == null) {
			throw new IllegalStateException(COUNT_SYMBOL_NAME + " symbol not found in model store");
		}
		var countAnyInterpretation = model.getInterpretation(countSymbol);
		var countInterpretation = castInterpretation(countAnyInterpretation);
		var cursor = countInterpretation.getAll();
		while (cursor.move()) {
			Tuple key = cursor.getKey();
			Object value = cursor.getValue();
			if (value instanceof CardinalityInterval ci && !ci.isConcrete()) {
				abstractIds.add(key.get(0));
			}
		}
		return countInterpretation.getSize();
	}

	private Version applyDeltasAndCommit(Set<Integer> toPreserveIds, Set<Integer> toAbstractIds) {

		// Handling nodes
		var nodeChanges = mergeNodes(toPreserveIds, toAbstractIds);

		// Handling edges and attributes
		for (var symbol : crossoverSymbols) {
			var anyInterpretation = model.getInterpretation(symbol);

			var toDeleteOrAbstractIds = new HashSet<>(nodeChanges.deletedNodes);
			toDeleteOrAbstractIds.addAll(toAbstractIds);
			var toPreserveOrIgnoreIds = new HashSet<>(toPreserveIds);
			toPreserveOrIgnoreIds.addAll(nodeChanges.nodesToIgnore);

			var deltas = getDeltasOfAnySymbolWithIgnoreAndAbstraction(symbol, anyInterpretation,
					toPreserveOrIgnoreIds, toDeleteOrAbstractIds);
			Collections.shuffle(deltas, random);
			int limit = (int) (deltas.size() * deltaSelectionRatio);
			var interpretation = castInterpretation(anyInterpretation);

			for (int i = 0; i < limit; i++) {
				var d = deltas.get(i);
				interpretation.put(d.getKey(), d.getNewValue());
			}
		}

		// Propagation
		var propagationAdapter = problem.getPropagationAdapter();
		if (propagationAdapter != null) {
			var propagationResult = propagationAdapter.propagate();
			if (propagationResult.isRejected()) {
				return null;
			}
		}

		return model.commit();
	}

	private NodeChanges mergeNodes(Set<Integer> toPreserveIds, Set<Integer> toAbstractIds) {
		var nodeChanges = updateTypes(toPreserveIds, toAbstractIds);
		updateCounts(nodeChanges, toAbstractIds);
		return nodeChanges;
	}

	private NodeChanges updateTypes(Set<Integer> toPreserveIds, Set<Integer> toAbstractIds) {
		var typeSymbol = modelStore.getSymbolByName(TYPE_SYMBOL_NAME);
		if (typeSymbol == null) {
			throw new IllegalStateException(TYPE_SYMBOL_NAME + " symbol not found in model store");
		}
		var typeAnyInterpretation = model.getInterpretation(typeSymbol);
		var typeDeltas = getDeltasOfAnySymbol(typeSymbol, typeAnyInterpretation);
		var typeInterpretation = castInterpretation(typeAnyInterpretation);

		var createdNodes = new HashSet<Integer>();
		var deletedNodes = new HashSet<Integer>();
		var nodesToIgnore = new HashSet<Integer>();

		for (MapDelta<Tuple, ?> td : typeDeltas) {
			int id = td.getKey().get(0);
			if(toPreserveIds.contains(id)) {
				continue;
			}
			else if(toAbstractIds.contains(id)) {
				typeInterpretation.put(td.getKey(), td.getNewValue()); // works if we get diffs in the right order
			}
			else if (shouldCrossoverNodes && random.nextDouble() < deltaSelectionRatio) {
				typeInterpretation.put(td.getKey(), td.getNewValue());
				if (checkIfTypeValueIsNull(td.getOldValue())) {
					createdNodes.add(id);
					deletedNodes.remove(id);
				}
				else if (checkIfTypeValueIsNull(td.getNewValue())) {
					deletedNodes.add(id);
					createdNodes.remove(id);
				}
			} else if (checkIfTypeValueIsNull(td.getOldValue())){
				boolean hasDeletionForId = typeDeltas.stream()
						.anyMatch(md -> md.getKey().get(0) == id && checkIfTypeValueIsNull(md.getNewValue()));
				if (!hasDeletionForId) {
					nodesToIgnore.add(id);
				}
			}
		}

		return new NodeChanges(createdNodes, deletedNodes, nodesToIgnore);
	}

	private boolean checkIfTypeValueIsNull(Object value) {
		return value instanceof InferredType type && type.candidateType() == null;
	}

	private void updateCounts(NodeChanges nodeChanges, Set<Integer> toAbstractIds) {
		var countSymbol = modelStore.getSymbolByName(COUNT_SYMBOL_NAME);
		if (countSymbol == null) {
			throw new IllegalStateException(COUNT_SYMBOL_NAME + " symbol not found in model store");
		}
		var countAnyInterpretation = model.getInterpretation(countSymbol);
		var countInterpretation = castInterpretation(countAnyInterpretation);

		// 1) For every count diff whose key refers to a deleted node, set the interpretation to null.
		for (int id: nodeChanges.deletedNodes) {
			countInterpretation.put(Tuple.of(id), null);
		}

		// 2) Collect last newValue for created nodes and nodes to be abstracted (per key) by scanning COUNT diffs and overwriting entries.
		var nodesToUpdateCount = new HashSet<>(nodeChanges.createdNodes);
		nodesToUpdateCount.addAll(toAbstractIds);
		Map<Tuple, Object> newCountValues = collectNewCountForNodes((Symbol<?>) countSymbol, nodesToUpdateCount);

		// 3) Apply collected last values to the interpretation.
		for (Map.Entry<Tuple, Object> e : newCountValues.entrySet()) {
			countInterpretation.put(e.getKey(), e.getValue());
		}
	}

	private Map<Tuple, Object> collectNewCountForNodes(Symbol<?> countSymbol, Set<Integer> ids) {
		Map<Tuple, Object> result = new HashMap<>();
		var cursor = diffCursor.getCursor(countSymbol);
		while (cursor.move()) {
			Tuple key = cursor.getKey();
			int id = key.get(0);
			if (ids.contains(id)) {
				// overwrite previous value for the same key so final map keeps the last seen newValue
				// only works if the cursor moves in proper order
				result.put(key, cursor.getToValue());
			}
		}
		return result;
	}

	private List<MapDelta<Tuple, ?>> getDeltasOfAnySymbol(AnySymbol anySymbol, AnyInterpretation interpretation) {
		return getDeltasOfAnySymbolWithIgnoreAndAbstraction(anySymbol, interpretation, null, null);
	}

	private List<MapDelta<Tuple, ?>> getDeltasOfAnySymbolWithIgnoreAndAbstraction(AnySymbol anySymbol, AnyInterpretation interpretation, Set<Integer> toIgnoreIds, Set<Integer> toAbstractOrDeletedIds) {
		if (!(interpretation instanceof Interpretation<?>)) {
			throw new IllegalStateException(anySymbol.name()+" symbol interpretation is not of type Interpretation");
		}
		var symbol = (Symbol<?>) anySymbol;
		var cursor = diffCursor.getCursor(symbol);

		Map<Tuple, List<MapDelta<Tuple, ?>>> deltasToKeys = new HashMap<>();
		while (cursor.move()) {
			Tuple key = cursor.getKey();

			if(isAnyIdOfKeyInSet(key, toIgnoreIds)) {
				continue;
			}
			else if(isAnyIdOfKeyInSet(key, toAbstractOrDeletedIds)) {
				try {
					@SuppressWarnings("unchecked")
					Interpretation<TruthValue> tvInterpretation = (Interpretation<TruthValue>) interpretation;
					tvInterpretation.put(key, TruthValue.UNKNOWN);
				} catch (ClassCastException e) {
					@SuppressWarnings("unchecked")
					Interpretation<Object> objInterpretation = (Interpretation<Object>) interpretation;
					objInterpretation.put(key, null); // might check some other types as well (int, bool etc.)
				}
			}
			else {
				Object fromValue = cursor.getFromValue();
				Object toValue = cursor.getToValue();

				MapDelta<Tuple, ?> newDelta = new MapDelta<>(key, fromValue, toValue);
				List<MapDelta<Tuple, ?>> deltasToKey = deltasToKeys.computeIfAbsent(key, k -> new ArrayList<>());
				removeInverseOrAddDelta(deltasToKey, newDelta);
			}
		}

		List<MapDelta<Tuple, ?>> result = new ArrayList<>();
		for (List<MapDelta<Tuple, ?>> l : deltasToKeys.values()) {
			result.addAll(l);
		}
		return result;
	}

	private boolean isAnyIdOfKeyInSet(Tuple key, Set<Integer> ids) {
		if (ids != null && !ids.isEmpty()) {
			for (int i = 0; i < key.getSize(); i++) {
				Object elem = key.get(i);
				if (ids.contains(elem)) {
					return true;
				}
			}
		}
		return false;
	}

	private void removeInverseOrAddDelta(List<MapDelta<Tuple, ?>> deltasToKey, MapDelta<Tuple, ?> newDelta) {
		for (Iterator<MapDelta<Tuple, ?>> it = deltasToKey.iterator(); it.hasNext(); ) {
			MapDelta<Tuple, ?> existingDelta = it.next();
			if (isInverse(existingDelta, newDelta)) {
				it.remove();
				return;
			}
		}
		deltasToKey.add(newDelta);
	}

	private boolean isInverse(MapDelta<Tuple, ?> existingDelta, MapDelta<Tuple, ?> newDelta) {
		Object existingDeltaOldValue = existingDelta.getOldValue();
		Object existingDeltaNewValue = existingDelta.getNewValue();
		Object newDeltaOldValue = newDelta.getOldValue();
		Object newDeltaNewValue = newDelta.getNewValue();
		return Objects.equals(existingDeltaOldValue, newDeltaNewValue) && Objects.equals(existingDeltaNewValue, newDeltaOldValue);
	}

	@SuppressWarnings("unchecked")
	private static <T> Interpretation<T> castInterpretation(AnyInterpretation anyInterpretation) {
		return (Interpretation<T>) anyInterpretation;
	}

	private record NodeChanges(Set<Integer> createdNodes, Set<Integer> deletedNodes, Set<Integer> nodesToIgnore) {}
}
