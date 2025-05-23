/*
 * SPDX-FileCopyrightText: 2021-2023 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.logic.term.int_;

import tools.refinery.logic.term.Term;

public class IntMinTerm extends IntBinaryTerm {
	public IntMinTerm(Term<Integer> left, Term<Integer> right) {
		super(left, right);
	}

	@Override
    protected Term<Integer> constructWithSubTerms(Term<Integer> newLeft,
                                                  Term<Integer> newRight) {
		return new IntMinTerm(newLeft, newRight);
	}

	@Override
	protected Integer doEvaluate(Integer leftValue, Integer rightValue) {
		return Math.min(rightValue, leftValue);
	}

	@Override
	public String toString() {
		return "min(%s, %s)".formatted(getLeft(), getRight());
	}
}
