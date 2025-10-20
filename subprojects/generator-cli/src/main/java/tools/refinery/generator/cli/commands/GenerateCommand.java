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
				var v2 = solutions.get(50).version();
				generator.loadSolution(99);
				var diff = generator.getModel().getDiffCursor(v2);

				Model model = generator.getModel();
				var modelStore = generator.getModelStore();
				double ratio = 0.4;

				// var modificationAdapter = model.getAdapter(ModificationAdapter.class);
				var propagationAdapter = model.getAdapter(PropagationAdapter.class);
				var crossover = new Crossover(ratio);
				var childVersion = crossover.mergeDiffAndCommit(model, modelStore, diff, propagationAdapter);

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



	private class Crossover /*TODO uncomment implements Variation*/ {

		private static final String TYPE_SYMBOL_NAME = "TYPE";
		private static final String COUNT_SYMBOL_NAME = "COUNT";
		private static final String MODEL_SIZE_SYMBOL_NAME = "MODEL_SIZE";
		private static final Random random = new Random();

		private final double includeDiffRatio;

		public Crossover(double includeDiffRatio) {
			if(includeDiffRatio <= 0.0 || includeDiffRatio >= 1.0) {
				throw new IllegalArgumentException("Inclusion of differences ratio must be between 0 and 1");
			}
			this.includeDiffRatio = includeDiffRatio;
		}

		//TODO after testing:
		// model modelStore and diffCursor could be parameters of the class instead of each method
		// can be private
		public Version mergeDiffAndCommit(Model model, ModelStore modelStore, ModelDiffCursor diffCursor, PropagationAdapter propagationAdapter) {

			// Handling nodes
			var deletedNodes = mergeNodesAndReturnDeletedIds(model, modelStore, diffCursor);

			// Handling edges and attributes
			for (var anySymbol : modelStore.getSymbols()) {
				if(anySymbol.name().equals(TYPE_SYMBOL_NAME) || anySymbol.name().equals(COUNT_SYMBOL_NAME) || anySymbol.name().equals(MODEL_SIZE_SYMBOL_NAME)) {
					continue;
				}
				var anyInterpretation = model.getInterpretation(anySymbol);
				var deltas = getDeltasOfAnySymbol(diffCursor, anySymbol, anyInterpretation, deletedNodes);
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

		private Set<Integer> mergeNodesAndReturnDeletedIds(Model model, ModelStore modelStore, ModelDiffCursor diffCursor) {
			var nodeChanges = updateTypes(model, modelStore, diffCursor);
			updateCounts(model, modelStore, diffCursor, nodeChanges);
			return nodeChanges.deletedNodes;
		}

		private NodeChanges updateTypes(Model model, ModelStore modelStore, ModelDiffCursor diffCursor) {
			var typeSymbol = modelStore.getSymbolByName(TYPE_SYMBOL_NAME);
			if (typeSymbol == null) {
				throw new IllegalStateException(TYPE_SYMBOL_NAME + " symbol not found in model store");
			}
			var typeAnyInterpretation = model.getInterpretation(typeSymbol);
			var typeDeltas = getDeltasOfAnySymbol(diffCursor, typeSymbol, typeAnyInterpretation, null);
			var typeInterpretation = castInterpretation(typeAnyInterpretation);

			var createdNodes = new HashSet<Integer>();
			var deletedNodes = new HashSet<Integer>();

			for (MapDelta<Tuple, ?> td : typeDeltas) {
				int id = td.getKey().get(0);
				if (random.nextDouble() < includeDiffRatio) {
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
						deletedNodes.add(id);
					}
				}
			}

			return new NodeChanges(createdNodes, deletedNodes);
		}

		private boolean checkIfTypeValueIsNull(Object value) {
			return value instanceof InferredType type && type.candidateType() == null;
		}

		private void updateCounts(Model model, ModelStore modelStore, ModelDiffCursor diffCursor, NodeChanges nodeChanges) {
			var countSymbol = modelStore.getSymbolByName(COUNT_SYMBOL_NAME);
			if (countSymbol == null) {
				throw new IllegalStateException(COUNT_SYMBOL_NAME + " symbol not found in model store");
			}
			var countAnyInterpretation = model.getInterpretation(countSymbol);
			var countDeltas = getDeltasOfAnySymbol(diffCursor, countSymbol, countAnyInterpretation, null);
			var countInterpretation = castInterpretation(countAnyInterpretation);

			//TODO check the form of giving COUNT etc. null [0] [0..12]
			for (MapDelta<Tuple, ?> cd : countDeltas) {
				int id = cd.getKey().get(0);
				if (nodeChanges.createdNodes.contains(id) && cd.getOldValue() == null) {
					System.out.println("Applying change to " + countSymbol.name() + " at " + cd.getKey() +
							" from " + cd.getOldValue() + " to " + cd.getNewValue());
					countInterpretation.put(cd.getKey(), cd.getNewValue());
				}
				else if (nodeChanges.deletedNodes.contains(id)) {
					countInterpretation.put(cd.getKey(), 0);
					System.out.println("Applying change to " + countSymbol.name() + " at " + cd.getKey() +
							" from " + cd.getOldValue() + " to " + cd.getNewValue());
				}
			}
		}

		private List<MapDelta<Tuple, ?>> getDeltasOfAnySymbol(ModelDiffCursor diffCursor, AnySymbol anySymbol,
															  AnyInterpretation interpretation,
															  Set<Integer> excludeIds) {
			if (!(interpretation instanceof Interpretation<?>)) {
				throw new IllegalStateException(anySymbol.name()+" symbol interpretation is not of type Interpretation");
			}
			var symbol = (Symbol<?>) anySymbol;
			var cursor = diffCursor.getCursor(symbol);

			Map<Tuple, MapDelta<Tuple, ?>> typeDeltas = new HashMap<>();
			while (cursor.move()) {
				Tuple key = cursor.getKey();

				if(checkIfShouldSkip(key, excludeIds)) {
					continue;
				}

				Object fromValue = cursor.getFromValue();
				Object toValue = cursor.getToValue();

				MapDelta<Tuple, ?> existing = typeDeltas.get(key);
				if (existing != null && existing.getOldValue().equals(toValue) && existing.getNewValue().equals(fromValue)) {
					typeDeltas.remove(key);
				} else {
					typeDeltas.put(key, new MapDelta<>(key, fromValue, toValue));
				}
			}

			return new ArrayList<>(typeDeltas.values());
		}

		private boolean checkIfShouldSkip(Tuple key, Set<Integer> excludeIds) {
			if (excludeIds != null && !excludeIds.isEmpty()) {
				for (int i = 0; i < key.getSize(); i++) {
					Object elem = key.get(i);
					if (excludeIds.contains(elem)) {
						return true;
					}
				}
			}
			return false;
		}

		@SuppressWarnings("unchecked")
		private static <T> Interpretation<T> castInterpretation(AnyInterpretation anyInterpretation) {
			return (Interpretation<T>) anyInterpretation;
		}

		private record NodeChanges(Set<Integer> createdNodes, Set<Integer> deletedNodes) {}
	}
}


