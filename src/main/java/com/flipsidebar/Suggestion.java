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

/** The flip the side panel and the box over the game are showing (the card), or a priced own pick. */
class Suggestion
{
	/** "BUY", "SELL", or "NONE" when there is nothing worth doing. */
	String action;
	int itemId;
	String name;
	int quantity;
	/** The price to type into the GE for this step. */
	int price;
	/** For a BUY: the price it is planned to sell at afterwards. 0 for a SELL. */
	int sellPrice;
	/** Profit on the whole offer after the GE tax, in coins. */
	long profit;
	/** A BUY: what it makes at the last hour's average sell price (Pick.likely); Long.MIN_VALUE = not shown. */
	long likely = Long.MIN_VALUE;
	/** Estimated minutes to fill; negative when unknown. */
	double fillMinutes;
	/** One short warning, or empty. */
	String note;
	/** When the brain priced this, epoch seconds. */
	long asOf;
	/** True for an item the user picked themselves (FlipBrain.priceItem), not the card. */
	boolean manual;
	/** A pick that cannot be placed (its GE buy limit is used up): no highlights, no fill. */
	boolean discouraged;
	/** Short, for the GE's number box line: an own pick priced off older trades ("last insta-sell 51m ago"). */
	String chatNote;
}
