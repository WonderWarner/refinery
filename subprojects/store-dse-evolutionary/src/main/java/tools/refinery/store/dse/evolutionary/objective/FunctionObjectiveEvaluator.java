package tools.refinery.store.dse.evolutionary.objective;

import org.moeaframework.core.TypeMismatchException;
import tools.refinery.language.model.problem.Relation;
import tools.refinery.language.semantics.ProblemTrace;
import tools.refinery.logic.AbstractValue;
import tools.refinery.store.model.Model;
import tools.refinery.store.reasoning.interpretation.PartialInterpretation;
import tools.refinery.store.reasoning.representation.PartialSymbol;

import java.util.ArrayList;


/**
 * Evaluator that parses integer values produced by a function interpretation.
 */
public class FunctionObjectiveEvaluator extends ObjectiveEvaluator {

	public FunctionObjectiveEvaluator() {
		type = ObjectiveEvalType.FUNCTION;
	}

	@Override
	public double evaluate(PartialInterpretation<?, ?> interpretation, Model model) {
		var cursor = interpretation.getAll();
		double sum = 0;
		boolean any = false;

		while (cursor.move()) {
			any = true;
			Object value = cursor.getValue();
			Double parsed = extractNumber(value);
			if (parsed == null) {
				return Integer.MAX_VALUE;
			}
			sum += parsed;
		}
		return any ? sum : 0;
	}

	private Double extractNumber(Object value) {
		switch (value) {
		case null -> {
			return null;
		}
		case AbstractValue<?, ?> av -> {
			Object concrete = av.getConcrete();
			if (concrete != null) {
				return extractNumber(concrete);
			}

			Object arbitrary = av.getArbitrary();
			if (arbitrary != null) {
				return extractNumber(arbitrary);
			}

			return null;
		}
		case Number n -> {
			return n.doubleValue();
		}
		case String s -> {
			try {
				return Double.parseDouble(s);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		default -> {
		}
		}

		return null;
	}

	@Override
	public void AddPartialSymbolToSymbols(ArrayList<PartialSymbol<?, ?>> symbols, ProblemTrace problemTrace, Relation relation) {
		symbols.add((PartialSymbol<?, ?>) problemTrace.getPartialFunction(relation));
	}
}
