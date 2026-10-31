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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plugin's own brain, used while "Live prices" is on, so no program has to run beside the
 * game: it scans the GE every 3 minutes (FlipEngine), keeps ONE flip on the card until you place
 * or skip it, puts "sell what you just bought" first, and judges your open offers (OfferAdvice).
 * A port of the Flip Sidebar's card logic (flip_sidebar.py: _current, _draw_holdings, placed,
 * skip, _install_scan).
 *
 * <p>Threads: network work runs on this class's own single thread; the game hands in snapshots
 * from the client thread; the panel's buttons call in from Swing. Every field below is guarded by
 * {@code this}; the listener is always called outside the lock.</p>
 */
@Singleton
class FlipBrain
{
	private static final Logger log = LoggerFactory.getLogger(FlipBrain.class);
	private static final long TICK_MS = 5_000;
	private static final long SCAN_EVERY_MS = 180_000;
	/** After a failed scan or with no coins seen, look again this soon instead of in 3 minutes. */
	private static final long RETRY_MS = 30_000;
	private static final long PRICES_EVERY_MS = 60_000;
	/** A sell you pressed Placed on stays off the card this long — until the GE shows the offer. */
	private static final long SOLD_HOLD_MS = 180_000;
	/** A card older than this says "prices may have moved". */
	static final long STALE_MS = 120_000;
	private static final int MAX_RESEARCH = 150;
	private static final int GE_SLOTS = 8;
	/** An item that went south is not suggested again for this long (judgement: the trend broke). */
	private static final long SOUTH_MS = 60 * 60_000L;
	/** A pick older than this is re-priced on the latest trades when it becomes the card. */
	private static final long REPRICE_AFTER_S = 30;
	private static final int NEXT_SHOWN = 6;

	/** Everything the panel shows, made in one go under the lock. */
	static final class View
	{
		final boolean live;
		final boolean loggedIn;
		final String status;
		/** The card; null while live prices are off. */
		final Suggestion card;
		/** Under the card: gp/hr, ROI and gp in — or what was paid, for a sell. */
		final String cardSub;
		/** Why this card: "kept until you place or skip", "selling first frees cash ...". */
		final String cardHint;
		final long inventoryCoins;
		/** -1 = the bank has not been open since the plugin started tracking it. */
		final long bankCoins;
		final List<OfferAdvice> offers;
		/** GE slots (1-8) whose offer the advice says to cancel — painted red in the GE. */
		final Set<Integer> cancelSlots;
		/** GE slots (1-8) whose offer the advice says to RAISE or LOWER — painted amber in the GE. */
		final Set<Integer> adjustSlots;
		final List<Pick> next;
		/** Finished flips, newest first — profit or LOSS each. */
		final List<GameSnapshot.Done> history;
		/** Bought and not sold, valued at today's 5-minute average insta-buy. */
		final List<Held> held;
		/** Coins still tied up in the unfilled part of buy offers. */
		final long inBuys;
		final long sessionStart;
		final boolean paused;
		final int blocked;
		/** The "Use at most" cash setting; 0 = all coins. */
		final long cashCap;
		/** {left, limit, resets at} of the card's item when it is a BUY; null otherwise or unknown. */
		final long[] cardLimit;
		/** {left, limit, resets at} of every item in an offer or held — the panel's rows; unknown ones absent. */
		final Map<Integer, long[]> limits;
		final long scannedAt;
		final boolean scanning;

		View(boolean live, boolean loggedIn, String status, Suggestion card, String cardSub, String cardHint,
			long inventoryCoins, long bankCoins, List<OfferAdvice> offers, List<Pick> next, long scannedAt,
			boolean scanning)
		{
			this(live, loggedIn, status, card, cardSub, cardHint, inventoryCoins, bankCoins, offers, next,
				List.of(), List.of(), 0, 0, false, 0, 0, null, Map.of(), scannedAt, scanning);
		}

		View(boolean live, boolean loggedIn, String status, Suggestion card, String cardSub, String cardHint,
			long inventoryCoins, long bankCoins, List<OfferAdvice> offers, List<Pick> next,
			List<GameSnapshot.Done> history, List<Held> held, long inBuys, long sessionStart, boolean paused,
			int blocked, long cashCap, long[] cardLimit, Map<Integer, long[]> limits, long scannedAt, boolean scanning)
		{
			this.cardLimit = cardLimit;
			this.limits = limits;
			this.history = history;
			this.held = held;
			this.inBuys = inBuys;
			this.sessionStart = sessionStart;
			this.paused = paused;
			this.blocked = blocked;
			this.cashCap = cashCap;
			this.live = live;
			this.loggedIn = loggedIn;
			this.status = status;
			this.card = card;
			this.cardSub = cardSub;
			this.cardHint = cardHint;
			this.inventoryCoins = inventoryCoins;
			this.bankCoins = bankCoins;
			this.offers = offers;
			Set<Integer> cancel = new HashSet<>();
			Set<Integer> raise = new HashSet<>();
			for (OfferAdvice a : offers)
			{
				if (live && OfferAdvice.CANCEL.equals(a.verdict))
				{
					cancel.add(a.offer.slot);
				}
				else if (live && a.adjust())
				{
					raise.add(a.offer.slot);
				}
			}
			this.cancelSlots = Collections.unmodifiableSet(cancel);
			this.adjustSlots = Collections.unmodifiableSet(raise);
			this.next = next;
			this.scannedAt = scannedAt;
			this.scanning = scanning;
		}
	}

