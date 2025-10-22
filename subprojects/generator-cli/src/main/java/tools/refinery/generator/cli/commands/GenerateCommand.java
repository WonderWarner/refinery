/*
 * SPDX-FileCopyrightText: 2023-2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.generator.cli.commands;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.inject.Inject;
import tools.refinery.generator.ModelGeneratorFactory;
import tools.refinery.generator.cli.RefineryCli;
import tools.refinery.generator.cli.utils.CliProblemLoader;
import tools.refinery.generator.cli.utils.CliProblemSerializer;
import tools.refinery.generator.cli.utils.CliUtils;
import tools.refinery.logic.term.cardinalityinterval.CardinalityInterval;
import tools.refinery.logic.term.truthvalue.TruthValue;
import tools.refinery.store.dse.propagation.PropagationAdapter;
import tools.refinery.store.map.Version;
import tools.refinery.store.map.internal.delta.MapDelta;
import tools.refinery.store.model.*;
import tools.refinery.store.reasoning.translator.typehierarchy.InferredType;
import tools.refinery.store.representation.AnySymbol;
import tools.refinery.store.representation.Symbol;
import tools.refinery.store.tuple.Tuple;
import tools.refinery.visualization.ModelVisualizerAdapter;
import tools.refinery.visualization.statespace.internal.VisualizationStoreImpl;

import java.io.IOException;
import java.util.*;

@Parameters(commandDescription = "Generate a model from a partial model")
public class GenerateCommand implements Command {
	private final CliProblemLoader loader;
	private final ModelGeneratorFactory generatorFactory;
	private final CliProblemSerializer serializer;

	private String inputPath;
	private String outputPath = CliUtils.STANDARD_OUTPUT_PATH;
	private List<String> scopes = new ArrayList<>();
	private List<String> overrideScopes = new ArrayList<>();
	private long randomSeed = 1;
	private int count = 1;

	@Inject
	public GenerateCommand(CliProblemLoader loader, ModelGeneratorFactory generatorFactory,
						   CliProblemSerializer serializer) {
		this.loader = loader;
		this.generatorFactory = generatorFactory;
		this.serializer = serializer;
	}

	@Parameter(description = "input path", required = true)
	public void setInputPath(String inputPath) {
		this.inputPath = inputPath;
	}

	@Parameter(names = {"-output", "-o"}, description = "Output path")
	public void setOutputPath(String outputPath) {
		this.outputPath = outputPath;
	}

	@Parameter(names = {"-scope", "-s"}, description = "Extra scope constraints")
	public void setScopes(List<String> scopes) {
		this.scopes = scopes;
	}

	@Parameter(names = {"-scope-override", "-S"}, description = "Override scope constraints")
	public void setOverrideScopes(List<String> overrideScopes) {
		this.overrideScopes = overrideScopes;
	}

	@Parameter(names = {"-random-seed", "-r"}, description = "Random seed")
	public void setRandomSeed(long randomSeed) {
		this.randomSeed = randomSeed;
	}

	@Parameter(names = {"-solution-number", "-n"}, description = "Maximum number of solutions")
	public void setCount(int count) {
		if (count <= 0) {
			throw new IllegalArgumentException("Count must be positive");
		}
		this.count = count;
	}

	@Override
	public int run() throws IOException {
		if (count > 1 && CliUtils.isStandardStream(outputPath)) {
			throw new IllegalArgumentException("Must provide output path if count is larger than 1");
		}
		var problem = loader.loadProblem(inputPath, scopes, overrideScopes);
		generatorFactory.partialInterpretationBasedNeighborhoods(count >= 2);
		try (var generator = generatorFactory.createGenerator(problem)) {
			generator.setRandomSeed(randomSeed);
			generator.setMaxNumberOfSolutions(count);
			generator.generate();
			if (count == 1) {
				serializer.saveModel(generator, outputPath);
			} else {
				int solutionCount = generator.getSolutionCount();
				/*for (int i = 0; i < solutionCount; i++) {
					generator.loadSolution(i);
					var pathWithIndex = CliUtils.getFileNameWithIndex(outputPath, i + 1);
					serializer.saveModel(generator, pathWithIndex, false);
				}*/


				var solutionStore = generator.getSolutionStore();
				var solutions = solutionStore.getSolutions();
				var version1 = solutions.get(99).version();
				var version2 = solutions.get(50).version();

				Model model = generator.getModel();
				var modelStore = generator.getModelStore();
				double ratio = 0.4;

				// var modificationAdapter = model.getAdapter(ModificationAdapter.class);
				var propagationAdapter = model.getAdapter(PropagationAdapter.class);
				var crossover = new Crossover(ratio, model, propagationAdapter);
				var childVersion = crossover.evolve(version2, version1);

				// visualize the child version
				var visualizer = model.getAdapter(ModelVisualizerAdapter.class);
				if (visualizer != null) {
					var vs = new VisualizationStoreImpl();
					vs.addState(childVersion, "child");
					vs.addSolution(childVersion);
					visualizer.visualize(vs);
				}
			}
		}
		return RefineryCli.EXIT_SUCCESS;
	}

	public class Crossover {

		private static final String TYPE_SYMBOL_NAME = "TYPE";
		private static final String COUNT_SYMBOL_NAME = "COUNT";
		private static final String MODEL_SIZE_SYMBOL_NAME = "MODEL_SIZE";
		private static final String CONTAINS_SYMBOL_NAME = "CONTAINS";
		private static final Random random = new Random();

		private final Model model;
		private final ModelStore modelStore;
		private ModelDiffCursor diffCursor;
		private final double includeDiffRatio;
		private final PropagationAdapter propagationAdapter;

		public Crossover(double includeDiffRatio, Model model, PropagationAdapter propagationAdapter) {
			if(includeDiffRatio <= 0.0 || includeDiffRatio >= 1.0) {
				throw new IllegalArgumentException("Inclusion of differences ratio must be between 0 and 1");
			}
			this.includeDiffRatio = includeDiffRatio;
			this.model = model;
			this.modelStore = model.getStore();
			this.diffCursor = null; // Will be set in evolve method
			this.propagationAdapter = propagationAdapter;
		}

		public Version evolve(Version version1, Version version2) {

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

			return mergeDiffAndCommit(preserveIds, abstractIdsOfVersion2);
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

		private Version mergeDiffAndCommit(Set<Integer> idsToBePreserved, Set<Integer> idsToBeAbstracted) {

			// Handling nodes
			var nodeChanges = mergeNodesAndReturnDeletedIds(idsToBePreserved, idsToBeAbstracted);

			// Handling edges and attributes
			for (var anySymbol : modelStore.getSymbols()) {
				if(anySymbol.name().equals(TYPE_SYMBOL_NAME) || anySymbol.name().equals(COUNT_SYMBOL_NAME)
						|| anySymbol.name().equals(MODEL_SIZE_SYMBOL_NAME) || anySymbol.name().equals(CONTAINS_SYMBOL_NAME)) {
					continue;
				}
				var anyInterpretation = model.getInterpretation(anySymbol);

				var deletedOrAbstractedNodes = new HashSet<>(nodeChanges.deletedNodes);
				deletedOrAbstractedNodes.addAll(idsToBeAbstracted);
				var preservedOrIgnoredNodes = new HashSet<>(idsToBePreserved);
				preservedOrIgnoredNodes.addAll(nodeChanges.nodesToIgnore);

				var deltas = getDeltasOfAnySymbolWithIgnoreAndAbstraction(anySymbol, anyInterpretation, preservedOrIgnoredNodes, deletedOrAbstractedNodes);
				Collections.shuffle(deltas);
				int limit = (int) (deltas.size() * includeDiffRatio);
				var interpretation = castInterpretation(anyInterpretation);

				for (int i = 0; i < limit; i++) {
					var d = deltas.get(i);
					System.out.println("Applying change to " + anySymbol.name() + " at " + d.getKey() +
							" from " + d.getOldValue() + " to " + d.getNewValue());
					interpretation.put(d.getKey(), d.getNewValue());
				}
			}

			// Propagation
			if (propagationAdapter != null) {
				var propagationResult = propagationAdapter.propagate();
				if (propagationResult.isRejected()) {
					return null;
				}
			}

			return model.commit();
		}

		private NodeChanges mergeNodesAndReturnDeletedIds(Set<Integer> idsToBePreserved, Set<Integer> idsToBeAbstracted) {
			var nodeChanges = updateTypes(idsToBePreserved, idsToBeAbstracted);
			updateCounts(nodeChanges, idsToBeAbstracted);
			return nodeChanges;
		}

		private NodeChanges updateTypes(Set<Integer> idsToBePreserved, Set<Integer> idsToBeAbstracted) {
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
				if(idsToBePreserved.contains(id)) {
					continue;
				}
				else if(idsToBeAbstracted.contains(id)) {
					typeInterpretation.put(td.getKey(), td.getNewValue()); // works if we get diffs in the right order
					System.out.println("Applying abstraction to " + typeSymbol.name() + " at " + td.getKey() +
							" from " + td.getOldValue() + " to " + td.getNewValue());
				}
				else if (random.nextDouble() < includeDiffRatio) {
					typeInterpretation.put(td.getKey(), td.getNewValue());
					System.out.println("Applying change to " + typeSymbol.name() + " at " + td.getKey() +
							" from " + td.getOldValue() + " to " + td.getNewValue());
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

		private void updateCounts(NodeChanges nodeChanges, Set<Integer> idsToBeAbstracted) {
			var countSymbol = modelStore.getSymbolByName(COUNT_SYMBOL_NAME);
			if (countSymbol == null) {
				throw new IllegalStateException(COUNT_SYMBOL_NAME + " symbol not found in model store");
			}
			var countAnyInterpretation = model.getInterpretation(countSymbol);
			var countInterpretation = castInterpretation(countAnyInterpretation);

			// 1) For every count diff whose key refers to a deleted node, set the interpretation to null.
			for (int id: nodeChanges.deletedNodes) {
				countInterpretation.put(Tuple.of(id), null);
				System.out.println("Applying null to " + countSymbol.name() + " at " + id);
			}

			// 2) Collect last newValue for created nodes and nodes to be abstracted (per key) by scanning COUNT diffs and overwriting entries.
			var nodesToUpdateCount = new HashSet<Integer>(nodeChanges.createdNodes);
			nodesToUpdateCount.addAll(idsToBeAbstracted);
			Map<Tuple, Object> newCountValues = collectNewCountForNodes((Symbol<?>) countSymbol, nodesToUpdateCount);

			// 3) Apply collected last values to the interpretation.
			for (Map.Entry<Tuple, Object> e : newCountValues.entrySet()) {
				countInterpretation.put(e.getKey(), e.getValue());
				System.out.println("Applying change to " + countSymbol.name() + " at " + e.getKey() +
						" to " + e.getValue());
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

		private List<MapDelta<Tuple, ?>> getDeltasOfAnySymbolWithIgnoreAndAbstraction(AnySymbol anySymbol, AnyInterpretation interpretation, Set<Integer> idsToBeIgnored, Set<Integer> idsToBeAbstractedOrDeleted) {
			if (!(interpretation instanceof Interpretation<?>)) {
				throw new IllegalStateException(anySymbol.name()+" symbol interpretation is not of type Interpretation");
			}
			var symbol = (Symbol<?>) anySymbol;
			var cursor = diffCursor.getCursor(symbol);

			Map<Tuple, List<MapDelta<Tuple, ?>>> deltasToKeys = new HashMap<>();
			while (cursor.move()) {
				Tuple key = cursor.getKey();

				if(isAnyIdOfKeyInSet(key, idsToBeIgnored)) {
					continue;
				}
				else if(isAnyIdOfKeyInSet(key, idsToBeAbstractedOrDeleted)) {
					try {
						@SuppressWarnings("unchecked")
						Interpretation<TruthValue> tvInterp = (Interpretation<TruthValue>) interpretation;
						tvInterp.put(key, TruthValue.UNKNOWN);
					} catch (ClassCastException e) {
						@SuppressWarnings("unchecked")
						Interpretation<Object> objInterp = (Interpretation<Object>) interpretation;
						objInterp.put(key, null); // might check some other types as well (int, bool etc.)
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

//
//	private class Crossover {
//
//		private static final String TYPE_SYMBOL_NAME = "TYPE";
//		private static final String COUNT_SYMBOL_NAME = "COUNT";
//		private static final String MODEL_SIZE_SYMBOL_NAME = "MODEL_SIZE";
//		private static final String CONTAINS_SYMBOL_NAME = "CONTAINS";
//		private static final Random random = new Random();
//
//		private final double includeDiffRatio;
//
//		public Crossover(double includeDiffRatio) {
//			if(includeDiffRatio <= 0.0 || includeDiffRatio >= 1.0) {
//				throw new IllegalArgumentException("Inclusion of differences ratio must be between 0 and 1");
//			}
//			this.includeDiffRatio = includeDiffRatio;
//		}
//
//		private static long sizeOfVersion(Model model, ModelStore modelStore, Version version) {
//			model.restore(version);
//			var typeSymbol = modelStore.getSymbolByName(TYPE_SYMBOL_NAME);
//			if (typeSymbol == null) {
//				throw new IllegalStateException(TYPE_SYMBOL_NAME + " symbol not found in model store");
//			}
//			var typeAnyInterpretation = model.getInterpretation(typeSymbol);
//			var typeInterpretation = castInterpretation(typeAnyInterpretation);
//			return typeInterpretation.getSize();
//		}
//
//		private Version mergeDiffAndCommit(Model model, ModelStore modelStore, ModelDiffCursor diffCursor,
//									 PropagationAdapter propagationAdapter) {
//
//			// Handling nodes
//			var deletedNodes = mergeNodesAndReturnDeletedIds(model, modelStore, diffCursor);
//
//			// Handling edges and attributes
//			for (var anySymbol : modelStore.getSymbols()) {
//				if(anySymbol.name().equals(TYPE_SYMBOL_NAME) || anySymbol.name().equals(COUNT_SYMBOL_NAME) || anySymbol.name().equals(MODEL_SIZE_SYMBOL_NAME) || anySymbol.name().equals(CONTAINS_SYMBOL_NAME)) {
//					continue;
//				}
//				var anyInterpretation = model.getInterpretation(anySymbol);
//				var deltas = getDeltasOfAnySymbol(diffCursor, anySymbol, anyInterpretation, deletedNodes);
//				Collections.shuffle(deltas);
//				int limit = (int) (deltas.size() * includeDiffRatio);
//				var interpretation = castInterpretation(anyInterpretation);
//
//				for (int i = 0; i < limit; i++) {
//					var d = deltas.get(i);
//					System.out.println("Applying change to " + anySymbol.name() + " at " + d.getKey() +
//							" from " + d.getOldValue() + " to " + d.getNewValue());
//					interpretation.put(d.getKey(), d.getNewValue());
//				}
//			}
//
//			// Propagation
//			if (propagationAdapter != null) {
//				var propagationResult = propagationAdapter.propagate();
//				if (propagationResult.isRejected()) {
//					return null;
//				}
//			}
//
//			return model.commit();
//		}
//
//		private Set<Integer> mergeNodesAndReturnDeletedIds(Model model, ModelStore modelStore, ModelDiffCursor diffCursor) {
//			var nodeChanges = updateTypes(model, modelStore, diffCursor);
//			updateCounts(model, modelStore, diffCursor, nodeChanges);
//			return nodeChanges.deletedNodes;
//		}
//
//		private NodeChanges updateTypes(Model model, ModelStore modelStore, ModelDiffCursor diffCursor) {
//			var typeSymbol = modelStore.getSymbolByName(TYPE_SYMBOL_NAME);
//			if (typeSymbol == null) {
//				throw new IllegalStateException(TYPE_SYMBOL_NAME + " symbol not found in model store");
//			}
//			var typeAnyInterpretation = model.getInterpretation(typeSymbol);
//			var typeDeltas = getDeltasOfAnySymbol(diffCursor, typeSymbol, typeAnyInterpretation, null);
//			var typeInterpretation = castInterpretation(typeAnyInterpretation);
//
//			var createdNodes = new HashSet<Integer>();
//			var deletedNodes = new HashSet<Integer>();
//
//			for (MapDelta<Tuple, ?> td : typeDeltas) {
//				int id = td.getKey().get(0);
//				if (random.nextDouble() < includeDiffRatio) {
//					typeInterpretation.put(td.getKey(), td.getNewValue());
//					System.out.println("Applying change to " + typeSymbol.name() + " at " + td.getKey() +
//							" from " + td.getOldValue() + " to " + td.getNewValue());
//					if (checkIfTypeValueIsNull(td.getOldValue())) {
//						createdNodes.add(id);
//						deletedNodes.remove(id);
//					}
//					else if (checkIfTypeValueIsNull(td.getNewValue())) {
//						deletedNodes.add(id);
//						createdNodes.remove(id);
//					}
//				} else if (checkIfTypeValueIsNull(td.getOldValue())){
//					boolean hasDeletionForId = typeDeltas.stream()
//							.anyMatch(md -> md.getKey().get(0) == id && checkIfTypeValueIsNull(md.getNewValue()));
//					if (!hasDeletionForId) {
//						deletedNodes.add(id);
//					}
//				}
//			}
//
//			return new NodeChanges(createdNodes, deletedNodes);
//		}
//
//		private boolean checkIfTypeValueIsNull(Object value) {
//			return value instanceof InferredType type && type.candidateType() == null;
//		}
//
//		private void updateCounts(Model model, ModelStore modelStore, ModelDiffCursor diffCursor, NodeChanges nodeChanges) {
//			var countSymbol = modelStore.getSymbolByName(COUNT_SYMBOL_NAME);
//			if (countSymbol == null) {
//				throw new IllegalStateException(COUNT_SYMBOL_NAME + " symbol not found in model store");
//			}
//			var countAnyInterpretation = model.getInterpretation(countSymbol);
//			var countInterpretation = castInterpretation(countAnyInterpretation);
//
//			// 1) For every count diff whose key refers to a deleted node, set the interpretation to null.
//			for (int id: nodeChanges.deletedNodes) {
//				countInterpretation.put(Tuple.of(id), null);
//				System.out.println("Applying null to " + countSymbol.name() + " at " + id);
//			}
//
//			// 2) Collect last newValue for created nodes (per key) by scanning COUNT diffs and overwriting entries.
//			Map<Tuple, Object> newCountValues = collectNewCountForCreatedNodes(diffCursor, (Symbol<?>) countSymbol,
//					nodeChanges.createdNodes);
//
//			// 3) Apply collected last values to the interpretation.
//			for (Map.Entry<Tuple, Object> e : newCountValues.entrySet()) {
//				countInterpretation.put(e.getKey(), e.getValue());
//				System.out.println("Applying change to " + countSymbol.name() + " at " + e.getKey() +
//						" to " + e.getValue());
//			}
//		}
//
//		private Map<Tuple, Object> collectNewCountForCreatedNodes(ModelDiffCursor diffCursor, Symbol<?> countSymbol, Set<Integer> createdIds) {
//			Map<Tuple, Object> result = new HashMap<>();
//			var cursor = diffCursor.getCursor(countSymbol);
//			while (cursor.move()) {
//				Tuple key = cursor.getKey();
//				int id = key.get(0);
//				if (createdIds.contains(id)) {;
//					// overwrite previous value for the same key so final map keeps the last seen newValue
//					// only works if the cursor moves in proper order
//					result.put(key, cursor.getToValue());
//				}
//			}
//			return result;
//		}
//
//		private List<MapDelta<Tuple, ?>> getDeltasOfAnySymbol(ModelDiffCursor diffCursor, AnySymbol anySymbol,
//															  AnyInterpretation interpretation,
//															  Set<Integer> excludeIds) {
//			if (!(interpretation instanceof Interpretation<?>)) {
//				throw new IllegalStateException(anySymbol.name()+" symbol interpretation is not of type Interpretation");
//			}
//			var symbol = (Symbol<?>) anySymbol;
//			var cursor = diffCursor.getCursor(symbol);
//
//			Map<Tuple, List<MapDelta<Tuple, ?>>> deltasToKeys = new HashMap<>();
//			while (cursor.move()) {
//				Tuple key = cursor.getKey();
//
//				if(checkIfShouldSkip(key, excludeIds)) {
//					continue;
//				}
//
//				Object fromValue = cursor.getFromValue();
//				Object toValue = cursor.getToValue();
//
//				MapDelta<Tuple, ?> newDelta = new MapDelta<>(key, fromValue, toValue);
//				List<MapDelta<Tuple, ?>> deltasToKey = deltasToKeys.computeIfAbsent(key, k -> new ArrayList<>());
//				removeInverseOrAddDelta(deltasToKey, newDelta);
//			}
//
//			List<MapDelta<Tuple, ?>> result = new ArrayList<>();
//			for (List<MapDelta<Tuple, ?>> l : deltasToKeys.values()) {
//				result.addAll(l);
//			}
//			return result;
//		}
//
//		private boolean checkIfShouldSkip(Tuple key, Set<Integer> excludeIds) {
//			if (excludeIds != null && !excludeIds.isEmpty()) {
//				for (int i = 0; i < key.getSize(); i++) {
//					Object elem = key.get(i);
//					if (excludeIds.contains(elem)) {
//						return true;
//					}
//				}
//			}
//			return false;
//		}
//
//		private void removeInverseOrAddDelta(List<MapDelta<Tuple, ?>> deltasToKey, MapDelta<Tuple, ?> newDelta) {
//			for (Iterator<MapDelta<Tuple, ?>> it = deltasToKey.iterator(); it.hasNext(); ) {
//				MapDelta<Tuple, ?> existingDelta = it.next();
//				if (isInverse(existingDelta, newDelta)) {
//					it.remove();
//					return;
//				}
//			}
//			deltasToKey.add(newDelta);
//		}
//
//		private boolean isInverse(MapDelta<Tuple, ?> existingDelta, MapDelta<Tuple, ?> newDelta) {
//			Object existingDeltaOldValue = existingDelta.getOldValue();
//			Object existingDeltaNewValue = existingDelta.getNewValue();
//			Object newDeltaOldValue = newDelta.getOldValue();
//			Object newDeltaNewValue = newDelta.getNewValue();
//			return Objects.equals(existingDeltaOldValue, newDeltaNewValue) && Objects.equals(existingDeltaNewValue, newDeltaOldValue);
//		}
//
//		@SuppressWarnings("unchecked")
//		private static <T> Interpretation<T> castInterpretation(AnyInterpretation anyInterpretation) {
//			return (Interpretation<T>) anyInterpretation;
//		}
//
//		private record NodeChanges(Set<Integer> createdNodes, Set<Integer> deletedNodes) {}
//	}
}


