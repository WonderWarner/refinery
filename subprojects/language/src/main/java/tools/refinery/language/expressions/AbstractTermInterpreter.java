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
import tools.refinery.logic.AbstractDomain;
import tools.refinery.logic.AbstractValue;
import tools.refinery.logic.AnyAbstractDomain;
import tools.refinery.logic.ComparableAbstractDomain;
import tools.refinery.logic.term.*;
import tools.refinery.logic.term.abstractdomain.AbstractDomainTerms;
import tools.refinery.logic.term.comparable.EqTerm;
import tools.refinery.logic.term.comparable.NotEqTerm;
import tools.refinery.logic.term.operators.*;
import tools.refinery.logic.term.truthvalue.TruthValue;
import tools.refinery.logic.term.truthvalue.TruthValueTerms;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

// This class is used to configure term interpreters by clients with various arguments.
@SuppressWarnings("SameParameterValue")
public abstract class AbstractTermInterpreter implements TermInterpreter {
	private static final Set<AggregatorName> SPECIAL_AGGREGATORS = Set.of(
			BuiltInTerms.COUNT_AGGREGATOR,
			BuiltInTerms.REIFY_AGGREGATOR,
			BuiltInTerms.MEET_AGGREGATOR,
			BuiltInTerms.JOIN_AGGREGATOR
	);
	private static final Set<PrimitiveName> SPECIAL_OVERLOADED_FUNCTIONS = Set.of(
			BuiltInTerms.MIN,
			BuiltInTerms.MAX,
			BuiltInTerms.IS_ERROR,
			BuiltInTerms.IS_CONCRETE,
			BuiltInTerms.LOWER_BOUND,
			BuiltInTerms.UPPER_BOUND
	);

	private final Map<DataExprType, UnaryValue<?, ?>> negations = new HashMap<>();
	private final Map<UnaryKey, UnaryValue<?, ?>> unaryOperators = new HashMap<>();
	private final Map<BinaryKey, BinaryValue<?, ?, ?>> binaryOperators = new HashMap<>();
	private final Map<CastKey, Cast<?, ?>> casts = new HashMap<>();
	private final Map<AggregatorKey, AggregatorValue> aggregators = new HashMap<>();
	private final Map<PrimitiveName, List<OverloadedSignature>> overloadMap = new HashMap<>();
	private final Map<DataExprType, AnyAbstractDomain> domains = new HashMap<>();
	private final Map<DataExprType, Serializer<?, ?>> serializers = new HashMap<>();

	protected AbstractTermInterpreter() {
	}

	protected void addDomain(DataExprType type, AnyAbstractDomain domain) {
		domains.put(type, domain);
		addImplementedOperators(type, (AbstractDomain<?, ?>) domain);
	}

	protected <A extends AbstractValue<A, C>, C> void addDomain(DataExprType type, AbstractDomain<A, C> domain,
																Serializer<A, C> serializer) {
		addDomain(type, domain);
		serializers.put(type, serializer);
	}

