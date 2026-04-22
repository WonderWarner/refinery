package tools.refinery.store.dse.evolutionary.objective;

import tools.refinery.language.model.problem.Relation;
import tools.refinery.language.semantics.ProblemTrace;
import tools.refinery.store.reasoning.interpretation.PartialInterpretation;
import tools.refinery.store.model.Model;
import tools.refinery.store.reasoning.representation.PartialSymbol;

import java.util.ArrayList;

public abstract class ObjectiveEvaluator {

	public ObjectiveEvalType type;

	/**
	 * Evaluate the given partial interpretation in the context of the provided model and
	 * return a double objective value.
	 */
	public abstract double evaluate(PartialInterpretation<?,?> interpretation, Model model);

	public static ObjectiveEvaluator provideEvaluator(ObjectiveEvalType objEvalType) {
		if (objEvalType == ObjectiveEvalType.FUNCTION) {
			return new tools.refinery.store.dse.evolutionary.objective.FunctionObjectiveEvaluator();
		} else {
			return new tools.refinery.store.dse.evolutionary.objective.PredicateObjectiveEvaluator();
		}
	}

	public abstract void AddPartialSymbolToSymbols(ArrayList<PartialSymbol<?, ?>> symbols,
	                                                        ProblemTrace problemTrace, Relation relation);
}
