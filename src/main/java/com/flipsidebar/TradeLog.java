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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What your GE offers bought and sold, worked out from how each slot changed — the way RuneLite's
 * own Grand Exchange plugin keeps its trade history. It is how the plugin knows "you bought 2,431
 * Manta rays at 960 and have not sold them" — and, when they sell,
 * what that flip made or LOST (user, 2026-10-31: "i wanna see when a flip is a loss"). Saved per
 * account (FlipSidebarPlugin, RuneScape-profile config). Client thread only.
 *
 * <p>⚠ A sell offer's {@code spent} is the coins BEFORE the 2% tax — MEASURED 2026-10-31: 4,201
 * Ascension shards sold at 1,778 showed spent = 7,469,378 = 4,201 × 1,778 exactly. So the tax is
 * taken off here.</p>
 */
class TradeLog
{
	private static final int SLOTS = 8;
	/** Bought-and-unsold counts (Holding) for this long after the last buy — 12 hours — or while it is still in the
	 * inventory or a GE offer (holdings(nowMs, here), 1.55.0). */
	static final long HOLD_MS = 12L * 3600 * 1000;
	/**
	 * ⭐ What an unsold buy cost is KEPT this long after the last buy (a week, FlipLearner.WEEK_MS), so its sale is
	 * still recorded as a flip — its loss learned (FlipLearner) — however long it sat. Until 1.55.0 it was dropped at
	 * HOLD_MS: the user's Ascension shards, bought 2026-11-01 ~17:00 and sold at a loss 11-02 05:09, were no flip, never
	 * rested, and a swing card suggested 17,749 of them at 05:14 (user: "Yea no way were going to to right back to buying
	 * a trouble item"). trim() still drops a record once the item is gone.
	 */
	static final long KEEP_MS = 7L * 24 * 3600 * 1000;
	/** The GE buy-limit window: an item bought this recently is not offered again. */
	static final long LIMIT_MS = 4L * 3600 * 1000;
	/** A swing mark with nothing bought and no buy in a slot is dropped this long after it was placed (swing). */
	static final long SWING_GRACE_MS = 10L * 60 * 1000;

	/** A slot as last seen. */
	static class Slot
	{
		int itemId;
		boolean buy;
		long price;
		int total;
		int sold;
		long spent;
		/** When the offer was placed, epoch ms; 0 = unknown (it was there before it was tracked). */
		long placedAt;
		/** When it last filled, epoch ms; 0 = no fill seen yet. */
		long lastFill;
		/** A sell re-placed at a LOWER price in the same slot (the GE's Modify): the price it came down from; 0 = not. */
		long loweredFrom;
		/** {when, sold} as its fills were seen live, oldest first — its pace of the last minutes ({@link #recentFilled}). Not saved. */
		transient List<long[]> marks;
	}

	/** How far back a slot's recent pace looks (OfferAdvice.slowSell). */
	static final long RECENT_MS = 5 * 60_000L;

	static class Bought
	{
		long qty;
		long cost;
		long lastBuy;
		/**
		 * How many of these were SEEN going into the bank — moved there from the inventory with the bank open, or a buy
		 * collected straight to the bank: the flip items in the bank (the Holding bank tab, the cash total). Never the
		 * player's own stock of the same item, which the plugin cannot tell apart from a flip's (user, 2026-11-05: "if
		 * i take a break and deposit my items id want all my flip items showing in that bank tab regardless of them
		 * being long hold items or not"). 0 in a log saved before 1.60.29.
		 */
		long inBank;
		/**
		 * ⭐ LOGGED-IN time, ms, since the last buy with some of it unsold OUTSIDE the bank — in the inventory or a GE
		 * offer — one game tick at a time ({@link #tickHeld}): what "it sat" is judged by (FlipLearner.sat). Not the
		 * time logged out, nor in the bank: user, 2026-11-05: "items that are for buy or sell and the user logs out
		 * shouldnt be counted as items to avoid. Thats not the plug ins fault its the users". MEASURED that day: Dragon
		 * javelin tips, Soul rune and Regen bracelet "sat 5.2h" from buy to sale — 0.7h, 0.7h and 0.6h of it logged in.
		 * Null in a log saved before 1.60.30: it starts at the first tick seen (a lower bound).
		 */
		Long heldMs;
	}