	// We disable generic type checking for this method, because the curiously recurring generic marker interfaces
	// ensure that the types always line up.
	@SuppressWarnings({"rawtypes", "unchecked"})
	private <A extends AbstractValue<A, C>, C> void addImplementedOperators(DataExprType type,
																			AbstractDomain<A, C> domain) {
		var unarySignature = new Signature(List.of(type), type);
		var abstractType = domain.abstractType();
		for (var implementedInterface : abstractType.getInterfaces()) {
			if (Not.class.equals(implementedInterface)) {
				addNegation(type, type, body -> new NotTerm(abstractType, body));
			}
			if (Plus.class.equals(implementedInterface)) {
				addUnaryOperator(UnaryOp.PLUS, type, type, body -> new PlusTerm(abstractType, body));
			}
			if (Minus.class.equals(implementedInterface)) {
				addUnaryOperator(UnaryOp.MINUS, type, type, body -> new MinusTerm(abstractType, body));
			}
			if (Add.class.equals(implementedInterface)) {
				addBinaryOperator(BinaryOp.ADD, type, type, type, (left, right) ->
						new AddTerm(abstractType, left, right));
			}
			if (Sub.class.equals(implementedInterface)) {
				addBinaryOperator(BinaryOp.SUB, type, type, type, (left, right) ->
						new SubTerm(abstractType, left, right));
			}
			if (Mul.class.equals(implementedInterface)) {
				addBinaryOperator(BinaryOp.MUL, type, type, type, (left, right) ->
						new MulTerm(abstractType, left, right));
			}
			if (Div.class.equals(implementedInterface)) {
				addBinaryOperator(BinaryOp.DIV, type, type, type, (left, right) ->
						new DivTerm(abstractType, left, right));
			}
			if (Pow.class.equals(implementedInterface)) {
				addBinaryOperator(BinaryOp.POW, type, type, type, (left, right) ->
						new PowTerm(abstractType, left, right));
			}
			if (And.class.equals(implementedInterface)) {
				addBinaryOperator(BinaryOp.AND, type, type, type, (left, right) ->
						new AndTerm(abstractType, left, right));
			}
			if (Or.class.equals(implementedInterface)) {
				addBinaryOperator(BinaryOp.OR, type, type, type, (left, right) ->
						new OrTerm(abstractType, left, right));
			}
			if (Xor.class.equals(implementedInterface)) {
				addBinaryOperator(BinaryOp.XOR, type, type, type, (left, right) ->
						new XorTerm(abstractType, left, right));
			}
			if (Exp.class.equals(implementedInterface)) {
				addOverloadInternal(BuiltInTerms.EXP, unarySignature, args -> new ExpTerm(abstractType,
						(Term) args.getFirst()));
			}
			if (Log.class.equals(implementedInterface)) {
				addOverloadInternal(BuiltInTerms.LOG, unarySignature, args -> new LogTerm(abstractType,
						(Term) args.getFirst()));
			}
			if (Sqrt.class.equals(implementedInterface)) {
				addOverloadInternal(BuiltInTerms.SQRT, unarySignature, args -> new SqrtTerm(abstractType,
						(Term) args.getFirst()));
			}
		}
		addAggregatorInternal(BuiltInTerms.MEET_AGGREGATOR, type, type, PartialAggregator.meet(domain));
		addAggregatorInternal(BuiltInTerms.JOIN_AGGREGATOR, type, type, PartialAggregator.join(domain));
		var checkSignature = new Signature(List.of(type), BuiltInTerms.BOOLEAN_TYPE);
		addOverloadInternal(BuiltInTerms.IS_ERROR, checkSignature, args -> TruthValueTerms.asTruthValue(
				AbstractDomainTerms.isError(domain, args.getFirst().asType(abstractType))));
		addOverloadInternal(BuiltInTerms.IS_CONCRETE, checkSignature, args -> TruthValueTerms.asTruthValue(
				AbstractDomainTerms.isConcrete(domain, args.getFirst().asType(abstractType))));
		if (domain instanceof ComparableAbstractDomain<?, ?> comparableAbstractDomain) {
			addImplementedComparableOperators(type, comparableAbstractDomain);
		}
		if (!BuiltInTerms.STRING_TYPE.equals(type)) {
			addCast(type, BuiltInTerms.STRING_TYPE, (Term<A> body) -> AbstractDomainTerms.asString(domain, body));
		}
	}

	private <A extends ComparableAbstractValue<A, C>, C extends Comparable<C>> void addImplementedComparableOperators(
			DataExprType type, ComparableAbstractDomain<A, C> domain) {
		var abstractType = domain.abstractType();
		var binarySignature = new Signature(List.of(type, type), type);
		addOverloadInternal(BuiltInTerms.MIN, binarySignature, args -> AbstractDomainTerms.min(
				domain, args.get(1).asType(abstractType), args.get(2).asType(abstractType)));
		addOverloadInternal(BuiltInTerms.MAX, binarySignature, args -> AbstractDomainTerms.max(
				domain, args.get(1).asType(abstractType), args.get(2).asType(abstractType)));
		var unarySignature = new Signature(List.of(type), type);
		addOverloadInternal(BuiltInTerms.LOWER_BOUND, unarySignature, args -> AbstractDomainTerms.lowerBound(
				domain, args.getFirst().asType(abstractType)));
		addOverloadInternal(BuiltInTerms.UPPER_BOUND, unarySignature, args -> AbstractDomainTerms.upperBound(
				domain, args.getFirst().asType(abstractType)));
	}

