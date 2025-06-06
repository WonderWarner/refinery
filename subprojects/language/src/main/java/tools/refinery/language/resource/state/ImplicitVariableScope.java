/*
 * SPDX-FileCopyrightText: 2021-2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.language.resource.state;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.linking.impl.LinkingHelper;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import tools.refinery.language.model.problem.*;
import tools.refinery.language.naming.NamingUtil;
import tools.refinery.language.resource.ProblemResourceDescriptionStrategy;

import java.util.*;

public class ImplicitVariableScope {
	private final EObject root;

	private final ExistentialQuantifier quantifier;

	private final ImplicitVariableScope parent;

	private Set<String> knownVariables;

	private ImplicitVariableScope(ExistentialQuantifier quantifier, ImplicitVariableScope parent) {
		this.root = quantifier;
		this.quantifier = quantifier;
		this.parent = parent;
		this.knownVariables = null;
	}

	public ImplicitVariableScope(EObject root, ExistentialQuantifier quantifier, Set<String> knownVariables) {
		this.root = root;
		this.quantifier = quantifier;
		this.parent = null;
		this.knownVariables = new HashSet<>(knownVariables);
	}

	public ImplicitVariableScope(ExistentialQuantifier root, Set<String> knownVariables) {
		this(root, root, knownVariables);
	}

	public void createVariables(IScopeProvider scopeProvider, LinkingHelper linkingHelper,
								IQualifiedNameConverter qualifiedNameConverter,
								Deque<ImplicitVariableScope> scopeQueue) {
		initializeKnownVariables();
		processEObject(root, scopeProvider, linkingHelper, qualifiedNameConverter);
		var treeIterator = root.eAllContents();
		while (treeIterator.hasNext()) {
			var child = treeIterator.next();
			if (child instanceof ExistentialQuantifier nestedQuantifier) {
				scopeQueue.addLast(new ImplicitVariableScope(nestedQuantifier, this));
				treeIterator.prune();
			} else {
				processEObject(child, scopeProvider, linkingHelper, qualifiedNameConverter);
			}
		}
	}

	private void initializeKnownVariables() {
		boolean hasKnownVariables = knownVariables != null;
		boolean hasParent = parent != null;
		if ((hasKnownVariables && hasParent) || (!hasKnownVariables && !hasParent)) {
			throw new IllegalStateException("Either known variables or parent must be provided, but not both");
		}
		if (!hasKnownVariables) {
			if (parent.knownVariables == null) {
				throw new IllegalStateException("Parent scope must be processed before current scope");
			}
			knownVariables = new HashSet<>(parent.knownVariables);
		}
	}

	private void processEObject(EObject eObject, IScopeProvider scopeProvider, LinkingHelper linkingHelper,
								IQualifiedNameConverter qualifiedNameConverter) {
		if (!(eObject instanceof VariableOrNodeExpr variableOrNodeExpr)) {
			return;
		}
		IScope scope = scopeProvider.getScope(variableOrNodeExpr,
				ProblemPackage.Literals.VARIABLE_OR_NODE_EXPR__VARIABLE_OR_NODE);
		List<INode> nodes = NodeModelUtils.findNodesForFeature(variableOrNodeExpr,
				ProblemPackage.Literals.VARIABLE_OR_NODE_EXPR__ELEMENT);
		for (INode node : nodes) {
			var variableName = linkingHelper.getCrossRefNodeAsString(node, true);
			var created = tryCreateVariableForArgument(variableOrNodeExpr, variableName, qualifiedNameConverter,
					scope);
			if (created) {
				break;
			}
		}
	}

	protected boolean tryCreateVariableForArgument(VariableOrNodeExpr variableOrNodeExpr, String crossRefString,
												   IQualifiedNameConverter qualifiedNameConverter, IScope scope) {
		if (!NamingUtil.isValidId(crossRefString)) {
			return false;
		}
		QualifiedName qualifiedName;
		try {
			qualifiedName = qualifiedNameConverter.toQualifiedName(crossRefString);
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (qualifiedName.getSegmentCount() != 1) {
			return false;
		}
		var variableName = qualifiedName.getFirstSegment();
		if (NamingUtil.isSingletonVariableName(crossRefString)) {
			createSingletonVariable(variableOrNodeExpr, variableName);
			return true;
		}
		var element = scope.getSingleElement(qualifiedName);
		if (element != null && ProblemResourceDescriptionStrategy.ATOM_TRUE.equals(
				element.getUserData(ProblemResourceDescriptionStrategy.ATOM))) {
			return false;
		}
		if (!knownVariables.contains(variableName)) {
			createVariable(variableName);
			return true;
		}
		return false;
	}

	protected void createVariable(String variableName) {
		knownVariables.add(variableName);
		ImplicitVariable variable = createNamedVariable(variableName);
		quantifier.getImplicitVariables().add(variable);
	}

	protected void createSingletonVariable(VariableOrNodeExpr variableOrNodeExpr, String variableName) {
		ImplicitVariable variable = createNamedVariable(variableName);
		variableOrNodeExpr.setSingletonVariable(variable);
	}

	protected ImplicitVariable createNamedVariable(String variableName) {
		var variable = ProblemFactory.eINSTANCE.createImplicitVariable();
		variable.setName(variableName);
		return variable;
	}
}
