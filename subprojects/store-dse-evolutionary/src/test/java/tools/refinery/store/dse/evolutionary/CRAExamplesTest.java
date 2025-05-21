/*
 * SPDX-FileCopyrightText: 2021-2023 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.store.dse.evolutionary;

import org.junit.jupiter.api.Test;
import org.moeaframework.algorithm.NSGAII;
import org.moeaframework.core.Solution;
import org.moeaframework.core.population.NondominatedPopulation;
import tools.refinery.logic.term.int_.IntTerms;
import tools.refinery.logic.term.real.RealTerms;
import tools.refinery.store.dse.modification.DanglingEdges;
import tools.refinery.store.dse.modification.ModificationAdapter;
import tools.refinery.store.dse.tests.DummyCriterion;
import tools.refinery.store.dse.tests.DummyRandomObjective;
import tools.refinery.store.dse.transition.DesignSpaceExplorationAdapter;
import tools.refinery.store.dse.transition.Rule;
import tools.refinery.store.dse.transition.objectives.Criteria;
import tools.refinery.store.dse.transition.objectives.Objectives;
import tools.refinery.store.map.Version;
import tools.refinery.store.model.ModelStore;
import tools.refinery.store.query.ModelQueryAdapter;
import tools.refinery.logic.dnf.Query;
import tools.refinery.logic.dnf.RelationalQuery;
import tools.refinery.logic.term.Variable;
import tools.refinery.store.query.interpreter.QueryInterpreterAdapter;
import tools.refinery.store.query.view.AnySymbolView;
import tools.refinery.store.query.view.KeyOnlyView;
import tools.refinery.store.representation.Symbol;
import tools.refinery.store.statecoding.StateCoderAdapter;
import tools.refinery.store.tuple.Tuple;
import tools.refinery.visualization.ModelVisualizerAdapter;
import tools.refinery.visualization.internal.FileFormat;

import java.util.List;

import static tools.refinery.store.dse.modification.actions.ModificationActionLiterals.create;
import static tools.refinery.store.dse.modification.actions.ModificationActionLiterals.delete;
import static tools.refinery.store.dse.transition.actions.ActionLiterals.add;
import static tools.refinery.store.dse.transition.actions.ActionLiterals.remove;
import static tools.refinery.logic.literal.Literals.not;

//Questions:
//Mi is most az Objective value?
//Most nem fordulhat elő, hogy ugyanazt többször felfedezi? DE
//State-ek hozzáadása a vizualizációhoz mikor lenne érdemes? - duplikációkat szűri? izomorfiavizsgálat?

class CRAExamplesTest {

	// Osztályok, adattagjai és azok közti kapcsolatok
	private static final Symbol<String> name = Symbol.of("Name", 1, String.class);
	private static final Symbol<Boolean> classElement = Symbol.of("ClassElement", 1);
	private static final Symbol<Boolean> attribute = Symbol.of("Attribute", 1);
	private static final Symbol<Boolean> method = Symbol.of("Method", 1);
	private static final Symbol<Boolean> encapsulates = Symbol.of("Encapsulates", 2);
	private static final Symbol<Boolean> dataDependency = Symbol.of("DataDependency", 2);
	private static final Symbol<Boolean> functionalDependency = Symbol.of("FunctionalDependency", 2);

	private static final AnySymbolView classElementView = new KeyOnlyView<>(classElement);
	private static final AnySymbolView attributeView = new KeyOnlyView<>(attribute);
	private static final AnySymbolView methodView = new KeyOnlyView<>(method);
	private static final AnySymbolView encapsulatesView = new KeyOnlyView<>(encapsulates);
	private static final AnySymbolView dataDependencyView = new KeyOnlyView<>(dataDependency);
	private static final AnySymbolView functionalDependencyView = new KeyOnlyView<>(functionalDependency);

	// feature = attribútum vagy metódus
	private static final RelationalQuery feature = Query.of("Feature", (builder, f) -> builder
			.clause(
					attributeView.call(f)
			)
			.clause(
					methodView.call(f)
			));

	private static final RelationalQuery unEncapsulatedFeature = Query.of("unEncapsulatedFeature",
			(builder, f) -> builder.clause(
					feature.call(f),
					not(encapsulatesView.call(Variable.of(), f))	// Variable.of() same as a parameter in the clause
			));

	// !!!
	// .action -> a lekérdezett elemekkel mit tegyen (pl. tranzitív baloldaliságot behúzni rögtön)
	// új kapcsolat hozzáadása
	private static final Rule assignFeatureRule = Rule.of("AssignFeature", (builder, f, c1) -> builder
			.clause(
					feature.call(f),
					classElementView.call(c1),
					not(encapsulatesView.call(Variable.of(), f))
			)
			.action(
					add(encapsulates, c1, f)
			));

	// létező példány törlése
	private static final Rule deleteEmptyClassRule = Rule.of("DeleteEmptyClass", (builder, c) -> builder
			.clause(
					classElementView.call(c),
					not(encapsulatesView.call(c, Variable.of()))
			)
			.action(
					remove(classElement, c),
					delete(c, DanglingEdges.IGNORE)
			));

	// új példány létrehozása
	private static final Rule createClassRule = Rule.of("CreateClass", (builder, f) -> builder
			.clause(
					feature.call(f),
					not(encapsulatesView.call(Variable.of(), f))
			)
			.action(newClass -> List.of(
					create(newClass),
					add(classElement, newClass),
					add(encapsulates, newClass, f)
			)));

	private static final Rule moveFeatureRule = Rule.of("MoveFeature", (builder, c1, c2, f) -> builder
			.clause(
					classElementView.call(c1),
					classElementView.call(c2),
					c1.notEquivalent(c2),
					feature.call(f),
					encapsulatesView.call(c1, f)
			)
			.action(
					remove(encapsulates, c1, f),
					add(encapsulates, c2, f)
			));

	@Test
	//@Disabled("This test is only for debugging purposes")
	void craTest() {
		var classMethod = Query.of("classMethod", (builder, c, m) -> builder
				.clause(
						encapsulatesView.call(c, m),
						methodView.call(m)
				));
		var methodCount = Query.of("methodCount", Integer.class, (builder, c, output) -> builder
				.clause(
						classElementView.call(c),
						output.assign(classMethod.count(c, Variable.of()))
				));
		var classAttribute = Query.of("classAttribute", (builder, c, a) -> builder
				.clause(
						encapsulatesView.call(c, a),
						attributeView.call(a)
				));
		var attributeCount = Query.of("attributeCount", Integer.class, (builder, c, output) -> builder
				.clause(
						classElementView.call(c),
						output.assign(classAttribute.count(c, Variable.of()))
				));
		var dma = Query.of("DMA", (builder, ci, cj, mi, aj) -> builder
				.clause(
						classMethod.call(ci, mi),
						classAttribute.call(cj, aj),
						dataDependencyView.call(mi, aj)
				));
		var mai = Query.of("MAI", Integer.class, (builder, ci, cj, output) -> builder
				.clause(
						classElementView.call(ci),
						classElementView.call(cj),
						output.assign(dma.count(ci, cj, Variable.of(), Variable.of()))
				));
		var dmm = Query.of("DMM", (builder, ci, cj, mi, mj) -> builder
				.clause(
						classMethod.call(ci, mi),
						classMethod.call(cj, mj),
						functionalDependencyView.call(mi, mj)
				));
		var mmi = Query.of("MMI", Integer.class, (builder, ci, cj, output) -> builder
				.clause(
						classElementView.call(ci),
						classElementView.call(cj),
						output.assign(dmm.count(ci, cj, Variable.of(), Variable.of()))
				));
		var classCohesion = Query.of("classCohesion", Double.class, (builder, c, output) -> builder
				.clause(Integer.class, mc -> List.of(
						classElementView.call(c),
						mc.assign(methodCount.leftJoin(0, c)),
						output.assign(RealTerms.add(
								RealTerms.div(
										RealTerms.asReal(mai.leftJoin(0, c, c)),
										RealTerms.asReal(IntTerms.mul(
												mc,
												attributeCount.leftJoin(0, c)
										))
								),
								RealTerms.div(
										RealTerms.asReal(mmi.leftJoin(0, c, c)),
										RealTerms.asReal(IntTerms.mul(
												mc,
												IntTerms.sub(mc, IntTerms.constant(1))
										))
								)
						))
				)));
		var classCoupling = Query.of("classCoupling", Double.class, (builder, ci, cj, output) -> builder
				.clause(Integer.class, mci -> List.of(
						classElementView.call(ci),
						classElementView.call(cj),
						mci.assign(methodCount.leftJoin(0, ci)),
						output.assign(RealTerms.add(
								RealTerms.div(
										RealTerms.asReal(mai.leftJoin(0, ci, cj)),
										RealTerms.asReal(IntTerms.mul(
												mci,
												attributeCount.leftJoin(0, cj)
										))
								),
								RealTerms.div(
										RealTerms.asReal(mmi.leftJoin(0, ci, cj)),
										RealTerms.asReal(IntTerms.mul(
												mci,
												IntTerms.sub(
														methodCount.leftJoin(0, cj),
														IntTerms.constant(1)
												)
										))
								)
						))
				)));
		var objective = Query.of("objective", Double.class, (builder, output) -> builder
				.clause(
						output.assign(RealTerms.sub(
								classCoupling.aggregate(RealTerms.REAL_SUM, Variable.of(), Variable.of()),
								classCohesion.aggregate(RealTerms.REAL_SUM, Variable.of())
						))
				));

		var store = ModelStore.builder()
				.symbols(classElement, encapsulates, attribute, method, dataDependency, functionalDependency, name)
				.with(QueryInterpreterAdapter.builder())
				// ModelVisualizerAdapter -> elmenti a generált fájlt, állapotteret, állapotokat
				.with(ModelVisualizerAdapter.builder()
						.withOutputPath("test_output")
						.withFormat(FileFormat.DOT)
						.withFormat(FileFormat.SVG)
						.saveStates()
						.saveDesignSpace())
				.with(StateCoderAdapter.builder())
				.with(ModificationAdapter.builder())
				.with(DesignSpaceExplorationAdapter.builder()
						// Ezek itt a decesion rule-ok
						.transformations(assignFeatureRule, deleteEmptyClassRule, createClassRule, moveFeatureRule)
						// Célérték!
						.objectives(Objectives.sum(
								Objectives.value(objective),
								Objectives.count(unEncapsulatedFeature)
						))
						.accept(Criteria.whenNoMatch(unEncapsulatedFeature)))
				.build();

		var model = store.createEmptyModel();
		var queryEngine = model.getAdapter(ModelQueryAdapter.class);

		var nameInterpretation = model.getInterpretation(name);
		var methodInterpretation = model.getInterpretation(method);
		var attributeInterpretation = model.getInterpretation(attribute);
		var dataDependencyInterpretation = model.getInterpretation(dataDependency);
		var functionalDependencyInterpretation = model.getInterpretation(functionalDependency);

		//!!
		var modificationAdapter = model.getAdapter(ModificationAdapter.class);

		// Változók és azonosítóik létrehozása
		var method1 = modificationAdapter.createObject();
		var method1Id = method1.get(0);
		var method2 = modificationAdapter.createObject();
		var method2Id = method2.get(0);
		var method3 = modificationAdapter.createObject();
		var method3Id = method3.get(0);
		var method4 = modificationAdapter.createObject();
		var method4Id = method4.get(0);
		var attribute1 = modificationAdapter.createObject();
		var attribute1Id = attribute1.get(0);
		var attribute2 = modificationAdapter.createObject();
		var attribute2Id = attribute2.get(0);
		var attribute3 = modificationAdapter.createObject();
		var attribute3Id = attribute3.get(0);
		var attribute4 = modificationAdapter.createObject();
		var attribute4Id = attribute4.get(0);
		var attribute5 = modificationAdapter.createObject();
		var attribute5Id = attribute5.get(0);

		nameInterpretation.put(method1, "M1");
		nameInterpretation.put(method2, "M2");
		nameInterpretation.put(method3, "M3");
		nameInterpretation.put(method4, "M4");
		nameInterpretation.put(attribute1, "A1");
		nameInterpretation.put(attribute2, "A2");
		nameInterpretation.put(attribute3, "A3");
		nameInterpretation.put(attribute4, "A4");
		nameInterpretation.put(attribute5, "A5");

		methodInterpretation.put(method1, true);
		methodInterpretation.put(method2, true);
		methodInterpretation.put(method3, true);
		methodInterpretation.put(method4, true);
		attributeInterpretation.put(attribute1, true);
		attributeInterpretation.put(attribute2, true);
		attributeInterpretation.put(attribute3, true);
		attributeInterpretation.put(attribute4, true);
		attributeInterpretation.put(attribute5, true);

		dataDependencyInterpretation.put(Tuple.of(method1Id, attribute1Id), true);
		dataDependencyInterpretation.put(Tuple.of(method1Id, attribute3Id), true);
		dataDependencyInterpretation.put(Tuple.of(method2Id, attribute2Id), true);
		dataDependencyInterpretation.put(Tuple.of(method3Id, attribute3Id), true);
		dataDependencyInterpretation.put(Tuple.of(method3Id, attribute4Id), true);
		dataDependencyInterpretation.put(Tuple.of(method4Id, attribute3Id), true);
		dataDependencyInterpretation.put(Tuple.of(method4Id, attribute5Id), true);

		functionalDependencyInterpretation.put(Tuple.of(method1Id, attribute3Id), true);
		functionalDependencyInterpretation.put(Tuple.of(method1Id, attribute4Id), true);
		functionalDependencyInterpretation.put(Tuple.of(method2Id, attribute1Id), true);
		functionalDependencyInterpretation.put(Tuple.of(method3Id, attribute1Id), true);
		functionalDependencyInterpretation.put(Tuple.of(method3Id, attribute4Id), true);
		functionalDependencyInterpretation.put(Tuple.of(method4Id, attribute2Id), true);

		var initialVersion = model.commit();
		queryEngine.flushChanges();

		RefineryProblem problem = new RefineryProblem(store, initialVersion, 5);
		NSGAII algorithm = new NSGAII(problem);

		algorithm.setVariation(problem.getMutation());
		algorithm.setInitialPopulationSize(10);

		algorithm.run(10000);
		NondominatedPopulation result = algorithm.getResult();
		result.display();
		for(int i = 0; i< result.size(); i++) {
			Solution sol = result.get(i);
			VersionVariable variable = (VersionVariable) sol.getVariable(0);
			Version version = variable.getVersion();
			System.out.println(version.toString());
			model.restore(version);
			var encapsulatesInterpretation = model.getInterpretation(encapsulates);
			var cursor = encapsulatesInterpretation.getAll();
			while (cursor.move()) {
				System.out.println(cursor.getKey());
			}
		}


		// model.getAdapter(ModelVisualizerAdapter.class).visualize(problem.getVisualizationStore());

		// !
		// Ez visszavezet a getRandomAndMarkAsVisited-hez
		/*var bestFirst = new BestFirstStoreManager(store, 50);
		bestFirst.startExploration(initialVersion);
		var resultStore = bestFirst.getSolutionStore();
		System.out.println("states size: " + resultStore.getSolutions().size());
		//model.getAdapter(ModelVisualizerAdapter.class).visualize(bestFirst.getVisualizationStore());*/
	}
}