	/** One finished (or finishing) flip: a sell matched against what was paid. */
	static class Flip
	{
		int itemId;
		long qty;
		long buyEach;
		long sellEach;
		/** After the 2% tax. */
		long profit;
		/** When it last filled, epoch ms; 0 = before the history was kept (filled earlier). */
		long at;
		/** The sell offer it came from: its fills grow ONE row, not a row per fill. */
		String offer;
		/** When what it sold was last bought, epoch ms; 0 = unknown — what FlipLearner looks back from. */
		long boughtAt;
		/** {@link Bought#heldMs} when it last filled; null = not measured (before 1.60.30). */
		Long heldMs;
	}

	/** The newest flips kept, per account. */
	static final int FLIPS_KEPT = 200;

	private Slot[] slots = new Slot[SLOTS];
	private Map<Integer, Bought> bought = new HashMap<>();
	/**
	 * Item -> {window start, units bought in it}: the GE buy limit. The 4-hour window starts at the
	 * FIRST item bought, is not extended by later buys, and then resets completely (OSRS wiki,
	 * "Grand Exchange/Buying limits", read 2026-10-31).
	 */
	private Map<Integer, long[]> limitUse = new HashMap<>();
	/**
	 * Items the user chose to HOLD (the card's Hold button) — kept in the inventory, not sold at a loss, no time limit,
	 * and never forgotten after HOLD_MS. Cleared when it is all sold, or by "Stop holding". User, 2026-11-01: "on tha
	 * card there needs to be somewhere next to placed and skip that says hold. so i can manually hold something i dont
	 * want to take a loss on".
	 */
	private Set<Integer> held = new HashSet<>();
	/** Swing buys (Swing): item -> {target price, until epoch ms} — held for the target, then like any other buy. */
	private Map<Integer, long[]> swing = new HashMap<>();
	/**
	 * Held items in the BANK as it was last seen: item -> how many. Kept between logins, so a long-term hold banked
	 * counts before the bank is opened again (user, 2026-11-03, the Robin Hood hat banked: "that should cound as money
	 * towards the cash total it still says bank 0" — the bank had not been opened since the login).
	 */
	private Map<Integer, Integer> banked = new HashMap<>();
	/**
	 * Items bought to ALCH — a High alch mode buy at a price that pays to alch, or the user's "Save for alching": never
	 * offered for sale nor held to sell (user, 2026-11-03: "there needs to be a right click save it for alch or bought
	 * alchs need to not be recomedded to be sold"). Gone when none of it is left.
	 */
	private Set<Integer> forAlch = new HashSet<>();
	/**
	 * Item -> how many of it in the inventory are the player's OWN: taken out of the bank beyond the flip units there
	 * (Bought.inBank). Never offered for sale. User, 2026-11-05: 4,000 of their own Chaos runes, taken out of the bank,
	 * got a sell card from a record that was wrong (4,040 "unsold" since a stale save was loaded at a login on 10-31).
	 */
	private Map<Integer, Long> own = new HashMap<>();
	/**
	 * True once the bank was seen with these rules (1.60.29): the flip units then in the bank were marked (traceBank) —
	 * before that, nothing is dropped as untraced.
	 */
	private Boolean traced;
	/** Newest last. */
	private List<Flip> flips = new ArrayList<>();
	/** {slot 0-7, profit} of each sell fill since the last {@link #takeDrops} — the slot flash. Not saved. */
	private transient List<long[]> drops;
	/** Flip rows made or grown since the last {@link #takeFresh} — for the plugin's flip archive (all time). Not saved. */
	private transient List<Flip> fresh;
	/**
	 * What each slot held just before it went EMPTY, and when (Slot.loweredFrom): "Modify offer" goes CANCELLED → EMPTY
	 * → the re-placed offer (journal 2026-11-03 20:23:21 → 20:23:22.470 EMPTY → 20:23:26 SELLING at the lower price).
	 * Not saved.
	 */
	private transient Slot[] gone;
	private transient long[] goneAt;
	/** A re-placed offer within this long of its slot emptying is the same offer modified. */
	static final long MODIFY_GAP_MS = 120_000;
	/** Null in a log saved before flips were kept: fix() fills in what is already selling. */
	private Integer version;
	/** When it was last saved, epoch ms — the newer of the two saved copies wins at load (FlipSidebarPlugin.tradeLog). */
	long savedAt;

