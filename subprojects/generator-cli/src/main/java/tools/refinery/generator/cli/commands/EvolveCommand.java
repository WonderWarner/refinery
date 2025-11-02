package tools.refinery.generator.cli.commands;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.inject.Inject;
import org.eclipse.emf.common.util.EList;
import org.moeaframework.algorithm.NSGAII;
import org.moeaframework.core.Solution;
import org.moeaframework.core.operator.CompoundVariation;
import org.moeaframework.core.population.NondominatedPopulation;
import org.moeaframework.core.termination.MaxElapsedTime;
import org.moeaframework.util.format.TableFormat;
import tools.refinery.generator.ModelGeneratorFactory;
import tools.refinery.generator.cli.RefineryCli;
import tools.refinery.generator.cli.utils.CliProblemLoader;
import tools.refinery.generator.cli.utils.CliProblemSerializer;
import tools.refinery.generator.cli.utils.CliUtils;
import tools.refinery.language.model.problem.*;
import tools.refinery.language.model.problem.impl.LogicConstantImpl;
import tools.refinery.store.dse.evolutionary.DeltaCrossover;
import tools.refinery.store.dse.evolutionary.RefineryProblem;
import tools.refinery.store.dse.evolutionary.RuleBasedMutation;
import tools.refinery.store.dse.evolutionary.VersionVariable;
import tools.refinery.store.map.Version;
import tools.refinery.store.model.Model;
import tools.refinery.store.query.ModelQueryAdapter;
import tools.refinery.store.reasoning.representation.PartialSymbol;
import tools.refinery.visualization.ModelVisualizerAdapter;
import tools.refinery.visualization.statespace.internal.VisualizationStoreImpl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// example usage: cli --args="evolve cra.problem -o test_output/solution.refinery -seed 1 -size 100 -depth 10 -t 100 -v 30 -delta 50 -p 10 -xnode 0"
@Parameters(commandDescription = "Generate Solutions from a partial model via evolutionary algorithm")
public class EvolveCommand implements Command {
	private final CliProblemLoader loader;
	private final ModelGeneratorFactory generatorFactory;

	private String inputPath;
	private String outputPath = CliUtils.STANDARD_OUTPUT_PATH;
	private final List<String> scopes = new ArrayList<>();
	private int initialPopulationSize = 100;
	private int randomizationDepth = 10;
	private int time = 300;
	private int maxViolations = 10;
	private long randomSeed = 1;
	private double deltaSelectionRatio = 0.3;
	private double probabilityOfCrossover = 0.3;
	private boolean shouldCrossoverNodes = false;

	@Inject
	public EvolveCommand(CliProblemLoader loader, ModelGeneratorFactory generatorFactory,
						 CliProblemSerializer serializer) {
		this.loader = loader;
		this.generatorFactory = generatorFactory;
	}

	@Parameter(description = "input path", required = true)
	public void setInputPath(String inputPath) {
		this.inputPath = inputPath;
	}

	@Parameter(names = {"-output", "-o"}, description = "Output path")
	public void setOutputPath(String outputPath) {
		this.outputPath = outputPath;
	}

	@Parameter(names = {"-random-seed", "-seed"}, description = "Random seed")
	public void setRandomSeed(long randomSeed) {
		this.randomSeed = randomSeed;
	}

	@Parameter(names = {"-initial-population-size", "-size"}, description = "Initial population size")
	public void setInitialPopulationSize(int initialPopulationSize) {
		this.initialPopulationSize = initialPopulationSize;
	}

	@Parameter(names = {"-randomization-depth", "-depth"}, description = "Randomization depth")
	public void setRandomizationDepth(int randomizationDepth) {
		this.randomizationDepth = randomizationDepth;
	}


	@Parameter(names = {"-time", "-t"}, description = "Running time in seconds")
	public void setTime(int time) {
		this.time = time;
	}

	@Parameter(names = {"-max-violation", "-v"}, description = "Allowed violation count")
	public void setMaxViolations(int maxViolations) {
		this.maxViolations = maxViolations;
	}

