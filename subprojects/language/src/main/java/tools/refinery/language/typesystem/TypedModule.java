/*
 * SPDX-FileCopyrightText: 2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.language.typesystem;

import com.google.inject.Inject;
import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.validation.CheckType;
import org.eclipse.xtext.validation.FeatureBasedDiagnostic;
import org.eclipse.xtext.validation.ValidationMessageAcceptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.refinery.language.expressions.BuiltInTerms;
import tools.refinery.language.expressions.TermInterpreter;
import tools.refinery.language.model.problem.*;
import tools.refinery.language.scoping.imports.ImportAdapterProvider;
import tools.refinery.language.utils.ProblemUtil;
import tools.refinery.language.validation.ProblemValidator;

import java.util.*;
import java.util.stream.Collectors;

public class TypedModule {
	private static final String OPERAND_TYPE_ERROR_MESSAGE = "Cannot determine operand type.";
	private static final Map<AggregatorName, DataExprType> SPECIAL_AGGREGATORS = Map.of(
			BuiltInTerms.REIFY_AGGREGATOR, BuiltInTerms.BOOLEAN_TYPE,
			BuiltInTerms.COUNT_AGGREGATOR, BuiltInTerms.INT_TYPE
	);

	@Inject
	private SignatureProvider signatureProvider;

	@Inject
	private ImportAdapterProvider importAdapterProvider;

	private TermInterpreter interpreter;
	private final Map<Variable, List<AssignmentExpr>> assignments = new LinkedHashMap<>();
	private final Map<Variable, FixedType> variableTypes = new HashMap<>();
	private final Map<Expr, ExprType> expressionTypes = new HashMap<>();
	private final Set<Variable> variablesToProcess = new LinkedHashSet<>();
	private final List<FeatureBasedDiagnostic> diagnostics = new ArrayList<>();
	private ValidationMessageAcceptor acceptor;

	void setProblem(Problem problem) {
		interpreter = importAdapterProvider.getTermInterpreter(problem);
		gatherAssignments(problem);
		checkTypes(problem);
	}

	private void gatherAssignments(Problem problem) {
		var iterator = problem.eAllContents();
		while (iterator.hasNext()) {
			var eObject = iterator.next();
			if (!(eObject instanceof AssignmentExpr assignmentExpr)) {
				continue;
			}
			if (assignmentExpr.getLeft() instanceof VariableOrNodeExpr variableOrNodeExpr &&
					variableOrNodeExpr.getVariableOrNode() instanceof Variable variable) {
				var assignmentList = assignments.computeIfAbsent(variable, ignored -> new ArrayList<>(1));
				assignmentList.add(assignmentExpr);
			}
			iterator.prune();
		}
	}

	private void checkTypes(Problem problem) {
		for (var statement : problem.getStatements()) {
			switch (statement) {
			case PredicateDefinition predicateDefinition -> checkTypes(predicateDefinition);
			case FunctionDefinition functionDefinition -> checkTypes(functionDefinition);
			case RuleDefinition ruleDefinition -> checkTypes(ruleDefinition);
			case Assertion assertion -> checkTypes(assertion);
			default -> {
				// Nothing to type check.
			}
			}
		}
	}

	private void checkTypes(PredicateDefinition predicateDefinition) {
		for (var conjunction : predicateDefinition.getBodies()) {
			for (var literal : conjunction.getLiterals()) {
				coerceIntoLiteral(literal);
			}
		}
	}

	private void checkTypes(FunctionDefinition functionDefinition) {
		var functionType = functionDefinition.getFunctionType();
		if (!(functionType instanceof DatatypeDeclaration datatypeDeclaration)) {
			return;
		}
		var expectedType = signatureProvider.getDataType(datatypeDeclaration);
		if (ProblemUtil.isSingleExpression(functionDefinition)) {
			expectType(functionDefinition.getCases().getFirst().getCondition().getLiterals().getFirst(), expectedType);
			return;
		}
		for (var match : functionDefinition.getCases()) {
			var condition = match.getCondition();
			if (condition != null) {
				for (var literal : condition.getLiterals()) {
					coerceIntoLiteral(literal);
				}
			}
			var value = match.getValue();
			if (value == null) {
				var message = "Missing value after case";
				if (condition == null || condition.getLiterals().isEmpty()) {
					error(message, match, null, 0, ProblemValidator.TYPE_ERROR);
				} else {
					error(message, condition, ProblemPackage.Literals.CONJUNCTION__LITERALS,
							condition.getLiterals().size() - 1, ProblemValidator.TYPE_ERROR);
				}
			} else {
				expectType(value, expectedType);
			}
		}
	}

	private void checkTypes(RuleDefinition ruleDefinition) {
		for (var conjunction : ruleDefinition.getPreconditions()) {
			for (var literal : conjunction.getLiterals()) {
				coerceIntoLiteral(literal);
			}
		}
		for (var consequent : ruleDefinition.getConsequents()) {
			for (var action : consequent.getActions()) {
				if (action instanceof AssertionAction assertionAction) {
					checkTypes(assertionAction);
				}
			}
		}
	}

	private void checkTypes(Assertion assertion) {
		checkAssertionValueType(assertion);
		checkNodeAssertionArgumentTypes(assertion, false);
	}

	private void checkAssertionValueType(AbstractAssertion assertion) {
		var relation = assertion.getRelation();
		if (relation == null) {
			return;
		}
		var type = signatureProvider.getSignature(relation).resultType();
		var value = assertion.getValue();
		if (type == ExprType.LITERAL) {
			if (value == null) {
				return;
			}
			expectType(value, BuiltInTerms.BOOLEAN_TYPE);
			return;
		}
		if (value == null) {
			var message = "Assertion value of type %s is required.".formatted(type);
			error(message, assertion, ProblemPackage.Literals.ABSTRACT_ASSERTION__VALUE, 0,
					ProblemValidator.TYPE_ERROR);
		}
		expectType(value, type);
	}

	private void checkNodeAssertionArgumentTypes(AbstractAssertion assertion, boolean allowVariables) {
		for (var argument : assertion.getArguments()) {
			if (argument instanceof NodeAssertionArgument nodeAssertionArgument) {
				checkNodeAssertionArgumentType(nodeAssertionArgument, allowVariables);
			}
		}
	}

	private void checkNodeAssertionArgumentType(NodeAssertionArgument nodeAssertionArgument, boolean allowVariables) {
		var variableOrNode = nodeAssertionArgument.getNode();
		if (variableOrNode == null || variableOrNode.eIsProxy()) {
			return;
		}
		if (allowVariables && variableOrNode instanceof Variable variable) {
			var variableType = getVariableType(variable);
			if (variableType == ExprType.INVALID || variableType == ExprType.NODE) {
				return;
			}
		}
		if (variableOrNode instanceof Node) {
			return;
		}
		error("Assertion argument must be a node.", nodeAssertionArgument,
				ProblemPackage.Literals.NODE_ASSERTION_ARGUMENT__NODE, 0, ProblemValidator.TYPE_ERROR);
	}

	private void checkTypes(AssertionAction assertionAction) {
		checkAssertionValueType(assertionAction);
		checkNodeAssertionArgumentTypes(assertionAction, true);
	}

	public List<FeatureBasedDiagnostic> getDiagnostics() {
		return diagnostics;
	}

	public FixedType getVariableType(Variable variable) {
		// We can't use computeIfAbsent here, because translating referenced queries calls this method in a reentrant
		// way, which would cause a ConcurrentModificationException with computeIfAbsent.
		@SuppressWarnings("squid:S3824")
		var type = variableTypes.get(variable);
		//noinspection Java8MapApi
		if (type == null) {
			type = computeVariableType(variable);
			variableTypes.put(variable, type);
		}
		return type;
	}

	private FixedType computeVariableType(Variable variable) {
		if (variable instanceof Parameter) {
			return computeUnassignedVariableType(variable);
		}
		var assignmnentList = assignments.get(variable);
		if (assignmnentList == null || assignmnentList.isEmpty()) {
			return computeUnassignedVariableType(variable);
		}
		if (variablesToProcess.contains(variable)) {
			throw new IllegalStateException("Circular reference to variable: " + variable.getName());
		}
		if (assignmnentList.size() > 1) {
			var message = "Multiple assignments for variable '%s'.".formatted(variable.getName());
			for (var assignment : assignmnentList) {
				error(message, assignment, ProblemPackage.Literals.BINARY_EXPR__LEFT, 0,
						ProblemValidator.INVALID_ASSIGNMENT_ISSUE);
			}
			return ExprType.INVALID;
		}
		var assignment = assignmnentList.getFirst();
		variablesToProcess.add(variable);
		try {
			var assignedType = getExpressionType(assignment.getRight());
			if (assignedType instanceof MutableType) {
				var message = "Cannot determine type of variable '%s'.".formatted(variable.getName());
				error(message, assignment, ProblemPackage.Literals.BINARY_EXPR__RIGHT, 0, ProblemValidator.TYPE_ERROR);
				return ExprType.INVALID;
			}
			if (assignedType instanceof DataExprType dataExprType) {
				return dataExprType;
			}
			if (assignedType != ExprType.INVALID) {
				var message = "Expected data expression for variable '%s', got %s instead."
						.formatted(variable.getName(), assignedType);
				error(message, assignment, ProblemPackage.Literals.BINARY_EXPR__RIGHT, 0, ProblemValidator.TYPE_ERROR);
			}
			return ExprType.INVALID;
		} finally {
			variablesToProcess.remove(variable);
		}
	}

	private FixedType computeUnassignedVariableType(Variable variable) {
		if (variable instanceof Parameter parameter &&
				parameter.getParameterType() instanceof DatatypeDeclaration datatypeDeclaration) {
			return signatureProvider.getDataType(datatypeDeclaration);
		}
		// Parameters without an explicit datatype annotation are node variables.
		return ExprType.NODE;
	}

	@NotNull
	public ExprType getExpressionType(Expr expr) {
		// We can't use computeIfAbsent here, because translating referenced queries calls this method in a reentrant
		// way, which would cause a ConcurrentModificationException with computeIfAbsent.
		@SuppressWarnings("squid:S3824")
		var type = expressionTypes.get(expr);
		//noinspection Java8MapApi
		if (type == null) {
			type = computeExpressionType(expr);
			expressionTypes.put(expr, type);
		}
		return type.unwrapIfSet();
	}

	@NotNull
	private ExprType computeExpressionType(Expr expr) {
		return switch (expr) {
			case LogicConstant logicConstant -> computeExpressionType(logicConstant);
			case IntConstant ignored -> BuiltInTerms.INT_TYPE;
			case RealConstant ignored -> BuiltInTerms.REAL_TYPE;
			case StringConstant ignored -> BuiltInTerms.STRING_TYPE;
			case InfiniteConstant ignored -> new MutableType();
			case VariableOrNodeExpr variableOrNodeExpr -> computeExpressionType(variableOrNodeExpr);
			case AssignmentExpr assignmentExpr -> computeExpressionType(assignmentExpr);
			case Atom atom -> computeExpressionType(atom);
			case NegationExpr negationExpr -> computeExpressionType(negationExpr);
			case ArithmeticUnaryExpr arithmeticUnaryExpr -> computeExpressionType(arithmeticUnaryExpr);
			case AggregationExpr aggregationExpr -> computeExpressionType(aggregationExpr);
			case ComparisonExpr comparisonExpr -> computeExpressionType(comparisonExpr);
			case LatticeBinaryExpr latticeBinaryExpr -> computeExpressionType(latticeBinaryExpr);
			case RangeExpr rangeExpr -> computeExpressionType(rangeExpr);
			case ArithmeticBinaryExpr arithmeticBinaryExpr -> computeExpressionType(arithmeticBinaryExpr);
			case ModalExpr modalExpr -> computeExpressionType(modalExpr);
			default -> {
				error("Unknown expression: " + expr.getClass().getSimpleName(), expr, null, 0,
						ProblemValidator.UNKNOWN_EXPRESSION_ISSUE);
				yield ExprType.INVALID;
			}
		};
	}

	@NotNull
	private ExprType computeExpressionType(LogicConstant expr) {
		return switch (expr.getLogicValue()) {
			case TRUE, FALSE -> BuiltInTerms.BOOLEAN_TYPE;
			case UNKNOWN, ERROR -> new MutableType();
			case null -> ExprType.INVALID;
		};
	}

	@NotNull
	private ExprType computeExpressionType(VariableOrNodeExpr expr) {
		var target = expr.getVariableOrNode();
		if (target == null || target.eIsProxy()) {
			return ExprType.INVALID;
		}
		return switch (target) {
			case Node ignored -> ExprType.NODE;
			case Variable variable -> {
				if (variablesToProcess.contains(variable)) {
					var message = "Circular reference to variable '%s'.".formatted(variable.getName());
					error(message, expr, ProblemPackage.Literals.VARIABLE_OR_NODE_EXPR__ELEMENT, 0,
							ProblemValidator.INVALID_ASSIGNMENT_ISSUE);
					yield ExprType.INVALID;
				}
				yield getVariableType(variable);
			}
			default -> {
				error("Unknown variable: " + target.getName(), expr,
						ProblemPackage.Literals.VARIABLE_OR_NODE_EXPR__ELEMENT, 0,
						ProblemValidator.UNKNOWN_EXPRESSION_ISSUE);
				yield ExprType.INVALID;
			}
		};
	}

	@NotNull
	private ExprType computeExpressionType(AssignmentExpr expr) {
		// Force the left side to type check. Since the left side is a variable, it will force the right side to also
		// type check in order to infer the variable type.
		return getExpressionType(expr.getLeft()) == ExprType.INVALID ? ExprType.INVALID : ExprType.LITERAL;
	}

	@NotNull
	private ExprType computeExpressionType(Atom atom) {
		var relation = atom.getRelation();
		if (relation == null || relation.eIsProxy()) {
			return ExprType.INVALID;
		}
		if (relation instanceof DatatypeDeclaration) {
			return computeCastExpressionType(atom);
		}
		var signature = signatureProvider.getSignature(relation);
		var parameterTypes = signature.parameterTypes();
		var arguments = atom.getArguments();
		int size = Math.min(parameterTypes.size(), arguments.size());
		boolean ok = parameterTypes.size() == arguments.size();
		if (relation instanceof OverloadedDeclaration overloadedDeclaration) {
			var name = signatureProvider.getPrimitiveName(overloadedDeclaration);
			var argumentTypes = new ArrayList<@Nullable DataExprType>(size);
			for (var argument : arguments) {
				var argumentType = getExpressionType(argument);
				switch (argumentType) {
				case DataExprType dataExprType -> argumentTypes.add(dataExprType);
				case MutableType ignoredMutableType -> argumentTypes.add(null);
				default -> ok = false;
				}
			}
			if (ok) {
				var optionalSignature = interpreter.getOverloadedSignature(name,
						Collections.unmodifiableList(argumentTypes));
				if (optionalSignature.isPresent()) {
					signature = optionalSignature.get();
					parameterTypes = signature.parameterTypes();
				} else {
					ok = false;
					var argumentsString = argumentTypes.stream()
							.map(argument -> argument == null ? "unknown" : '\'' + argument.toString() + '\'')
							.collect(Collectors.joining(", "));
					var message = "No matching overload of function '%s' was found with argument%s %s."
							.formatted(name, size == 1 ? "" : "s", argumentsString);
					error(message, atom, ProblemPackage.Literals.ATOM__RELATION, 0, ProblemValidator.TYPE_ERROR);
				}
			}
		}
		if (ok) {
			for (int i = 0; i < size; i++) {
				var parameterType = parameterTypes.get(i);
				var argument = arguments.get(i);
				if (!expectType(argument, parameterType)) {
					// Avoid short-circuiting to let us type check all arguments.
					ok = false;
				}
			}
		}
		return ok ? signature.resultType() : ExprType.INVALID;
	}

	@NotNull
	private ExprType computeExpressionType(NegationExpr negationExpr) {
		var body = negationExpr.getBody();
		if (body == null) {
			return ExprType.INVALID;
		}
		var actualType = getExpressionType(body);
		if (actualType == ExprType.LITERAL) {
			// Negation of literals yields another (non-enumerable) literal.
			return ExprType.LITERAL;
		}
		if (actualType == ExprType.INVALID) {
			return ExprType.INVALID;
		}
		if (actualType instanceof MutableType) {
			error(OPERAND_TYPE_ERROR_MESSAGE, body, null, 0, ProblemValidator.TYPE_ERROR);
			return ExprType.INVALID;
		}
		if (actualType instanceof DataExprType dataExprType) {
			var result = interpreter.getNegationType(dataExprType);
			if (result.isPresent()) {
				return result.get();
			}
		}
		var message = "Data type %s does not support negation.".formatted(actualType);
		error(message, negationExpr, null, 0, ProblemValidator.TYPE_ERROR);
		return ExprType.INVALID;
	}

	@NotNull
	private ExprType computeExpressionType(ArithmeticUnaryExpr expr) {
		var op = expr.getOp();
		var body = expr.getBody();
		if (op == null || body == null) {
			return ExprType.INVALID;
		}
		var actualType = getExpressionType(body);
		if (actualType == ExprType.INVALID) {
			return ExprType.INVALID;
		}
		if (actualType instanceof MutableType) {
			error(OPERAND_TYPE_ERROR_MESSAGE, body, null, 0, ProblemValidator.TYPE_ERROR);
			return ExprType.INVALID;
		}
		if (actualType instanceof DataExprType dataExprType) {
			var result = interpreter.getUnaryOperationType(op, dataExprType);
			if (result.isPresent()) {
				return result.get();
			}
		}
		var message = "Unsupported operator for data type %s.".formatted(actualType);
		error(message, expr, null, 0, ProblemValidator.TYPE_ERROR);
		return ExprType.INVALID;
	}

	@NotNull
	private ExprType computeExpressionType(AggregationExpr expr) {
		var aggregator = expr.getAggregator();
		if (aggregator == null || aggregator.eIsProxy()) {
			return ExprType.INVALID;
		}
		// Avoid short-circuiting to let us type check both the value and the condition.
		boolean ok = coerceIntoLiteral(expr.getCondition());
		var value = expr.getValue();
		var aggregatorName = signatureProvider.getAggregatorName(aggregator);
		var specialAggregatorResult = SPECIAL_AGGREGATORS.get(aggregatorName);
		if (specialAggregatorResult != null) {
			if (value != null) {
				var simpleName = aggregatorName.qualifiedName().getLastSegment();
				error("Aggregator '%s' must not have any value expression.".formatted(simpleName), expr,
						ProblemPackage.Literals.AGGREGATION_EXPR__VALUE, 0, ProblemValidator.TYPE_ERROR);
			}
			return specialAggregatorResult;
		}
		if (value == null) {
			error("Missing value for aggregation expression.", expr,
					ProblemPackage.Literals.AGGREGATION_EXPR__AGGREGATOR, 0, ProblemValidator.TYPE_ERROR);
			return ExprType.INVALID;
		}
		var actualType = getExpressionType(value);
		if (actualType == ExprType.INVALID) {
			return ExprType.INVALID;
		}
		if (actualType instanceof MutableType) {
			error(OPERAND_TYPE_ERROR_MESSAGE, value, null, 0, ProblemValidator.TYPE_ERROR);
			return ExprType.INVALID;
		}
		if (actualType instanceof DataExprType dataExprType) {
			var result = interpreter.getAggregationType(aggregatorName, dataExprType);
			if (result.isPresent()) {
				return ok ? result.get() : ExprType.INVALID;
			}
		}
		var message = "Unsupported aggregator for type %s.".formatted(actualType);
		error(message, expr, ProblemPackage.Literals.AGGREGATION_EXPR__AGGREGATOR, 0, ProblemValidator.TYPE_ERROR);
		return ExprType.INVALID;
	}

	@NotNull
	private ExprType computeExpressionType(ComparisonExpr expr) {
		var left = expr.getLeft();
		var right = expr.getRight();
		if (left == null || right == null) {
			return ExprType.INVALID;
		}
		var op = expr.getOp();
		if (op == ComparisonOp.EQ || op == ComparisonOp.NOT_EQ) {
			var leftType = getExpressionType(left);
			if (ExprType.NODE.equals(leftType) && expectType(right, ExprType.NODE)) {
				return ExprType.LITERAL;
			}
		}
		if (!(getCommonDataType(expr) instanceof DataExprType commonType)) {
			return ExprType.INVALID;
		}
		// Data equality and inequality are always supported for data types.
		if (op != ComparisonOp.EQ && op != ComparisonOp.NOT_EQ && !interpreter.isComparable(commonType)) {
			var message = "Data type %s does not support comparison.".formatted(commonType);
			error(message, expr, null, 0, ProblemValidator.TYPE_ERROR);
			return ExprType.INVALID;
		}
		return BuiltInTerms.BOOLEAN_TYPE;
	}

	@NotNull
	private ExprType computeExpressionType(LatticeBinaryExpr expr) {
		if (!(getCommonDataType(expr) instanceof DataExprType commonType)) {
			return ExprType.INVALID;
		}
		return switch (expr.getOp()) {
			case EQ, NOT_EQ, SUBSET, SUPERSET -> BuiltInTerms.BOOLEAN_TYPE;
			case JOIN, MEET -> commonType;
		};
	}

	@NotNull
	private ExprType computeExpressionType(RangeExpr expr) {
		var left = expr.getLeft();
		var right = expr.getRight();
		if (left instanceof InfiniteConstant && right instanceof InfiniteConstant) {
			// `*..*` is equivalent to `unknown` if neither subexpression have been typed yet.
			var mutableType = new MutableType();
			if (expressionTypes.putIfAbsent(left, mutableType) == null &&
					expressionTypes.put(right, mutableType) == null) {
				return mutableType;
			}
		}
		if (!(getCommonDataType(expr) instanceof DataExprType commonType)) {
			return ExprType.INVALID;
		}
		if (!interpreter.isComparable(commonType)) {
			var message = "Data type %s does not support ranges.".formatted(commonType);
			error(message, expr, null, 0, ProblemValidator.TYPE_ERROR);
			return ExprType.INVALID;
		}
		return commonType;
	}

	@NotNull
	private ExprType computeExpressionType(ArithmeticBinaryExpr expr) {
		var op = expr.getOp();
		var left = expr.getLeft();
		var right = expr.getRight();
		if (op == null || left == null || right == null) {
			return ExprType.INVALID;
		}
		// Avoid short-circuiting to let us type check both arguments.
		var leftType = getExpressionType(left);
		var rightType = getExpressionType(right);
		var result = computeBinaryExpressionType(left, leftType, right, rightType, op);
		if (result != null) {
			return result;
		}
		var messageBuilder = new StringBuilder("Unsupported operator for ");
		if (leftType.equals(rightType)) {
			messageBuilder.append("data type ").append(leftType);
		} else {
			messageBuilder.append("data types ").append(leftType).append(" and ").append(rightType);
		}
		messageBuilder.append(".");
		error(messageBuilder.toString(), expr, null, 0, ProblemValidator.TYPE_ERROR);
		return ExprType.INVALID;
	}

	@Nullable
	private ExprType computeBinaryExpressionType(Expr left, ExprType leftType, Expr right, ExprType rightType,
												 BinaryOp op) {
		if (leftType == ExprType.INVALID || rightType == ExprType.INVALID) {
			return ExprType.INVALID;
		}
		if (rightType instanceof MutableType rightMutableType) {
			if (leftType instanceof DataExprType leftExprType) {
				rightMutableType.setActualType(leftExprType);
				rightType = leftExprType;
			} else {
				error(OPERAND_TYPE_ERROR_MESSAGE, right, null, 0, ProblemValidator.TYPE_ERROR);
				return ExprType.INVALID;
			}
		}
		if (leftType instanceof MutableType leftMutableType) {
			if (rightType instanceof DataExprType rightExprType) {
				leftMutableType.setActualType(rightExprType);
				leftType = rightExprType;
			} else {
				error(OPERAND_TYPE_ERROR_MESSAGE, left, null, 0, ProblemValidator.TYPE_ERROR);
				return ExprType.INVALID;
			}
		}
		if (leftType instanceof DataExprType leftExprType && rightType instanceof DataExprType rightExprType) {
			var result = interpreter.getBinaryOperatorType(op, leftExprType, rightExprType);
			if (result.isPresent()) {
				return result.get();
			}
		}
		return null;
	}

	@NotNull
	private ExprType computeCastExpressionType(Atom expr) {
		var arguments = expr.getArguments();
		if (arguments.size() != 1) {
            return ExprType.INVALID;
        }
		var body = arguments.getFirst();
		var targetRelation = expr.getRelation();
		if (body == null || !(targetRelation instanceof DatatypeDeclaration targetDeclaration)) {
			return ExprType.INVALID;
		}
		var actualType = getExpressionType(body);
		if (actualType == ExprType.INVALID) {
			return ExprType.INVALID;
		}
		var targetType = (DataExprType) signatureProvider.getDataType(targetDeclaration);
		if (actualType instanceof MutableType mutableType) {
			// Type ascription for polymorphic literal (e.g., `unknown as int` for the set of all integers).
			mutableType.setActualType(targetType);
			return targetType;
		}
		if (actualType.equals(targetType)) {
			return targetType;
		}
		if (actualType instanceof DataExprType dataExprType && interpreter.isCastSupported(dataExprType, targetType)) {
			return targetType;
		}
		var message = "Casting from %s to %s is not supported.".formatted(actualType, targetType);
		error(message, expr, null, 0, ProblemValidator.TYPE_ERROR);
		return ExprType.INVALID;
	}

	@NotNull
	private ExprType computeExpressionType(ModalExpr expr) {
		var body = expr.getBody();
		if (body == null) {
			return ExprType.INVALID;
		}
		var actualType = getExpressionType(body);
		if (expr.getModality() == Modality.UNSPECIFIED) {
			// Change the concreteness of a partial function call without applying a modality.
			return actualType;
		}
		if (actualType == ExprType.LITERAL || BuiltInTerms.BOOLEAN_TYPE.equals(actualType)) {
			// Only literals and booleans may have a modality applied.
			return actualType;
		}
		if (actualType == ExprType.INVALID) {
			return ExprType.INVALID;
		}
		if (actualType instanceof MutableType mutableType) {
			mutableType.setActualType(BuiltInTerms.BOOLEAN_TYPE);
			return BuiltInTerms.BOOLEAN_TYPE;
		}
		var message = "Data type %s does not support modal operators.".formatted(actualType);
		error(message, expr, null, 0, ProblemValidator.TYPE_ERROR);
		return ExprType.INVALID;
	}

	private FixedType getCommonDataType(BinaryExpr expr) {
		var commonType = getCommonType(expr);
		if (!(commonType instanceof DataExprType) && commonType != ExprType.INVALID) {
			var message = "Expected data expression, got %s instead.".formatted(commonType);
			error(message, expr, null, 0, ProblemValidator.TYPE_ERROR);
			return ExprType.INVALID;
		}
		return commonType;
	}

	private FixedType getCommonType(BinaryExpr expr) {
		var left = expr.getLeft();
		var right = expr.getRight();
		if (left == null || right == null) {
			return ExprType.INVALID;
		}
		var leftType = getExpressionType(left);
		if (leftType instanceof FixedType fixedLeftType) {
			return expectType(right, fixedLeftType) ? fixedLeftType : ExprType.INVALID;
		} else {
			var rightType = getExpressionType(right);
			if (rightType instanceof FixedType fixedRightType) {
				return expectType(left, leftType, fixedRightType) ? fixedRightType : ExprType.INVALID;
			} else {
				error(OPERAND_TYPE_ERROR_MESSAGE, left, null, 0, ProblemValidator.TYPE_ERROR);
				error(OPERAND_TYPE_ERROR_MESSAGE, right, null, 0, ProblemValidator.TYPE_ERROR);
				return ExprType.INVALID;
			}
		}
	}

	private boolean coerceIntoLiteral(Expr expr) {
		if (expr == null) {
			return false;
		}
		var actualType = getExpressionType(expr);
		if (actualType == ExprType.LITERAL) {
			return true;
		}
		return expectType(expr, actualType, BuiltInTerms.BOOLEAN_TYPE);
	}

	private boolean expectType(Expr expr, FixedType expectedType) {
		if (expr == null) {
			return false;
		}
		var actualType = getExpressionType(expr);
		return expectType(expr, actualType, expectedType);
	}

	private boolean expectType(Expr expr, ExprType actualType, FixedType expectedType) {
		if (expectedType == ExprType.INVALID) {
			// Silence any further errors is the expected type failed to compute.
			return false;
		}
		if (actualType.equals(expectedType)) {
			return true;
		}
		if (actualType == ExprType.INVALID) {
			// We have already emitted an error previously.
			return false;
		}
		if (actualType instanceof MutableType mutableType && expectedType instanceof DataExprType dataExprType) {
			mutableType.setActualType(dataExprType);
			return true;
		}
		var builder = new StringBuilder()
				.append("Expected ")
				.append(expectedType)
				.append(" expression");
		if (!(actualType instanceof MutableType)) {
			builder.append(", got ")
					.append(actualType)
					.append(" instead");
		}
		builder.append(".");
		error(builder.toString(), expr, null, 0, ProblemValidator.TYPE_ERROR);
		return false;
	}

	private void error(String message, EObject object, EStructuralFeature feature, int index, String code,
					   String... issueData) {
		if (acceptor == null) {
			diagnostics.add(new FeatureBasedDiagnostic(Diagnostic.ERROR, message, object, feature, index,
					CheckType.NORMAL, code, issueData));
			return;
		}
		acceptor.acceptError(message, object, feature, index, code, issueData);
	}

	public boolean expectType(ValidationMessageAcceptor acceptor, Expr expr, FixedType expectedType) {
		// The methods of {@link TypedModule} are only called from a single thread, so there is no need to use a
		// {@code ThreadLocal} here.
		var oldAcceptor = this.acceptor;
		this.acceptor = acceptor;
		try {
			return expectType(expr, expectedType);
		} finally {
			this.acceptor = oldAcceptor;
		}
	}
}
