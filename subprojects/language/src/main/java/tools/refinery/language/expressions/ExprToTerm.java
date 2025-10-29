/*
 * SPDX-FileCopyrightText: 2025 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.language.expressions;

import com.google.inject.Inject;
import tools.refinery.language.model.problem.*;
import tools.refinery.language.scoping.imports.ImportAdapterProvider;
import tools.refinery.language.typesystem.DataExprType;
import tools.refinery.language.typesystem.ProblemTypeAnalyzer;
import tools.refinery.language.typesystem.SignatureProvider;
import tools.refinery.logic.term.AnyTerm;
import tools.refinery.logic.term.intinterval.IntInterval;
import tools.refinery.logic.term.intinterval.IntIntervalTerms;
import tools.refinery.logic.term.realinterval.RealInterval;
import tools.refinery.logic.term.realinterval.RealIntervalTerms;
import tools.refinery.logic.term.string.StringTerms;
import tools.refinery.logic.term.string.StringValue;
import tools.refinery.logic.term.truthvalue.TruthValue;
import tools.refinery.logic.term.truthvalue.TruthValueTerms;

import java.util.List;
import java.util.Optional;

public class ExprToTerm {
	@Inject
	private ImportAdapterProvider importAdapterProvider;

	@Inject
	private ProblemTypeAnalyzer typeAnalyzer;

	@Inject
	private SignatureProvider signatureProvider;

	protected ImportAdapterProvider getImportAdapterProvider() {
		return importAdapterProvider;
	}

	protected ProblemTypeAnalyzer getTypeAnalyzer() {
		return typeAnalyzer;
	}

	public Optional<AnyTerm> toTerm(Expr expr) {
		return switch (expr) {
			case NegationExpr negationExpr -> createNegation(negationExpr);
			case ArithmeticUnaryExpr arithmeticUnaryExpr -> createUnaryOperator(arithmeticUnaryExpr);
			case ArithmeticBinaryExpr arithmeticBinaryExpr -> createBinaryOperator(arithmeticBinaryExpr);
			case ComparisonExpr comparisonExpr -> createComparison(comparisonExpr);
			case RangeExpr rangeExpr -> createRange(rangeExpr);
			case LatticeBinaryExpr latticeBinaryExpr -> createLatticeBinaryOperator(latticeBinaryExpr);
			case ModalExpr modalExpr -> createModalOperator(modalExpr);
			case Atom atom -> createAtom(atom);
			case LogicConstant logicConstant -> createLogicConstant(logicConstant);
			case IntConstant intConstant -> createIntConstant(intConstant);
			case RealConstant realConstant -> createRealConstant(realConstant);
			case StringConstant stringConstant -> createStringConstant(stringConstant);
			case null, default -> Optional.empty();
		};
	}

	private Optional<AnyTerm> createNegation(NegationExpr expr) {
		var body = expr.getBody();
		if (!(typeAnalyzer.getExpressionType(body) instanceof DataExprType bodyType)) {
			return Optional.empty();
		}
		var termInterpreter = importAdapterProvider.getTermInterpreter(expr);
		return toTerm(body).flatMap(bodyTerm ->
				termInterpreter.createNegation(bodyType, bodyTerm));
	}

	private Optional<AnyTerm> createUnaryOperator(ArithmeticUnaryExpr expr) {
		var body = expr.getBody();
		if (!(typeAnalyzer.getExpressionType(body) instanceof DataExprType bodyType)) {
			return Optional.empty();
		}
		var termInterpreter = importAdapterProvider.getTermInterpreter(expr);
		return toTerm(body).flatMap(bodyTerm ->
				termInterpreter.createUnaryOperator(expr.getOp(), bodyType, bodyTerm));
	}

	private Optional<AnyTerm> createBinaryOperator(ArithmeticBinaryExpr expr) {
		var left = expr.getLeft();
		var right = expr.getRight();
		if (!(typeAnalyzer.getExpressionType(left) instanceof DataExprType leftType) ||
				!(typeAnalyzer.getExpressionType(right) instanceof DataExprType rightType)) {
			return Optional.empty();
		}
		var termInterpreter = importAdapterProvider.getTermInterpreter(expr);
		return toTerm(left).flatMap(leftTerm ->
				toTerm(right).flatMap(rightTerm -> termInterpreter.createBinaryOperator(
						expr.getOp(), leftType, rightType, leftTerm, rightTerm)));
	}

	private Optional<AnyTerm> createComparison(ComparisonExpr expr) {
		var left = expr.getLeft();
		if (!(typeAnalyzer.getExpressionType(left) instanceof DataExprType leftType)) {
			return Optional.empty();
		}
		var termInterpreter = importAdapterProvider.getTermInterpreter(expr);
		var right = expr.getRight();
		return toTerm(left).flatMap(leftTerm ->
				toTerm(right).flatMap(rightTerm -> termInterpreter.createComparison(
						expr.getOp(), leftType, leftTerm, rightTerm)));
	}

	private Optional<AnyTerm> createRange(RangeExpr expr) {
		var left = expr.getLeft();
		if (!(typeAnalyzer.getExpressionType(left) instanceof DataExprType leftType)) {
			return Optional.empty();
		}
		var right = expr.getRight();
		var termInterpreter = importAdapterProvider.getTermInterpreter(expr);
		Optional<AnyTerm> maybeLeftTerm;
		if (left instanceof InfiniteConstant) {
			if (right instanceof InfiniteConstant) {
				return termInterpreter.createUnknown(leftType);
			}
			maybeLeftTerm = termInterpreter.createNegativeInfinity(leftType);
		} else {
			maybeLeftTerm = toTerm(left);
		}
		var maybeRightTerm = right instanceof InfiniteConstant ? termInterpreter.createPositiveInfinity(leftType) :
				toTerm(right);
		return maybeLeftTerm.flatMap(leftTerm ->
				maybeRightTerm.flatMap(rightTerm ->
						termInterpreter.createRange(leftType, leftTerm, rightTerm)));
	}

	private Optional<AnyTerm> createLatticeBinaryOperator(LatticeBinaryExpr expr) {
		var left = expr.getLeft();
		var right = expr.getRight();
		if (!(typeAnalyzer.getExpressionType(left) instanceof DataExprType type)) {
			return Optional.empty();
		}
		var termInterpreter = importAdapterProvider.getTermInterpreter(expr);
		return toTerm(left).flatMap(leftTerm ->
				toTerm(right).flatMap(rightTerm -> termInterpreter.createLatticeOperator(
						expr.getOp(), type, leftTerm, rightTerm)));
	}

	private Optional<AnyTerm> createModalOperator(ModalExpr expr) {
		if (expr.getConcreteness() != Concreteness.UNSPECIFIED) {
			return Optional.empty();
		}
		return toTerm(expr.getBody()).map(bodyTerm -> wrapModality(bodyTerm, expr.getModality()));
	}

	protected Optional<AnyTerm> createAtom(Atom atom) {
		return switch (atom.getRelation()) {
			case OverloadedDeclaration overloadedDeclaration -> createOverloadedFunctionCall(atom,
					overloadedDeclaration);
			case DatatypeDeclaration ignoredDatatypeDeclaration -> createCast(atom);
			case null, default -> Optional.empty();
		};
	}

	private Optional<AnyTerm> createOverloadedFunctionCall(Atom atom, OverloadedDeclaration overloadedDeclaration) {
		var arguments = atom.getArguments();
		var name = signatureProvider.getPrimitiveName(overloadedDeclaration);
		int arity = arguments.size();
		var argumentTypes = new DataExprType[arity];
		var argumentTerms = new AnyTerm[arity];
		for (int i = 0; i < arity; i++) {
			var argument = arguments.get(i);
			var argumentType = typeAnalyzer.getExpressionType(argument);
			if (!(argumentType instanceof DataExprType dataExprType)) {
				return Optional.empty();
			}
			argumentTypes[i] = dataExprType;
			var argumentTerm = toTerm(argument);
			if (argumentTerm.isEmpty()) {
				return Optional.empty();
			}
			argumentTerms[i] = argumentTerm.get();
		}
		var termInterpreter = importAdapterProvider.getTermInterpreter(atom);
		return termInterpreter.createOverloadedFunctionCall(name, List.of(argumentTypes), List.of(argumentTerms));
	}

	private Optional<AnyTerm> createCast(Atom atom) {
		var arguments = atom.getArguments();
		if (arguments.size() != 1) {
			return Optional.empty();
		}
		var body = arguments.getFirst();
		if (!(typeAnalyzer.getExpressionType(body) instanceof DataExprType fromType) ||
				!(typeAnalyzer.getExpressionType(atom) instanceof DataExprType toType)) {
			return Optional.empty();
		}
		var result = toTerm(body);
		if (fromType.equals(toType)) {
			return result;
		}
		var termInterpreter = importAdapterProvider.getTermInterpreter(atom);
		return result.flatMap(bodyTerm -> termInterpreter.createCast(fromType, toType, bodyTerm));
	}

	protected static AnyTerm wrapModality(AnyTerm bodyTerm, Modality modality) {
		if (modality == Modality.UNSPECIFIED) {
			return bodyTerm;
		}
		var typedBodyTerm = bodyTerm.asType(TruthValue.class);
		return switch (modality) {
			case MAY -> TruthValueTerms.asTruthValue(TruthValueTerms.may(typedBodyTerm));
			case MUST -> TruthValueTerms.asTruthValue(TruthValueTerms.must(typedBodyTerm));
			default -> throw new IllegalArgumentException("Unsupported modality: " + modality);
		};
	}

	private Optional<AnyTerm> createLogicConstant(LogicConstant expr) {
		if (!(typeAnalyzer.getExpressionType(expr) instanceof DataExprType type)) {
			return Optional.empty();
		}
		var termInterpreter = importAdapterProvider.getTermInterpreter(expr);
		return switch (expr.getLogicValue()) {
			case UNKNOWN -> termInterpreter.createUnknown(type);
			case TRUE -> termInterpreter.createPositiveInfinity(type);
			case FALSE -> termInterpreter.createNegativeInfinity(type);
			case ERROR -> termInterpreter.createError(type);
			case null -> Optional.empty();
		};
	}

	private static Optional<AnyTerm> createIntConstant(IntConstant expr) {
		return Optional.of(IntIntervalTerms.constant(IntInterval.of(expr.getIntValue())));
	}

	private static Optional<AnyTerm> createRealConstant(RealConstant expr) {
		return Optional.of(RealIntervalTerms.constant(RealInterval.of(expr.getRealValue())));
	}

	private static Optional<AnyTerm> createStringConstant(StringConstant expr) {
		return Optional.of(StringTerms.constant(StringValue.of(expr.getStringValue())));
	}
}