	protected <R, T> void addNegation(DataExprType type, DataExprType result, Function<Term<R>, Term<T>> termFactory) {
		negations.put(type, new UnaryValue<>(result, termFactory));
	}

	protected <R, T> void addUnaryOperator(
			UnaryOp op, DataExprType type, DataExprType result, Function<Term<T>, Term<R>> termFactory) {
		unaryOperators.put(new UnaryKey(op, type), new UnaryValue<>(result, termFactory));
	}

	protected <R, T1, T2> void addBinaryOperator(
			BinaryOp op, DataExprType leftType, DataExprType rightType, DataExprType result,
			BiFunction<Term<T1>, Term<T2>, Term<R>> termFactory) {
		binaryOperators.put(new BinaryKey(op, leftType, rightType), new BinaryValue<>(result, termFactory));
	}

	protected <R, T> void addCast(DataExprType fromType, DataExprType toType, Cast<R, T> cast) {
		if (fromType.equals(toType)) {
			throw new IllegalArgumentException("The fromType and toType of a cast operator must be different");
		}
		casts.put(new CastKey(fromType, toType), cast);
	}

	protected void addAggregator(AggregatorName aggregator, DataExprType type, DataExprType result,
								 AnyPartialAggregator partialAggregator) {
		if (SPECIAL_AGGREGATORS.contains(aggregator)) {
			throw new IllegalArgumentException("The aggregator '%s' cannot be overridden.".formatted(aggregator));
		}
		addAggregatorInternal(aggregator, type, result, partialAggregator);
	}

	private void addAggregatorInternal(AggregatorName aggregator, DataExprType type, DataExprType result,
									   AnyPartialAggregator partialAggregator) {
		aggregators.put(new AggregatorKey(aggregator, type), new AggregatorValue(result, partialAggregator));
	}

	protected void addOverload(PrimitiveName name, Signature signature,
							   Function<List<AnyTerm>, AnyTerm> termFactory) {
		if (SPECIAL_OVERLOADED_FUNCTIONS.contains(name)) {
			throw new IllegalArgumentException("The primitive function '%s' cannot be overridden.".formatted(name));
		}
		addOverloadInternal(name, signature, termFactory);
	}

	protected void addOverloadInternal(PrimitiveName name, Signature signature,
									   Function<List<AnyTerm>, AnyTerm> termFactory) {
		overloadMap.computeIfAbsent(name, ignored -> new ArrayList<>())
				.add(new OverloadedSignature(signature, termFactory));
	}

	@Override
	public Optional<DataExprType> getNegationType(DataExprType type) {
		return Optional.ofNullable(negations.get(type))
				.map(UnaryValue::resultType);
	}

	@Override
	public Optional<AnyAbstractDomain> getDomain(DataExprType type) {
		return Optional.ofNullable(domains.get(type));
	}

	@Override
	public Optional<AnyTerm> createNegation(DataExprType type, AnyTerm body) {
		var value = negations.get(type);
		if (value == null) {
			return Optional.empty();
		}
		return Optional.of(value.createTerm(body));
	}

	@Override
	public Optional<DataExprType> getUnaryOperationType(UnaryOp op, DataExprType type) {
		return Optional.ofNullable(unaryOperators.get(new UnaryKey(op, type)))
				.map(UnaryValue::resultType);
	}

	@Override
	public Optional<AnyTerm> createUnaryOperator(UnaryOp op, DataExprType type, AnyTerm body) {
		var value = unaryOperators.get(new UnaryKey(op, type));
		if (value == null) {
			return Optional.empty();
		}
		return Optional.of(value.createTerm(body));
	}

	@Override
	public boolean isComparable(DataExprType type) {
		var abstractDomain = domains.get(type);
		return abstractDomain instanceof ComparableAbstractDomain<?, ?>;
	}

	@Override
	public Optional<Term<TruthValue>> createComparison(ComparisonOp op, DataExprType type, AnyTerm left,
													   AnyTerm right) {
		var abstractDomain = (AbstractDomain<?, ?>) domains.get(type);
		if (abstractDomain == null) {
			return Optional.empty();
		}
		if (op == ComparisonOp.EQ) {
			return createEqTerm(true, abstractDomain, left, right);
		}
		if (op == ComparisonOp.NOT_EQ) {
			return createEqTerm(false, abstractDomain, left, right);
		}
		if (abstractDomain instanceof ComparableAbstractDomain<?, ?> comparableAbstractDomain) {
			return createComparisonTerm(op, comparableAbstractDomain, left, right);
		}
		return Optional.empty();
	}

