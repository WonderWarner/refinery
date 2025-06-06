/*
 * SPDX-FileCopyrightText: 2021-2023 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.logic.term;

import org.jetbrains.annotations.Nullable;
import tools.refinery.logic.InvalidQueryException;
import tools.refinery.logic.equality.LiteralEqualityHelper;
import tools.refinery.logic.equality.LiteralHashCodeHelper;
import tools.refinery.logic.literal.AssignLiteral;
import tools.refinery.logic.literal.EquivalenceLiteral;
import tools.refinery.logic.literal.Literal;
import tools.refinery.logic.rewriter.TermRewriter;
import tools.refinery.logic.substitution.Substitution;
import tools.refinery.logic.valuation.Valuation;

import java.util.Objects;

public final class DataVariable<T> extends AnyDataVariable implements Term<T> {
	private final Class<T> type;

	DataVariable(String name, Class<T> type) {
		super(name);
		this.type = type;
	}

	@Override
	public Class<T> getType() {
		return type;
	}

	@Override
	public DataVariable<T> renew(@Nullable String name) {
		return new DataVariable<>(name, type);
	}

	@Override
	public DataVariable<T> renew() {
		return renew(getExplicitName());
	}

	@Override
	public <U> DataVariable<U> asDataVariable(Class<U> newType) {
		if (!getType().equals(newType)) {
			throw new InvalidQueryException("%s is not of type %s but of type %s"
					.formatted(this, newType.getName(), getType().getName()));
		}
		@SuppressWarnings("unchecked")
		var result = (DataVariable<U>) this;
		return result;
	}

	@Override
	public T evaluate(Valuation valuation) {
		return valuation.getValue(this);
	}

	@Override
	public Term<T> rewriteSubTerms(TermRewriter termRewriter) {
		// No sub-terms to rewrite.
		return this;
	}

	@Override
	public Term<T> substitute(Substitution substitution) {
		return substitution.getTypeSafeSubstitute(this);
	}

	@Override
	public boolean equalsWithSubstitution(LiteralEqualityHelper helper, AnyTerm other) {
		return other instanceof DataVariable<?> dataVariable && helper.variableEqual(this, dataVariable);
	}

	@Override
	public int hashCodeWithSubstitution(LiteralHashCodeHelper helper) {
		return helper.getVariableHashCode(this);
	}

	@Override
	public int hashCodeWithSubstitution(int sequenceNumber) {
		return Objects.hash(type, sequenceNumber);
	}

	public Literal assign(Term<T> value) {
		return new AssignLiteral<>(this, value);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;
		DataVariable<?> that = (DataVariable<?>) o;
		return type.equals(that.type);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), type);
	}

	public EquivalenceLiteral isEquivalent(DataVariable<T> other) {
		return new EquivalenceLiteral(true, this, other);
	}

	public EquivalenceLiteral notEquivalent(DataVariable<T> other) {
		return new EquivalenceLiteral(false, this, other);
	}
}
