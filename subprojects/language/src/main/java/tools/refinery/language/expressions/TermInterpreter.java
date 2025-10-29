/*
 * SPDX-FileCopyrightText: 2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.language.expressions;

import org.jetbrains.annotations.Nullable;
import tools.refinery.language.model.problem.*;
import tools.refinery.language.typesystem.AggregatorName;
import tools.refinery.language.typesystem.DataExprType;
import tools.refinery.language.typesystem.PrimitiveName;
import tools.refinery.language.typesystem.Signature;
import tools.refinery.logic.AnyAbstractDomain;
import tools.refinery.logic.term.AnyPartialAggregator;
import tools.refinery.logic.term.AnyTerm;
import tools.refinery.logic.term.Term;
import tools.refinery.logic.term.truthvalue.TruthValue;

import java.util.List;
import java.util.Optional;

public interface TermInterpreter {
	Optional<DataExprType> getNegationType(DataExprType type);

	Optional<AnyAbstractDomain> getDomain(DataExprType type);

	Optional<AnyTerm> createNegation(DataExprType type, AnyTerm body);

	Optional<AnyTerm> createRange(DataExprType type, AnyTerm left, AnyTerm right);

	Optional<AnyTerm> createUnaryOperator(UnaryOp op, DataExprType type, AnyTerm body);

	Optional<AnyTerm> createBinaryOperator(BinaryOp op, DataExprType leftType, DataExprType rightType, AnyTerm left,
										   AnyTerm right);

	Optional<DataExprType> getUnaryOperationType(UnaryOp op, DataExprType type);

	boolean isComparable(DataExprType type);

	Optional<Term<TruthValue>> createComparison(ComparisonOp op, DataExprType type, AnyTerm left, AnyTerm right);

	Optional<AnyTerm> createLatticeOperator(LatticeBinaryOp op, DataExprType type, AnyTerm left, AnyTerm right);

	Optional<DataExprType> getBinaryOperatorType(BinaryOp op, DataExprType leftType, DataExprType rightType);

	boolean isCastSupported(DataExprType fromType, DataExprType toType);

	Optional<AnyTerm> createCast(DataExprType fromType, DataExprType toType, AnyTerm body);

	Optional<DataExprType> getAggregationType(AggregatorName aggregator, DataExprType type);

	Optional<AnyPartialAggregator> getAggregator(AggregatorName aggregator, DataExprType type);

	Optional<Signature> getOverloadedSignature(PrimitiveName primitive, List<@Nullable DataExprType> argumentTypes);

	Optional<AnyTerm> createOverloadedFunctionCall(PrimitiveName primitive, List<DataExprType> argumentTypes,
												   List<AnyTerm> arguments);

	Optional<AnyTerm> createUnknown(DataExprType type);

	Optional<AnyTerm> createError(DataExprType type);

	Optional<AnyTerm> createNegativeInfinity(DataExprType type);

	Optional<AnyTerm> createPositiveInfinity(DataExprType type);

	Optional<Expr> serialize(DataExprType type, Object value);
}