	private static <A extends AbstractValue<A, C>, C> Optional<Term<TruthValue>> createEqTerm(
			boolean positive, AbstractDomain<A, C> abstractDomain, AnyTerm left, AnyTerm right) {
		// Since the subterms are well-typed, we can safely cast them to their actual types.
		@SuppressWarnings("unchecked")
		var uncheckedLeft = (Term<A>) left;
		@SuppressWarnings("unchecked")
		var uncheckedRight = (Term<A>) right;
		var term = positive ? AbstractDomainTerms.eq(abstractDomain, uncheckedLeft, uncheckedRight) :
				AbstractDomainTerms.notEq(abstractDomain, uncheckedLeft, uncheckedRight);
		return Optional.of(term);
	}

	private static <A extends ComparableAbstractValue<A, C>, C extends Comparable<C>> Optional<Term<TruthValue>> createComparisonTerm(
			ComparisonOp comparisonOp, ComparableAbstractDomain<A, C> abstractDomain, AnyTerm left, AnyTerm right) {
		// Since the subterms are well-typed, we can safely cast them to their actual types.
		@SuppressWarnings("unchecked")
		var uncheckedLeft = (Term<A>) left;
		@SuppressWarnings("unchecked")
		var uncheckedRight = (Term<A>) right;
		var term = switch (comparisonOp) {
			case LESS -> AbstractDomainTerms.less(abstractDomain, uncheckedLeft, uncheckedRight);
			case LESS_EQ -> AbstractDomainTerms.lessEq(abstractDomain, uncheckedLeft, uncheckedRight);
			case GREATER -> AbstractDomainTerms.greater(abstractDomain, uncheckedLeft, uncheckedRight);
			case GREATER_EQ -> AbstractDomainTerms.greaterEq(abstractDomain, uncheckedLeft, uncheckedRight);
			case EQ, NOT_EQ ->
					throw new IllegalArgumentException("Use createEqTerm instead for operator: " + comparisonOp);
		};
		return Optional.of(term);
	}

	@Override
	public Optional<AnyTerm> createLatticeOperator(LatticeBinaryOp op, DataExprType type, AnyTerm left,
												   AnyTerm right) {
		var abstractDomain = (AbstractDomain<?, ?>) domains.get(type);
		if (abstractDomain == null) {
			return Optional.empty();
		}
		return Optional.of(createLatticeOperatorTerm(op, abstractDomain, left, right));
	}

	public <A extends AbstractValue<A, C>, C> AnyTerm createLatticeOperatorTerm(
			LatticeBinaryOp op, AbstractDomain<A, C> abstractDomain, AnyTerm left, AnyTerm right) {
		// Since the subterms are well-typed, we can safely cast them to their actual types.
		@SuppressWarnings("unchecked")
		var uncheckedLeft = (Term<A>) left;
		@SuppressWarnings("unchecked")
		var uncheckedRight = (Term<A>) right;
		return switch (op) {
			case EQ -> TruthValueTerms.asTruthValue(new EqTerm<>(abstractDomain.abstractType(), uncheckedLeft,
					uncheckedRight));
			case NOT_EQ -> TruthValueTerms.asTruthValue(new NotEqTerm<>(abstractDomain.abstractType(), uncheckedLeft,
					uncheckedRight));
			case JOIN -> AbstractDomainTerms.join(abstractDomain, uncheckedLeft, uncheckedRight);
			case MEET -> AbstractDomainTerms.meet(abstractDomain, uncheckedLeft, uncheckedRight);
			case SUBSET -> TruthValueTerms.asTruthValue(AbstractDomainTerms.subset(abstractDomain, uncheckedLeft,
					uncheckedRight));
			case SUPERSET -> TruthValueTerms.asTruthValue(AbstractDomainTerms.superset(abstractDomain, uncheckedLeft,
					uncheckedRight));
		};
	}

	@Override
	public Optional<AnyTerm> createBinaryOperator(
			BinaryOp op, DataExprType leftType, DataExprType rightType, AnyTerm left, AnyTerm right) {
		var value = binaryOperators.get(new BinaryKey(op, leftType, rightType));
		if (value == null) {
			return Optional.empty();
		}
		return Optional.of(value.createTerm(left, right));
	}