	/**
	 * One slot changed. Works out how many filled since it was last seen, and records them as
	 * bought (a buy) or takes them off what is held (a sell). Returns true when something changed
	 * and the log should be saved.
	 */
	boolean update(int slot, boolean empty, boolean buy, int itemId, long price, int total, int sold, long spent,
		long nowMs)
	{
		fix();
		if (slot < 0 || slot >= SLOTS)
		{
			return false;
		}
		Slot prev = slots[slot];
		if (empty)
		{
			if (prev != null)
			{
				if (gone == null)
				{
					gone = new Slot[SLOTS];
					goneAt = new long[SLOTS];
				}
				gone[slot] = prev;
				goneAt[slot] = nowMs;
			}
			slots[slot] = null;
			return prev != null;
		}
		int dq;
		long ds;
		if (prev != null && prev.itemId == itemId && prev.buy == buy && prev.price == price && prev.total == total)
		{
			dq = sold - prev.sold;
			ds = spent - prev.spent;
			if (dq == 0 && ds == 0)
			{
				return false;         // the login burst re-sends every slot unchanged
			}
		}
		else
		{
			dq = sold;                // a new offer in this slot
			ds = spent;
		}
		if (dq > 0 && buy)
		{
			addBought(itemId, dq, ds, nowMs);
		}
		else if (dq > 0)
		{
			Bought b = bought.get(itemId);
			if (b != null)
			{
				long n = Math.min(dq, b.qty);
				record(slot, itemId, price, total, n, b.qty > 0 ? b.cost / b.qty : 0, ds / dq, nowMs, b.lastBuy, b.heldMs);
				b.cost -= b.qty > 0 ? b.cost * n / b.qty : 0;
				b.qty -= n;
				b.inBank = Math.min(b.inBank, Math.max(0, b.qty));     // a sell takes from the inventory, never the bank
				if (b.qty <= 0)
				{
					bought.remove(itemId);
					held.remove(itemId);
					swing.remove(itemId);
					forAlch.remove(itemId);
				}
			}
		}
		Slot s = new Slot();
		s.itemId = itemId;
		s.buy = buy;
		s.price = price;
		s.total = total;
		s.sold = sold;
		s.spent = spent;
		boolean same = prev != null && prev.itemId == itemId && prev.buy == buy && prev.price == price
			&& prev.total == total;
		// a new offer seen with nothing filled was just placed; one already part-filled when first
		// seen was there before it was tracked — its times are unknown, not "now"
		s.placedAt = same ? prev.placedAt : sold == 0 ? nowMs : 0;
		s.lastFill = !same ? 0 : dq > 0 ? nowMs : prev.lastFill;
		// "Modify offer": the slot goes CANCELLED → EMPTY → the re-placed offer (the 1.60.8 comment said "no empty slot
		// between" — WRONG, that journal view had left out the EMPTY rows, item 0), so the offer it came from is the one
		// before the empty slot when that was just now
		Slot before = prev != null ? prev
			: gone != null && gone[slot] != null && nowMs - goneAt[slot] <= MODIFY_GAP_MS ? gone[slot] : null;
		s.loweredFrom = same ? prev.loweredFrom
			: before != null && before.itemId == itemId && !buy && !before.buy && price < before.price ? before.price : 0;
		// its fills as seen live: the first mark is where it stood when it was first seen (placed: 0 sold)
		s.marks = new ArrayList<>(same && prev.marks != null ? prev.marks : List.of());
		if (s.marks.isEmpty() || dq > 0)
		{
			s.marks.add(new long[]{nowMs, sold});
		}
		while (s.marks.size() > 2 && s.marks.get(1)[0] <= nowMs - RECENT_MS)
		{
			s.marks.remove(0);    // the newest mark at or before RECENT_MS ago stays: the pace is measured from it
		}
		slots[slot] = s;
		// a flip in the bank is kept however long the break (Bought.inBank), like a hold
		bought.entrySet().removeIf(e -> !held.contains(e.getKey()) && e.getValue().inBank <= 0
			&& nowMs - e.getValue().lastBuy > KEEP_MS);
		limitUse.values().removeIf(w -> nowMs - w[0] >= LIMIT_MS);
		return true;
	}

