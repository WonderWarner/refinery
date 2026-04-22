package tools.refinery.store.dse.evolutionary.objective;

import tools.refinery.language.model.problem.Relation;
import tools.refinery.language.semantics.ProblemTrace;
import tools.refinery.store.model.Model;
import tools.refinery.store.reasoning.interpretation.PartialInterpretation;
import tools.refinery.store.reasoning.representation.PartialSymbol;

import java.util.ArrayList;

public class PredicateObjectiveEvaluator extends ObjectiveEvaluator {

	public PredicateObjectiveEvaluator() {
		type = ObjectiveEvalType.PREDICATE;
	}

	@Override
	public double evaluate(PartialInterpretation<?, ?> interpretation, Model model) {
		var cursor = interpretation.getAll();
		int count = 0;
		while (cursor.move()) {
			count++;
		}
		return count;
	}

	@Override
	public void AddPartialSymbolToSymbols(ArrayList<PartialSymbol<?, ?>> symbols, ProblemTrace problemTrace, Relation relation) {
			symbols.add(problemTrace.getPartialRelation(relation));
	}
}
