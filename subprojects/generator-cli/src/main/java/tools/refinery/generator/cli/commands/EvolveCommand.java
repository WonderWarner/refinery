package tools.refinery.generator.cli.commands;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.inject.Inject;
import org.moeaframework.algorithm.NSGAII;
import org.moeaframework.core.Solution;
import org.moeaframework.core.operator.CompoundVariation;
import org.moeaframework.core.population.NondominatedPopulation;
import tools.refinery.generator.ModelGeneratorFactory;
import tools.refinery.generator.cli.RefineryCli;
import tools.refinery.generator.cli.utils.CliProblemLoader;
import tools.refinery.generator.cli.utils.CliProblemSerializer;
import tools.refinery.generator.cli.utils.CliUtils;
import tools.refinery.store.dse.evolutionary.RefineryProblem;
import tools.refinery.store.dse.evolutionary.VersionVariable;
import tools.refinery.store.map.Version;
import tools.refinery.store.model.Model;
import tools.refinery.store.query.ModelQueryAdapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Parameters(commandDescription = "Generate Solutions from a partial model via evolutionary algorithm")
public class EvolveCommand implements Command {
	private final CliProblemLoader loader;
	private final ModelGeneratorFactory generatorFactory;

	private String inputPath;
	private String outputPath = CliUtils.STANDARD_OUTPUT_PATH;
	private final List<String> scopes = new ArrayList<>();
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

	@Parameter(names = {"-random-seed", "-r"}, description = "Random seed")
	public void setRandomSeed(long randomSeed) {
		this.randomSeed = randomSeed;
	}

	@Parameter(names = {"-delta-selection-ratio", "-d"}, description = "Delta selection ratio of Crossover operation")
	public void setDeltaSelectionRatio(double deltaSelectionRatio) {
		if (deltaSelectionRatio < 0 || deltaSelectionRatio > 1) {
			throw new IllegalArgumentException("Ratio must be between 0 and 1");
		}
		this.deltaSelectionRatio = deltaSelectionRatio;
	}

	@Override
	public int run() throws IOException {
		if (CliUtils.isStandardStream(outputPath)) {
			throw new IllegalArgumentException("Must provide output path");
		}
		var problem = loader.loadProblem(inputPath, scopes, new ArrayList<>());
		generatorFactory.partialInterpretationBasedNeighborhoods(true);
		try (var generator = generatorFactory.createGenerator(problem)) {
			generator.setRandomSeed(randomSeed);
			generator.setMaxNumberOfSolutions(10000);

			Model model = generator.getModel();
			var queryEngine = model.getAdapter(ModelQueryAdapter.class);
			var store = model.getStore();
			var problemTrace = generator.getProblemTrace();

			var initialVersion = model.commit();
			queryEngine.flushChanges();

			RefineryProblem moeaProblem = new RefineryProblem(store, problemTrace, initialVersion, 20);
			moeaProblem.setDeltaSelectionRatio(deltaSelectionRatio);
			moeaProblem.setRandomSeed(randomSeed);

			NSGAII algorithm = new NSGAII(moeaProblem);
			var variation = new CompoundVariation(
					moeaProblem.getCrossover(),
					moeaProblem.getMutation()
			);

			algorithm.setVariation(variation);
			algorithm.setInitialPopulationSize(100);

			algorithm.run(10000);
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
}