	@Override
	public Optional<AnyTerm> createRange(DataExprType type, AnyTerm left, AnyTerm right) {
		if (domains.get(type) instanceof ComparableAbstractDomain<?, ?> abstractDomain) {
			return Optional.of(createRangeTerm(abstractDomain, left, right));
		}
		return Optional.empty();
	}

	private static <A extends ComparableAbstractValue<A, C>, C extends Comparable<C>> Term<A> createRangeTerm(
			ComparableAbstractDomain<A, C> abstractDomain, AnyTerm left, AnyTerm right) {
		// Since the subterms are well-typed, we can safely cast them to their actual types.
		@SuppressWarnings("unchecked")
		var uncheckedLeft = (Term<A>) left;
		@SuppressWarnings("unchecked")
		var uncheckedRight = (Term<A>) right;
		return AbstractDomainTerms.range(abstractDomain, uncheckedLeft, uncheckedRight);
	}

	@Override
	public Optional<DataExprType> getBinaryOperatorType(BinaryOp op, DataExprType leftType, DataExprType rightType) {
		return Optional.ofNullable(binaryOperators.get(new BinaryKey(op, leftType, rightType)))
				.map(BinaryValue::resultType);
	}

	@Override
	public Optional<DataExprType> getAggregationType(AggregatorName aggregator, DataExprType type) {
		return Optional.ofNullable(aggregators.get(new AggregatorKey(aggregator, type)))
				.map(AggregatorValue::resultType);
	}

	@Override
	public Optional<AnyPartialAggregator> getAggregator(AggregatorName aggregator, DataExprType type) {
		return Optional.ofNullable(aggregators.get(new AggregatorKey(aggregator, type)))
				.map(AggregatorValue::aggregator);
	}

	@Override
	public boolean isCastSupported(DataExprType fromType, DataExprType toType) {
		return casts.containsKey(new CastKey(fromType, toType));
	}

	@Override
	public Optional<AnyTerm> createCast(DataExprType fromType, DataExprType toType, AnyTerm body) {
		return Optional.ofNullable(casts.get(new CastKey(fromType, toType)))
				.map(cast -> cast.createCastUnchecked(body));
	}

	@Override
	public Optional<Signature> getOverloadedSignature(PrimitiveName primitive,
													  List<@Nullable DataExprType> argumentTypes) {
		return findOverload(primitive, argumentTypes).map(OverloadedSignature::signature);
	}