	@Parameter(names = {"-delta-selection-percent", "-delta"}, description = "Delta selection percent of Crossover ")
	public void setDeltaSelectionRatio(long deltaSelectionPercent) {
		double ratio = deltaSelectionPercent / 100.0;
		if (ratio < 0 || ratio > 1) {
			throw new IllegalArgumentException("Ratio must be between 0 and 1");
		}
		this.deltaSelectionRatio = ratio;
	}

	@Parameter(names = {"-crossover-percent", "-p"}, description = "Probability of Crossover operation in percentage")
	public void setProbabilityOfCrossover(long percentOfCrossover) {
		double prob = percentOfCrossover / 100.0;
		if (prob < 0 || prob > 1) {
			throw new IllegalArgumentException("Probability must be between 0 and 1");
		}
		this.probabilityOfCrossover = prob;
	}

	@Parameter(names = {"-crossover-nodes", "-xnode"}, description = "Boolean value if we should crossover object " +
			"nodes as well")
	public void setShouldCrossoverNodes(long shouldCrossoverNodes) {
		if (shouldCrossoverNodes == 0) {
			this.shouldCrossoverNodes = false;
		}
		else if (shouldCrossoverNodes == 1) {
			this.shouldCrossoverNodes = true;
		}
		else {
			throw new IllegalArgumentException("Boolean must be 0 or 1");
		}
	}

	@Override
	public int run() throws IOException {
		if (CliUtils.isStandardStream(outputPath)) {
			throw new IllegalArgumentException("Must provide output path");
		}
		var problem = loader.loadProblem(inputPath, scopes, new ArrayList<>());

		var statements = problem.getStatements();

		var crossoverRelations = new ArrayList<Relation>();
		var minObjectiveRelations = new ArrayList<Relation>();
		var maxObjectiveRelations = new ArrayList<Relation>();
		var violationRelations = new ArrayList<Relation>();
		getAnnotatedRelations(statements, crossoverRelations, minObjectiveRelations, maxObjectiveRelations,
				violationRelations);

		generatorFactory.partialInterpretationBasedNeighborhoods(true);

		var originalTime = time;
		time = 1;
		for(int i = 1; i<= 10; i++) {
			runEvolutionaryAlgorithm(problem, crossoverRelations, minObjectiveRelations, maxObjectiveRelations,
					violationRelations, i);
		}
		time = originalTime;
		runEvolutionaryAlgorithm(problem, crossoverRelations, minObjectiveRelations, maxObjectiveRelations,
				violationRelations, 0);

		return RefineryCli.EXIT_SUCCESS;
	}

