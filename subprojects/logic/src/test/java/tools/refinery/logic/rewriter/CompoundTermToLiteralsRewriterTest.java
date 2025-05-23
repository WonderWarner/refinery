/*
 * SPDX-FileCopyrightText: 2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.logic.rewriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.refinery.logic.Constraint;
import tools.refinery.logic.dnf.Query;
import tools.refinery.logic.term.Variable;
import tools.refinery.logic.term.int_.IntTerms;
import tools.refinery.logic.tests.FakeFunctionView;
import tools.refinery.logic.tests.FakeKeyOnlyView;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static tools.refinery.logic.literal.Literals.check;
import static tools.refinery.logic.tests.QueryMatchers.structurallyEqualTo;

class CompoundTermToLiteralsRewriterTest {
	private static final Constraint personView = new FakeKeyOnlyView("Person", 1);
	private static final Constraint friendView = new FakeKeyOnlyView("friend", 2);
	private static final Constraint ageView = new FakeFunctionView<>("age", 1, Integer.class);
	private static final Constraint adultView = new FakeFunctionView<>("adult", 1, Boolean.class);

	private CompoundTermToLiteralsRewriter sut = new CompoundTermToLiteralsRewriter();

	@BeforeEach
	void beforeEach() {
		sut = new CompoundTermToLiteralsRewriter();
	}

	@Test
	void singleAssignmentTest() {
		var query = Query.of("Actual", Integer.class, (builder, p1, output) -> builder
				.clause(Integer.class, v1 -> List.of(
						personView.call(p1),
						output.assign(ageView.leftJoinBy(v1, 0, p1, v1))
				)));

		var actual = sut.rewrite(query);

		var expected = Query.of("Expected", Integer.class, (builder, p1, output) -> builder
				.clause(Integer.class, v1 -> List.of(
						personView.call(p1),
						output.assign(ageView.leftJoinBy(v1, 0, p1, v1))
				)));

		assertThat(actual.getDnf(), structurallyEqualTo(expected.getDnf()));
	}

	@Test
	void assignmentWithOperationTest() {
		var query = Query.of("Actual", Integer.class, (builder, p1, output) -> builder
				.clause(Integer.class, v1 -> List.of(
						personView.call(p1),
						output.assign(IntTerms.mul(IntTerms.constant(2), ageView.leftJoinBy(v1, 0, p1, v1)))
				)));

		var actual = sut.rewrite(query);

		var expected = Query.of("Expected", Integer.class, (builder, p1, output) -> builder
				.clause(Integer.class, Integer.class, (v1, v2) -> List.of(
						personView.call(p1),
						v2.assign(ageView.leftJoinBy(v1, 0, p1, v1)),
						output.assign(IntTerms.mul(IntTerms.constant(2), v2))
				)));

		assertThat(actual.getDnf(), structurallyEqualTo(expected.getDnf()));
	}

	@Test
	void multipleAssignmentTest() {
		var query = Query.of("Actual", Integer.class, (builder, p1, p2, output) -> builder
				.clause(Integer.class, Integer.class, (v1, v2) -> List.of(
						personView.call(p1),
						personView.call(p2),
						output.assign(IntTerms.add(ageView.leftJoinBy(v1, 0, p1, v1),
								ageView.leftJoinBy(v2, 18, p2, v2)))
				)));

		var actual = sut.rewrite(query);

		var expected = Query.of("Expected", Integer.class, (builder, p1, p2, output) -> builder
				.clause(Integer.class, Integer.class, Integer.class, Integer.class, (v1, v2, v3, v4) -> List.of(
						personView.call(p1),
						personView.call(p2),
						v3.assign(ageView.leftJoinBy(v1, 0, p1, v1)),
						v4.assign(ageView.leftJoinBy(v2, 18, p2, v2)),
						output.assign(IntTerms.add(v3, v4))
				)));

		assertThat(actual.getDnf(), structurallyEqualTo(expected.getDnf()));
	}

	@Test
	void singleCheckTest() {
		var query = Query.of("Actual", (builder, p1) -> builder
				.clause(Boolean.class, v1 -> List.of(
						personView.call(p1),
						check(adultView.leftJoinBy(v1, false, p1, v1))
				)));

		var actual = sut.rewrite(query);

		var expected = Query.of("Expected", (builder, p1) -> builder
				.clause(Boolean.class, Boolean.class, (v1, v2) -> List.of(
						personView.call(p1),
						v2.assign(adultView.leftJoinBy(v1, false, p1, v1)),
						check(v2)
				)));

		assertThat(actual.getDnf(), structurallyEqualTo(expected.getDnf()));
	}

	@Test
	void nestedCallTest() {
		var subQuery = Query.of("SubQuery", Integer.class, (builder, p1, output) -> builder
				.clause(Integer.class, v1 -> List.of(
						personView.call(p1),
						output.assign(IntTerms.mul(IntTerms.constant(2), ageView.leftJoinBy(v1, 0, p1, v1)))
				)));
		var query = Query.of("Actual", Integer.class, (builder, p1, p2, output) -> builder
				.clause(
						friendView.call(p1, p2),
						subQuery.getDnf().call(p2, output)
				));

		var actual = sut.rewrite(query);

		var expectedSubQuery = Query.of("SubQuery", Integer.class, (builder, p1, output) -> builder
				.clause(Integer.class, Integer.class, (v1, v2) -> List.of(
						personView.call(p1),
						v2.assign(ageView.leftJoinBy(v1, 0, p1, v1)),
						output.assign(IntTerms.mul(IntTerms.constant(2), v2))
				)));
		var expected = Query.of("Expected", Integer.class, (builder, p1, p2, output) -> builder
				.clause(
						friendView.call(p1, p2),
						expectedSubQuery.getDnf().call(p2, output)
				));

		assertThat(actual.getDnf(), structurallyEqualTo(expected.getDnf()));
	}

	@Test
	void nestedTermTest() {
		var subQuery = Query.of("SubQuery", Integer.class, (builder, p1, output) -> builder
				.clause(Integer.class, v1 -> List.of(
						personView.call(p1),
						output.assign(IntTerms.mul(IntTerms.constant(2), ageView.leftJoinBy(v1, 0, p1, v1)))
				)));
		var query = Query.of("Actual", Integer.class, (builder, p1, p2, output) -> builder
				.clause(
						friendView.call(p1, p2),
						output.assign(IntTerms.div(subQuery.leftJoin(36, p2), IntTerms.constant(2)))
				));

		var actual = sut.rewrite(query);

		var expectedSubQuery = Query.of("SubQuery", Integer.class, (builder, p1, output) -> builder
				.clause(Integer.class, Integer.class, (v1, v2) -> List.of(
						personView.call(p1),
						v2.assign(ageView.leftJoinBy(v1, 0, p1, v1)),
						output.assign(IntTerms.mul(IntTerms.constant(2), v2))
				)));
		var expected = Query.of("Expected", Integer.class, (builder, p1, p2, output) -> builder
				.clause(Integer.class, Integer.class, (v1, v2) -> List.of(
						friendView.call(p1, p2),
						v2.assign(expectedSubQuery.leftJoin(36, p2)),
						output.assign(IntTerms.div(v2, IntTerms.constant(2)))
				)));

		assertThat(actual.getDnf(), structurallyEqualTo(expected.getDnf()));
	}

	@Test
	void singleLiteralTest() {
		var query = Query.of("Actual", Integer.class, (builder, output) -> builder
				.clause(
						output.assign(personView.count(Variable.of()))
				));

		var actual = sut.rewrite(query);

		var expected = Query.of("Expected", Integer.class, (builder, output) -> builder
				.clause(
						output.assign(personView.count(Variable.of()))
				));

		assertThat(actual.getDnf(), structurallyEqualTo(expected.getDnf()));
	}

	@Test
	void singleLiteralCompoundExpressionTest() {
		var query = Query.of("Actual", Integer.class, (builder, output) -> builder
				.clause(
						output.assign(IntTerms.mul(personView.count(Variable.of()), IntTerms.constant(2)))
				));

		var actual = sut.rewrite(query);

		var expected = Query.of("Expected", Integer.class, (builder, output) -> builder
				.clause(Integer.class, v1 -> List.of(
						v1.assign(personView.count(Variable.of())),
						output.assign(IntTerms.mul(v1, IntTerms.constant(2)))
				)));

		assertThat(actual.getDnf(), structurallyEqualTo(expected.getDnf()));
	}
}
