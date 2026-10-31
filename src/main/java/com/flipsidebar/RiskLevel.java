/*
 * Copyright (c) 2026, bycoo222
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.flipsidebar;

/**
 * How much the scan tolerates thin trading and a margin that comes and goes. Thresholds on
 * measured evidence (Research), not a mood — calibrated on the user's 958 real flips, 2026-10-31.
 */
public enum RiskLevel
{
	LOW("Low", 0.60, 0.10, 5, 0.5),
	MED("Med", 0.30, 0.50, 10, 1.0),
	HIGH("High", 0.10, 0.65, 20, 2.0);

	private final String label;
	/** Share of 5-minute buckets that must contain a trade. */
	final double minContiguity;
	/** Share of the last 4h's buckets allowed to show no margin after tax. */
	final double maxNegativeFrac;
	/** Minimum margin each, gp — waived on items trading 50k+ units an hour. */
	final long minMargin;
	/** How many adjust-windows a fill may take. */
	final double fillSlack;

	RiskLevel(String label, double minContiguity, double maxNegativeFrac, long minMargin, double fillSlack)
	{
		this.label = label;
		this.minContiguity = minContiguity;
		this.maxNegativeFrac = maxNegativeFrac;
		this.minMargin = minMargin;
		this.fillSlack = fillSlack;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