	private void runEvolutionaryAlgorithm(Problem problem,
										 ArrayList<Relation> crossoverRelations,
										 ArrayList<Relation> minObjectiveRelations,
										 ArrayList<Relation> maxObjectiveRelations,
										 ArrayList<Relation> violationRelations, int runNumber) {
		randomSeed = runNumber;

		try (var generator = generatorFactory.createGenerator(problem)) {
			generator.setRandomSeed(randomSeed);

			Model model = generator.getModel();
			var queryEngine = model.getAdapter(ModelQueryAdapter.class);
			var store = model.getStore();
			var problemTrace = generator.getProblemTrace();

			var crossoverSymbols = new ArrayList<PartialSymbol<?,?>>();
			var minObjectiveSymbols = new ArrayList<PartialSymbol<?,?>>();
			var maxObjectiveSymbols = new ArrayList<PartialSymbol<?,?>>();
			var violationSymbols = new ArrayList<PartialSymbol<?,?>>();

			for(var relation : crossoverRelations) {
				crossoverSymbols.add(problemTrace.getPartialRelation(relation));
			}
			for(var relation : minObjectiveRelations) {
				minObjectiveSymbols.add(problemTrace.getPartialRelation(relation));
			}
			for(var relation : maxObjectiveRelations) {
				maxObjectiveSymbols.add(problemTrace.getPartialRelation(relation));
			}
			for(var relation : violationRelations) {
				violationSymbols.add(problemTrace.getPartialRelation(relation));
			}

			var initialVersion = model.commit();
			queryEngine.flushChanges();

			RefineryProblem moeaProblem = new RefineryProblem(store, initialVersion, crossoverSymbols,
					minObjectiveSymbols, maxObjectiveSymbols, violationSymbols, randomizationDepth, true);

			moeaProblem.setDeltaSelectionRatio(deltaSelectionRatio);
			moeaProblem.setRandomSeed(randomSeed);
			moeaProblem.setShouldCrossoverNodes(shouldCrossoverNodes);
			moeaProblem.setProbabilityOfCrossover(probabilityOfCrossover);
			moeaProblem.setMaxViolations(maxViolations);

			NSGAII algorithm = new NSGAII(moeaProblem);

			var variation = new CompoundVariation(
					moeaProblem.getCrossover(),
					moeaProblem.getMutation()
			);
			algorithm.setVariation(variation);
			algorithm.setInitialPopulationSize(initialPopulationSize);

			algorithm.run(new MaxElapsedTime(Duration.ofSeconds(time)));


			//for measurements and visualization
			try {
				if(runNumber == 0) exportEvaluationRecords(moeaProblem, runNumber);
				else exportTimings(runNumber);
			}
			catch (Exception e) {
				System.out.println("[ERROR] "+e.getMessage());
			}
			finally {
				resetMetrics(moeaProblem);
			}

			if(runNumber == 0) {
				NondominatedPopulation result = algorithm.getResult();

				try {
					Path outDir = Path.of(outputPath);
					Files.createDirectories(outDir);
					Path file = outDir.resolve("result_population.csv");
					try (var ps = new java.io.PrintStream(
							Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING),
							false,
							StandardCharsets.UTF_8)) {
						result.display(TableFormat.CSV, ps);
					}
				} catch (Exception e) {
					System.out.println("[ERROR] writing result to file: " + e.getMessage());
					result.display(); // fallback to console
				}

				var visualizationStore = moeaProblem.getVisualizationStore();
//			System.out.println(visualizationStore.getStates().size());
				visualizationStore = new VisualizationStoreImpl();

				var visualizer = model.getAdapter(ModelVisualizerAdapter.class);
				if (visualizer != null) {
					//visualizer.visualize(moeaProblem.getVisualizationStore());
					for(int i = 0; i<result.size();i++) {
						var versionVariable = (VersionVariable)result.get(i).getVariable(0);
						visualizationStore.addState(versionVariable.getVersion(), versionVariable.getVersion().toString());
						//visualizationStore.addSolution(versionVariable.getVersion());
					}
					visualizer.visualize(visualizationStore);
				}

				for(int i = 0; i< result.size(); i++) {
					Solution sol = result.get(i);
					VersionVariable variable = (VersionVariable) sol.getVariable(0);
					Version version = variable.getVersion();
					Path outDir = Path.of(outputPath);
					try {
						Files.createDirectories(outDir);
					} catch (IOException ignored) {
					}

					Path file = outDir.resolve("version_" + i + ".txt");
					java.io.PrintStream originalOut = System.out;
					try (var os = Files.newOutputStream(file,
							java.nio.file.StandardOpenOption.CREATE,
							java.nio.file.StandardOpenOption.APPEND);
						 var ps = new java.io.PrintStream(os, false, StandardCharsets.UTF_8)) {
						System.setOut(ps);
						moeaProblem.displayVersion(version);
						ps.flush();
					} catch (Exception e) {
						originalOut.println("[ERROR] writing version file: " + e.getMessage());
					} finally {
						System.setOut(originalOut);
					}
				}
			}
		}
	}

	private void getAnnotatedRelations(EList<Statement> statements, ArrayList<Relation> crossoverRelations,
									   ArrayList<Relation> minOjectiveRelations, ArrayList<Relation> maxOjectiveRelations,
									   ArrayList<Relation> violationRelations) {
		for(var statement: statements) {
			if(statement instanceof AnnotatedElement element) {
				var container = element.getAnnotations();
				for(var annotation : container.getAnnotations()) {
					var annotationName = annotation.getDeclaration().getName();
					if(annotationName.equals("objective")) {
						var val = annotation.getArguments().getFirst();
						if (val.getValue() instanceof LogicConstantImpl logicConstantImpl) {
							if (logicConstantImpl.getLogicValue().equals(LogicValue.TRUE)) {
								minOjectiveRelations.add((Relation) statement);
							}
							else if (logicConstantImpl.getLogicValue().equals(LogicValue.FALSE)) {
								maxOjectiveRelations.add((Relation) statement);
							}
						}
					}
					else if(annotationName.equals("violation")) {
						violationRelations.add((Relation) statement);
					}
				}
			}
			if (statement instanceof ClassDeclaration classDeclaration) {
				for(var featureDeclaration : classDeclaration.getFeatureDeclarations()) {
					for(var featureAnnotation : featureDeclaration.getAnnotations().getAnnotations()) {
						if(featureAnnotation.getDeclaration().getName().equals("crossover")) {
							crossoverRelations.add(featureDeclaration);
						}
					}
				}
			}
		}
	}

	private void exportEvaluationRecords(RefineryProblem moeaProblem, int runNumber) throws IOException {
		RefineryProblem.EvaluationRecord[] records = moeaProblem.getEvaluationRecords();
		if (records == null || records.length == 0) {
			return;
		}

		Path outDir = Path.of(outputPath);
		Files.createDirectories(outDir);

		Path file = outDir.resolve("evaluation_run_" + runNumber + ".csv");
		try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			// header: elapsed_nanos, obj0, obj1, ..., constraint
			int objCount = records[0].objectives().length;
			StringBuilder header = new StringBuilder("elapsed_nanos");
			for (int i = 0; i < objCount; i++) {
				header.append(",objective_").append(i);
			}
			header.append(",constraint");
			writer.write(header.toString());
			writer.newLine();

			for (RefineryProblem.EvaluationRecord rec : records) {
				StringBuilder line = new StringBuilder(Long.toString(rec.elapsedSinceFirstEvalNanos()));
				double[] objs = rec.objectives();
				for (double d : objs) {
					line.append(',').append(d);
				}
				line.append(',').append(rec.constraintValue());
				writer.write(line.toString());
				writer.newLine();
			}
		}
	}

	private void exportTimings(int runNumber) throws IOException {
		if(runNumber < 2) return;
		Path outDir = Path.of(outputPath);
		Files.createDirectories(outDir);

		Path file = outDir.resolve("variation_metrics.csv");
		boolean append = runNumber != 2;

		try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
				java.nio.file.StandardOpenOption.CREATE,
				append ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
			if (!append) {
				writer.write("crossover_nanos,mutation_nanos,eval_measurement_nanos,total_cross,success_cross," +
						"total_mut,success_mut,total_eval,success_eval");
				writer.newLine();
			}
			long crossoverNanos = DeltaCrossover.getCrossoverTimeNanos();
			long mutationNanos = RuleBasedMutation.getMutationTimeNanos();
			long measurementNanos = RefineryProblem.getMeasurementTimeNanos();
			long totalCross = DeltaCrossover.getTotalCrossover();
			long successCross = DeltaCrossover.getSuccessfulCrossover();
			long totalMut = RuleBasedMutation.getTotalMutation();
			long successMut = RuleBasedMutation.getSuccessfulMutation();
			long totalEval = RefineryProblem.getTotalEvaluations();
			long successEval = totalEval - RefineryProblem.getFailedEvaluations();
			writer.write(crossoverNanos + "," + mutationNanos + "," + measurementNanos + "," +
					totalCross + "," + successCross + "," + totalMut + "," + successMut + "," + totalEval + "," + successEval);
			writer.newLine();
		}
	}

	private void resetMetrics(RefineryProblem moeaProblem) {
		// reset problem records and static timers
		try {
			moeaProblem.resetEvaluationRecords();
		} catch (Exception ignored) {
		}
		RefineryProblem.resetMeasurements();
		DeltaCrossover.resetMeasurements();
		RuleBasedMutation.resetMeasurements();
	}
}
