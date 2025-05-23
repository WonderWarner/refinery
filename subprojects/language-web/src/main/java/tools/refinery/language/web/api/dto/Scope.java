/*
 * SPDX-FileCopyrightText: 2025 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package tools.refinery.language.web.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@ConsistentBounds
public class Scope {
	@NotNull
	private String relation;

	private boolean override;

	private boolean incremental;

	@Min(0)
	private int lowerBound;

	@Min(0)
	private Integer upperBound;

	public String getRelation() {
		return relation;
	}

	public void setRelation(String relation) {
		this.relation = relation;
	}

	public boolean isIncremental() {
		return incremental;
	}

	public boolean isOverride() {
		return override;
	}

	public void setOverride(boolean override) {
		this.override = override;
	}

	public void setIncremental(boolean incremental) {
		this.incremental = incremental;
	}

	public int getLowerBound() {
		return lowerBound;
	}

	public void setLowerBound(int lowerBound) {
		this.lowerBound = lowerBound;
	}

	public Integer getUpperBound() {
		return upperBound;
	}

	public void setUpperBound(Integer upperBound) {
		this.upperBound = upperBound;
	}

	public String toScopeConstraint() {
		return relation +
				(incremental ? "+=" : "=") +
				lowerBound +
				".." +
				(upperBound == null ? "*" : upperBound);
	}
}