	/** Something bought and not sold: what it is worth now. */
	static final class Held
	{
		final int itemId;
		final String name;
		final long qty;
		final long paidEach;
		/** Today's 5-minute average insta-buy; 0 = no trades to value it on. */
		final long market;
		/** If all sold at {@code market}, after the 2% tax, minus what was paid. */
		final long profit;

		Held(int itemId, String name, long qty, long paidEach, long market, long profit)
		{
			this.itemId = itemId;
			this.name = name;
			this.qty = qty;
			this.paidEach = paidEach;
			this.market = market;
			this.profit = profit;
		}
	}

	/** Something bought, in the inventory now, and what selling it would make. */
	private static final class SellRow
	{
		final GameSnapshot.Holding holding;
		final long qty;
		final long ask;
		final long each;
		/** The 5-minute insta-buy; below {@code ask} when the ask was raised to break-even. */
		final long market;
		/** Sold at the market at a loss: it went south. */
		final boolean cut;

		SellRow(GameSnapshot.Holding holding, long qty, long ask, long each, long market, boolean cut)
		{
			this.holding = holding;
			this.qty = qty;
			this.ask = ask;
			this.each = each;
			this.market = market;
			this.cut = cut;
		}
	}

	private final FlipEngine engine;
	private final WikiPrices prices;
	private final FlipSidebarConfig config;

	private ScheduledExecutorService exec;
	private ScheduledFuture<?> loop;
	private Consumer<View> listener;

	private GameSnapshot snap;
	/** The wiki's /5m averages — what prices are quoted from (FlipEngine.quote), and "the market now". */
	private JsonObject five;
	/** The wiki's /1h averages — the quote's fallback for a side with no trades in 5 minutes. */
	private JsonObject hour;
	private long pricesAt;
	private List<Pick> best = Collections.emptyList();
	private long scannedAt;
	private long nextScanAt;
	private boolean scanning;
	private boolean rescanAsked;
	private String status = "";
	private Set<String> ignored = Collections.emptySet();
	/** The item in the GE's offer set-up screen right now; -1 when it is not open. */
	private int settingUp = -1;

	/** The BUY on the card — locked until Placed, Skip, or its offer appears on the GE. */
	private Pick pin;
	/** "Do this one" from the list. */
	private Integer forced;
	private final Set<Integer> skipped = new HashSet<>();
	private final Set<Integer> placed = new HashSet<>();
	/** Picks that no longer work at today's prices — left out until the next scan. */
	private final Set<Integer> stale = new HashSet<>();
	private SellRow sellNow;
	/** The item of the SELL on the card, so the same sell stays up. */
	private Integer pinSell;
	private final Map<Integer, Long> soldAt = new HashMap<>();
	/** Item -> when its buy was told to CANCEL (gone south): sell it at the market, do not re-buy it soon. */
	private final Map<Integer, Long> south = new HashMap<>();
	private final Set<Integer> sellSkipped = new HashSet<>();

	private volatile Suggestion card;
	private volatile View view;

	@Inject
	FlipBrain(FlipEngine engine, WikiPrices prices, FlipSidebarConfig config)
	{
		this.engine = engine;
		this.prices = prices;
		this.config = config;
	}

	// ── life ────────────────────────────────────────────────────────────────────

