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

import java.util.List;
import java.util.Map;

/**
 * What the game showed on one tick: coins, inventory, the 8 GE offers and what was bought and not
 * yet sold. Built on the client thread, handed to FlipBrain, never changed afterwards.
 */
final class GameSnapshot
{
	/** One GE slot that is in use. */
	static final class Offer
	{
		/** 1-8, as the GE numbers them. */
		final int slot;
		final boolean buy;
		final int itemId;
		final String name;
		final long price;
		final int total;
		final int filled;
		final boolean complete;
		final boolean cancelled;
		/** When it was placed and last filled, epoch ms; 0 = unknown (TradeLog). */
		final long placedAt;
		final long lastFill;

		Offer(int slot, boolean buy, int itemId, String name, long price, int total, int filled,
			boolean complete, boolean cancelled)
		{
			this(slot, buy, itemId, name, price, total, filled, complete, cancelled, 0, 0);
		}

		Offer(int slot, boolean buy, int itemId, String name, long price, int total, int filled,
			boolean complete, boolean cancelled, long placedAt, long lastFill)
		{
			this.placedAt = placedAt;
			this.lastFill = lastFill;
			this.slot = slot;
			this.buy = buy;
			this.itemId = itemId;
			this.name = name;
			this.price = price;
			this.total = total;
			this.filled = filled;
			this.complete = complete;
			this.cancelled = cancelled;
		}

		/** Names an offer for "ignore": item|BUY|price|quantity — the sidebar's parked_offers.json key. */
		String key()
		{
			return itemId + "|" + (buy ? "BUY" : "SELL") + "|" + price + "|" + total;
		}
	}

	/** Bought on the GE and not sold since (TradeLog). */
	static final class Holding
	{
		final int itemId;
		final String name;
		final long qty;
		/** Average paid each. */
		final long basis;
		/** When its last buy filled, epoch ms; 0 = unknown. */
		final long lastBuy;

		Holding(int itemId, String name, long qty, long basis)
		{
			this(itemId, name, qty, basis, 0);
		}

		Holding(int itemId, String name, long qty, long basis, long lastBuy)
		{
			this.itemId = itemId;
			this.name = name;
			this.qty = qty;
			this.basis = basis;
			this.lastBuy = lastBuy;
		}
	}

	/** A flip from the trade log, named — for the panel's history. */
	static final class Done
	{
		final int itemId;
		final String name;
		final long qty;
		final long buyEach;
		final long sellEach;
		/** After the 2% tax; negative = a loss. */
		final long profit;
		/** Epoch ms of its last fill; 0 = before the history was kept. */
		final long at;

		Done(int itemId, String name, long qty, long buyEach, long sellEach, long profit, long at)
		{
			this.itemId = itemId;
			this.name = name;
			this.qty = qty;
			this.buyEach = buyEach;
			this.sellEach = sellEach;
			this.profit = profit;
			this.at = at;
		}
	}

	final long inventoryCoins;
	/** Coins and platinum tokens in the bank when it was last open; -1 = not seen yet. */
	final long bankCoins;
	/** Unnoted item id -> how many are in the inventory (noted and unnoted together). */
	final Map<Integer, Integer> inventory;
	final List<Offer> offers;
	final List<Holding> holdings;
	/** Item -> {units bought in its open 4-hour buy-limit window, when it resets (epoch ms)}. */
	final Map<Integer, long[]> limitUse;
	/** Flips, newest first. */
	final List<Done> history;
	/** When this session began, epoch ms (the panel's Session stats; "new session" resets it). */
	final long sessionStart;

	GameSnapshot(long inventoryCoins, long bankCoins, Map<Integer, Integer> inventory, List<Offer> offers,
		List<Holding> holdings, Map<Integer, long[]> limitUse)
	{
		this(inventoryCoins, bankCoins, inventory, offers, holdings, limitUse, List.of(), 0);
	}

	GameSnapshot(long inventoryCoins, long bankCoins, Map<Integer, Integer> inventory, List<Offer> offers,
		List<Holding> holdings, Map<Integer, long[]> limitUse, List<Done> history)
	{
		this(inventoryCoins, bankCoins, inventory, offers, holdings, limitUse, history, 0);
	}

	GameSnapshot(long inventoryCoins, long bankCoins, Map<Integer, Integer> inventory, List<Offer> offers,
		List<Holding> holdings, Map<Integer, long[]> limitUse, List<Done> history, long sessionStart)
	{
		this.history = history;
		this.sessionStart = sessionStart;
		this.inventoryCoins = inventoryCoins;
		this.bankCoins = bankCoins;
		this.inventory = inventory;
		this.offers = offers;
		this.holdings = holdings;
		this.limitUse = limitUse;
	}

	/** Free coins: inventory + bank. Never counts gp locked in buy offers. */
	long cash()
	{
		return inventoryCoins + Math.max(0, bankCoins);
	}
}
