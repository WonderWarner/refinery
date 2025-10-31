package tools.refinery.generator.cli.commands;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.inject.Inject;
import org.eclipse.emf.common.util.EList;
import org.moeaframework.algorithm.NSGAII;
import org.moeaframework.core.Solution;
import org.moeaframework.core.operator.CompoundVariation;
import org.moeaframework.core.population.NondominatedPopulation;
import tools.refinery.generator.ModelGeneratorFactory;
import tools.refinery.generator.cli.RefineryCli;
import tools.refinery.generator.cli.utils.CliProblemLoader;
import tools.refinery.generator.cli.utils.CliProblemSerializer;
import tools.refinery.generator.cli.utils.CliUtils;
import tools.refinery.language.model.problem.*;
import tools.refinery.store.dse.evolutionary.RefineryProblem;
import tools.refinery.store.dse.evolutionary.VersionVariable;
import tools.refinery.store.map.Version;
import tools.refinery.store.model.Model;
import tools.refinery.store.query.ModelQueryAdapter;
import tools.refinery.store.reasoning.representation.PartialFunction;
import tools.refinery.store.reasoning.representation.PartialSymbol;
import tools.refinery.visualization.ModelVisualizerAdapter;

import java.io.IOException;
import java.util.*;

@Parameters(commandDescription = "Generate Solutions from a partial model via evolutionary algorithm")
public class EvolveCommand implements Command {
	private final CliProblemLoader loader;
	private final ModelGeneratorFactory generatorFactory;

	private String inputPath;
	private String outputPath = CliUtils.STANDARD_OUTPUT_PATH;
	private final List<String> scopes = new ArrayList<>();
	private long initialPopulationSize = 100;
	private long randomizationDepth = 10;
	private long numberOfGenerations = 100;
	private long randomSeed = 1;
	private double deltaSelectionRatio = 0.3;

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

	// TODO continue also use attributes
	@Parameter(names = {"-initial-population-size", "-size"}, description = "Initial population size")
	public void setInitialPopulationSize(long initialPopulationSize) {
		this.initialPopulationSize = initialPopulationSize;
	}

	@Parameter(names = {"-randomization-depth", "-depth"}, description = "Randomization depth")
	public void setRandomizationDepth(long randomizationDepth) {
		this.randomizationDepth = randomizationDepth;
	}


	@Parameter(names = {"-generations", "-g"}, description = "Number of generations")
	public void setNumberOfGenerations(long numberOfGenerations) {
		this.numberOfGenerations = numberOfGenerations;
	}

	@Parameter(names = {"-delta-selection-percent", "-delta"}, description = "Delta selection percent of Crossover " +
			"operation")
	public void setDeltaSelectionRatio(long deltaSelectionPercent) {
		double ratio = deltaSelectionPercent / 100.0;
		if (ratio < 0 || ratio > 1) {
			throw new IllegalArgumentException("Ratio must be between 0 and 1");
		}
		this.deltaSelectionRatio = ratio;
	}

	@Override
	public int run() throws IOException {
		if (CliUtils.isStandardStream(outputPath)) {
			throw new IllegalArgumentException("Must provide output path");
		}
		var problem = loader.loadProblem(inputPath, scopes, new ArrayList<>());
		try (var generator = generatorFactory.createGenerator(problem)) {
			generator.generate();
			System.exit(0);
		}

		var statements = problem.getStatements();

		var crossoverRelations = new ArrayList<Relation>();
		var objectiveRelations = new ArrayList<Relation>();
		Relation violationCountRelation = getAnnotatedRelations(statements, crossoverRelations, objectiveRelations);

		generatorFactory.partialInterpretationBasedNeighborhoods(true);
		try (var generator = generatorFactory.createGenerator(problem)) {
			generator.setRandomSeed(randomSeed);
			generator.setMaxNumberOfSolutions(100);

			Model model = generator.getModel();
			var queryEngine = model.getAdapter(ModelQueryAdapter.class);
			var store = model.getStore();
			var problemTrace = generator.getProblemTrace();

			var crossoverSymbols = new ArrayList<PartialSymbol<?,?>>();
			var objectiveFunctions = new ArrayList<PartialFunction<?,?>>();
			PartialFunction<?,?> violationFunction = null;
			for(var relation : crossoverRelations) {
				crossoverSymbols.add(problemTrace.getPartialRelation(relation));
			}
			for(var function : objectiveRelations) {
				objectiveFunctions.add((PartialFunction<?,?>) problemTrace.getPartialFunction(function));
			}
			if(violationCountRelation != null) {
				violationFunction = (PartialFunction<?,?>) problemTrace.getPartialFunction(violationCountRelation);
			}

			var initialVersion = model.commit();
			queryEngine.flushChanges();

			RefineryProblem moeaProblem = new RefineryProblem(store, initialVersion, crossoverSymbols,
					objectiveFunctions, violationFunction, 10, true);
			var visualizer = model.getAdapter(ModelVisualizerAdapter.class);
			if (visualizer != null) {
				visualizer.visualize(moeaProblem.getVisualizationStore());
			}
			moeaProblem.setDeltaSelectionRatio(deltaSelectionRatio);
			moeaProblem.setRandomSeed(randomSeed);

			NSGAII algorithm = new NSGAII(moeaProblem);
			var variation = new CompoundVariation(
					moeaProblem.getCrossover(),
					moeaProblem.getMutation()
			);
			algorithm.setVariation(variation);
			algorithm.setInitialPopulationSize(10);

			algorithm.run(100);

			NondominatedPopulation result = algorithm.getResult();
			result.display();

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

	private Relation getAnnotatedRelations(EList<Statement> statements, ArrayList<Relation> crossoverRelations,
									   ArrayList<Relation> objectiveRelations) {
		Relation violationCountRelation = null;
		for(var statement: statements) {
			if(statement instanceof AnnotatedElement element) {
				var container = element.getAnnotations();
				for(var annotation : container.getAnnotations()) {
					var annotationName = annotation.getDeclaration().getName();
					if(annotationName.equals("crossover")) {
						//TODO set classCrossover to true if needed
					}
					else if(annotationName.equals("objective")) {
						objectiveRelations.add((Relation) statement);
					}
					else if(annotationName.equals("violationCount")) {
						violationCountRelation = (Relation) statement;
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
		return violationCountRelation;
	}
}
