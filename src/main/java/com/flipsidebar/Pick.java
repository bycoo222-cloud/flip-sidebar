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

import java.util.ArrayList;
import java.util.List;

/** One flip the engine found: what to bid, what to ask, how many, and what could go wrong. */
class Pick
{
	int itemId;
	String name;
	/** Price to BUY at (one step above the insta-sell, so the offer is at the front). */
	long bid;
	/** Price to SELL at afterwards (one step below the insta-buy). */
	long ask;
	long qty;
	/** Profit each, after the GE tax. */
	long each;
	long total;
	double fillMinutes;
	final List<String> warnings = new ArrayList<>();
	/** When it was priced, epoch seconds. */
	long asOf;
	/** What it was judged on, kept so FlipBrain can re-price it on newer trades. */
	Research research;
	long limit;
	/** Why it is not a recommended flip (FlipEngine.judge); null when it is one. */
	String rejected;

	double perHour()
	{
		return total / Math.max(fillMinutes, 0.5) * 60.0;
	}

	double roi()
	{
		return total * 100.0 / Math.max(1, qty * bid);
	}

	long committed()
	{
		return qty * bid;
	}

	String firstWarning()
	{
		return warnings.isEmpty() ? "" : warnings.get(0);
	}
}
