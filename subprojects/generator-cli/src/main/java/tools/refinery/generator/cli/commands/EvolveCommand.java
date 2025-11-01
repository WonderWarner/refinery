package tools.refinery.generator.cli.commands;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.inject.Inject;
import org.eclipse.emf.common.util.EList;
import org.moeaframework.algorithm.NSGAII;
import org.moeaframework.core.Solution;
import org.moeaframework.core.operator.CompoundVariation;
import org.moeaframework.core.population.NondominatedPopulation;
import org.moeaframework.core.population.NondominatedSortingPopulation;
import org.moeaframework.core.termination.MaxElapsedTime;
import org.moeaframework.core.termination.TerminationCondition;
import tools.refinery.generator.ModelGeneratorFactory;
import tools.refinery.generator.cli.RefineryCli;
import tools.refinery.generator.cli.utils.CliProblemLoader;
import tools.refinery.generator.cli.utils.CliProblemSerializer;
import tools.refinery.generator.cli.utils.CliUtils;
import tools.refinery.language.model.problem.*;
import tools.refinery.language.model.problem.impl.LogicConstantImpl;
import tools.refinery.logic.term.truthvalue.TruthValue;
import tools.refinery.store.dse.evolutionary.RefineryProblem;
import tools.refinery.store.dse.evolutionary.VersionVariable;
import tools.refinery.store.map.Version;
import tools.refinery.store.model.Model;
import tools.refinery.store.query.ModelQueryAdapter;
import tools.refinery.store.reasoning.representation.PartialSymbol;
import tools.refinery.visualization.ModelVisualizerAdapter;
import tools.refinery.visualization.statespace.internal.VisualizationStoreImpl;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
	private boolean shouldCrossoverTypes = false;

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

	@Parameter(names = {"-crossover-types", "-xtype"}, description = "Boolean value if we should crossover class" +
			" types as well")
	public void setShouldCrossoverTypes(long shouldCrossoverTypes) {
		if (shouldCrossoverTypes == 0) {
			this.shouldCrossoverTypes = false;
		}
		else if (shouldCrossoverTypes == 1) {
			this.shouldCrossoverTypes = true;
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
			moeaProblem.setShouldCrossoverTypes(shouldCrossoverTypes);
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

			NondominatedPopulation result = algorithm.getResult();
			System.out.println();
			NondominatedSortingPopulation population = algorithm.getPopulation();
			population.display();
			System.out.println();
			result.display();

			var visualizationStore = moeaProblem.getVisualizationStore();
			System.out.println(visualizationStore.getStates().size());
			visualizationStore = new VisualizationStoreImpl();

			var visualizer = model.getAdapter(ModelVisualizerAdapter.class);
			if (visualizer != null) {
				//visualizer.visualize(moeaProblem.getVisualizationStore());
				for(int i = 0; i<population.size();i++) {
					var versionVariable = (VersionVariable)population.get(i).getVariable(0);
					visualizationStore.addState(versionVariable.getVersion(), "");
					visualizationStore.addSolution(versionVariable.getVersion());
				}
				visualizer.visualize(visualizationStore);
			}

			for(int i = 0; i< result.size(); i++) {
				Solution sol = result.get(i);
				VersionVariable variable = (VersionVariable) sol.getVariable(0);
				Version version = variable.getVersion();
				System.out.println(version.toString());
				//for measurements and visualization
			}
		}
		return RefineryCli.EXIT_SUCCESS;
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
}