	/** [item id, quantity, average paid each, last buy fill (epoch ms)] of everything bought in the last 12h and not sold. */
	/**
	 * What is held of an item can be no more than the player has: cut the record to {@code have} (the
	 * average paid stays). For a sale the plugin never saw — filled or collected while it was not running.
	 * True when the record changed.
	 */
	boolean trim(int itemId, long have)
	{
		Bought b = bought.get(itemId);
		if (b == null || b.qty <= have)
		{
			return false;
		}
		if (have <= 0)
		{
			bought.remove(itemId);
			held.remove(itemId);
			swing.remove(itemId);
			forAlch.remove(itemId);
		}
		else
		{
			b.cost = b.cost / b.qty * have;
			b.qty = have;
			b.inBank = Math.min(b.inBank, have);
		}
		return true;
	}

	List<long[]> holdings(long nowMs)
	{
		return holdings(nowMs, Set.of());
	}

	/** ...and, bought longer ago, what is still {@code here} (in the inventory or a GE offer): its price still counts. */
	List<long[]> holdings(long nowMs, Set<Integer> here)
	{
		fix();
		List<long[]> out = new ArrayList<>();
		for (Map.Entry<Integer, Bought> e : bought.entrySet())
		{
			Bought b = e.getValue();
			if (b.qty > 0 && (nowMs - b.lastBuy <= HOLD_MS || held.contains(e.getKey()) || here.contains(e.getKey())
				|| b.inBank > 0))
			{
				out.add(new long[]{e.getKey(), b.qty, b.cost / b.qty, b.lastBuy});
			}
		}
		return out;
	}

	/** [item id, quantity, average paid each, last buy fill (epoch ms)] of every unsold buy kept (KEEP_MS), shown or not. */
	List<long[]> kept()
	{
		fix();
		List<long[]> out = new ArrayList<>();
		for (Map.Entry<Integer, Bought> e : bought.entrySet())
		{
			Bought b = e.getValue();
			if (b.qty > 0)
			{
				out.add(new long[]{e.getKey(), b.qty, b.cost / b.qty, b.lastBuy});
			}
		}
		return out;
	}

	/** Item -> {units bought in its open buy-limit window, when that window resets (epoch ms)}. */
	Map<Integer, long[]> limitUse(long nowMs)
	{
		fix();
		Map<Integer, long[]> out = new HashMap<>();
		for (Map.Entry<Integer, long[]> e : limitUse.entrySet())
		{
			long[] w = e.getValue();
			if (nowMs - w[0] < LIMIT_MS)
			{
				out.put(e.getKey(), new long[]{w[1], w[0] + LIMIT_MS});
			}
		}
		return out;
	}

	/** {item id, units bought} of the BUY offer in {@code slot} (0-7) as last seen; null when the slot holds no buy. */
	long[] buyIn(int slot)
	{
		fix();
		Slot s = slot >= 0 && slot < SLOTS ? slots[slot] : null;
		return s == null || !s.buy ? null : new long[]{s.itemId, s.sold};
	}

	/** {placed, last fill} of the offer in {@code slot} (0-7), epoch ms (0 = unknown); null if none. */
	long[] times(int slot)
	{
		fix();
		Slot s = slot >= 0 && slot < SLOTS ? slots[slot] : null;
		return s == null ? null : new long[]{s.placedAt, s.lastFill};
	}

	/**
	 * Units the offer in {@code slot} (0-7) filled in the last {@link #RECENT_MS} — from where it stood then, or when it
	 * was first seen if later; -1 = not known (none, or not seen since this login: its fills are not saved).
	 */
	long recentFilled(int slot, long nowMs)
	{
		fix();
		Slot s = slot >= 0 && slot < SLOTS ? slots[slot] : null;
		if (s == null || s.marks == null || s.marks.isEmpty())
		{
			return -1;
		}
		long base = s.marks.get(0)[1];
		for (long[] m : s.marks)
		{
			if (m[0] <= nowMs - RECENT_MS)
			{
				base = m[1];
			}
		}
		return Math.max(0, s.sold - base);
	}

	/** Slot.loweredFrom of the offer in {@code slot} (0-7); 0 if none. */
	long loweredFrom(int slot)
	{
		fix();
		Slot s = slot >= 0 && slot < SLOTS ? slots[slot] : null;
		return s == null ? 0 : s.loweredFrom;
	}

