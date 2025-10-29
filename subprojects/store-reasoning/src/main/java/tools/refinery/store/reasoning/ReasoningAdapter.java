/*
 * SPDX-FileCopyrightText: 2021-2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.store.reasoning;

import org.jetbrains.annotations.Nullable;
import tools.refinery.logic.AbstractValue;
import tools.refinery.logic.term.intinterval.IntInterval;
import tools.refinery.logic.term.intinterval.IntIntervalDomain;
import tools.refinery.store.adapter.ModelAdapter;
import tools.refinery.store.reasoning.internal.ReasoningBuilderImpl;
import tools.refinery.store.reasoning.interpretation.AnyPartialInterpretation;
import tools.refinery.store.reasoning.interpretation.PartialInterpretation;
import tools.refinery.store.reasoning.literal.Concreteness;
import tools.refinery.store.reasoning.refinement.AnyPartialInterpretationRefiner;
import tools.refinery.store.reasoning.refinement.PartialInterpretationRefiner;
import tools.refinery.store.reasoning.representation.AnyPartialSymbol;
import tools.refinery.store.reasoning.representation.PartialFunction;
import tools.refinery.store.reasoning.representation.PartialRelation;
import tools.refinery.store.reasoning.representation.PartialSymbol;
import tools.refinery.store.reasoning.seed.ModelSeed;
import tools.refinery.store.tuple.Tuple1;

public interface ReasoningAdapter extends ModelAdapter {
	PartialRelation EXISTS_SYMBOL = PartialSymbol.of("exists", 1);
	PartialRelation EQUALS_SYMBOL = PartialSymbol.of("equals", 2);
	PartialFunction<IntInterval, Integer> COUNT_SYMBOL = PartialSymbol.of("count", 1, IntIntervalDomain.INSTANCE);

	@Override
	ReasoningStoreAdapter getStoreAdapter();

	default AnyPartialInterpretation getPartialInterpretation(Concreteness concreteness,
															  AnyPartialSymbol partialSymbol) {
		var typedPartialSymbol = (PartialSymbol<?, ?>) partialSymbol;
		return getPartialInterpretation(concreteness, typedPartialSymbol);
	}

	<A extends AbstractValue<A, C>, C> PartialInterpretation<A, C> getPartialInterpretation(
			Concreteness concreteness, PartialSymbol<A, C> partialSymbol);

	default AnyPartialInterpretationRefiner getRefiner(AnyPartialSymbol partialSymbol) {
		var typedPartialSymbol = (PartialSymbol<?, ?>) partialSymbol;
		return getRefiner(typedPartialSymbol);
	}

	<A extends AbstractValue<A, C>, C> PartialInterpretationRefiner<A, C> getRefiner(PartialSymbol<A, C> partialSymbol);
	@Nullable
	Tuple1 split(int parentMultiObject);

	@Nullable
	Tuple1 focus(int parentObject);

	boolean cleanup(int nodeToDelete);

	int getNodeCount();

	void resetInitialModel(ModelSeed modelSeed);

	static ReasoningBuilder builder() {
		return new ReasoningBuilderImpl();
	}
}
