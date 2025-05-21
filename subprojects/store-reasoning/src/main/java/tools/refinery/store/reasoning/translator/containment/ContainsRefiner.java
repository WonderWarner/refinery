/*
 * SPDX-FileCopyrightText: 2023-2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.store.reasoning.translator.containment;

import tools.refinery.logic.term.truthvalue.TruthValue;
import tools.refinery.store.model.Interpretation;
import tools.refinery.store.reasoning.ReasoningAdapter;
import tools.refinery.store.reasoning.refinement.AbstractPartialInterpretationRefiner;
import tools.refinery.store.reasoning.refinement.PartialInterpretationRefiner;
import tools.refinery.store.reasoning.refinement.RefinementUtils;
import tools.refinery.store.reasoning.refinement.TypeConstraintRefiner;
import tools.refinery.store.reasoning.representation.PartialSymbol;
import tools.refinery.store.reasoning.seed.ModelSeed;
import tools.refinery.store.representation.Symbol;
import tools.refinery.store.tuple.Tuple;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

class ContainsRefiner extends AbstractPartialInterpretationRefiner.ConcretizationAware<TruthValue, Boolean> {
	private static final Map<TruthValue, InferredContainment> EMPTY_VALUES;

	static {
		var values = TruthValue.values();
		EMPTY_VALUES = LinkedHashMap.newLinkedHashMap(values.length);
		for (var value : values) {
			EMPTY_VALUES.put(value, new InferredContainment(value, Set.of(), Set.of()));
		}
	}

	private final Interpretation<InferredContainment> interpretation;
	private TypeConstraintRefiner typeConstraintRefiner;

	private ContainsRefiner(ReasoningAdapter adapter, PartialSymbol<TruthValue, Boolean> partialSymbol,
							Symbol<InferredContainment> containsStorage) {
		super(adapter, partialSymbol);
		interpretation = adapter.getModel().getInterpretation(containsStorage);
	}

	@Override
	public void afterCreate() {
		var adapter = getAdapter();
		typeConstraintRefiner = new TypeConstraintRefiner(adapter, ContainmentHierarchyTranslator.CONTAINER_SYMBOL,
				ContainmentHierarchyTranslator.CONTAINED_SYMBOL, Set.of(), Set.of());
	}

	@Override
	public boolean merge(Tuple key, TruthValue value) {
		var oldValue = interpretation.get(key);
		var newValue = mergeLink(oldValue, value);
		if (oldValue != newValue) {
			interpretation.put(key, newValue);
		}
		if (value.must()) {
			return typeConstraintRefiner.merge(key);
		}
		return true;
	}

	@Override
	public void afterInitialize(ModelSeed modelSeed) {
		RefinementUtils.refineFromSeed(this, modelSeed);
	}

	public InferredContainment mergeLink(InferredContainment oldValue, TruthValue toMerge) {
		var oldContains = oldValue.contains();
		TruthValue newContains;
		if (!oldContains.must() && toMerge == TruthValue.TRUE && concretizationInProgress()) {
			newContains = TruthValue.ERROR;
		} else {
			newContains = oldContains.meet(toMerge);
		}
		if (newContains.equals(oldContains)) {
			return oldValue;
		}
		var mustLinks = oldValue.mustLinks();
		var forbiddenLinks = oldValue.forbiddenLinks();
		if (mustLinks.isEmpty() && forbiddenLinks.isEmpty()) {
			return EMPTY_VALUES.get(newContains);
		}
		return new InferredContainment(newContains, mustLinks, forbiddenLinks);
	}

	public static PartialInterpretationRefiner.Factory<TruthValue, Boolean> of(Symbol<InferredContainment> symbol) {
		return (adapter, partialSymbol) -> new ContainsRefiner(adapter, partialSymbol, symbol);
	}
}
