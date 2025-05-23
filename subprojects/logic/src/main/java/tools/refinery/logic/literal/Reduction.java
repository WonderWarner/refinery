/*
 * SPDX-FileCopyrightText: 2021-2023 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.logic.literal;

/**
 * Represents the possible reductions of a clause. A clause may be reduced to a smaller clause by omitting literals that
 * are always true or by omitting the clause entirely if it is always false. Sometimes it is not possible to reduce
 * the clause.
 */
public enum Reduction {
	/**
	 * Signifies that a literal should be preserved in the clause.
	 */
	NOT_REDUCIBLE,

	/**
	 * Signifies that the literal may be omitted from the cause (if the model being queried is nonempty).
	 */
	ALWAYS_TRUE,

	/**
	 * Signifies that the clause with the literal may be omitted entirely.
	 */
	ALWAYS_FALSE;

	public Reduction negate() {
		return switch (this) {
			case NOT_REDUCIBLE -> NOT_REDUCIBLE;
			case ALWAYS_TRUE -> ALWAYS_FALSE;
			case ALWAYS_FALSE -> ALWAYS_TRUE;
		};
	}
}
