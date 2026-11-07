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
import java.util.Set;

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
		/** A sell re-placed lower in the same slot: the price it came down from; 0 = not (TradeLog.Slot.loweredFrom). */
		final long loweredFrom;
		/** Units it filled in the last TradeLog.RECENT_MS (TradeLog.recentFilled); -1 = not known. */
		long recentFilled = -1;

		Offer(int slot, boolean buy, int itemId, String name, long price, int total, int filled,
			boolean complete, boolean cancelled)
		{
			this(slot, buy, itemId, name, price, total, filled, complete, cancelled, 0, 0);
		}

		Offer(int slot, boolean buy, int itemId, String name, long price, int total, int filled,
			boolean complete, boolean cancelled, long placedAt, long lastFill)
		{
			this(slot, buy, itemId, name, price, total, filled, complete, cancelled, placedAt, lastFill, 0);
		}

		Offer(int slot, boolean buy, int itemId, String name, long price, int total, int filled,
			boolean complete, boolean cancelled, long placedAt, long lastFill, long loweredFrom)
		{
			this.placedAt = placedAt;
			this.lastFill = lastFill;
			this.loweredFrom = loweredFrom;
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

		/** Names an offer for "ignore": item|BUY|price|quantity. */
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
		/** Its sell offer's key (TradeLog.Flip.offer); null for an old save. */
		final String offer;
		/** When what it sold was last bought, epoch ms; 0 = unknown. */
		final long boughtAt;
		/**
		 * Logged-in time, ms, from that buy to this sale with some of it unsold outside the bank (TradeLog.Bought.heldMs):
		 * what "it sat" is judged by (FlipLearner.sat). -1 = not measured (a flip from before 1.60.30).
		 */
		final long heldMs;

		Done(int itemId, String name, long qty, long buyEach, long sellEach, long profit, long at)
		{
			this(itemId, name, qty, buyEach, sellEach, profit, at, null, 0);
		}

		Done(int itemId, String name, long qty, long buyEach, long sellEach, long profit, long at, String offer,
			long boughtAt)
		{
			this(itemId, name, qty, buyEach, sellEach, profit, at, offer, boughtAt, -1);
		}

		Done(int itemId, String name, long qty, long buyEach, long sellEach, long profit, long at, String offer,
			long boughtAt, long heldMs)
		{
			this.heldMs = heldMs;
			this.itemId = itemId;
			this.name = name;
			this.qty = qty;
			this.buyEach = buyEach;
			this.sellEach = sellEach;
			this.profit = profit;
			this.at = at;
			this.offer = offer;
			this.boughtAt = boughtAt;
		}

		/** One flip's identity for the learner: its offer and when it was bought. */
		String id()
		{
			return (offer == null ? itemId + ":" + qty : offer) + "@" + (boughtAt > 0 ? boughtAt : at);
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
	/** Items the user chose to hold (TradeLog.held — the card's Hold button). */
	final Set<Integer> held;
	/** Swing buys on record: item -> {target price, until epoch ms} (TradeLog.swing). */
	final Map<Integer, long[]> swing;
	/**
	 * Held items (TradeLog.held) in the bank: unnoted item id -> how many, as the bank was last seen this login (empty
	 * until it is opened) — LONG-TERM holds, kept in the bank (FlipBrain.Held.banked).
	 */
	final Map<Integer, Integer> bank;
	/** Items bought to alch (TradeLog.forAlch): not in {@link #holdings}; High alch mode lists them to cast. */
	Set<Integer> forAlch = Set.of();
	/** Flip items SEEN going into the bank (TradeLog.Bought.inBank): unnoted id -> units. */
	Map<Integer, Long> inBank = Map.of();
	/** The player's OWN units in the inventory (taken out of the bank beyond its flip units): never offered for sale. */
	Map<Integer, Long> own = Map.of();
	/**
	 * Every finished flip of the account, all time (the plugin's flip archive), per item: {flips, lost, profit} — what
	 * FlipLearner.proven judges by. Empty until the archive is read: then {@link #history} is used.
	 */
	Map<Integer, long[]> record = Map.of();
	/** The archive's flips of the last week, newest first — what FlipLearner rests items by. Empty until it is read. */
	List<Done> week = List.of();

	/** Flip units of {@code itemId} in the inventory: what is there less the player's own (TradeLog.own). */
	int flipUnits(int itemId)
	{
		return (int) Math.max(0, inventory.getOrDefault(itemId, 0) - own.getOrDefault(itemId, 0L));
	}

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
		this(inventoryCoins, bankCoins, inventory, offers, holdings, limitUse, history, sessionStart, Set.of());
	}

	GameSnapshot(long inventoryCoins, long bankCoins, Map<Integer, Integer> inventory, List<Offer> offers,
		List<Holding> holdings, Map<Integer, long[]> limitUse, List<Done> history, long sessionStart, Set<Integer> held)
	{
		this(inventoryCoins, bankCoins, inventory, offers, holdings, limitUse, history, sessionStart, held, Map.of());
	}

	GameSnapshot(long inventoryCoins, long bankCoins, Map<Integer, Integer> inventory, List<Offer> offers,
		List<Holding> holdings, Map<Integer, long[]> limitUse, List<Done> history, long sessionStart, Set<Integer> held,
		Map<Integer, long[]> swing)
	{
		this(inventoryCoins, bankCoins, inventory, offers, holdings, limitUse, history, sessionStart, held, swing, Map.of());
	}

	GameSnapshot(long inventoryCoins, long bankCoins, Map<Integer, Integer> inventory, List<Offer> offers,
		List<Holding> holdings, Map<Integer, long[]> limitUse, List<Done> history, long sessionStart, Set<Integer> held,
		Map<Integer, long[]> swing, Map<Integer, Integer> bank)
	{
		this.bank = bank;
		this.held = held;
		this.swing = swing;
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