	/** What was paid each for {@code itemId} still held, or 0 when not known. */
	long paidEach(int itemId)
	{
		fix();
		Bought b = bought.get(itemId);
		return b == null || b.qty <= 0 ? 0 : b.cost / b.qty;
	}

	/** The sell fills seen since the last call, {slot 0-7, profit after tax} — then forgotten. */
	/** The flip rows made or grown since the last call (the archive appends them). */
	List<Flip> takeFresh()
	{
		List<Flip> out = fresh == null ? List.of() : fresh;
		fresh = null;
		return out;
	}

	private void fresh(Flip f)
	{
		if (fresh == null)
		{
			fresh = new ArrayList<>();
		}
		if (!fresh.contains(f))
		{
			fresh.add(f);
		}
	}

	/** A flip row's identity in the archive: its offer and when it was bought (a row grows as its sell fills). */
	static String archiveKey(Flip f)
	{
		return (f.offer == null ? f.itemId + ":" + f.at : f.offer) + "@" + f.boughtAt;
	}

	List<long[]> takeDrops()
	{
		List<long[]> out = drops == null ? List.of() : drops;
		drops = null;
		return out;
	}

	/** Flips, newest first. */
	List<Flip> flips()
	{
		fix();
		List<Flip> out = new ArrayList<>(flips);
		Collections.reverse(out);
		return out;
	}

	/** {@code n} sold at {@code sellEach} (before tax) against {@code buyEach} paid — added to its offer's row. */
	/** {@code qty} bought for {@code cost} in all: held, and counted against the 4-hour buy limit. */
	private void addBought(int itemId, long qty, long cost, long nowMs)
	{
		Bought b = bought.computeIfAbsent(itemId, k -> new Bought());
		b.qty += qty;
		b.cost += cost;
		b.lastBuy = nowMs;
		b.heldMs = 0L;            // "sat" counts from the last buy, like Flip.boughtAt
		long[] w = limitUse.get(itemId);
		if (w == null || nowMs - w[0] >= LIMIT_MS)
		{
			w = new long[]{nowMs, 0};
			limitUse.put(itemId, w);
		}
		w[1] += qty;
	}

	/**
	 * An offer the game filled and paid out WITHOUT ever showing it in a GE slot. In-game 2026-11-01 a Dark bow sold at
	 * 447,993 (439,034 after tax) and no slot event came at all — not even RuneLite's own (GrandExchangePlugin's "GE
	 * offer updated" log): the bow left the inventory the tick after Confirm, and History never had the flip. Seen as
	 * the item leaving (a sell) or arriving (a buy) and the coins moving right after an offer was confirmed
	 * (FlipSidebarPlugin.watchInstant). {@code coins}: received after tax (a sell) or paid (a buy). True when recorded.
	 */
	boolean instant(boolean buy, int itemId, long qty, long coins, long nowMs)
	{
		fix();
		if (qty <= 0 || coins <= 0)
		{
			return false;
		}
		if (buy)
		{
			addBought(itemId, qty, coins, nowMs);
			return true;
		}
		Bought b = bought.get(itemId);
		if (b == null || b.qty <= 0)
		{
			return false;             // nothing on record to have sold
		}
		long n = Math.min(qty, b.qty);
		long gross = FlipEngine.breakEven(coins / qty - 1, itemId);       // the price before tax that pays this after it
		record(-1, itemId, gross, (int) Math.min(qty, Integer.MAX_VALUE), n, b.cost / b.qty, gross, nowMs, b.lastBuy, b.heldMs);
		b.cost -= b.cost * n / b.qty;
		b.qty -= n;
		b.inBank = Math.min(b.inBank, Math.max(0, b.qty));
		if (b.qty <= 0)
		{
			bought.remove(itemId);
			held.remove(itemId);
			swing.remove(itemId);
			forAlch.remove(itemId);
		}
		return true;
	}

	/** A swing buy was placed: hold its item for {@code target} until {@code until} (epoch ms). */
	void markSwing(int itemId, long target, long until)
	{
		fix();
		swing.put(itemId, new long[]{target, until});
	}

