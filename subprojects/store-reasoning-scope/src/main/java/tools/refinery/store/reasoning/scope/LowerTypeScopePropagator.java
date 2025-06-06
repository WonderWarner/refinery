/*
 * SPDX-FileCopyrightText: 2023 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.store.reasoning.scope;

import tools.refinery.logic.dnf.AnyQuery;
import tools.refinery.logic.dnf.FunctionalQuery;
import tools.refinery.logic.dnf.Query;
import tools.refinery.logic.dnf.RelationalQuery;
import tools.refinery.logic.term.Variable;
import tools.refinery.logic.term.uppercardinality.UpperCardinality;
import tools.refinery.logic.term.uppercardinality.UpperCardinalityTerms;
import tools.refinery.store.dse.transition.DesignSpaceExplorationBuilder;
import tools.refinery.store.dse.transition.objectives.Criteria;
import tools.refinery.store.dse.transition.objectives.Criterion;
import tools.refinery.store.dse.transition.objectives.Objectives;
import tools.refinery.store.model.ModelStoreBuilder;
import tools.refinery.store.reasoning.ReasoningBuilder;
import tools.refinery.store.reasoning.literal.CountCandidateLowerBoundLiteral;
import tools.refinery.store.reasoning.literal.CountUpperBoundLiteral;
import tools.refinery.store.reasoning.representation.PartialRelation;

import java.util.Collection;
import java.util.List;

import static tools.refinery.logic.literal.Literals.check;
import static tools.refinery.logic.term.int_.IntTerms.*;
import static tools.refinery.store.reasoning.literal.PartialLiterals.may;
import static tools.refinery.store.reasoning.translator.multiobject.MultiObjectTranslator.MULTI_VIEW;

class LowerTypeScopePropagator extends TypeScopePropagator {
	private final int lowerBound;

	private LowerTypeScopePropagator(BoundScopePropagator adapter, int lowerBound, RelationalQuery allQuery,
									 RelationalQuery multiQuery, Criterion acceptCriterion, PartialRelation type) {
		super(adapter, allQuery, multiQuery, acceptCriterion, type);
		this.lowerBound = lowerBound;
	}

	@Override
	protected void doUpdateBounds() {
		constraint.setLb((lowerBound - getSingleCount()));
	}

	@Override
	public String getName() {
		return "lower type scope bound for '%s' (>= %d)".formatted(getType().name(), lowerBound);
	}

	static class Factory extends TypeScopePropagator.Factory {
		private final PartialRelation type;
		private final int lowerBound;
		private final RelationalQuery allMay;
		private final RelationalQuery multiMay;
		private final FunctionalQuery<Integer> requiredObjects;
		private final RelationalQuery tooFewObjects;
		private final Criterion acceptCriterion;

		public Factory(PartialRelation type, int lowerBound) {
			this.type = type;
			this.lowerBound = lowerBound;
			allMay = Query.of(type.name() + "#may", (builder, instance) -> builder.clause(
					may(type.call(instance))
			));
			multiMay = Query.of(type.name() + "#multiMay", (builder, instance) -> builder.clause(
					may(type.call(instance)),
					MULTI_VIEW.call(instance)
			));
			requiredObjects = Query.of(type.name() + "#required", Integer.class, (builder, output) -> builder
					.clause(Integer.class, candidateLowerBound -> List.of(
							new CountCandidateLowerBoundLiteral(candidateLowerBound, type, List.of(Variable.of())),
							output.assign(sub(constant(lowerBound), candidateLowerBound)),
							check(greater(output, constant(0)))
					)));
			tooFewObjects = Query.of(type.name() + "#tooFew", builder -> builder
					.clause(UpperCardinality.class, upperBound -> List.of(
							new CountUpperBoundLiteral(upperBound, type, List.of(Variable.of())),
							check(UpperCardinalityTerms.less(upperBound,
									UpperCardinalityTerms.constant(UpperCardinality.of(lowerBound))))
					)));
			acceptCriterion = Criteria.whenNoMatch(requiredObjects);
		}

		@Override
		public TypeScopePropagator createPropagator(BoundScopePropagator adapter) {
			return new LowerTypeScopePropagator(adapter, lowerBound, allMay, multiMay, acceptCriterion, type);
		}

		@Override
		protected Collection<AnyQuery> getQueries() {
			return List.of(allMay, multiMay);
		}

		@Override
		public Criterion getAcceptCriterion() {
			return acceptCriterion;
		}

		@Override
		public void configure(ModelStoreBuilder storeBuilder) {
			super.configure(storeBuilder);
			storeBuilder.getAdapter(ReasoningBuilder.class).objective(Objectives.value(requiredObjects));
			storeBuilder.tryGetAdapter(DesignSpaceExplorationBuilder.class).ifPresent(dseBuilder -> {
                dseBuilder.accept(acceptCriterion);
				dseBuilder.exclude(Criteria.whenHasMatch(tooFewObjects));
            });
		}
	}
}