	private Optional<OverloadedSignature> findOverload(PrimitiveName primitiveName, List<DataExprType> argumentTypes) {
		var overloads = overloadMap.get(primitiveName);
		if (overloads == null) {
			return Optional.empty();
		}
		for (var overload : overloads) {
			if (overload.matches(argumentTypes)) {
				return Optional.of(overload);
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<AnyTerm> createOverloadedFunctionCall(PrimitiveName primitive, List<DataExprType> argumentTypes,
														  List<AnyTerm> arguments) {
		return findOverload(primitive, argumentTypes).map(overload -> overload.createTerm(arguments));
	}

	@Override
	public Optional<AnyTerm> createUnknown(DataExprType type) {
		var abstractDomain = domains.get(type);
		return abstractDomain == null ? Optional.empty() : Optional.of(
				AbstractDomainTerms.unknown((AbstractDomain<?, ?>) abstractDomain));
	}

	@Override
	public Optional<AnyTerm> createError(DataExprType type) {
		var abstractDomain = domains.get(type);
		return abstractDomain == null ? Optional.empty() : Optional.of(
				AbstractDomainTerms.error((AbstractDomain<?, ?>) abstractDomain));
	}

	@Override
	public Optional<AnyTerm> createNegativeInfinity(DataExprType type) {
		return domains.get(type) instanceof ComparableAbstractDomain<?, ?> abstractDomain ? Optional.of(
				AbstractDomainTerms.negativeInfinity(abstractDomain)) : Optional.empty();
	}

	@Override
	public Optional<AnyTerm> createPositiveInfinity(DataExprType type) {
		return domains.get(type) instanceof ComparableAbstractDomain<?, ?> abstractDomain ? Optional.of(
				AbstractDomainTerms.positiveInfinity(abstractDomain)) : Optional.empty();
	}

	@Override
	public Optional<Expr> serialize(DataExprType type, Object value) {
		var abstractDomain = domains.get(type);
		if (abstractDomain == null) {
			return Optional.empty();
		}
		var serializer = serializers.get(type);
		if (serializer == null) {
			return Optional.empty();
		}
		return Optional.of(serializer.serialize(abstractDomain, value));
	}

	private record UnaryKey(UnaryOp op, DataExprType type) {
	}

	private record BinaryKey(BinaryOp op, DataExprType leftType, DataExprType rightType) {
	}

	private record CastKey(DataExprType fromType, DataExprType toType) {
	}

	private record AggregatorKey(AggregatorName aggregator, DataExprType type) {
	}

	private record UnaryValue<R, T>(DataExprType resultType, Function<Term<T>, Term<R>> termFactory) {
		public AnyTerm createTerm(AnyTerm body) {
			// This is safe, because the constructor of the term created by {@code termFactory} will always check the
			// runtime type of the term, avoiding heap pollution.
			@SuppressWarnings("unchecked")
			var uncheckedBody = (Term<T>) body;
			return termFactory.apply(uncheckedBody);
		}
	}

	private record BinaryValue<R, T1, T2>(
			DataExprType resultType, BiFunction<Term<T1>, Term<T2>, Term<R>> termFactory) {

		public AnyTerm createTerm(AnyTerm left, AnyTerm right) {
			// This is safe, because the constructor of the term created by {@code toComparison} will always check the
			// runtime type of the term, avoiding heap pollution.
			@SuppressWarnings("unchecked")
			var uncheckedLeft = (Term<T1>) left;
			@SuppressWarnings("unchecked")
			var uncheckedRight = (Term<T2>) right;
			return termFactory.apply(uncheckedLeft, uncheckedRight);
		}
	}

	private record AggregatorValue(DataExprType resultType, AnyPartialAggregator aggregator) {
	}

	private record OverloadedSignature(Signature signature, Function<List<AnyTerm>, AnyTerm> termFactory) {
		public boolean matches(List<@Nullable DataExprType> argumentTypes) {
			var parameterTypes = signature.parameterTypes();
			int arity = parameterTypes.size();
			if (argumentTypes.size() != arity) {
				return false;
			}
			for (int i = 0; i < arity; i++) {
				var parameterType = parameterTypes.get(i);
				var argumentType = argumentTypes.get(i);
				if (argumentType != null && !argumentType.equals(parameterType)) {
					return false;
				}
			}
			return true;
		}

		public AnyTerm createTerm(List<AnyTerm> arguments) {
			return termFactory.apply(arguments);
		}
	}

	@FunctionalInterface
	public interface Range<T> {
		Term<T> toRange(Term<T> left, Term<T> right);

		default Term<T> toRangeUnchecked(AnyTerm left, AnyTerm right) {
			// This is safe, because the constructor of the term created by {@code toComparison} will always check the
			// runtime type of the term, avoiding heap pollution.
			@SuppressWarnings("unchecked")
			var uncheckedLeft = (Term<T>) left;
			@SuppressWarnings("unchecked")
			var uncheckedRight = (Term<T>) right;
			return toRange(uncheckedLeft, uncheckedRight);
		}
	}

	@FunctionalInterface
	public interface Cast<R, T> {
		Term<R> createCast(Term<T> body);

		default Term<R> createCastUnchecked(AnyTerm body) {
			// This is safe, because the constructor of the term created by {@code createCast} will always check the
			// runtime type of the term, avoiding heap pollution.
			@SuppressWarnings("unchecked")
			var uncheckedBody = (Term<T>) body;
			return createCast(uncheckedBody);
		}
	}

	@FunctionalInterface
	public interface Serializer<A extends AbstractValue<A, C>, C> {
		Expr serialize(A value);

		default Expr serialize(AnyAbstractDomain abstractDomain, Object value) {
			// This si safe, because we only allow setting the abstract domain and the serializer in one place.
			@SuppressWarnings("unchecked")
			var typedAbstractDomain = (AbstractDomain<A, C>) abstractDomain;
			var typedValue = typedAbstractDomain.abstractType().cast(value);
			return serialize(typedValue);
		}
	}
}