	void start(Consumer<View> listener)
	{
		synchronized (this)
		{
			this.listener = listener;
			exec = Executors.newSingleThreadScheduledExecutor(r ->
			{
				Thread t = new Thread(r, "Flip Sidebar brain");
				t.setDaemon(true);
				return t;
			});
			loop = exec.scheduleWithFixedDelay(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
		}
		changed();
	}

	synchronized void stop()
	{
		if (loop != null)
		{
			loop.cancel(true);
			loop = null;
		}
		if (exec != null)
		{
			exec.shutdownNow();
			exec = null;
		}
		listener = null;
		snap = null;
		hour = null;
		five = null;
		pricesAt = 0;
		best = Collections.emptyList();
		scannedAt = 0;
		nextScanAt = 0;
		scanning = false;
		status = "";
		forgetCard();
		card = null;
		view = null;
	}

	/** A different account logged in: nothing on the card carries over. */
	void accountChanged()
	{
		synchronized (this)
		{
			best = Collections.emptyList();
			nextScanAt = 0;
			forgetCard();
		}
		changed();
	}

	private void forgetCard()
	{
		pin = null;
		forced = null;
		skipped.clear();
		placed.clear();
		stale.clear();
		sellNow = null;
		pinSell = null;
		soldAt.clear();
		sellSkipped.clear();
		south.clear();
	}

	Suggestion card()
	{
		return card;
	}

	View view()
	{
		return view;
	}

	// ── input: the game, the settings, the panel ────────────────────────────────

	/** The game's state now (client thread), or null when logged out. */
	void update(GameSnapshot s)
	{
		synchronized (this)
		{
			snap = s;
			if (s != null && pinActive())
			{
				for (GameSnapshot.Offer o : s.offers)
				{
					// the offer on the card is now on the GE: it is placed — move on by itself
					if (o.buy && o.itemId == pin.itemId && !ignored.contains(o.key()))
					{
						placed.add(pin.itemId);
						pin = null;
						forced = null;
						break;
					}
				}
			}
		}
		changed();
	}

	/** The item in the GE's offer set-up screen, every tick (client thread); -1 when it is closed. */
	void setSettingUp(int itemId)
	{
		synchronized (this)
		{
			if (settingUp == itemId)
			{
				return;
			}
			settingUp = itemId;
		}
		changed();
	}

	void setIgnored(Set<String> keys)
	{
		synchronized (this)
		{
			ignored = keys;
		}
		changed();
	}

	/** Risk, re-price window, minimum profit or live prices changed: scan again now. */
	void settingsChanged()
	{
		ScheduledExecutorService e;
		synchronized (this)
		{
			rescanAsked = true;
			e = exec;
		}
		if (e != null && !e.isShutdown())
		{
			e.execute(this::tick);
		}
		changed();
	}

	void placed()
	{
		synchronized (this)
		{
			if (sellNow != null)
			{
				// hold it back so it cannot bounce straight back onto the card before the GE shows the offer
				soldAt.put(sellNow.holding.itemId, System.currentTimeMillis());
				sellNow = null;
				pinSell = null;
			}
			else
			{
				Pick p = current();
				if (p != null)
				{
					placed.add(p.itemId);
					forced = null;
					pin = null;
				}
			}
		}
		changed();
	}

	void skip()
	{
		synchronized (this)
		{
			if (sellNow != null)
			{
				sellSkipped.add(sellNow.holding.itemId);     // a skipped sell stays dismissed this session
				sellNow = null;
				pinSell = null;
			}
			else
			{
				Pick p = current();
				if (p != null)
				{
					skipped.add(p.itemId);
					forced = null;
					pin = null;
				}
			}
		}
		changed();
	}

	/** Make a pick from the list the card. */
	void doThis(int itemId)
	{
		synchronized (this)
		{
			forced = itemId;
			pin = null;
			skipped.remove(itemId);
		}
		changed();
	}

	void rescan()
	{
		settingsChanged();
	}

	/** An item's price history for the chart, fetched on OkHttp's pool; {@code done} hears it. */
	void chart(int itemId, String range, Consumer<PriceHistory> done)
	{
		Object[] r = PriceHistory.RANGES.get(range);
		if (r == null)
		{
			return;
		}
		CompletableFuture<JsonElement> ts = prices.getAsync("/timeseries?timestep=" + r[0] + "&id=" + itemId);
		CompletableFuture<JsonElement> lt = prices.getAsync("/latest?id=" + itemId);
		CompletableFuture.allOf(ts, lt).thenRun(() ->
		{
			JsonObject data = lt.join().getAsJsonObject().getAsJsonObject("data");
			JsonElement one = data == null ? null : data.get(String.valueOf(itemId));
			done.accept(PriceHistory.of(itemId, range, ts.join().getAsJsonObject().getAsJsonArray("data"),
				one != null && one.isJsonObject() ? one.getAsJsonObject() : null, System.currentTimeMillis() / 1000));
		}).exceptionally(e ->
		{
			log.debug("Flip Sidebar: chart prices for {} failed", itemId, e);
			return null;
		});
	}

	/**
	 * A price for an item the user picked themselves in the GE — not the card's (user, 2026-10-31:
	 * "if i manually search an item i want a buy price for it. it needs to adapt like the others").
	 * A buy is judged like any pick (FlipEngine.judge): the same bid, ask and quantity, and if it is
	 * not a recommended flip the note says why. A sell asks one step under the insta-buy for all
	 * {@code have}, with the profit when what was paid is known ({@code basis} > 0). Fetched on
	 * OkHttp's pool; {@code done} hears nothing when the item has no price.
	 */
	void priceItem(int itemId, String name, boolean buy, int have, long basis, Consumer<Suggestion> done)
	{
		GameSnapshot s;
		JsonObject h;
		JsonObject f;
		synchronized (this)
		{
			s = snap;
			h = hour;
			f = five;
		}
		long budget = budget(s);
		RiskLevel risk = config.riskLevel();
		AdjustWindow window = config.adjustWindow();
		String key = String.valueOf(itemId);
		long[] q = FlipEngine.quote(item(h, key), item(f, key));
		if (q[0] <= 0 && q[1] <= 0)
		{
			return;                   // no trades in the last hour to price it on
		}
		long[] used = s == null ? null : s.limitUse.get(itemId);
		long left = FlipEngine.remaining(engine.limit(itemId), used == null ? 0 : used[0]);
		CompletableFuture<JsonElement> ts = buy ? prices.getAsync("/timeseries?timestep=5m&id=" + itemId)
			: CompletableFuture.completedFuture(null);
		ts.thenAccept(series ->
		{
			long nowS = System.currentTimeMillis() / 1000;
			Suggestion out = new Suggestion();
			out.manual = true;
			out.itemId = itemId;
			out.name = name;
			out.asOf = nowS;
			if (buy && left == 0)
			{
				// the GE will not sell more until the window resets: the one hard stop
				out.action = "BUY";
				out.price = (int) q[0];
				out.discouraged = true;
				out.note = "Buy limit reached - resets in " + FlipSidebarSlotOverlay.clock(used[1] - nowS * 1000);
			}
			else if (buy)
			{
				Research r = series == null ? null
					: Research.of(itemId, series.getAsJsonObject().getAsJsonArray("data"));
				Pick p = FlipEngine.judge(itemId, name, Math.max(0, left), q, budget, risk, window, r, nowS);
				if (p == null)
				{
					return;
				}
				out.action = "BUY";
				out.quantity = (int) p.qty;
				out.price = (int) p.bid;
				out.sellPrice = (int) p.ask;
				out.profit = p.total;
				out.fillMinutes = p.fillMinutes;
				out.note = p.rejected != null ? "Not a recommended flip: " + p.rejected
					: used != null && left > 0 ? String.format("limit %,d left · resets %s", left,
						FlipSidebarSlotOverlay.clock(used[1] - nowS * 1000))
					: p.firstWarning();
			}
			else
			{
				if (q[1] <= 0)
				{
					return;
				}
				// never under what was paid + tax (see sellCandidate)
				long ask = Math.max(q[1], basis > 0 ? FlipEngine.breakEven(basis, itemId) : 0);
				out.action = "SELL";
				out.quantity = have;
				out.price = (int) ask;
				out.profit = basis > 0 ? (ask - FlipEngine.tax(ask, itemId) - basis) * have : 0;
				out.fillMinutes = -1;
				out.note = basis <= 0 ? "no buy price on record - profit unknown"
					: ask > q[1] ? String.format("market %,d is under what you paid + tax - listed at break-even", q[1]) : "";
			}
			done.accept(out);
		}).exceptionally(e ->
		{
			log.debug("Flip Sidebar: pricing item {} failed", itemId, e);
			return null;
		});
	}

	/** What is left of the item's 4-hour buy limit now; -1 when its limit is unknown. Under the lock. */
	private long limitLeft(int itemId)
	{
		long[] u = snap == null ? null : snap.limitUse.get(itemId);
		return FlipEngine.remaining(engine.limit(itemId), u == null ? 0 : u[0]);
	}

	/** {used, resets at (epoch ms), limit} of an item's buy-limit window; null when none is open. */
	/**
	 * {left, limit, resets at (0 = no window open)} of an item's GE buy limit now; null when its limit
	 * is unknown (live prices off, or before the first scan loads the item list).
	 */
	synchronized long[] limitNow(int itemId)
	{
		return limitNowLocked(itemId);
	}

	/** The buy limit of every item in an offer or held. Under the lock. */
	private Map<Integer, long[]> rowLimits()
	{
		Map<Integer, long[]> out = new HashMap<>();
		if (snap != null)
		{
			for (GameSnapshot.Offer o : snap.offers)
			{
				long[] l = limitNowLocked(o.itemId);
				if (l != null)
				{
					out.put(o.itemId, l);
				}
			}
			for (GameSnapshot.Holding h : snap.holdings)
			{
				long[] l = limitNowLocked(h.itemId);
				if (l != null)
				{
					out.put(h.itemId, l);
				}
			}
		}
		return Collections.unmodifiableMap(out);
	}

	private long[] limitNowLocked(int itemId)
	{
		long limit = engine.limit(itemId);
		if (limit <= 0)
		{
			return null;
		}
		long[] u = snap == null ? null : snap.limitUse.get(itemId);
		return new long[]{Math.max(0, limit - (u == null ? 0 : u[0])), limit, u == null ? 0 : u[1]};
	}

	synchronized long[] limitInfo(int itemId)
	{
		long[] u = snap == null ? null : snap.limitUse.get(itemId);
		return u == null ? null : new long[]{u[0], u[1], engine.limit(itemId)};
	}

	/** "limit 9,000 left · resets 2h05", or "" when no window is open or the limit is unknown. */
	private String limitText(int itemId, long nowMs)
	{
		long[] u = snap == null ? null : snap.limitUse.get(itemId);
		long left = limitLeft(itemId);
		return u == null || left < 0 ? ""
			: String.format("limit %,d left · resets %s", left, FlipSidebarSlotOverlay.clock(u[1] - nowMs));
	}

	/** One item's record from a wiki /1h or /5m "data" object, or null. */
	private static JsonObject item(JsonObject data, String key)
	{
		JsonElement e = data == null ? null : data.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	// ── the loop, on the brain's own thread ─────────────────────────────────────

	private void tick()
	{
		try
		{
			step();
		}
		catch (RuntimeException e)
		{
			// a task that throws is never run again — keep the loop alive and say so
			log.warn("Flip Sidebar: brain step failed", e);
			status("Something went wrong - trying again");
		}
	}

	private void step()
	{
		if (!config.livePrices())
		{
			status("Live prices are off");
			return;
		}
		GameSnapshot s;
		boolean needLatest;
		boolean scanDue;
		long now = System.currentTimeMillis();
		synchronized (this)
		{
			s = snap;
			needLatest = hour == null || now - pricesAt > PRICES_EVERY_MS;
			scanDue = rescanAsked || now >= nextScanAt;
		}
		if (s == null)
		{
			status("Log in to start");
			return;
		}
		if (needLatest)
		{
			try
			{
				JsonObject h = prices.data("/1h");
				JsonObject f = prices.data("/5m");
				synchronized (this)
				{
					hour = h;
					five = f;
					pricesAt = now;
				}
				changed();
			}
			catch (IOException e)
			{
				log.debug("Flip Sidebar: /1h or /5m failed", e);
				status("Can't reach the OSRS Wiki prices - retrying");
				return;
			}
		}
		if (config.paused())
		{
			status("Paused - no new buys");
			return;
		}
		if (scanDue)
		{
			scan(s);
		}
	}

	/** The coins to plan with: all of them, or the "Use at most" setting if lower. */
	private long budget(GameSnapshot s)
	{
		long cap = Math.max(0, config.maxCash());
		return s == null ? 0 : cap > 0 ? Math.min(s.cash(), cap) : s.cash();
	}

	/** Items never to suggest (right-click "Never suggest" in the panel). */
	private Set<Integer> blocked()
	{
		return FlipSidebarConfig.parseBlocked(config.blockedItems());
	}

	private void scan(GameSnapshot s)
	{
		long budget = budget(s);
		if (budget <= 0)
		{
			synchronized (this)
			{
				rescanAsked = false;
				nextScanAt = System.currentTimeMillis() + RETRY_MS;
			}
			status(s.bankCoins < 0 ? "Open your bank once so I can see your coins" : "No coins to flip with");
			return;
		}
		Set<Integer> busy = new HashSet<>(blocked());
		for (GameSnapshot.Offer o : s.offers)
		{
			busy.add(o.itemId);
		}
		synchronized (this)
		{
			busy.addAll(south.keySet());          // gone south: not straight back in
		}
		Map<Integer, Long> used = new HashMap<>();
		s.limitUse.forEach((id, u) -> used.put(id, u[0]));
		RiskLevel risk = config.riskLevel();
		AdjustWindow window = config.adjustWindow();
		long minProfit = Math.max(0, config.minProfit());
		synchronized (this)
		{
			scanning = true;
			rescanAsked = false;
			status = "Scanning the GE...";
		}
		changed();
		try
		{
			List<Pick> found = engine.scan(budget, risk, window, minProfit, busy, used, MAX_RESEARCH, new FlipEngine.Progress()
			{
				@Override
				public void checked(int done, int total)
				{
					status("Checking " + done + "/" + total + " items");
				}

				@Override
				public void found(Pick p)
				{
					earlyPick(p);
				}
			});
			synchronized (this)
			{
				best = found;
				skipped.clear();
				stale.clear();
				forced = null;
				scannedAt = System.currentTimeMillis();
				// settings changed mid-scan: go again at once
				nextScanAt = rescanAsked ? 0 : scannedAt + SCAN_EVERY_MS;
				scanning = false;
				status = found.size() + " worth doing (" + gp(budget) + " to spend)";
			}
		}
		catch (IOException e)
		{
			log.debug("Flip Sidebar: scan failed", e);
			synchronized (this)
			{
				scanning = false;
				nextScanAt = System.currentTimeMillis() + RETRY_MS;
				status = "Can't reach the OSRS Wiki prices - retrying";
			}
		}
		changed();
	}

	/** The first good pick goes up at once when the card is empty — an answer now, not in a minute. */
	private void earlyPick(Pick p)
	{
		synchronized (this)
		{
			if (sellNow != null || current() != null)
			{
				return;
			}
			best = Collections.singletonList(p);
		}
		changed();
	}

	private void status(String s)
	{
		synchronized (this)
		{
			if (s.equals(status))
			{
				return;
			}
			status = s;
		}
		changed();
	}

	// ── the card ────────────────────────────────────────────────────────────────

	private boolean pinActive()
	{
		return pin != null && !placed.contains(pin.itemId) && !skipped.contains(pin.itemId);
	}

	private Set<Integer> openItems()
	{
		Set<Integer> out = new HashSet<>();
		if (snap != null)
		{
			for (GameSnapshot.Offer o : snap.offers)
			{
				out.add(o.itemId);
			}
		}
		return out;
	}

	private List<Pick> pool()
	{
		Set<Integer> open = openItems();
		Set<Integer> blocked = blocked();
		List<Pick> out = new ArrayList<>();
		for (Pick p : best)
		{
			if (!skipped.contains(p.itemId) && !placed.contains(p.itemId) && !open.contains(p.itemId)
				&& !stale.contains(p.itemId) && !blocked.contains(p.itemId) && !south.containsKey(p.itemId))
			{
				out.add(p);
			}
		}
		return out;
	}

	/**
	 * The pick re-priced on the latest trades, at the moment it becomes the card — a scan's prices
	 * are minutes old by then (in-game 2026-10-31 a card that had just appeared already said "2m old
	 * - prices may have moved"). Null when it no longer works at today's prices. Once on the card
	 * it is not touched again: the card is locked while you set the offer up.
	 */
	private Pick fresh(Pick p)
	{
		long nowS = System.currentTimeMillis() / 1000;
		if (snap == null || hour == null || five == null || p.research == null || nowS - p.asOf < REPRICE_AFTER_S
			|| pricesAt / 1000 <= p.asOf)
		{
			return p;                 // young, or our prices are no newer than the scan's
		}
		String key = String.valueOf(p.itemId);
		long[] q = FlipEngine.quote(item(hour, key), item(five, key));
		if (q[0] <= 0 || q[1] <= 0)
		{
			return p;                 // no trades to price it on: keep the scan's prices
		}
		long left = limitLeft(p.itemId);
		if (left == 0)
		{
			return null;              // its buy limit was used up since the scan
		}
		Pick r = FlipEngine.evaluate(p.itemId, p.name, Math.max(0, left), q, budget(snap), config.riskLevel(),
			config.adjustWindow(), p.research, nowS);
		if (r == null || r.total < Math.max(0, config.minProfit()))
		{
			return null;
		}
		r.asOf = pricesAt / 1000;     // as old as the prices it was priced on
		return r;
	}

	/**
	 * The BUY on the card, or null when the card is a SELL.
	 * ⭐ ONCE SHOWN, A SUGGESTION IS LOCKED until Placed, Skip, or its offer appears on the GE.
	 * User, 2026-10-31: "half way threw setting it up the suggestions gone and i cant get it back."
	 */
	private Pick current()
	{
		if (sellNow != null)
		{
			return null;
		}
		if (forced != null && !placed.contains(forced))
		{
			if (pin != null && pin.itemId == forced)
			{
				return pin;           // already on the card: locked
			}
			for (Pick p : best)
			{
				if (p.itemId == forced)
				{
					Pick f = fresh(p);
					pin = f != null ? f : p;      // asked for by name: show it, its age line says how old
					return pin;
				}
			}
		}
		if (pinActive())
		{
			return pin;
		}
		pin = null;
		for (Pick p : pool())
		{
			Pick f = fresh(p);
			if (f != null)
			{
				pin = f;
				break;
			}
			stale.add(p.itemId);
		}
		return pin;
	}

	/**
	 * ⚠ THE MARGIN IS RE-CHECKED AT THE MOMENT OF BUYING, not only at the scan. MEASURED 2026-10-31
	 * on 101 Copilot flips + 33 of the user's: flips bought when the margin after tax was already
	 * gone in the latest 5-minute prices lost 37% of the time, -4.5m in all (Rune arrow bought into
	 * a crossed 38 / 37 market) — while thin but POSITIVE margins were the safest band (1 loss in 25).
	 * A card the user is not setting up is dropped for the next one; one being typed in stays (it never
	 * changes mid-setup) and its note says why to skip it. Under the lock; returns that note or null.
	 */
	private String checkMargin()
	{
		if (!pinActive() || hour == null)
		{
			return null;
		}
		String key = String.valueOf(pin.itemId);
		long now = FlipEngine.marginNow(pin.bid, FlipEngine.quote(item(hour, key), item(five, key)), pin.itemId);
		if (now > 0 || now == Long.MIN_VALUE)
		{
			return null;              // still there — or no ask to judge by: keep it
		}
		if (settingUp == pin.itemId)
		{
			return "margin gone since it was picked - Skip it";
		}
		stale.add(pin.itemId);
		pin = null;
		forced = null;
		return null;
	}

	/** The best thing to sell now: bought, in the inventory, not already on the GE. */
	private SellRow sellCandidate(long now)
	{
		if (snap == null || hour == null)
		{
			return null;
		}
		Set<Integer> busy = new HashSet<>();
		for (GameSnapshot.Offer o : snap.offers)
		{
			// ⚠ an item with a live SELL offer is not "bought and unsold" — it is selling; and one
			// with a live BUY is still being bought: no sell until that is done. In-game 2026-10-31,
			// after re-pricing an Amethyst arrow buy the 767 already bought lit up as a sell — "were
			// still buying more".
			if (!o.complete && !o.cancelled && !ignored.contains(o.key()))
			{
				busy.add(o.itemId);
			}
		}
		SellRow top = null;
		SellRow kept = null;
		for (GameSnapshot.Holding h : snap.holdings)
		{
			// ⚠ BOUGHT IS NOT HELD: only what is IN the inventory is a sell, capped at what is
			// there. User, 2026-10-31: "it tells me to sell death runes i dont have in my invenotry."
			int have = snap.inventory.getOrDefault(h.itemId, 0);
			if (have <= 0 || busy.contains(h.itemId) || sellSkipped.contains(h.itemId)
				|| now - soldAt.getOrDefault(h.itemId, 0L) <= SOLD_HOLD_MS)
			{
				continue;
			}
			String key = String.valueOf(h.itemId);
			long market = FlipEngine.quote(item(hour, key), item(five, key))[1];
			if (market <= 0)
			{
				continue;
			}
			// Under what was paid + tax: a fresh dip is listed AT break-even and given the window; an
			// item that WENT SOUTH (its buy was told to cancel) — or stayed under water a whole window
			// since the buy — is sold AT THE MARKET to cut the loss. User, 2026-10-31: "you stop buying
			// it you sell what you bough and you try to minimize loss. it happes its part of it."
			long even = h.basis > 0 ? FlipEngine.breakEven(h.basis, h.itemId) : 0;
			boolean cut = market < even && (south.containsKey(h.itemId)
				|| (h.lastBuy > 0 && now - h.lastBuy >= config.adjustWindow().minutes * 60_000L));
			long ask = cut ? market : Math.max(market, even);
			long qty = Math.min(have, h.qty);
			SellRow row = new SellRow(h, qty, ask, ask - FlipEngine.tax(ask, h.itemId) - h.basis, market, cut);
			if (top == null || row.each * row.qty > top.each * top.qty)
			{
				top = row;
			}
			if (pinSell != null && pinSell == h.itemId)
			{
				kept = row;             // keep the sell already on the card
			}
		}
		return kept != null ? kept : top;
	}

	/** Re-derive the card and the view from what is known. Cheap: no network. Under the lock. */
	private void recompute()
	{
		long now = System.currentTimeMillis();
		boolean live = config.livePrices();
		SellRow cand = sellCandidate(now);
		// SELL first — but never yank the card's BUY away while it is being typed into the GE.
		// ⚠ Not "while a buy is on the card" (the sidebar's rule): in-game 2026-10-31 1,167 Steam
		// runes sat in the inventory with no sell price because a Snape grass seed card was up.
		boolean typingBuy = pinActive() && settingUp == pin.itemId;
		sellNow = typingBuy ? null : cand;
		pinSell = sellNow == null ? null : sellNow.holding.itemId;

		Suggestion c = null;
		String sub = "";
		String hint = "";
		// ⭐ ALL 8 SLOTS IN USE: nothing can be placed (user, 2026-10-31: "when all slots are full
		// its should say wait not buy sunfire splinters"). A finished offer frees its slot once
		// collected, so that comes first. A card nobody can act on is let go — the next one is
		// priced fresh when a slot frees — unless it is being typed in right now.
		boolean full = snap != null && snap.offers.size() >= GE_SLOTS;
		if (live && full)
		{
			boolean collect = false;
			for (GameSnapshot.Offer o : snap.offers)
			{
				collect |= (o.complete || o.cancelled) && !ignored.contains(o.key());
			}
			if (!typingBuy)
			{
				pin = null;
			}
			sellNow = null;
			pinSell = null;
			c = new Suggestion();
			c.action = collect ? "COLLECT" : "WAIT";
			c.note = collect ? "Collect your finished offers to free a slot" : "All 8 GE slots are in use";
			c.asOf = now / 1000;
			hint = collect ? "the Collect button is lit in the GE" : "the next flip shows when a slot frees up";
		}
		else if (live && sellNow != null)
		{
			SellRow r = sellNow;
			c = new Suggestion();
			c.action = "SELL";
			c.itemId = r.holding.itemId;
			c.name = r.holding.name;
			c.quantity = (int) r.qty;
			c.price = (int) r.ask;
			c.profit = r.each * r.qty;
			c.fillMinutes = -1;
			c.asOf = now / 1000;
			sub = String.format("paid %,d · %+,d each after the 2%% tax", r.holding.basis, r.each);
			c.note = r.cut ? "cutting the loss - the price fell after buying" : "";
			hint = r.cut ? "sold at the market so it cannot fall further"
				: r.ask > r.market
				? String.format("the market (%,d) is under what you paid + tax - listed at break-even", r.market)
				: "selling first frees cash for the next buy";
		}
		else if (live && config.paused())
		{
			c = new Suggestion();
			c.action = "NONE";
			c.note = "Paused - no new buys";
			hint = "press Resume in the panel to get flips again";
		}
		else if (live)
		{
			String gone = checkMargin();
			Pick p = current();
			c = new Suggestion();
			if (p == null)
			{
				c.action = "NONE";
				c.note = scanning || snap == null ? "Looking for a flip..." : "Nothing worth doing right now";
				hint = scanning || snap == null ? "" : "lower the minimum profit, or raise the risk level";
			}
			else
			{
				c.action = "BUY";
				c.itemId = p.itemId;
				c.name = p.name;
				c.quantity = (int) p.qty;
				c.price = (int) p.bid;
				c.sellPrice = (int) p.ask;
				c.profit = p.total;
				c.fillMinutes = p.fillMinutes;
				c.note = gone != null ? gone : p.firstWarning();
				c.asOf = p.asOf;
				sub = String.format("%s/hr · %.0f%% ROI · %s in", gp((long) p.perHour()), p.roi(), gp(p.committed()));
				String limit = limitText(p.itemId, now);
				hint = limit.isEmpty() ? "kept until you place or skip" : "kept until you place or skip · " + limit;
			}
		}
		card = c;

		List<OfferAdvice> adv = snap == null ? Collections.emptyList()
			: OfferAdvice.advise(snap.offers, live ? five : null, ignored,
				config.adjustWindow().minutes * 60_000L, now);
		south.values().removeIf(t -> now - t > SOUTH_MS);
		for (OfferAdvice a : adv)
		{
			if (OfferAdvice.CANCEL.equals(a.verdict) && a.offer.buy)
			{
				south.putIfAbsent(a.offer.itemId, now);
			}
		}
		List<Pick> next = new ArrayList<>();
		for (Pick p : pool())
		{
			if (next.size() >= NEXT_SHOWN)
			{
				break;
			}
			if (c == null || p.itemId != c.itemId)
			{
				next.add(p);
			}
		}
		List<Held> held = new ArrayList<>();
		long inBuys = 0;
		if (snap != null)
		{
			for (GameSnapshot.Holding h : snap.holdings)
			{
				long market = live ? FlipEngine.quote(item(hour, String.valueOf(h.itemId)),
					item(five, String.valueOf(h.itemId)))[1] : 0;
				held.add(new Held(h.itemId, h.name, h.qty, h.basis, market,
					market <= 0 ? 0 : (market - FlipEngine.tax(market, h.itemId) - h.basis) * h.qty));
			}
			held.sort((a, b) -> Long.compare(b.qty * b.paidEach, a.qty * a.paidEach));
			for (GameSnapshot.Offer o : snap.offers)
			{
				if (o.buy && !o.complete && !o.cancelled && !ignored.contains(o.key()))
				{
					inBuys += (long) Math.max(0, o.total - o.filled) * o.price;
				}
			}
		}
		view = new View(live, snap != null, status, c, sub, hint,
			snap == null ? 0 : snap.inventoryCoins, snap == null ? -1 : snap.bankCoins,
			Collections.unmodifiableList(adv), Collections.unmodifiableList(next),
			snap == null ? List.of() : snap.history, Collections.unmodifiableList(held), inBuys,
			snap == null ? 0 : snap.sessionStart, config.paused(), blocked().size(), Math.max(0, config.maxCash()),
			c != null && "BUY".equals(c.action) ? limitNowLocked(c.itemId) : null, rowLimits(), scannedAt, scanning);
	}

	/** Today's 5-minute average {insta-sell, insta-buy} for an item (the hour's if none); 0 = unknown. */
	synchronized long[] quote(int itemId)
	{
		String key = String.valueOf(itemId);
		return FlipEngine.quote(item(hour, key), item(five, key));
	}

	private void changed()
	{
		Consumer<View> l;
		View v;
		synchronized (this)
		{
			recompute();
			v = view;
			l = listener;
		}
		if (l != null)
		{
			l.accept(v);
		}
	}

	/** 6900000 -> "6.90m", the sidebar's format. */
	static String gp(long n)
	{
		long a = Math.abs(n);
		String s = a >= 1_000_000_000L ? String.format("%.2fb", a / 1e9)
			: a >= 1_000_000L ? String.format("%.2fm", a / 1e6)
			: a >= 10_000L ? String.format("%.0fk", a / 1e3)
			: String.format("%,d", a);
		return (n < 0 ? "-" : "") + s;
	}
}
