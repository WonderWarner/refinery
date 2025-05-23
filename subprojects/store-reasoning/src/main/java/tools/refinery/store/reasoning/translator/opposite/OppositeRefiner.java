/*
 * SPDX-FileCopyrightText: 2023-2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.store.reasoning.translator.opposite;

import tools.refinery.logic.AbstractValue;
import tools.refinery.store.reasoning.ReasoningAdapter;
import tools.refinery.store.reasoning.refinement.AbstractPartialInterpretationRefiner;
import tools.refinery.store.reasoning.refinement.PartialInterpretationRefiner;
import tools.refinery.store.reasoning.refinement.RefinementUtils;
import tools.refinery.store.reasoning.representation.PartialSymbol;
import tools.refinery.store.reasoning.seed.ModelSeed;
import tools.refinery.store.tuple.Tuple;

public class OppositeRefiner<A extends AbstractValue<A, C>, C> extends AbstractPartialInterpretationRefiner<A, C> {
	private final PartialInterpretationRefiner<A, C> opposite;

	protected OppositeRefiner(ReasoningAdapter adapter, PartialSymbol<A, C> partialSymbol,
							  PartialSymbol<A, C> oppositeSymbol) {
		super(adapter, partialSymbol);
		opposite = adapter.getRefiner(oppositeSymbol);
	}

	@Override
	public boolean merge(Tuple key, A value) {
		var oppositeKey = OppositeUtils.flip(key);
		return opposite.merge(oppositeKey, value);
	}

	@Override
	public void afterInitialize(ModelSeed modelSeed) {
		RefinementUtils.refineFromSeed(this, modelSeed);
	}

	public static <A1 extends AbstractValue<A1, C1>, C1> Factory<A1, C1> of(PartialSymbol<A1, C1> oppositeSymbol) {
		return (adapter, partialSymbol) -> new OppositeRefiner<>(adapter, partialSymbol, oppositeSymbol);
	}
}