	/** A buy offer of the item is in a slot (any state until it is collected). */
	private boolean buying(int itemId)
	{
		for (Slot s : slots)
		{
			if (s != null && s.buy && s.itemId == itemId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Item -> {target, until} of the swing buys still on record. Dropped a day past their wait, or when the buy got
	 * nothing: no units on record and no buy of the item in a slot, SWING_GRACE_MS after it was placed (the card's Placed
	 * marks it before the GE shows the offer). 2026-11-01: 8 of 22 swing buys were cancelled empty and their marks stayed
	 * up to 8h — a normal buy of the item would have waited in Holding for the swing's target.
	 */
	Map<Integer, long[]> swing(long nowMs)
	{
		fix();
		swing.entrySet().removeIf(e -> nowMs > e.getValue()[1] + 24 * 3_600_000L
			|| (!bought.containsKey(e.getKey()) && !buying(e.getKey())
				&& nowMs - (e.getValue()[1] - Swing.HOLD_MS) > SWING_GRACE_MS));
		Map<Integer, long[]> out = new HashMap<>();
		swing.forEach((k, v) -> out.put(k, v.clone()));
		return out;
	}

	/** Hold {@code itemId} (it must be on record as bought). True when it changed. */
	boolean hold(int itemId)
	{
		fix();
		return bought.containsKey(itemId) && held.add(itemId);
	}

	/** Stop holding {@code itemId}. True when it changed. */
	boolean release(int itemId)
	{
		fix();
		return held.remove(itemId);
	}

	Set<Integer> held()
	{
		fix();
		return Collections.unmodifiableSet(new HashSet<>(held));
	}

	/** Items bought to alch (never offered for sale). */
	Set<Integer> forAlch()
	{
		fix();
		return Collections.unmodifiableSet(new HashSet<>(forAlch));
	}

	/** Mark {@code itemId} bought to alch, or not. True when that changed. */
	boolean setForAlch(int itemId, boolean on)
	{
		fix();
		return on ? forAlch.add(itemId) : forAlch.remove(itemId);
	}

	/** Held items in the bank as last seen (any login); only the ones still held. */
	Map<Integer, Integer> banked()
	{
		fix();
		Map<Integer, Integer> out = new HashMap<>(banked);
		out.keySet().retainAll(held);
		return out;
	}

	/** The bank was seen: its held items now. True when that changed what is kept. */
	boolean setBanked(Map<Integer, Integer> now)
	{
		fix();
		if (banked.equals(now))
		{
			return false;
		}
		banked = new HashMap<>(now);
		return true;
	}

	// ── where the flip units are: the bank (Bought.inBank) and the player's own units in the inventory (own) ───────

	/**
	 * {@code n} of the item went from the inventory into the bank, seen with the bank open — or {@code fromInventory}
	 * false: a buy collected straight to the bank. The player's own units taken out before go back first (own); the rest
	 * are flip units in the bank, up to the record, and the item is held long-term (held). Alch buys are not flip units.
	 * True when that changed.
	 */
	boolean deposited(int itemId, long n, boolean fromInventory)
	{
		fix();
		if (n <= 0)
		{
			return false;
		}
		long mine = fromInventory ? Math.min(n, own.getOrDefault(itemId, 0L)) : 0;
		if (mine > 0)
		{
			own.merge(itemId, -mine, Long::sum);
			own.values().removeIf(v -> v <= 0);
		}
		Bought b = bought.get(itemId);
		if (b == null || forAlch.contains(itemId) || n - mine <= 0)
		{
			return mine > 0;
		}
		long was = b.inBank;
		b.inBank = Math.min(b.qty, b.inBank + n - mine);
		// a flip item banked is a LONG-TERM HOLD at once (user, 2026-11-05: "They should automatically get a hold card
		// too"): never sold under what was paid, kept however long the break; "Stop holding" undoes it
		boolean nowHeld = b.inBank > 0 && held.add(itemId);
		return mine > 0 || b.inBank != was || nowHeld;
	}

	/**
	 * {@code n} of the item came out of the bank into the inventory (seen with the bank open): the flip units there
	 * first — they can be sold again — and the rest are the player's OWN (never offered for sale). True when changed.
	 */
	boolean withdrawn(int itemId, long n)
	{
		fix();
		if (n <= 0)
		{
			return false;
		}
		Bought b = bought.get(itemId);
		long flip = b == null ? 0 : Math.min(n, b.inBank);
		if (b != null)
		{
			b.inBank -= flip;
		}
		if (n - flip > 0)
		{
			own.merge(itemId, n - flip, Long::sum);
		}
		return true;
	}

	/**
	 * What is where now: {@code inventory} (unnoted id -> count, this login), {@code bank} (the same, null when it is not
	 * open). The flip units in the bank can be no more than the bank holds, the player's own units in the inventory no
	 * more than it holds — the rest were used, dropped or sold. True when that changed.
	 */
	boolean place(Map<Integer, Integer> inventory, Map<Integer, Integer> bank)
	{
		fix();
		boolean changed = false;
		for (Map.Entry<Integer, Long> e : own.entrySet())
		{
			long have = inventory.getOrDefault(e.getKey(), 0);
			if (e.getValue() > have)
			{
				e.setValue(have);
				changed = true;
			}
		}
		own.values().removeIf(v -> v <= 0);
		if (bank != null)
		{
			for (Map.Entry<Integer, Bought> e : bought.entrySet())
			{
				Bought b = e.getValue();
				long there = bank.getOrDefault(e.getKey(), 0);
				if (b.inBank > there)
				{
					b.inBank = there;
					changed = true;
				}
			}
		}
		return changed;
	}

	/**
	 * The player says the item on record is theirs, not a flip (a Holding row's "These are mine"): its record, hold, swing
	 * and alch marks go; flips already finished stay in the history. True when there was a record.
	 */
	boolean forget(int itemId)
	{
		fix();
		boolean had = bought.remove(itemId) != null;
		held.remove(itemId);
		swing.remove(itemId);
		forAlch.remove(itemId);
		banked.remove(itemId);
		return had;
	}

	/** Item -> the player's own units in the inventory (never offered for sale). */
	Map<Integer, Long> own()
	{
		fix();
		return new HashMap<>(own);
	}

	/** Item -> the flip units in the bank (Bought.inBank), alch buys left out. */
	Map<Integer, Long> inBank()
	{
		fix();
		Map<Integer, Long> out = new HashMap<>();
		bought.forEach((id, b) ->
		{
			if (b.inBank > 0 && b.qty > 0 && !forAlch.contains(id))
			{
				out.put(id, Math.min(b.inBank, b.qty));
			}
		});
		return out;
	}

	/**
	 * The first time the bank is seen with these rules (a log saved before 1.60.29): what is on record and not
	 * {@code here} (the inventory's flip units and the GE offers) is taken as in the bank, up to what the bank holds —
	 * banked before the bank was watched. True when it ran.
	 */
	boolean traceBank(Map<Integer, Long> here, Map<Integer, Integer> bank)
	{
		fix();
		if (Boolean.TRUE.equals(traced))
		{
			return false;
		}
		traced = true;
		bought.forEach((id, b) ->
		{
			if (!forAlch.contains(id))
			{
				long away = Math.max(0, b.qty - here.getOrDefault(id, 0L));
				b.inBank = Math.max(b.inBank, Math.min(away, bank.getOrDefault(id, 0)));
			}
		});
		return true;
	}

	/**
	 * ⭐ A record's units found NOWHERE — not {@code here} (the inventory's flip units, the GE offers), not seen going
	 * into the bank — {@link #HOLD_MS} after its last buy are dropped: sold, used or moved where the plugin did not see
	 * (another device, a stale save). Not the user's holds nor alch buys; not before the bank was first seen
	 * (traceBank). User, 2026-11-05: a Chaos rune record said 4,040 unsold for five days — loaded from a stale save at a
	 * login on 10-31, every later Chaos flip merged into it (its last buy refreshed), and the player's own runes in the
	 * bank hid it from trim — until it offered 4,000 of the player's own runes for sale. True when it changed.
	 */
	boolean dropUntraced(Map<Integer, Long> here, long nowMs)
	{
		fix();
		if (!Boolean.TRUE.equals(traced))
		{
			return false;
		}
		boolean changed = false;
		for (java.util.Iterator<Map.Entry<Integer, Bought>> it = bought.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Integer, Bought> e = it.next();
			Bought b = e.getValue();
			int id = e.getKey();
			if (held.contains(id) || forAlch.contains(id) || nowMs - b.lastBuy <= HOLD_MS)
			{
				continue;
			}
			long keep = Math.min(b.qty, here.getOrDefault(id, 0L) + b.inBank);
			if (keep >= b.qty)
			{
				continue;
			}
			changed = true;
			if (keep <= 0)
			{
				it.remove();
				swing.remove(id);
				continue;
			}
			b.cost = b.cost / b.qty * keep;
			b.qty = keep;
			b.inBank = Math.min(b.inBank, keep);
		}
		return changed;
	}

	/** One game tick: 0.6 seconds of the server's time. */
	static final long TICK_MS = 600;

	/**
	 * One game tick LOGGED IN: every buy with some of it unsold outside the bank adds {@link #TICK_MS} to its
	 * {@link Bought#heldMs} — not one the user chose to keep: a Hold (no time limit), a swing buy waiting for its target,
	 * a buy to alch. True when one counted. Not saved by itself (FlipSidebarPlugin saves it every few minutes). Client
	 * thread.
	 */
	boolean tickHeld(long nowMs)
	{
		fix();
		boolean any = false;
		for (Map.Entry<Integer, Bought> e : bought.entrySet())
		{
			int id = e.getKey();
			Bought b = e.getValue();
			long[] sw = swing.get(id);
			if (b.qty <= b.inBank || held.contains(id) || forAlch.contains(id) || (sw != null && sw[1] > nowMs))
			{
				continue;
			}
			b.heldMs = (b.heldMs == null ? 0 : b.heldMs) + TICK_MS;
			any = true;
		}
		return any;
	}

	private void record(int slot, int itemId, long price, int total, long n, long buyEach, long sellEach, long nowMs,
		long boughtAt, Long heldMs)
	{
		if (n <= 0)
		{
			return;
		}
		String key = slot < 0 ? "instant:" + itemId + ":" + nowMs : slot + ":" + itemId + ":" + price + ":" + total;
		long profit = (sellEach - FlipEngine.tax(sellEach, itemId) - buyEach) * n;
		if (nowMs > 0 && slot >= 0)   // a live fill in a slot, not one filled in from an older save (or never in a slot)
		{
			if (drops == null)
			{
				drops = new ArrayList<>();
			}
			drops.add(new long[]{slot, profit});
		}
		for (int i = flips.size() - 1; i >= Math.max(0, flips.size() - 16); i--)
		{
			if (key.equals(flips.get(i).offer))
			{
				Flip f = flips.remove(i);
				f.sellEach = (f.sellEach * f.qty + sellEach * n) / (f.qty + n);
				f.qty += n;
				f.profit += profit;
				f.at = nowMs;
				f.heldMs = heldMs;
				flips.add(f);
				fresh(f);
				return;
			}
		}
		Flip f = new Flip();
		f.itemId = itemId;
		f.qty = n;
		f.buyEach = buyEach;
		f.sellEach = sellEach;
		f.profit = profit;
		f.at = nowMs;
		f.offer = key;
		f.boughtAt = boughtAt;
		f.heldMs = heldMs;
		flips.add(f);
		fresh(f);
		while (flips.size() > FLIPS_KEPT)
		{
			flips.remove(0);
		}
	}

	/** A log loaded from an old or hand-edited save can miss fields. */
	private void fix()
	{
		if (slots == null || slots.length != SLOTS)
		{
			slots = new Slot[SLOTS];
		}
		if (bought == null)
		{
			bought = new HashMap<>();
		}
		if (limitUse == null)
		{
			limitUse = new HashMap<>();
		}
		if (held == null)
		{
			held = new HashSet<>();
		}
		if (swing == null)
		{
			swing = new HashMap<>();
		}
		if (banked == null)
		{
			banked = new HashMap<>();
		}
		if (forAlch == null)
		{
			forAlch = new HashSet<>();
		}
		if (own == null)
		{
			own = new HashMap<>();
		}
		if (flips == null)
		{
			flips = new ArrayList<>();
		}
		if (version == null)
		{
			// saved before flips were kept: the sells already filling are flips too, against what
			// is still held (selling does not change the average paid)
			version = 1;
			for (int i = 0; i < SLOTS; i++)
			{
				Slot sl = slots[i];
				Bought b = sl == null || sl.buy || sl.sold <= 0 ? null : bought.get(sl.itemId);
				if (b != null && b.qty > 0)
				{
					record(i, sl.itemId, sl.price, sl.total, sl.sold, b.cost / b.qty, sl.spent / sl.sold, 0, b.lastBuy, b.heldMs);
				}
			}
		}
	}
}
