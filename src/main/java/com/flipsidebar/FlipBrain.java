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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Filepath;
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
	static volatile long SCAN_EVERY_MS = 180_000;
	/** After a failed scan or with no coins seen, look again this soon instead of in 3 minutes. */
	private static final long RETRY_MS = 30_000;
	private static final long PRICES_EVERY_MS = 60_000;
	/** The whole market's replay runs this often (MarketReplay), once most of the day is held. */
	private static final long REPLAY_EVERY_MS = 30 * 60_000L;
	/** Over this many items held, rarely traded ones are dropped early (MarketMemory.prune). */
	private static final int PRUNE_OVER = 1_500;
	/** Missing whole-market blocks fetched per tick while the day fills in (one request each, every item). */
	private static final int BACKFILL_PER_TICK = 3;
	/** The last trades are fetched this often: the 5-minute averages run 3-8 minutes behind (quoteLive). */
	private static final long LATEST_EVERY_MS = 20_000;
	/** A sell you pressed Placed on stays off the card this long — until the GE shows the offer. */
	static volatile long SOLD_HOLD_MS = 180_000;
	/** A card older than this says "prices may have moved". */
	static final long STALE_MS = 120_000;
	static volatile int MAX_RESEARCH = 150;
	/**
	 * ⭐ A WORLD WITH ITS OWN ECONOMY (user, 2026-11-04: "if I use it in the hard core world that just launched itll likely
	 * send that data for a market thats unrelated to the main one", "Not to mention it would reccomend the wrong things on
	 * those worlds"): the prices are the main game's, so no guidance there (FlipSidebarPlugin.mainEconomy).
	 */
	static final String OTHER_ECONOMY = "This world has its own Grand Exchange - Flip Sidebar's prices are the main game's, "
		+ "so it suggests and records nothing here";
	/** Set by the plugin each tick: the world is not the main game's economy (FlipSidebarPlugin.mainEconomy). */
	volatile boolean otherEconomy;
	/** An item that went south is not suggested again for this long (judgement: the trend broke). */
	static volatile long SOUTH_MS = 60 * 60_000L;
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
		/** Holding is full (holdingFull): set before the view is published. */
		boolean holdingFull;
		/** The inventory's items, not coins, if all sold now after the tax (itemsValue): set before publishing. */
		long inventoryItems;
		/** ...and your long-term holds in the bank (Holding, banked) the same way: set before publishing. */
		long bankItems;
		/**
		 * Bought units waiting in a GE slot, not collected yet, at what they cost (1.60.77; user, 2026-11-07: "The cash
		 * number doesnt make sense right now the twisted buckle buy skewed It by 10m" — a filled buy's item was counted
		 * nowhere until collected). FlipBrain.uncollected.
		 */
		long uncollected;
		/** Every item not suggested now, and why (notSuggested): set before the view is published. */
		List<Off> off = List.of();
		/** High alch mode: the plan (null when the mode is off or there are no prices yet). Set before publishing. */
		AlchPlan.Plan alch;
		/** ...the settings it was made with. */
		AlchPlan.Settings alchSettings;
		/** ...and the coins alching ties up now (alchTied), for the panel's budget slider. */
		long alchInUse;
		/** High alch mode is on (with live prices): the GE steps are collects only (FlipSidebarPlugin.nextOfferStep). */
		boolean alchMode;
		/** High alch mode: what you hold to alch (alchHeld), the plan's items first. */
		List<AlchHeld> alchHeld = List.of();

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

	/**
	 * ⭐ An item NOT SUGGESTED now — the panel's "Not suggested" list, at the bottom (1.56.0). User, 2026-11-02: "I want
	 * to have that list transparent and editable", "Put it at the bottom", "Collapsible".
	 */
	static final class Off
	{
		/** Your "Never suggest" list (the settings). */
		static final int NEVER = 0;
		/** Your right-click "Don't suggest for a day / a week". */
		static final int YOURS = 1;
		/** The plugin's own rest (FlipLearner: a problem item). */
		static final int REST = 2;
		/** The spike guard: its price is far over its 30-day level (FlipEngine.SPIKE_MAX) — back when it settles. */
		static final int SPIKE = 3;

		final int itemId;
		final String name;
		/** When it is suggested again, epoch ms; 0 = never (your Never suggest list). */
		final long until;
		final String why;
		final int kind;

		Off(int itemId, String name, long until, String why, int kind)
		{
			this.itemId = itemId;
			this.name = name;
			this.until = until;
			this.why = why;
			this.kind = kind;
		}
	}

	/** Something bought and not sold: what it is worth now. */
	/**
	 * ⭐ An item held to ALCH (user, 2026-11-03: "shows no place about what your alching or your progress with it. like
	 * if i went back i wouldnt know what i should be alching and how many i need to alch"): how many are in the
	 * inventory to cast, how many its buy offers still bring, how many were cast since it showed up, what a cast makes.
	 */
	static final class AlchHeld
	{
		final int itemId;
		final String name;
		final long left;
		final long buying;
		/** Fewer than before since it was first listed (casts — or sold / banked; ASSUMED casts in alch mode). */
		final long cast;
		final long each;

		AlchHeld(int itemId, String name, long left, long buying, long cast, long each)
		{
			this.itemId = itemId;
			this.name = name;
			this.left = left;
			this.buying = buying;
			this.cast = cast;
			this.each = each;
		}
	}

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
		/** "held - waiting for 310 ..." or "back at 310 - sell it now ..." (FlipBrain.underWater), else "". */
		final String note;
		/** Parked and back at break-even: its sell is on the card. */
		final boolean ready;
		/** Held by the user (the card's Hold / "Hold long-term"), not parked by the rule: unticking it releases it. */
		final boolean mine;
		/** A LONG-TERM hold in the bank (held by the user, banked): withdraw it to sell it. */
		final boolean banked;

		/** Kept in the inventory for its price to come back — the panel's Holding section, not Active offers. */
		boolean parked()
		{
			return !note.isEmpty();
		}

		Held(int itemId, String name, long qty, long paidEach, long market, long profit)
		{
			this(itemId, name, qty, paidEach, market, profit, "", false, false);
		}

		Held(int itemId, String name, long qty, long paidEach, long market, long profit, String note, boolean ready,
			boolean mine)
		{
			this(itemId, name, qty, paidEach, market, profit, note, ready, mine, false);
		}

		Held(int itemId, String name, long qty, long paidEach, long market, long profit, String note, boolean ready,
			boolean mine, boolean banked)
		{
			this.banked = banked;
			this.note = note;
			this.ready = ready;
			this.mine = mine;
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
	/** The brain's loop has failed once and said so in the log. */
	private boolean warned;
	private Consumer<View> listener;

	private GameSnapshot snap;
	/** The wiki's /5m averages — what prices are quoted from (FlipEngine.quote), and "the market now". */
	private JsonObject five;
	/** The wiki's /1h averages — the quote's fallback for a side with no trades in 5 minutes. */
	private JsonObject hour;
	private long pricesAt;
	/** The wiki's /latest: each item's last insta-buy / insta-sell and when — FlipEngine.quoteLive. */
	private JsonObject latest;
	private long latestAt;
	/** Learning from the account's own flips (FlipLearner); saved by the plugin through {@link #learnSaver}. */
	private FlipLearner learner = new FlipLearner();
	private Consumer<FlipLearner> learnSaver;
	/**
	 * Held item -> {its break-even, share of the last day's 5-minute blocks whose insta-buy reached it} — whether an
	 * under-water holding's row says how often it comes back. Measured on the brain's thread (MarketMemory).
	 */
	private volatile Map<Integer, double[]> reach = Map.of();
	/** HoldOrSell's verdict for each held item under water (judgeHolds, the brain thread); an item not judged is absent. */
	private volatile Map<Integer, HoldOrSell.Verdict> verdicts = Map.of();
	/**
	 * Parked holdings, item -> since (epoch ms) — STICKY (parkedNow). User, 2026-11-01: "it told me to hold law runes
	 * ... then it decided that i was holding nature runes which i wasnt and now it switched back to law runes. needs to
	 * stay accurate" — 4,241 Nature runes on record since 01:45 (sold or banked unseen; the record is trimmed only once
	 * the bank is opened) showed whenever their price dipped, and the Law runes left and came back as theirs crossed
	 * break-even. Under the lock.
	 */
	private final Map<Integer, Long> parked = new HashMap<>();
	/**
	 * "Sell now at the market" on a Holding row (sellAtLoss): never parked again until sold, its sell at the market —
	 * user, 2026-11-01: "in holding there should be a way to sell the item at a loss some people will want that".
	 * {@link #sellFirst} puts it on the card. Under the lock.
	 */
	private final Set<Integer> letGo = new HashSet<>();
	private Integer sellFirst;
	/** Swing buys found now (Swing.find; the "Swing trades" setting), best first; empty when off. */
	private volatile List<Pick> swingPicks = List.of();
	private long swingAt;
	private static final long SWING_EVERY_MS = 60_000;
	/** Told when a swing buy goes into the GE: its item is held for the target (TradeLog.swing). */
	private volatile SwingPlaced swingPlaced;

	/** A swing buy went in: hold {@code itemId} for {@code target} until {@code until} (epoch ms). */
	interface SwingPlaced
	{
		void placed(int itemId, long target, long until);
	}

	/** High alch mode's plan (alchPlanNow), when it was made, and on what. Under the lock. */
	private AlchPlan.Plan alchPlan;
	private long alchAt;
	private String alchKey = "";
	/** The alch plan is re-made at most this often (the prices change every minute). */
	private static final long ALCH_EVERY_MS = 30_000;
	/** High alch mode's Skip: items passed over for the plan's next one, this session. Under the lock. */
	private final Set<Integer> alchSkipped = new HashSet<>();
	/** High alch mode: the inventory items worth alching now, plan items first (alchTargets). */
	private volatile List<Integer> alchTargets = List.of();
	/** High alch mode's progress, item -> {its count last refresh, the most it came to} (alchHeld). Under the lock. */
	private final Map<Integer, long[]> alchCount = new HashMap<>();

	/** The trade journal (TradeJournal): each new card and each slot's advice change. Null in tests. */
	private volatile TradeJournal journal;
	/** What the journal last heard: the card, and each slot's verdict. Under the lock. */
	private String journalCard = "";
	private final Map<Integer, String> journalAdvice = new HashMap<>();
	/** At least this many of the last day's blocks held before "how often it came back" counts (12 hours). */
	private static final int REACH_MIN_BLOCKS = 144;
	/**
	 * An under-water holding: PARK in the inventory, or CUT: sold at the market. (WAIT on the GE at break-even when
	 * Holding was full, 1.49.0, is gone from 1.54.0: it kept the GE slot and the same coins tied — user, 2026-11-01:
	 * "my ges now full of sell offers thatll never go threw".)
	 */
	static final int PARK = 1;
	static final int CUT = 2;
	/** Held {@link #HOLD_MAX_MS} under water: time's up — a sell at the market is suggested (the user's own Hold excepted). */
	static final int TIME = 3;
	/** The user's own Hold (the card's Hold button): never lowered under break-even (OfferAdvice.PAID_HELD, 1.55.0). */
	static final int HELD = 4;
	/**
	 * ⭐ An under-water holding is held at most this long from its buy; then a SELL at the market is suggested (never done
	 * by itself; the card's Hold keeps it). User, 2026-11-01 23:25: "if we just hold loss items all day and never sell
	 * idk if were really winning you know ... we cant hold things and tie up wealth for ever". 1.41.0 had this cut-off
	 * (PARK_MS); 1.44.0 removed it (user: "i would of held the ruens"). MEASURED: under 100k (patience_replay.py, 40 h,
	 * 149 items, a 5m order) an item back at break-even in 20%+ of the day: sold at once -52k, held up to 8 h +0.3k;
	 * 5-20%: -60k / -20k. 1m+ (dear_hold_replay.py, 100 h, 314 under-water fills): held with no limit -0.70% a flip, coins
	 * tied 5.0 h on average, 21 of 314 still unsold at the end; up to 8 h -0.86%, tied 2.3 h — and 5-20m items -1.14%
	 * against -1.39% (worst 5%: -22% against -35%). The 2.7 h less tied is worth more than the 0.16% (any flip rate over
	 * 0.06% an hour); NOT measured: what the freed coins made.
	 */
	static volatile long HOLD_MAX_MS = 8 * 3_600_000L;

	/** The whole market's last day (MarketMemory) — the brain's thread only; saved to {@link #marketFile}. */
	private MarketMemory market = new MarketMemory();
	private Filepath marketFile;
	private boolean marketLoaded;
	private long replayAt;
	/** What the last whole-market replay found: {blocks, items, replayed flips, lost}, and the items it rates weak. */
	private volatile long[] replayStats;
	private volatile List<Integer> replayWeak = List.of();
	/** Flips whose measuring was started this session (one at a time). */
	private final Set<String> measureTried = new HashSet<>();
	private boolean measuring;
	private List<Pick> best = Collections.emptyList();
	private long scannedAt;
	private long nextScanAt;
	private boolean scanning;
	private boolean rescanAsked;
	private String status = "";
	private Set<String> ignored = Collections.emptySet();
	/** The item in the GE's offer set-up screen right now; -1 when it is not open. */
	private int settingUp = -1;
	/** The card shows a scan's early pick (earlyPick) — the first found, not yet the best. */
	private boolean earlyShown;

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
	/**
	 * ⭐ The sell card as it was when its item opened in the GE's set-up screen — its quantity and price held there
	 * until the offer is placed or the screen closes (user, 2026-11-02: "it highlighted the quanity box istead of the
	 * value box": a Sapphire sell card's quantity followed the inventory — 5,414, 3,136, 539 — while the buy kept
	 * filling, so the box no longer matched the card). Buys were already locked while typed in (typingBuy).
	 */
	private SellRow sellLock;
	private final Map<Integer, Long> soldAt = new HashMap<>();
	/** Item -> when its buy was told to CANCEL (gone south): sell it at the market, do not re-buy it soon. */
	private final Map<Integer, Long> south = new HashMap<>();
	/**
	 * Item -> {the price its buy was first placed at, last seen epoch ms}: the base a buy is never RAISED far past
	 * (OfferAdvice.CHASE_PCT). A Modify (cancel, then place again at the raised price) keeps it; {@link #FIRST_BID_GAP_MS}
	 * with no buy of the item in the GE ends it. Not saved: after a restart the base is the offer's price then.
	 */
	private final Map<Integer, long[]> firstBid = new HashMap<>();
	private static final long FIRST_BID_GAP_MS = 10 * 60_000L;
	private final Set<Integer> sellSkipped = new HashSet<>();

	private volatile Suggestion card;
	private volatile View view;

	@Inject
	FlipBrain(FlipEngine engine, WikiPrices prices, FlipSidebarConfig config)
	{
		this.engine = engine;
		engine.memory = market;
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
			if (s != null)
			{
				long nowMs = System.currentTimeMillis();
				learner.learn(s.history, s.week, nowMs);
				applyLearned();
				// a GE buy limit used up: counted once per 4-hour window, saved with the account
				boolean hit = false;
				for (Map.Entry<Integer, long[]> e : s.limitUse.entrySet())
				{
					long limit = engine.limit(e.getKey());
					if (limit > 0 && e.getValue()[0] >= limit)
					{
						hit |= learner.noteLimitHit(e.getKey(), e.getValue()[1], nowMs);
					}
				}
				if (hit)
				{
					applyLearned();       // a new limit hit can make an item proven
					if (learnSaver != null)
					{
						learnSaver.accept(learner);
					}
				}
			}
			if (s != null && pinActive())
			{
				for (GameSnapshot.Offer o : s.offers)
				{
					// the offer on the card is now on the GE: it is placed — move on by itself
					if (o.buy && o.itemId == pin.itemId && !ignored.contains(o.key()))
					{
						noteSwing(pin);
						placed.add(pin.itemId);
						pin = null;
						forced = null;
						break;
					}
				}
			}
			// ⭐ PLACED ONLY UNTIL THE GE SHOWS THE BUY (1.60.33): from then its offer keeps it off the card (pool:
			// openItems), then the inventory (inHolding) until it sells — after that it can be a card again. Before,
			// `placed` was cleared only when the plugin stopped or the account changed. MEASURED 2026-11-06, one client
			// open from 19:26: 123 items bought, just 2 ever got a buy card again, and from 01:10 to 03:58 4-6 of 8
			// slots stood empty under "Nothing worth doing right now" (user: "I did not step away") while the judge,
			// run on the live market at 06:00 with the learned rules (harness NightWhy: no used buy limits, resting
			// items or market replay in it), passed 38 items.
			if (s != null)
			{
				for (GameSnapshot.Offer o : s.offers)
				{
					if (o.buy)
					{
						placed.remove(o.itemId);
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
			// picks found on the old settings are dropped, not shown: after "Use at most" went to 10m the
			// next card was over 10m — it came from the scan made before the change (in-game 2026-10-31).
			// The one being typed into the GE stays.
			best = Collections.emptyList();
			if (pin != null && settingUp != pin.itemId)
			{
				pin = null;
			}
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
					noteSwing(p);
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
			Suggestion shown = card;
			if (config.alchMode() && shown != null && "BUY".equals(shown.action))
			{
				alchSkipped.add(shown.itemId);           // High alch mode: the plan's next item instead
			}
			else if (sellNow != null)
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
		CompletableFuture<JsonElement> ts = prices.getAsync(WikiPrices.seriesPath(itemId, (String) r[0]));
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

	// ── learning from the account's own flips (FlipLearner) ─────────────────────

	/** The account's learner, loaded by the plugin (a new one when there is no save); {@code saver} stores it. */
	/** Buying {@code itemId} at {@code price} pays to alch (high alch − price − a nature rune ≥ "Least profit a cast"). */
	synchronized boolean paysToAlch(int itemId, long price)
	{
		long ha = engine.highAlch(itemId);
		long nature = live(String.valueOf(FlipEngine.NATURE_RUNE))[1];
		return ha > 0 && nature > 0 && price > 0 && ha - price - nature >= Math.max(1, config.alchMinProfit());
	}

	/** The team's flips (TeamSync, or the saved team file) in place of the ones held; the floors learned again at once. */
	synchronized void setTeam(Map<String, double[]> team)
	{
		learner.setTeamFlips(team);
		if (snap != null)
		{
			learner.learn(snap.history, snap.week, System.currentTimeMillis());
		}
		applyLearned();
		saveLearning();
	}

	/** This account's own measured flips with results (FlipLearner.ownFlips) — what the team sync uploads. */
	synchronized Map<String, double[]> ownFlips(List<TradeLog.Flip> flips)
	{
		return FlipLearner.ownFlips(learner, flips);
	}

	synchronized int teamSize()
	{
		return learner.teamSize();
	}

	/** What it has learned, as saved (BrainFile export): under the lock, a consistent copy. */
	synchronized String learnerJson(Gson gson)
	{
		return gson.toJson(learner);
	}

	synchronized void setLearner(FlipLearner l, Consumer<FlipLearner> saver)
	{
		learner = l == null ? new FlipLearner() : l.fixed();
		learnSaver = saver;
		measureTried.clear();
		if (snap != null)
		{
			learner.learn(snap.history, snap.week, System.currentTimeMillis());
		}
		applyLearned();
	}

	/** What the learner learned, into the engine's rules. Under the lock. */
	private void applyLearned()
	{
		FlipEngine.activityFloor = learner.floor();
		FlipEngine.flowFloor = learner.flowFloor();
		FlipEngine.gpFloor = learner.gpFloor();
		FlipEngine.proven = learner.proven(snap == null ? List.<GameSnapshot.Done>of() : snap.history,
			snap == null ? Map.<Integer, long[]>of() : snap.record, System.currentTimeMillis());
	}

	/**
	 * Items whose GE buy limit was used up, most often first: {name, maxed in 24h, maxed in all, last maxed epoch ms,
	 * limit resets at (0 = not now), GE limit, what its flips made} — Statistics → Buy limits.
	 */
	synchronized List<Object[]> limitTable()
	{
		long now = System.currentTimeMillis();
		Map<Integer, Long> profit = new HashMap<>();
		for (GameSnapshot.Done d : snap == null ? List.<GameSnapshot.Done>of() : snap.history)
		{
			profit.merge(d.itemId, d.profit, Long::sum);
		}
		List<Object[]> out = new ArrayList<>();
		for (Map.Entry<Integer, long[]> e : learner.limitHits(now).entrySet())
		{
			int id = e.getKey();
			long[] h = e.getValue();
			long[] use = snap == null ? null : snap.limitUse.get(id);
			String n = engine.name(id);
			out.add(new Object[]{n == null ? "Item " + id : n, h[0], h[1], h[2], use != null && use[1] > now ? use[1] : 0L,
				engine.limit(id), profit.getOrDefault(id, 0L)});
		}
		out.sort((a, b) -> Long.compare((long) b[1] * 1000 + (long) b[2], (long) a[1] * 1000 + (long) a[2]));
		return out;
	}

	/** What it has learned, in words (the Statistics window). */
	synchronized List<String> learned()
	{
		List<String> out = new ArrayList<>();
		long[] st = replayStats;
		if (st != null)
		{
			// names at the moment it is shown — the replay can run before the scan has loaded them (user: names,
			// never numbers)
			List<String> weak = new ArrayList<>();
			for (int id : replayWeak)
			{
				String n = engine.name(id);
				if (n != null)
				{
					weak.add(n);
				}
			}
			Collections.sort(weak);
			int shown = Math.min(12, weak.size());
			out.add(String.format("Whole market, last 24 hours (%d blocks, %,d items): the plugin's rules replayed on every "
					+ "item - %,d replayed flips, %d%% lost (the replay is kinder than real flips, so it is only used to skip items "
					+ "it rates weak). Skipping %d items that made under %,d a flip in it%s.", st[0], st[1], st[2],
				st[2] == 0 ? 0 : st[3] * 100 / st[2], replayWeak.size(), FlipEngine.REPLAY_MIN_EACH,
				shown == 0 ? "" : ": " + String.join(", ", weak.subList(0, shown))
					+ (replayWeak.size() > shown ? " and " + (replayWeak.size() - shown) + " more" : "")));
		}
		out.addAll(learner.lines());
		// the items it keeps maxing out (Statistics → Buy limits has them all)
		long now = System.currentTimeMillis();
		List<String> maxed = new ArrayList<>();
		learner.limitHits(now).entrySet().stream()
			.filter(e -> e.getValue()[0] > 0)
			.sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
			.limit(6)
			.forEach(e ->
			{
				String n = engine.name(e.getKey());
				maxed.add((n == null ? "item " + e.getKey() : n) + " x" + e.getValue()[0]);
			});
		if (!maxed.isEmpty())
		{
			out.add("Buy limits you maxed out in the last 24 hours: " + String.join(", ", maxed)
				+ " (Statistics → Buy limits has them all).");
		}
		List<String> mine = new ArrayList<>();
		for (int id : FlipEngine.proven)
		{
			String n = engine.name(id);
			mine.add(n == null ? "item " + id : n);
		}
		Collections.sort(mine);
		if (!mine.isEmpty())
		{
			out.add("Proven - 3+ flips of yours (or 2+ and its buy limit maxed out), made money, a third or fewer lost: "
				+ String.join(", ", mine) + ". Always looked at when their buy limit has room, without the rules learned "
				+ "from other items, and bought in a bigger slice (" + Math.round(FlipEngine.PROVEN_SHARE * 100) + "% of "
				+ "an hour's trading); the margin rules still apply, and they rank with the rest by profit per hour of the "
				+ "GE slot.");
		}
		return out;
	}

	/** Where the whole market's memory is saved (the plugin's folder). */
	void setMarketFile(Filepath f)
	{
		marketFile = f;
	}

	/**
	 * The whole market: load the saved day once, fill in missing blocks (a few a tick, newest first — one request
	 * each for every item), and every {@link #REPLAY_EVERY_MS} replay the rules on every item (MarketReplay), then
	 * skip items it rates weak (FlipEngine.replayed) and save. The brain's thread only.
	 */
	private void marketStep(long now)
	{
		if (!config.livePrices())
		{
			return;
		}
		if (market.newest() <= 0)
		{
			return;                   // the first /5m block has not come yet
		}
		if (market.items() > PRUNE_OVER && market.blocks() >= 72)
		{
			market.prune();           // while the day fills in: never thousands of rarely traded items in memory
		}
		List<Long> missing = market.missing(market.newest());
		for (int i = 0; i < Math.min(BACKFILL_PER_TICK, missing.size()); i++)
		{
			try
			{
				market.add(prices.block("/5m?timestamp=" + missing.get(i)));
			}
			catch (IOException | RuntimeException e)
			{
				log.debug("Flip Sidebar: market block {} failed", missing.get(i), e);
				break;
			}
		}
		long budget;
		synchronized (this)
		{
			budget = budget(snap);
		}
		if (market.blocks() < MarketReplay.MIN_BLOCKS || budget <= 0 || now - replayAt < REPLAY_EVERY_MS)
		{
			return;
		}
		replayAt = now;
		market.prune();
		// a typical buy: a quarter of the cash (the replay's item verdict was checked against real flips at that size)
		MarketReplay.Result r = MarketReplay.run(market, engine::limit, budget / 4, config.adjustWindow().volumeShare,
			FlipEngine.SELL_PATIENCE_MS / 60_000);
		Map<Integer, long[]> verdict = new HashMap<>();
		List<Integer> weak = new ArrayList<>();
		for (Map.Entry<Integer, MarketReplay.Item> e : r.items.entrySet())
		{
			MarketReplay.Item it = e.getValue();
			verdict.put(e.getKey(), new long[]{it.flips, it.lost, it.each()});
			if (it.flips >= 3 && it.each() < FlipEngine.REPLAY_MIN_EACH)
			{
				weak.add(e.getKey());
			}
		}
		FlipEngine.replayed = verdict;
		replayWeak = weak;
		replayStats = new long[]{market.blocks(), market.items(), r.flips, r.lost};
		saveMarket();
		changed();
	}

	/** The saved day, once — before the first fresh block is added to it. */
	private void loadMarketOnce()
	{
		if (marketLoaded || marketFile == null)
		{
			return;
		}
		marketLoaded = true;
		if (!marketFile.exists())
		{
			return;
		}
		try (InputStream in = marketFile.openInputStream())
		{
			market = MarketMemory.load(in);
			engine.memory = market;   // research reads it (FlipEngine.series5m)
		}
		catch (IOException e)
		{
			log.debug("Flip Sidebar: market memory unreadable - starting again", e);
		}
	}

	private void saveMarket()
	{
		if (marketFile == null)
		{
			return;
		}
		try
		{
			marketFile.getParent().createDirectories();
			Filepath tmp = marketFile.getParent().joinSegment(marketFile.getFileName() + ".tmp");
			try (OutputStream out = tmp.openOutputStream())
			{
				market.save(out);
			}
			tmp.moveTo(marketFile, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.debug("Flip Sidebar: could not save the market memory", e);
		}
	}

	/**
	 * Measure one finished flip — its item just before the buy — from the wiki's 5-minute history, on OkHttp's
	 * pool; then learn again and save. One at a time, newest flips first; a flip is tried once a session.
	 */
	private void learnStep()
	{
		GameSnapshot.Done d;
		synchronized (this)
		{
			if (measuring || snap == null || !config.livePrices())
			{
				return;
			}
			d = learner.nextToMeasure(snap.history, System.currentTimeMillis(), measureTried);
			if (d == null)
			{
				return;
			}
			measuring = true;
			measureTried.add(d.id());
		}
		final GameSnapshot.Done flip = d;
		prices.getAsync(WikiPrices.seriesPath(flip.itemId, "5m")).whenComplete((series, err) ->
		{
			double[] m = null;
			if (err == null && series != null && series.isJsonObject())
			{
				JsonArray data = series.getAsJsonObject().getAsJsonArray("data");
				m = data == null ? FlipLearner.UNMEASURABLE : FlipLearner.measure(flip.itemId, data, FlipLearner.before(flip));
			}
			synchronized (FlipBrain.this)
			{
				measuring = false;
				if (m == null)
				{
					log.debug("Flip Sidebar: measuring flip {} failed", flip.id(), err);
					return;               // the network: tried again next session
				}
				learner.put(flip.id(), m);
				learner.learn(snap == null ? List.<GameSnapshot.Done>of() : snap.history, snap == null ? List.<GameSnapshot.Done>of() : snap.week,
					System.currentTimeMillis());
				applyLearned();
				if (learnSaver != null)
				{
					learnSaver.accept(learner);
				}
			}
			changed();
		});
	}

	/** Item lookup in the panel: what one item is doing now, and whether it is worth flipping. */
	static final class Lookup
	{
		final int itemId;
		final String name;
		/** The 5-minute average insta-sell (what a buy pays) and insta-buy (what a sell gets); 0 = none. */
		long bid;
		long ask;
		/** ask - tax - bid: the margin each after the 2% tax (0 when a side has no trades). */
		long each;
		/** Insta-bought + insta-sold in the last hour. */
		long hourVolume;
		/** The GE buy limit (0 = unknown), what is left of it (-1 = unknown), when it resets (0 = not started). */
		long limit;
		long left;
		long resetsAt;
		/** The flip it would be (rejected says why not); null when there are no trades to judge it on. */
		Pick pick;

		Lookup(int itemId, String name)
		{
			this.itemId = itemId;
			this.name = name;
		}
	}

	/** Item names matching a lookup, best first (FlipEngine.search). */
	List<Integer> searchItems(String query, int max)
	{
		return engine.search(query, max);
	}

	boolean hasItemNames()
	{
		return engine.hasNames();
	}

	String itemName(int itemId)
	{
		return engine.name(itemId);
	}

	/**
	 * Look an item up: its prices, margin after tax, hour's volume and buy limit now, then — after its
	 * last day of 5-minute trades is fetched on OkHttp's pool — judged like any pick
	 * (FlipEngine.judge). {@code done} hears it once, on any thread; nothing with live prices off.
	 */
	void lookup(int itemId, Consumer<Lookup> done)
	{
		if (!config.livePrices())
		{
			return;
		}
		GameSnapshot s;
		JsonObject h;
		JsonObject f;
		synchronized (this)
		{
			s = snap;
			h = hour;
			f = five;
		}
		String key = String.valueOf(itemId);
		JsonObject hi = item(h, key);
		long nowS = System.currentTimeMillis() / 1000;
		long[] q = FlipEngine.quoteBuy(hi, item(f, key), latestItem(key), nowS);
		Lookup out = new Lookup(itemId, engine.name(itemId));
		out.bid = q[0];
		out.ask = q[1];
		out.each = q[0] > 0 && q[1] > 0 ? q[1] - FlipEngine.tax(q[1], itemId) - q[0] : 0;
		out.hourVolume = hi == null ? 0 : Research.num(hi, "highPriceVolume") + Research.num(hi, "lowPriceVolume");
		long[] used = s == null ? null : s.limitUse.get(itemId);
		out.limit = engine.limit(itemId);
		out.left = FlipEngine.remaining(out.limit, used == null ? 0 : used[0]);
		out.resetsAt = used == null ? 0 : used[1];
		if (q[0] <= 0 || q[1] <= 0)
		{
			done.accept(out);         // a side with no trades in the last hour: nothing to judge
			return;
		}
		long budget = flipCash(s);
		long all = flipCashAll(s);
		long most = perBuy(s);
		RiskLevel risk = config.riskLevel();
		AdjustWindow window = config.adjustWindow();
		prices.getAsync(WikiPrices.seriesPath(itemId, "5m")).thenAcceptBoth(engine.monthLevelAsync(itemId), (series, level) ->
		{
			Research r = Research.of(itemId, series.getAsJsonObject().getAsJsonArray("data"));
			r.monthLevel = Math.max(0, level);
			out.pick = judgeYours(itemId, out.name, Math.max(0, out.left), q, budget, all, most, risk, window, r, nowS);
			done.accept(out);
		}).exceptionally(e ->
		{
			log.debug("Flip Sidebar: lookup of {} failed", itemId, e);
			done.accept(out);         // the prices without a verdict
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
		long[] wait;
		synchronized (this)
		{
			s = snap;
			h = hour;
			f = five;
			wait = buy ? waitingBuys(itemId) : new long[2];
		}
		long budget = flipCash(s);
		long all = flipCashAll(s);
		long most = perBuy(s);
		RiskLevel risk = config.riskLevel();
		AdjustWindow window = config.adjustWindow();
		String key = String.valueOf(itemId);
		long nowQ = System.currentTimeMillis() / 1000;
		long[] q = buy ? FlipEngine.quoteBuy(item(h, key), item(f, key), latestItem(key), nowQ)
			: FlipEngine.quoteLive(item(h, key), item(f, key), latestItem(key), nowQ);
		long[] used = s == null ? null : s.limitUse.get(itemId);
		long left = FlipEngine.remaining(engine.limit(itemId), (used == null ? 0 : used[0]) + wait[0]);
		// a buy is judged on the day's 5-minute trades; a side with no trades in the last hour is
		// priced from them too (below)
		CompletableFuture<JsonElement> ts = buy || q[0] <= 0 || q[1] <= 0
			? prices.getAsync(WikiPrices.seriesPath(itemId, "5m")) : CompletableFuture.completedFuture(null);
		CompletableFuture<Long> month = buy ? engine.monthLevelAsync(itemId) : CompletableFuture.completedFuture(0L);
		ts.thenAcceptBoth(month, (series, level) ->
		{
			long nowS = System.currentTimeMillis() / 1000;
			Suggestion out = new Suggestion();
			out.manual = true;
			out.itemId = itemId;
			out.name = name;
			out.asOf = nowS;
			out.fillMinutes = -1;
			// ⭐ YOUR OWN PICK IS ALWAYS PRICED when anything traded (user, 2026-10-31, of Black dart with no
			// insta-sells in the hour: "it should actually just be giving us a warning and an offer from its
			// last known trading information ... why if it hasnt sold in an hour is it off the table"): a
			// side missing from the last hour takes its most recent 5-minute AVERAGE of the day (never a
			// single trade), with a warning saying how old it is. The card's own picks keep the hour rule.
			long[] qq = q.clone();
			String stale = "";
			if (qq[0] <= 0 || qq[1] <= 0)
			{
				long[] last = lastAverages(series);
				if (qq[0] <= 0 && buy && last[0] > 0)     // a sell only needs the insta-buy side
				{
					qq[0] = last[0];
					stale = "last insta-sell " + FlipSidebarSlotOverlay.clock((nowS - last[1]) * 1000) + " ago";
				}
				if (qq[1] <= 0 && last[2] > 0)
				{
					qq[1] = last[2];
					stale += (stale.isEmpty() ? "" : ", ") + "last insta-buy "
						+ FlipSidebarSlotOverlay.clock((nowS - last[3]) * 1000) + " ago";
				}
			}
			String unpriced = qq[0] <= 0 && qq[1] <= 0 ? "No trades in the last day - nothing to price it on"
				: buy && qq[1] <= 0 ? "No insta-buys in the last day - can't price a flip"
				: buy && qq[0] <= 0 ? "No insta-sells in the last day - can't price a flip"
				: !buy && qq[1] <= 0 ? "No insta-buys in the last day - nothing to price a sell on" : null;
			String warn = stale.isEmpty() ? "" : "Thin: " + stale + " - the price may have moved";
			out.chatNote = stale;
			if (unpriced != null)
			{
				out.action = buy ? "BUY" : "SELL";
				out.quantity = buy ? 0 : have;
				out.discouraged = true;           // nothing to fill in, no highlights
				out.note = unpriced;
			}
			else if (buy && left == 0 && wait[0] > 0)
			{
				// nothing to fill in: another offer would only wait behind it
				out.action = "BUY";
				out.price = (int) qq[0];
				out.discouraged = true;
				out.note = "Your offer in slot " + wait[1] + " already asks for the rest of its buy limit";
			}
			else if (buy && left == 0)
			{
				// the GE will not sell more until the window resets: the one hard stop
				out.action = "BUY";
				out.price = (int) qq[0];
				out.discouraged = true;
				out.note = "Buy limit reached - resets in " + FlipSidebarSlotOverlay.clock(used[1] - nowS * 1000);
			}
			else if (buy)
			{
				Research r = series == null ? null
					: Research.of(itemId, series.getAsJsonObject().getAsJsonArray("data"));
				if (r != null)
				{
					r.monthLevel = Math.max(0, level);
				}
				Pick p = judgeYours(itemId, name, Math.max(0, left), qq, budget, all, most, risk, window, r, nowS);
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
				String why = p.rejected != null ? "Not a recommended flip: " + p.rejected
					: wait[0] > 0 && left > 0 ? String.format("%,d more already in slot %d · limit %,d left after it",
						wait[0], wait[1], left)
					: used != null && left > 0 ? String.format("limit %,d left · resets %s", left,
						FlipSidebarSlotOverlay.clock(used[1] - nowS * 1000))
					: p.firstWarning();
				out.note = warn.isEmpty() ? why : why.isEmpty() ? warn : warn + " · " + why;
			}
			else
			{
				// never under what was paid + tax (see sellCandidate)
				long ask = Math.max(qq[1], basis > 0 ? FlipEngine.breakEven(basis, itemId) : 0);
				out.action = "SELL";
				out.quantity = have;
				out.price = (int) ask;
				out.profit = basis > 0 ? (ask - FlipEngine.tax(ask, itemId) - basis) * have : 0;
				String why = basis <= 0 ? "no buy price on record - profit unknown"
					: ask > qq[1] ? String.format("market %,d is under what you paid + tax - listed at break-even", qq[1]) : "";
				out.note = warn.isEmpty() ? why : why.isEmpty() ? warn : warn + " · " + why;
			}
			done.accept(out);
		}).exceptionally(e ->
		{
			log.debug("Flip Sidebar: pricing item {} failed", itemId, e);
			return null;
		});
	}

	/**
	 * The most recent 5-minute AVERAGE on each side of a /timeseries answer:
	 * [insta-sell average, its epoch seconds, insta-buy average, its epoch seconds]; 0 = none.
	 */
	static long[] lastAverages(JsonElement series)
	{
		long[] out = new long[4];
		JsonArray data = series == null || !series.isJsonObject() ? null : series.getAsJsonObject().getAsJsonArray("data");
		for (JsonElement e : data == null ? new JsonArray() : data)
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject p = e.getAsJsonObject();
			long t = Research.num(p, "timestamp");
			long low = Research.num(p, "avgLowPrice");
			long high = Research.num(p, "avgHighPrice");
			if (low > 0 && t >= out[1])
			{
				out[0] = low;
				out[1] = t;
			}
			if (high > 0 && t >= out[3])
			{
				out[2] = high;
				out[3] = t;
			}
		}
		return out;
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

	/**
	 * {units the item's open buy offers still wait on, the first such slot (1-8; 0 = none)}. The GE caps what is
	 * BOUGHT in 4 hours, so a second offer for what the first still waits on cannot fill — in-game 2026-11-01:
	 * 11,000 Steel cannonballs in slot 6, then 11,000 more in slot 7 (limit 11,000). What is already bought is in
	 * limitUse. Under the lock.
	 */
	private long[] waitingBuys(int itemId)
	{
		long[] out = new long[2];
		for (GameSnapshot.Offer o : snap == null ? List.<GameSnapshot.Offer>of() : snap.offers)
		{
			if (o.buy && o.itemId == itemId && !o.complete && !o.cancelled)
			{
				out[0] += Math.max(0, o.total - o.filled);
				out[1] = out[1] == 0 ? o.slot : out[1];
			}
		}
		return out;
	}

	synchronized long[] waitingBuysNow(int itemId)
	{
		return waitingBuys(itemId);
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
			// a task that throws is never run again — keep the loop alive and say so (once in the log:
			// the same failure every 5 seconds would flood it)
			if (!warned)
			{
				warned = true;
				log.warn("Flip Sidebar: brain step failed", e);
			}
			else
			{
				log.debug("Flip Sidebar: brain step failed again", e);
			}
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
		boolean needTrades;
		boolean scanDue;
		long now = System.currentTimeMillis();
		synchronized (this)
		{
			s = snap;
			needLatest = hour == null || now - pricesAt > PRICES_EVERY_MS;
			needTrades = now - latestAt > LATEST_EVERY_MS;
			scanDue = rescanAsked || now >= nextScanAt;
		}
		if (s == null)
		{
			status("Log in to start");
			return;
		}
		if (config.livePrices())
		{
			loadMarketOnce();
		}
		measureReach(s);
		judgeHolds(s);
		if (needLatest)
		{
			try
			{
				JsonObject h = prices.data("/1h");
				JsonObject fb = prices.block("/5m");          // {timestamp, data}: also the whole market's memory
				JsonObject f = fb.getAsJsonObject("data");
				market.add(fb);
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
		if (needTrades && s != null)
		{
			try
			{
				JsonObject l = prices.data("/latest");       // one bulk request for every item
				synchronized (this)
				{
					latest = l;
					latestAt = now;
				}
				changed();
			}
			catch (IOException e)
			{
				log.debug("Flip Sidebar: /latest failed", e);   // the averages still work: carry on
			}
		}
		swingStep(s, now);
		gearStep(now);
		learnStep();
		marketStep(now);
		if (off())
		{
			status(otherEconomy ? OTHER_ECONOMY : "Paused - no flips or guidance");
			return;
		}
		if (scanDue)
		{
			scan(s);
		}
	}

	/** No guidance: paused, or a world with its own economy. */
	private boolean off()
	{
		return config.paused() || otherEconomy;
	}

	/**
	 * The coins to plan with: all of them; with "Use at most" set, what is left of it after everything
	 * already in flips — coins in buy offers AND what was bought and not sold yet (Holding, including
	 * what sits in sell offers) at what it cost. So it caps ALL the flipping together (user, 2026-10-31,
	 * at 10m: "the first offer suggested after was worth more then 10m", then "its not accounting for
	 * pre exisiting offers from before the setting was turned on").
	 */
	private long budget(GameSnapshot s)
	{
		// the INVENTORY's coins: what a GE offer can be paid with (user, 2026-10-31: "what i have in my invenotry is
		// what i have set to flip with" — "i just want it to use mostly all the cash i have at hand")
		long cap = Math.max(0, config.maxCashMillions()) * 1_000_000L;
		long coins = s == null ? 0 : s.inventoryCoins;
		return cap > 0 ? Math.min(coins, Math.max(0, cap - inBuys(s) - heldCost(s))) : coins;
	}

	/**
	 * The cash ONE new buy may use: half the budget while two or more GE slots are free, all of it in the last one.
	 * The GE buy limit and the slice of the hour's volume size the buy (FlipEngine.qtyFor); the half only stops one
	 * item taking everything — judgement, not measured.
	 * <p>MEASURED live 2026-11-01 (5m, High, the learned rules, 150m): shared evenly over the free slots (18.75m a
	 * buy, 1.32.0-1.37.0) the top 8 picks held 55m — runes stop at their GE limit (1.5-7m), and the even share shut
	 * out the items that can hold more (Tormented synapse 31.5m and Noxious halberd 28.7m dropped, Rondache cut to
	 * one); at 50m or 150m a buy the top 8 held 123m (user: "so were using 54m still not the full 150"). Before
	 * 1.32.0 a fixed quarter of the cash.</p>
	 */
	private long flipCash(GameSnapshot s)
	{
		return Math.min(perBuy(s), flipCashAll(s));
	}

	/** {@link #flipCash} without Most per buy: for an item you picked yourself that costs more than it. */
	private long flipCashAll(GameSnapshot s)
	{
		int free = s == null ? 1 : FlipSidebarConfig.geSlots(config) - s.offers.size();
		return free > 1 ? budget(s) / 2 : budget(s);
	}

	/**
	 * An item YOU picked, judged with the cash for one buy ({@link #flipCash}). One dearer than Most per buy is still
	 * priced — with all the cash, and a warning first — you chose it; the card never would.
	 */
	static Pick judgeYours(int itemId, String name, long left, long[] quote, long cash, long allCash, long mostPerBuy,
		RiskLevel risk, AdjustWindow window, Research r, long nowS)
	{
		Pick p = FlipEngine.judge(itemId, name, left, quote, cash, risk, window, r, nowS);
		if (p == null || !FlipEngine.NO_CASH.equals(p.rejected) || allCash <= cash)
		{
			return p;
		}
		Pick all = FlipEngine.judge(itemId, name, left, quote, allCash, risk, window, r, nowS);
		if (all == null || FlipEngine.NO_CASH.equals(all.rejected))
		{
			return p;
		}
		all.warnings.add(0, "costs more than Most per buy (" + gp(mostPerBuy) + ") - the card never suggests one this dear");
		return all;
	}

	/**
	 * ⭐ MOST PER BUY (user, 2026-11-04: "make sure its adjustable ... some guys are flipping with 1b or more", then
	 * "ideally it should auto scale those setting with the amount of cash you have ... there should be an over ride
	 * setting"). The setting in millions when set; 0 (the default) = {@link #autoPerBuy} of the coins you flip with.
	 * An item dearer than this gets no card (FlipEngine.scan: b > cap); a bigger buy gets fewer items.
	 */
	long perBuy(GameSnapshot s)
	{
		int set = Math.max(0, config.mostPerBuyMillions());
		return set > 0 ? set * 1_000_000L : autoPerBuy(capital(s), FlipSidebarConfig.geSlots(config));
	}

	/**
	 * The coins in flipping: the inventory's coins + buy offers + what was bought and not sold yet (at cost), at most
	 * "Use at most" when set — what {@link #budget} hands out over the session, so Most per buy does not shrink as
	 * buys go in.
	 */
	private long capital(GameSnapshot s)
	{
		if (s == null)
		{
			return 0;
		}
		long all = Math.max(0, s.inventoryCoins) + inBuys(s) + heldCost(s);
		long cap = Math.max(0, config.maxCashMillions()) * 1_000_000L;
		return cap > 0 ? Math.min(cap, all) : all;
	}

	/**
	 * Share of the coins in flipping that one buy may take, when Most per buy is automatic. MEASURED (rule replay,
	 * 2026-11-04, ~146m in flips): capping each buy at 10m / 20m / 30m (bulk buys get fewer items; one item dearer than
	 * the cap gets no card) turned the plugin's 638 flips from +1.68m into +8.30m / +7.52m / +6.57m (with the spike
	 * guard), and the 1,324 Copilot flips from +16.98m into +41.7m / +41.6m / +34.0m — one item over 20m: 24 Copilot
	 * flips, 11 lost 26.1m, 13 won 1.3m. 15% of ~146m = ~22m. ASSUMED: that the share, not the coin amount, carries over
	 * to other stacks (1b+ was never measured).
	 * <p>⚠ CORRECTED 2026-11-05: the Copilot losses over 20m were the user's own gear sold with an insta-sell at about
	 * -5% after days of use (Kodai wand held 183h, Noxious halberd 408h, Ghrazi rapier 269h), not flips (user: "some of
	 * these flips were just items i sold while having the plug in installed"). The same replay with Copilot flips held
	 * over 4 hours left out (961 flips, +41.19m as traded): spike guard alone +41.05m, cap 10m +37.54m, 20m +40.46m,
	 * 30m +40.70m — the cap no longer helps there; one item over 20m: 14 flips, 1 lost, +0.31m. On the plugin's own 638
	 * flips the 20m cap still turns +4.54m (spike guard alone) into +7.52m, from two bulk losses it shrinks: Onyx
	 * ascension bolts (e) 10,974 x 8,850 (-2.21m) and Rondache 2 x 18.5m (-3.10m). The share rests on those two.</p>
	 */
	static volatile double AUTO_PER_BUY_SHARE = 0.30;
	/*
	 * 1.60.41: 0.30 (was 0.15), live in rules.json ("perBuyShare", FlipEngine.applyRules). User, 2026-11-07, of a
	 * Dexterous prayer scroll card for ONE at 13,105,027 (9 traded in 5 min, buy limit 5; the 1m+ rule said 2, this
	 * share's ~22m of ~146m held it to 1): "why doo we buy just like one of something bigger ... why not put in of 3 of
	 * them? Why limit out money out like that all the time ... Even if the buy offer doesnt go threw u can still sell what
	 * fills". The user's choice, not a measurement: the share rested on two bulk losses (above); Copilot's 1m+ offers ran
	 * a median 18.2m, 75th percentile 30.3m, max 80.1m (2026-11-06 evening).
	 */

	/**
	 * Automatic Most per buy: {@value #AUTO_PER_BUY_SHARE} of {@code capital}, but never under an even share over the
	 * GE slots — so the slots can always hold all the coins (free-to-play's 3 slots: a third each). At 0.30: 146m, 8
	 * slots: 43.8m; 1b: 300m; 20m: 6m; 20m free-to-play: 6.67m.
	 */
	static long autoPerBuy(long capital, int slots)
	{
		return (long) (Math.max(0, capital) * Math.max(AUTO_PER_BUY_SHARE, 1.0 / Math.max(1, slots)));
	}

	/**
	 * ⭐ The pick sized to the coins on hand NOW (user, 2026-11-02 — a Dragon bones swing card for 23.3m with 10.6m in
	 * the inventory: "its asking for buys that i dont have the cash for... thats a big no go", "it should be recheck ever
	 * flip ever offer all the time it should know how much cash is on hand"). Scan and swing picks are sized with the
	 * coins at the scan; buy offers placed since have taken them. Fewer items when it no longer fits; null when not one
	 * fits or the fewer do not make the minimum profit. Checked on every refresh: the card and the Next rows.
	 */
	private Pick affordable(Pick p)
	{
		if (p == null || snap == null || p.bid <= 0)
		{
			return p;
		}
		long can = Math.max(0, flipCash(snap)) / p.bid;
		if (can >= p.qty)
		{
			return p;
		}
		Pick c = can <= 0 ? null : p.sized(can);
		return c == null || c.total < Math.max(1, config.minProfit()) ? null : c;
	}

	/**
	 * The card's pick re-checked against the coins on hand ({@link #affordable}). While you type its offer in, the item
	 * stays (only fewer of it), so the card does not change under you.
	 */
	private Pick refit(Pick p)
	{
		Pick a = affordable(p);
		if (a == null && p != null && settingUp == p.itemId && snap != null && p.bid > 0)
		{
			long can = Math.max(0, flipCash(snap)) / p.bid;
			a = can > 0 ? p.sized(can) : p;
		}
		return a;
	}

	/**
	 * A RAISE on a buy costs more coins — what is left to buy times the step up — and the GE takes them from the
	 * inventory: WAIT, with why, when the inventory cannot pay (the same check as the card's, on every offer).
	 */
	private List<OfferAdvice> raisesYouCanPay(List<OfferAdvice> adv)
	{
		if (snap == null)
		{
			return adv;
		}
		List<OfferAdvice> out = new ArrayList<>(adv.size());
		for (OfferAdvice a : adv)
		{
			GameSnapshot.Offer o = a.offer;
			long extra = OfferAdvice.RAISE.equals(a.verdict) && o.buy
				? (long) Math.max(0, o.total - o.filled) * Math.max(0, a.target - o.price) : 0;
			out.add(extra > snap.inventoryCoins ? OfferAdvice.cannotPay(a, extra) : a);
		}
		return out;
	}

	/** The status line; a finished scan's "worth doing" with the coins on hand NOW (it said the scan's: 46.69m, 10.64m left). */
	private String statusNow()
	{
		return status != null && status.endsWith(" worth doing") && snap != null
			? status + " (" + gp(budget(snap)) + " to spend, up to " + gp(perBuy(snap)) + " a buy)" : status;
	}

	/** What the bought-and-unsold stock (Holding) cost. */
	private static long heldCost(GameSnapshot s)
	{
		long cost = 0;
		for (GameSnapshot.Holding h : s == null ? List.<GameSnapshot.Holding>of() : s.holdings)
		{
			cost += h.qty * h.basis;
		}
		return cost;
	}

	/**
	 * Item -> {paid each, last buy epoch ms, what to do under water (PARK / CUT, OfferAdvice.PAID_*), how often
	 * it came back to break-even in the last day in 1/1000 (-1 = not known)} from Holding — a sell offer's break-even.
	 */
	private Map<Integer, long[]> paid(GameSnapshot s, long now)
	{
		Map<Integer, long[]> out = new HashMap<>();
		for (GameSnapshot.Holding h : s.holdings)
		{
			long even = h.basis > 0 ? FlipEngine.breakEven(h.basis, h.itemId) : 0;
			double share = reachOf(h.itemId, even);
			// the user's own Hold is never lowered under break-even
			int how = s.held.contains(h.itemId) ? HELD : underWater(h, now);
			out.put(h.itemId, new long[]{h.basis, h.lastBuy, how, share < 0 ? -1 : Math.round(share * 1000)});
		}
		return out;
	}

	/** For each held item, how often in the last day its insta-buy reached its break-even (MarketMemory). Brain thread. */
	private void measureReach(GameSnapshot s)
	{
		Map<Integer, double[]> out = new HashMap<>();
		int held = market.blocks();
		if (held >= REACH_MIN_BLOCKS)
		{
			for (GameSnapshot.Holding h : s.holdings)
			{
				if (h.basis <= 0)
				{
					continue;
				}
				long even = FlipEngine.breakEven(h.basis, h.itemId);
				out.put(h.itemId, new double[]{even, reachShare(market.series(h.itemId), held, even)});
			}
		}
		reach = out;
	}

	/** How often the item's insta-buy reached {@code even} in the last day; -1 when not measured at that price. */
	private double reachOf(int itemId, long even)
	{
		double[] r = reach.get(itemId);
		return r == null || (long) r[0] != even ? -1 : r[1];
	}

	/**
	 * HOLD OR SELL for each held item worth less than what was paid (HoldOrSell): from the wiki's hourly prices of the
	 * week (cached ~30 min in WikiPrices, so one request per such item per half hour) and the last day's reach. Also
	 * hands the HOLD reasons to OfferAdvice so a sell offer of one is not lowered under break-even. Brain thread.
	 */
	private void judgeHolds(GameSnapshot s)
	{
		Map<Integer, HoldOrSell.Verdict> out = new HashMap<>();
		Map<Integer, String> holdWhy = new HashMap<>();
		if (config.livePrices())
		{
			for (GameSnapshot.Holding h : s.holdings)
			{
				if (h.basis <= 0 || h.qty <= 0)
				{
					continue;
				}
				long market = live(String.valueOf(h.itemId))[1];
				long even = FlipEngine.breakEven(h.basis, h.itemId);
				if (market <= 0 || market >= even)
				{
					continue;
				}
				try
				{
					HoldOrSell.Verdict v = HoldOrSell.decide(prices.timeseries(h.itemId, "1h"), h.basis, h.qty, market, h.itemId,
						reachOf(h.itemId, even), FlipEngine.ROI_GOAL_PCT_HOUR);
					if (v != null)
					{
						out.put(h.itemId, v);
						if (v.hold)
						{
							holdWhy.put(h.itemId, v.text);
						}
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("Flip Sidebar: no hold-or-sell for {}", h.itemId, e);
				}
			}
		}
		verdicts = out;
		OfferAdvice.holdWhy = holdWhy;
	}

	/**
	 * An under-water holding. ⚠ From 1.55.0 PARK no longer keeps anything: it means "listed at break-even until
	 * FlipEngine.SELL_PATIENCE_MS after the buy, then sold at the market" (parkedNow, sellCandidate, OfferAdvice.parkOrCut;
	 * user, 2026-11-02: "The point of this isnt to keep anything"). The history below is why it once kept items.
	 * PARK it — from 1.44.0, and from 1.54.0 for up to {@link #HOLD_MAX_MS} from its buy, then
	 * TIME: a sell at the market is suggested (the user's own Hold excepted). User, 2026-11-01: "it should be consistently
	 * pull profit or atleast suggesting to put items on hold for the user to pick to sell at a loss. i would of held the
	 * ruens and light bearers" (Soul runes -143k and Lightbearer -163k that morning, both sold under what was paid). The
	 * plugin never sells by itself: the item waits in Holding, its row shows the loss and how often the price got
	 * back to break-even in the last day, and "sell now at the market" is the user's choice (sellAtLoss). Measured
	 * (tools/patience_replay.py) for the record: an item back at break-even in under 5% of the day did a little better
	 * sold at once (-70k against -73k to -79k a 5m order) — the row shows that share so the user can judge.
	 */
	static int underWater(GameSnapshot.Holding h, long now)
	{
		int how = underWater(h.basis);
		return how == PARK && h.lastBuy > 0 && now - h.lastBuy >= HOLD_MAX_MS ? TIME : how;   // HOLD_MAX_MS from 1.54.0
	}

	/**
	 * Is this holding parked now? Only what is IN the inventory and in no GE offer. From its first dip under break-even
	 * with underWater = PARK it STAYS parked — listed under Holding, saying "back at ..." once the price is — until it is
	 * sold or listed, leaves the inventory, or underWater says CUT (its 8 hours are up, or it rarely comes back). Under
	 * the lock.
	 */
	private boolean parkedNow(GameSnapshot.Holding h, long market, long even, long now)
	{
		if (letGo.contains(h.itemId))
		{
			parked.remove(h.itemId);
			return false;             // the user chose to sell it at a loss
		}
		boolean here = snap != null && snap.flipUnits(h.itemId) > 0 && !inOffer(h.itemId);
		long[] sw = snap == null ? null : snap.swing.get(h.itemId);
		// ⭐ SELL ONLY lists a swing too (1.60.73): winding down, it goes on the card AT ITS TARGET instead of waiting
		// unseen in the inventory. User, 2026-11-07, in Sell only with 7,293 Sharks (a 7-day swing for 754) and 1,228
		// Super restores (an 8-hour swing for 8,824) in the inventory and two GE slots free: "its not adviseing me to sell
		// the last few items. its confused". The user's own Hold stays held.
		boolean swingWaits = sw != null && now < sw[1] && !config.sellOnly();
		if (here && market > 0 && (snap.held.contains(h.itemId) || swingWaits))
		{
			parked.putIfAbsent(h.itemId, now);
			return true;              // held by the user (the card's Hold), or a swing waiting for its target
		}
		// ⭐ a LOSING buy is never kept (1.55.0; user, 2026-11-02: "The point of this isnt to keep anything"): it is sold
		// — at break-even until FlipEngine.SELL_PATIENCE_MS after the buy, then at the market (sellCandidate)
		parked.remove(h.itemId);
		return false;
	}

	void setJournal(TradeJournal j)
	{
		journal = j;
	}

	void setSwingPlaced(SwingPlaced s)
	{
		swingPlaced = s;
	}

	/** A swing buy on the card went into the GE: hold its item for its target (Swing.HOLD_MS). Under the lock. */
	private void noteSwing(Pick p)
	{
		SwingPlaced sp = swingPlaced;
		if (p != null && p.swing && sp != null)
		{
			sp.placed(p.itemId, p.ask, System.currentTimeMillis() + (p.deep ? FlipEngine.DEEP_HOLD_MS : Swing.HOLD_MS));
		}
	}

	/**
	 * The swing buys now (Swing.find, once a minute) from the whole market's last day — the "Swing trades" setting. The
	 * brain's thread (MarketMemory is not thread-safe).
	 */
	private void swingStep(GameSnapshot s, long now)
	{
		if (!config.swingTrades() || s == null || market.blocks() < Swing.MIN_BLOCKS || !hasItemNames())
		{
			swingPicks = List.of();   // (no item list yet: no names, and no buy limits to size them by)
			return;
		}
		if (now - swingAt < SWING_EVERY_MS)
		{
			return;
		}
		swingAt = now;
		JsonObject h;
		JsonObject f;
		JsonObject l;
		long cash;
		Set<Integer> skip = new HashSet<>(blocked());
		synchronized (this)
		{
			h = hour;
			f = five;
			l = latest;
			for (GameSnapshot.Offer o : s.offers)
			{
				skip.add(o.itemId);
			}
			for (GameSnapshot.Holding x : s.holdings)
			{
				skip.add(x.itemId);
			}
			skip.addAll(south.keySet());
			skip.addAll(learner.resting(now));
			skip.addAll(learner.offByYou(now).keySet());     // your "Don't suggest for a day / a week"
			cash = flipCash(s);
		}
		boolean f2p = config.freeToPlay();
		long nowS = now / 1000;
		List<Pick> found = Swing.find(market.ids(), market::series, market.blocks(),
			id ->
			{
				String k = String.valueOf(id);
				return f2p && engine.members(id) ? 0 : FlipEngine.quoteLive(item(h, k), item(f, k), item(l, k), nowS)[0];
			},
			id -> FlipEngine.remaining(engine.limit(id), s.limitUse.containsKey(id) ? s.limitUse.get(id)[0] : 0),
			id ->
			{
				String n = engine.name(id);
				return n == null ? "Item " + id : n;
			},
			skip, cash, Math.max(0, config.minProfit()), nowS);
		// the spike guard (FlipEngine.SPIKE_MAX) — a slide off a spike looks "low in its day" too; no 30-day check, no swing
		List<Pick> calm = new ArrayList<>();
		for (Pick p : found)
		{
			long level = engine.monthLevel(p.itemId);
			if (level != FlipEngine.LEVEL_UNKNOWN && !FlipEngine.spiked(p.bid, level))
			{
				calm.add(p);
			}
		}
		// ⭐ deep swings: a multi-day dip under the 30-day level (Swing.findDeep, profiles.json)
		Set<Integer> taken = new HashSet<>(skip);
		calm.forEach(p -> taken.add(p.itemId));
		calm.addAll(Swing.findDeep(market.ids(), market::series, market.blocks(),
			id ->
			{
				String k = String.valueOf(id);
				return f2p && engine.members(id) ? 0 : FlipEngine.quoteLive(item(h, k), item(f, k), item(l, k), nowS)[0];
			},
			id -> FlipEngine.remaining(engine.limit(id), s.limitUse.containsKey(id) ? s.limitUse.get(id)[0] : 0),
			id ->
			{
				String n = engine.name(id);
				return n == null ? "Item " + id : n;
			},
			taken, id -> FlipEngine.dumping(item(f, String.valueOf(id))), cash, Math.max(0, config.minProfit()), nowS));
		calm.sort(java.util.Comparator.comparingDouble(Pick::rank).reversed());
		swingPicks = List.copyOf(calm);
		changed();
	}

	/**
	 * ⭐ EQUIPMENT WATCH (1.60.46): gear the player wants for their own account, and when to buy it. User, 2026-11-07: "I
	 * want a roundache and zoryas tomb for my account Idk when to buy them" — "Can I have this plug in track falling items
	 * and tell me when its time to buy something for my account". One item: its daily insta-sell averages (the wiki's
	 * /timeseries 24h — one cached request a day, WikiPrices) and its last insta-sell now. WAIT while it keeps making new
	 * lows (the last one under FlipEngine.GEAR_FLOOR_DAYS ago); BUY once it has gone that long without one and is within
	 * GEAR_NEAR_LOW_PCT of that low; UP when it has turned and climbed past that. The rule ("7 days without a new low") is
	 * JUDGEMENT, not measured — after a release the drops of past raids slid for weeks (NOTES.md: Raids 4, ToA).
	 */
	static final class Gear
	{
		static final int UNKNOWN = -1;
		static final int WAIT = 0;
		static final int BUY = 1;
		static final int UP = 2;
		final int itemId;
		final String name;
		final int state;
		/** Its last insta-sell now (or the last day's), its 90-day low, its 30-day median, days since that low. */
		final long now;
		final long low;
		final long avg30;
		final int daysSinceLow;
		final String why;
		/**
		 * Its USUAL price — the median daily insta-sell of the last 180 days, for an item with 180+ days traded; 0 for a
		 * NEW item (daysOld = days since its first trade in the year shown, -1 = not new). No "settles at" estimate:
		 * MEASURED 2026-11-07 (Weird Gloop's daily GE history of 35 past raid / boss drops,
		 * ~/.cache/claude-gs/bigbrain/settle/settle_test.py): a decay curve fitted to the first 14 / 21 / 30 days
		 * predicted the 3-6-month price a median -29% to -62% off (too low), worse than the price on day 21-30 itself
		 * (median +5% to +16%, |error| 23-40%), and many rose after their first month (Soulreaper axe ~12m → 345m,
		 * Oathplate helm ~11m → 88m, Avernic treads 107m → 234m). User asked for "estimated normal value".
		 */
		final long usual;
		final int daysOld;
		/** The high-alch floor: its high-alch value less a nature rune — a price it does not go under for long; 0 = none. */
		long floor;

		Gear(int itemId, String name, int state, long now, long low, long avg30, int daysSinceLow, String why)
		{
			this(itemId, name, state, now, low, avg30, daysSinceLow, why, 0, -1);
		}

		Gear(int itemId, String name, int state, long now, long low, long avg30, int daysSinceLow, String why, long usual,
			int daysOld)
		{
			this.itemId = itemId;
			this.name = name;
			this.state = state;
			this.now = now;
			this.low = low;
			this.avg30 = avg30;
			this.daysSinceLow = daysSinceLow;
			this.why = why;
			this.usual = usual;
			this.daysOld = daysOld;
		}

		Gear with(long usual, int daysOld)
		{
			Gear g = new Gear(itemId, name, state, now, low, avg30, daysSinceLow, why, usual, daysOld);
			g.floor = floor;
			return g;
		}
	}

	/** {usual price (180+ days traded) or 0, days since its first trade when under 180 traded days else -1}. */
	static long[] gearUsual(JsonArray daily, long nowMs)
	{
		long nowS = nowMs / 1000;
		List<Long> half = new ArrayList<>();
		long first = Long.MAX_VALUE;
		int traded = 0;
		for (JsonElement e : daily == null ? new JsonArray() : daily)
		{
			JsonObject d = e.getAsJsonObject();
			long ts = Research.num(d, "timestamp");
			long lo = Research.num(d, "avgLowPrice");
			if (ts <= 0 || lo <= 0)
			{
				continue;
			}
			first = Math.min(first, ts);
			traded++;
			if (ts >= nowS - 180 * 86_400L)
			{
				half.add(lo);
			}
		}
		if (traded >= 180 && !half.isEmpty())
		{
			Collections.sort(half);
			return new long[]{half.get(half.size() / 2), -1};
		}
		return new long[]{0, first == Long.MAX_VALUE ? 0 : (int) Math.max(0, (nowS - first) / 86_400L)};
	}

	/** An item's watch verdict from its daily series ({@code daily}, /timeseries 24h) and its last insta-sell ({@code live}). */
	static Gear judgeGear(int itemId, String name, JsonArray daily, long live, long nowMs)
	{
		long nowS = nowMs / 1000;
		List<long[]> pts = new ArrayList<>();
		for (JsonElement e : daily == null ? new JsonArray() : daily)
		{
			JsonObject d = e.getAsJsonObject();
			long ts = Research.num(d, "timestamp");
			long lo = Research.num(d, "avgLowPrice");
			if (ts > 0 && lo > 0)
			{
				pts.add(new long[]{ts, lo});
			}
		}
		if (pts.size() < 3)
		{
			return new Gear(itemId, name, Gear.UNKNOWN, live, 0, 0, 0, "not enough price history yet");
		}
		long low = Long.MAX_VALUE;
		long lowTs = 0;
		List<Long> month = new ArrayList<>();
		for (long[] p : pts)
		{
			if (p[0] >= nowS - 90 * 86_400L && p[1] <= low)
			{
				low = p[1];
				lowTs = p[0];
			}
			if (p[0] >= nowS - 30 * 86_400L)
			{
				month.add(p[1]);
			}
		}
		long now = live > 0 ? live : pts.get(pts.size() - 1)[1];
		if (low == Long.MAX_VALUE || now < low)
		{
			low = now;
			lowTs = nowS;                 // a new low right now
		}
		Collections.sort(month);
		long avg30 = month.isEmpty() ? 0 : month.get(month.size() / 2);
		int days = (int) Math.max(0, (nowS - lowTs) / 86_400L);
		double up = (now - low) * 100.0 / Math.max(1, low);
		if (days < FlipEngine.GEAR_FLOOR_DAYS)
		{
			return new Gear(itemId, name, Gear.WAIT, now, low, avg30, days, String.format("Wait - still falling: new low %s at "
				+ "%s", days == 0 ? "today" : days == 1 ? "1 day ago" : days + " days ago", gp(low)));
		}
		if (up <= FlipEngine.GEAR_NEAR_LOW_PCT)
		{
			return new Gear(itemId, name, Gear.BUY, now, low, avg30, days, String.format("Good time to buy - no new low for %d "
				+ "days; %.0f%% over its low (%s)", days, up, gp(low)));
		}
		return new Gear(itemId, name, Gear.UP, now, low, avg30, days, String.format("Up %.0f%% off its low (%s, %d days ago) "
			+ "- it has turned", up, gp(low), days));
	}

	/** The watch list's verdicts (gearStep), in the list's order. Any thread. */
	List<Gear> gear()
	{
		return gear;
	}

	/** Told when an item turns BUY ({@code true}) or stops being one ({@code false}): FlipSidebarPlugin notifies once. */
	interface GearEvents
	{
		void changed(Gear g, boolean buy);
	}

	void setGearEvents(GearEvents e)
	{
		gearEvents = e;
	}

	/** The watch list was edited: judge it again on the next tick. Any thread. */
	void gearChanged()
	{
		gearAt = 0;
		gearWatchWas = null;
	}

	private volatile List<Gear> gear = List.of();
	private volatile GearEvents gearEvents;
	private final Map<Integer, Integer> gearState = new HashMap<>();
	private volatile long gearAt;
	private volatile String gearWatchWas;
	/** The watch list is judged again this often (a day's price comes once a day; the live price moves). */
	static final long GEAR_EVERY_MS = 30 * 60_000L;

	/** The equipment watch (Gear), on the brain's thread: the "gearWatch" ids (comma-separated), each judged. */
	private void gearStep(long now)
	{
		String watch = config.gearWatch() == null ? "" : config.gearWatch().trim();
		if (!config.livePrices() || watch.isEmpty())
		{
			if (!gear.isEmpty())
			{
				gear = List.of();
				changed();
			}
			gearWatchWas = watch;
			return;
		}
		if (watch.equals(gearWatchWas) && now - gearAt < GEAR_EVERY_MS)
		{
			return;
		}
		gearWatchWas = watch;
		gearAt = now;
		List<Gear> out = new ArrayList<>();
		for (String part : watch.split(","))
		{
			int id;
			try
			{
				id = Integer.parseInt(part.trim());
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			String name = engine.name(id) == null ? "Item " + id : engine.name(id);
			Gear g;
			try
			{
				JsonObject l = latestItem(String.valueOf(id));
				JsonArray daily = prices.timeseries(id, "24h");
				g = judgeGear(id, name, daily, l == null ? 0 : Research.num(l, "low"), now);
				long[] u = gearUsual(daily, now);
				g = g.with(u[0], (int) u[1]);
				long ha = engine.highAlch(id);
				long nature = live(String.valueOf(FlipEngine.NATURE_RUNE))[1];
				g.floor = ha > 0 && nature > 0 ? Math.max(0, ha - nature) : 0;
			}
			catch (IOException | RuntimeException e)
			{
				g = new Gear(id, name, Gear.UNKNOWN, 0, 0, 0, 0, "can't get its price history right now");
			}
			Integer was = gearState.put(id, g.state);
			GearEvents ev = gearEvents;
			if (ev != null && g.state != Gear.UNKNOWN && (was == null || was != g.state))
			{
				ev.changed(g, g.state == Gear.BUY);
			}
			out.add(g);
		}
		gear = List.copyOf(out);
		changed();
	}

	/** What a holding is sold at: its swing target while its swing waits (TradeLog.swing), else break-even. Under the lock. */
	private long goal(GameSnapshot.Holding h, long even, long now)
	{
		long[] sw = snap == null ? null : snap.swing.get(h.itemId);
		// a swing sells at +1% after tax (Swing.EXIT_PCT) — or its marked target, when an older mark has a lower one
		return sw != null && now < sw[1] ? Math.max(even, Math.min(sw[0], Swing.exitPrice(h.basis, h.itemId))) : even;
	}

	/**
	 * The market for {@code itemId} now, for the journal: the 5-minute and hour averages with their volumes and the last
	 * trade each side with its time (the wiki's /latest). Empty when not loaded.
	 */
	synchronized JsonObject marketNow(int itemId)
	{
		JsonObject m = new JsonObject();
		String key = String.valueOf(itemId);
		JsonObject f = item(five, key);
		JsonObject h = item(hour, key);
		JsonObject l = item(latest, key);
		if (f != null)
		{
			m.addProperty("h5", Research.num(f, "avgHighPrice"));
			m.addProperty("l5", Research.num(f, "avgLowPrice"));
			m.addProperty("hv5", Research.num(f, "highPriceVolume"));
			m.addProperty("lv5", Research.num(f, "lowPriceVolume"));
		}
		if (h != null)
		{
			m.addProperty("h1", Research.num(h, "avgHighPrice"));
			m.addProperty("l1", Research.num(h, "avgLowPrice"));
		}
		if (l != null)
		{
			m.addProperty("hi", Research.num(l, "high"));
			m.addProperty("hit", Research.num(l, "highTime"));
			m.addProperty("lo", Research.num(l, "low"));
			m.addProperty("lot", Research.num(l, "lowTime"));
		}
		return m;
	}

	/** Journal a new card: what it says, what was predicted and from what. Under the lock. */
	private void journalCard(Suggestion c, Pick p, SellRow r)
	{
		TradeJournal j = journal;
		String key = c == null ? "" : c.action + ":" + c.itemId + ":" + c.price + ":" + c.quantity;
		if (j == null || key.equals(journalCard))
		{
			return;
		}
		journalCard = key;
		JsonObject o = new JsonObject();
		o.addProperty("action", c == null ? "NONE" : c.action);
		if (c != null && c.itemId > 0)
		{
			o.addProperty("item", c.itemId);
			o.addProperty("name", c.name);
			o.addProperty("qty", c.quantity);
			o.addProperty("price", c.price);
			o.addProperty("profit", c.profit);
			if (c.likely != Long.MIN_VALUE)
			{
				o.addProperty("likely", c.likely);
			}
		}
		if (c != null && c.note != null && !c.note.isEmpty())
		{
			o.addProperty("note", c.note);
		}
		if (p != null)
		{
			o.addProperty("ask", p.ask);
			o.addProperty("each", p.each);
			o.addProperty("fillMin", p.fillMinutes);
			o.addProperty("perHour", (long) p.perHour());
			o.addProperty("asOf", p.asOf);
			o.addProperty("proven", FlipEngine.proven.contains(p.itemId));
			// every note and the ranking boost (1.60.47): the card shows only the first note, so a dip / cycle reason
			// behind a risk note never reached the journal
			o.addProperty("boost", p.boost);
			o.addProperty("boostWhy", p.boostWhy);
			o.addProperty("deep", p.deep);
			JsonArray ws = new JsonArray();
			p.warnings.forEach(ws::add);
			o.add("warnings", ws);
			Research res = p.research;
			if (res != null && res.ok)
			{
				o.addProperty("contig", res.contiguity);
				o.addProperty("medMargin", res.medianMargin);
				o.addProperty("neg", res.negativeBuckets);
				o.addProperty("sampled", res.sampled);
				o.addProperty("buyFlowHr", res.buyFlowHr);
				o.addProperty("sellFlowHr", res.sellFlowHr);
				if (res.trendKnown)
				{
					o.addProperty("trendPct", res.trendPct);
				}
			}
		}
		if (r != null)
		{
			o.addProperty("paid", r.holding.basis);
			o.addProperty("lastBuy", r.holding.lastBuy);
			o.addProperty("each", r.each);
			o.addProperty("market", r.market);
			o.addProperty("cut", r.cut);
		}
		if (c != null && c.itemId > 0)
		{
			o.add("mk", marketNow(c.itemId));
		}
		j.log("card", o);
	}

	/**
	 * A held item back at break-even, every GE slot taken and none about to free itself (nothing to collect or already
	 * to cancel): CANCEL the newest buy offer that has not filled at all, so the held item can be sold while it is back
	 * — user, 2026-11-01: "when something goes into profit. we should canel an offer that hasnt been filled at all like
	 * a recent buy offer and cancel it to get the hold item sold off while its back in profit". Under the lock.
	 */
	private List<OfferAdvice> freeSlotForHeld(List<OfferAdvice> adv, boolean live)
	{
		if (!live || snap == null || adv.size() < FlipSidebarConfig.geSlots(config))
		{
			return adv;
		}
		for (OfferAdvice a : adv)
		{
			if (OfferAdvice.COLLECT.equals(a.verdict) || OfferAdvice.CANCEL.equals(a.verdict))
			{
				return adv;           // a slot frees anyway
			}
		}
		String ready = null;
		long gain = 0;
		for (GameSnapshot.Holding h : snap.holdings)
		{
			int have = snap.flipUnits(h.itemId);
			long market = live(String.valueOf(h.itemId))[1];
			long even = h.basis > 0 ? FlipEngine.breakEven(h.basis, h.itemId) : 0;
			if (parked.containsKey(h.itemId) && have > 0 && even > 0 && market >= goal(h, even, System.currentTimeMillis()))
			{
				ready = h.name;
				gain = (market - FlipEngine.tax(market, h.itemId) - h.basis) * Math.min(have, h.qty);
				break;
			}
		}
		OfferAdvice newest = null;
		for (OfferAdvice a : ready == null ? List.<OfferAdvice>of() : adv)
		{
			GameSnapshot.Offer o = a.offer;
			if (o.buy && o.filled == 0 && !o.complete && !o.cancelled && !a.ignored()
				&& (newest == null || o.placedAt > newest.offer.placedAt))
			{
				newest = a;
			}
		}
		if (newest == null)
		{
			return adv;
		}
		List<OfferAdvice> out = new ArrayList<>(adv);
		out.remove(newest);
		out.add(0, OfferAdvice.freeSlot(newest, String.format("Cancel offer - nothing bought yet; frees a slot to sell the "
			+ "held %s while it is back in profit (%s%s)", ready, gain >= 0 ? "+" : "-", gp(Math.abs(gain)))));
		return out;
	}

	/** Journal each slot whose advice changed. Under the lock. */
	private void journalAdvice(List<OfferAdvice> adv)
	{
		TradeJournal j = journal;
		if (j == null)
		{
			return;
		}
		Set<Integer> seen = new HashSet<>();
		for (OfferAdvice a : adv)
		{
			seen.add(a.offer.slot);
			String key = a.offer.itemId + ":" + a.verdict + ":" + a.target;
			if (key.equals(journalAdvice.put(a.offer.slot, key)))
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("slot", a.offer.slot);
			o.addProperty("item", a.offer.itemId);
			o.addProperty("buy", a.offer.buy);
			o.addProperty("price", a.offer.price);
			o.addProperty("verdict", a.verdict);
			o.addProperty("target", a.target);
			o.addProperty("why", a.why);
			if (a.expected != Long.MIN_VALUE)
			{
				o.addProperty("expected", a.expected);
			}
			o.add("mk", marketNow(a.offer.itemId));
			j.log("advice", o);
		}
		journalAdvice.keySet().retainAll(seen);
	}

	/** "Sell now at the market" on a Holding row: no more parking it, and its sell (at a loss) goes on the card. */
	void sellAtLoss(int itemId)
	{
		TradeJournal j = journal;
		if (j != null)
		{
			JsonObject o = new JsonObject();
			o.addProperty("item", itemId);
			j.log("letgo", o);
		}
		synchronized (this)
		{
			letGo.add(itemId);
			parked.remove(itemId);
			sellSkipped.remove(itemId);
			sellFirst = itemId;
		}
		changed();
	}

	/**
	 * Held again by the user (the card's Hold, or Hold long-term) after "sell now at the market": the hold wins. In-game
	 * 2026-11-01 19:39-19:40, Ascension shards: a click on the Holding row's sell-now link let them go, the card's Hold
	 * went into the trade log, and the sell card stayed — letGo outranks held in parkedNow (user: "then i went to put
	 * the card on hold and it wont let me").
	 */
	void keep(int itemId)
	{
		synchronized (this)
		{
			letGo.remove(itemId);
			if (sellFirst != null && sellFirst == itemId)
			{
				sellFirst = null;
			}
		}
		changed();
	}

	private static Map<Integer, Long> flipsInBank(GameSnapshot snap)
	{
		return snap == null ? Map.of() : snap.inBank;
	}

	/** Each buy offer's item -> the price its buy was first placed at, kept in {@code memory} ({@link #firstBid}). */
	static Map<Integer, Long> firstBids(Map<Integer, long[]> memory, List<GameSnapshot.Offer> offers, long now)
	{
		Map<Integer, Long> out = new HashMap<>();
		for (GameSnapshot.Offer o : offers)
		{
			if (o.buy && o.price > 0)
			{
				long[] b = memory.computeIfAbsent(o.itemId, k -> new long[]{o.price, now});
				b[1] = now;
				out.put(o.itemId, b[0]);
			}
		}
		memory.values().removeIf(b -> now - b[1] > FIRST_BID_GAP_MS);
		return out;
	}

	/**
	 * In Holding (parked, held by the user, or a swing waiting): never suggested as a buy until it is sold — user,
	 * 2026-11-01, of a card to buy 2 Hallowfell at 1,409,703 while 3 bought at 1,445,490 waited under water: "it wants be
	 * to buy an item i have on hold. thats confusing to me bc i cant turn a profit on the ones i have why buy more".
	 * Under the lock.
	 */
	private boolean inHolding(int itemId)
	{
		// ...or anywhere in the inventory, on record or not (user: "buying items i already have in my inventory")
		return parked.containsKey(itemId) || (snap != null && (snap.held.contains(itemId) || snap.inventory.containsKey(itemId)));
	}

	/**
	 * Holding is FULL: as many items wait in the inventory as the "Most items held" setting allows, counting the swing
	 * buys on the GE that will join them — no new swing is bought (a losing buy is never parked from 1.55.0). User, 2026-11-01, with 12 items held (~75m): "we need a hold item limit or something ten
	 * items is a lot to hold". The card's Hold (the user's own choice) and a swing already bought still go in. Under the
	 * lock.
	 */
	private boolean holdingFull()
	{
		Set<Integer> ids = new HashSet<>(parked.keySet());
		for (GameSnapshot.Offer o : snap == null ? List.<GameSnapshot.Offer>of() : snap.offers)
		{
			if (o.buy && !o.cancelled && snap.swing.containsKey(o.itemId))
			{
				ids.add(o.itemId);
			}
		}
		return ids.size() >= Math.max(0, config.holdLimit());
	}

	/** An open (not finished, not cancelled) GE offer of the item. Under the lock. */
	private boolean inOffer(int itemId)
	{
		for (GameSnapshot.Offer o : snap == null ? List.<GameSnapshot.Offer>of() : snap.offers)
		{
			if (o.itemId == itemId && !o.complete && !o.cancelled)
			{
				return true;
			}
		}
		return false;
	}

	/** underWater on its numbers: what was paid each (0 = not on record). */
	static int underWater(long basis)
	{
		return basis > 0 ? PARK : CUT;          // no price on record: nothing to hold out for
	}

	/** Of the {@code held} blocks in a MarketMemory series, the share whose average insta-buy reached {@code even}. */
	static double reachShare(int[] series, int held, long even)
	{
		int at = 0;
		for (int i = 0; i < series.length / 4; i++)
		{
			at += series[i * 4] >= even ? 1 : 0;
		}
		return held <= 0 ? -1 : (double) at / held;
	}

	/**
	 * Bought units waiting in a GE slot (not collected): the record (Holding) beyond what is in the inventory, the bank and
	 * open sell offers — only for items in a GE slot now, so a stale record elsewhere is never counted — at what they
	 * cost. A filled buy's items count here until collected; once in the inventory they are its items (itemsValue).
	 */
	private long uncollected(GameSnapshot s)
	{
		if (s == null)
		{
			return 0;
		}
		Map<Integer, Long> listed = new HashMap<>();
		Set<Integer> inSlot = new HashSet<>();
		for (GameSnapshot.Offer o : s.offers)
		{
			if (ignored.contains(o.key()))
			{
				continue;
			}
			inSlot.add(o.itemId);
			if (!o.buy && !o.complete && !o.cancelled)
			{
				listed.merge(o.itemId, (long) Math.max(0, o.total - o.filled), Long::sum);
			}
		}
		long value = 0;
		for (GameSnapshot.Holding h : s.holdings)
		{
			if (h.basis <= 0 || !inSlot.contains(h.itemId))
			{
				continue;
			}
			long known = s.flipUnits(h.itemId) + s.inBank.getOrDefault(h.itemId, 0L) + listed.getOrDefault(h.itemId, 0L);
			value += Math.max(0, h.qty - known) * h.basis;
		}
		return value;
	}

	/** Coins still tied up in the unfilled part of open buy offers (an ignored offer is not flipping money). */
	private long inBuys(GameSnapshot s)
	{
		long in = 0;
		for (GameSnapshot.Offer o : s == null ? List.<GameSnapshot.Offer>of() : s.offers)
		{
			if (o.buy && !o.complete && !o.cancelled && !ignored.contains(o.key()))
			{
				in += (long) Math.max(0, o.total - o.filled) * o.price;
			}
		}
		return in;
	}

	/** Items never to suggest (right-click "Never suggest" in the panel). */
	/**
	 * ⭐ Every item kept off the suggestions RIGHT NOW: your Never list, your "Don't suggest for a day / a week", and the
	 * items the plugin rests after a loss. Checked on every refresh (user, 2026-11-02: Ascension shards on "a week" came
	 * back as a swing card after the next card — pool() checked only the Never list; the day/week list reached the swing
	 * picks only at their next 60 s pass and the flips only at the next scan).
	 */
	private Set<Integer> keptOff(long nowMs)
	{
		Set<Integer> out = blocked();
		out.addAll(learner.offByYou(nowMs).keySet());
		out.addAll(learner.resting(nowMs));
		return out;
	}

	private Set<Integer> blocked()
	{
		Set<Integer> out = new LinkedHashSet<>();
		for (String k : FlipSidebarConfig.parseNames(config.blockedItems()))
		{
			Integer id = k.matches("\\d{1,9}") ? Integer.valueOf(k) : engine.idOf(k);
			if (id != null)
			{
				out.add(id);
			}
		}
		return out;
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
			status("No coins in your inventory to flip with");
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
			busy.addAll(learner.resting(System.currentTimeMillis()));   // lost repeatedly: resting (FlipLearner)
			busy.addAll(learner.offByYou(System.currentTimeMillis()).keySet());   // your "Don't suggest for a day / a week"
			busy.addAll(parked.keySet());         // in Holding: not bought again while it waits (inHolding)
			busy.addAll(s.held);
			busy.addAll(s.inventory.keySet());    // in the inventory: not bought again (inHolding)
		}
		Map<Integer, Long> used = new HashMap<>();
		s.limitUse.forEach((id, u) -> used.put(id, u[0]));
		RiskLevel risk = config.riskLevel();
		AdjustWindow window = config.adjustWindow();
		long minProfit = Math.max(0, config.minProfit());
		engine.freeToPlay = config.freeToPlay();
		FlipEngine.dearFromTrades = config.dearFromTrades();
		FlipEngine.dearWins = FlipEngine.dearWins(s.history, System.currentTimeMillis());
		synchronized (this)
		{
			scanning = true;
			rescanAsked = false;
			status = "Scanning the GE...";
			earlyShown = false;
		}
		changed();
		try
		{
			List<Pick> found = engine.scan(flipCash(s), risk, window, minProfit, busy, used, MAX_RESEARCH, new FlipEngine.Progress()
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
				// the early card was only the FIRST found — research goes widest margin first, mostly cheap items — so
				// it gives way to the scan's best unless it is being typed in (in-game 2026-11-01: 8 slots of small rune
				// orders with 150m to flip)
				if (earlyShown && pin != null && settingUp != pin.itemId)
				{
					pin = null;
				}
				earlyShown = false;
				scannedAt = System.currentTimeMillis();
				// settings changed mid-scan: go again at once
				nextScanAt = rescanAsked ? 0 : scannedAt + SCAN_EVERY_MS;
				scanning = false;
				status = found.size() + " worth doing";          // + the coins on hand now: statusNow()
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
			earlyShown = true;
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
		Set<Integer> blocked = keptOff(System.currentTimeMillis());     // Never + day/week + resting, every refresh
		List<Pick> out = new ArrayList<>();
		List<Pick> src = best;
		if (config.swingTrades() && !swingPicks.isEmpty() && !holdingFull())
		{
			// swing buys compete with the flips by profit per hour of the slot (Pick.perHour)
			src = new ArrayList<>(best);
			src.addAll(swingPicks);
			src.sort(java.util.Comparator.comparingDouble(Pick::rank).reversed());
		}
		for (Pick p : src)
		{
			if (!skipped.contains(p.itemId) && !placed.contains(p.itemId) && !open.contains(p.itemId)
				&& !stale.contains(p.itemId) && !blocked.contains(p.itemId) && !south.containsKey(p.itemId)
				&& !inHolding(p.itemId))
			{
				Pick a = affordable(p);           // sized to the coins on hand now — dropped when none fit
				if (a != null)
				{
					out.add(a);
				}
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
		long newest = Math.max(pricesAt, latestAt) / 1000;
		if (FlipEngine.dumping(item(five, String.valueOf(p.itemId))))
		{
			return null;              // being dumped now (FlipEngine.DUMP_RATIO): never a buy card
		}
		if (p.dear && snap != null)
		{
			// an expensive item (FlipEngine.judgeDear): re-priced on its latest trades, never the averages; gone when they
			// are too old to price it on, its buy limit is used up, or it no longer passes
			long[] q = FlipEngine.dearQuote(latestItem(String.valueOf(p.itemId)), nowS);
			long left = limitLeft(p.itemId);
			if (q == null || left == 0)
			{
				return null;
			}
			Pick r = FlipEngine.judgeDear(p.itemId, p.name, Math.max(0, left), q, item(five, String.valueOf(p.itemId)),
				flipCash(snap), p.research, nowS);
			return r.rejected == null && r.total >= Math.max(0, config.minProfit()) ? r : null;
		}
		if (snap == null || hour == null || five == null || p.research == null)
		{
			return p;
		}
		String key = String.valueOf(p.itemId);
		long[] q = FlipEngine.quoteBuy(item(hour, key), item(five, key), latestItem(key), nowS);
		if (q[0] <= 0 || q[1] <= 0)
		{
			return p;                 // no trades to price it on: keep the scan's prices
		}
		boolean young = nowS - p.asOf < REPRICE_AFTER_S || newest <= p.asOf;
		if (young)
		{
			// young, or our prices are no newer than the scan's — but the scan prices the ask on the 5-minute / hour
			// averages only, never the last trades: a last insta-buy UNDER its ask is news. In-game 2026-11-01, a
			// Masori mask card 16 s old: no insta-buys in the 5 minutes, so its ask was the hour's 13,377,966 while
			// it had last sold at 13,066,675 four minutes before — +345k on the card, +40k really (0.3%, under the
			// 0.5% a dear item needs). Bought at 12,765,264; it was ~500k under water within hours. Re-judged on
			// that ask with the scan's bid.
			if (q[1] >= p.ask)
			{
				return p;
			}
			q = new long[]{p.bid, q[1]};
		}
		long left = limitLeft(p.itemId);
		if (left == 0)
		{
			return null;              // its buy limit was used up since the scan
		}
		Pick r = FlipEngine.evaluate(p.itemId, p.name, Math.max(0, left), q, flipCash(snap), config.riskLevel(),
			config.adjustWindow(), p.research, nowS);
		if (r == null || r.total < Math.max(0, config.minProfit()))
		{
			return null;
		}
		r.asOf = young ? p.asOf : newest;     // as old as the prices it was priced on
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
				Pick a = refit(pin);  // already on the card: locked — but only what the coins on hand buy
				if (a != null)
				{
					pin = a;
					return pin;
				}
			}
			List<Pick> all = new ArrayList<>(best);
			all.addAll(config.swingTrades() ? swingPicks : List.<Pick>of());     // a swing in the Next list, clicked
			for (Pick p : all)
			{
				if (p.itemId == forced)
				{
					Pick f = fresh(p);
					Pick a = affordable(f != null ? f : p);    // asked for by name: shown — if the coins buy some
					if (a != null)
					{
						pin = a;                  // its age line says how old
						return pin;
					}
					break;
				}
			}
		}
		if (pinActive() && !keptOff(System.currentTimeMillis()).contains(pin.itemId))
		{
			Pick a = refit(pin);
			if (a != null)
			{
				pin = a;
				return pin;
			}
		}
		pin = null;
		for (Pick p : pool())
		{
			Pick f = fresh(p);
			if (f == null)
			{
				stale.add(p.itemId);
				continue;
			}
			f = affordable(f);              // re-priced: sized to the coins on hand again
			if (f != null)
			{
				pin = f;
				break;
			}
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
		if (!pinActive() || hour == null || pin.swing)
		{
			return null;              // a swing is priced against the day's usual high, not today's spread
		}
		String key = String.valueOf(pin.itemId);
		long[] dq = pin.dear ? FlipEngine.dearQuote(item(latest, key), System.currentTimeMillis() / 1000) : null;
		long now = pin.dear ? (dq == null ? Long.MIN_VALUE : dq[1] - FlipEngine.tax(dq[1], pin.itemId) - pin.bid)
			: FlipEngine.marginNow(pin.bid, FlipEngine.quoteBuy(item(hour, key), item(five, key), item(latest, key),
			System.currentTimeMillis() / 1000), pin.itemId);
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
		SellRow first = null;
		for (GameSnapshot.Holding h : snap.holdings)
		{
			// ⚠ BOUGHT IS NOT HELD: only what is IN the inventory is a sell, capped at what is
			// there. User, 2026-10-31: "it tells me to sell death runes i dont have in my invenotry." Never the player's
			// OWN units taken out of the bank (GameSnapshot.flipUnits; 2026-11-05, their own Chaos runes got a sell card)
			int have = snap.flipUnits(h.itemId);
			if (have <= 0 || busy.contains(h.itemId) || sellSkipped.contains(h.itemId)
				|| now - soldAt.getOrDefault(h.itemId, 0L) <= SOLD_HOLD_MS)
			{
				continue;
			}
			// ⭐ the user's own HOLD (the card's Hold / Hold long-term) is never a sell card while held, in profit or not —
			// unticking Hold brings it back (1.60.40). User, 2026-11-07, of a Craw's bow sell card at +269k with no Hold
			// button: "what if i wanted to hold it" — "realistically player should be able to hold what ever they want".
			// Until then a held item came back as a sell once its price was back at break-even. "Sell now at the
			// market" (letGo) still outranks it.
			if (snap.held.contains(h.itemId) && !letGo.contains(h.itemId))
			{
				continue;
			}
			String key = String.valueOf(h.itemId);
			long market = sellMarket(key, h.basis);
			if (market <= 0)
			{
				continue;
			}
			// Under what was paid + tax: listed AT break-even for up to an hour after the last buy — gone south
			// or not — then sold AT THE MARKET (FlipEngine.SELL_PATIENCE_MS: measured, that halved the loss on
			// the plugin's losing flips; before 1.27.0 it was one re-price window, or at once when south).
			// Unknown buy time = long ago.
			// From 1.41.0 one whose price keeps coming back is PARKED in the inventory instead (underWater) — no
			// card, no slot — until it is back at break-even.
			long even = h.basis > 0 ? FlipEngine.breakEven(h.basis, h.itemId) : 0;
			long goal = letGo.contains(h.itemId) ? even : goal(h, even, now);
			if (parkedNow(h, market, even, now) && market < goal)
			{
				continue;             // parked, waiting for break-even — or a swing for its target (Holding)
			}
			// at break-even until SELL_PATIENCE_MS after the buy, then at the market (1.55.0: never kept)
			boolean cut = market < even && (letGo.contains(h.itemId) || underWater(h, now) != PARK || h.lastBuy <= 0
				|| now - FlipEngine.patienceFrom(h.lastBuy) >= FlipEngine.SELL_PATIENCE_MS);
			// HOLD OR SELL (1.60.72): a HOLD verdict keeps it at break-even (the user's "sell now" still wins)
			HoldOrSell.Verdict hv = verdicts.get(h.itemId);
			if (cut && hv != null && hv.hold && !letGo.contains(h.itemId))
			{
				cut = false;
			}
			long ask = cut ? market : Math.max(market, goal);
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
			if (sellFirst != null && sellFirst == h.itemId)
			{
				first = row;            // asked for from Holding ("sell now"): on the card before anything else
			}
		}
		if (first == null)
		{
			sellFirst = null;         // listed, sold or gone: done
		}
		return first != null ? first : kept != null ? kept : top;
	}

	/** Re-derive the card and the view from what is known. Cheap: no network. Under the lock. */
	private void recompute()
	{
		long now = System.currentTimeMillis();
		boolean live = config.livePrices();
		SellRow cand = sellCandidate(now);
		if (cand != null && settingUp == cand.holding.itemId)
		{
			if (sellLock == null || sellLock.holding.itemId != cand.holding.itemId)
			{
				sellLock = cand;          // set-up just opened: these numbers stay until it is placed
			}
			else if (cand.qty >= sellLock.qty)
			{
				cand = sellLock;          // (fewer left in the inventory than locked: show what is there)
			}
		}
		else
		{
			sellLock = null;
		}
		Pick shownPick = null;
		SellRow shownSell = null;
		// SELL first — but never yank the card's BUY away while it is being typed into the GE.
		// ⚠ Not "while a buy is on the card": in-game 2026-10-31 1,167 Steam
		// runes sat in the inventory with no sell price because a Snape grass seed card was up.
		boolean typingBuy = pinActive() && settingUp == pin.itemId;
		// HIGH ALCH MODE: what was bought is for alching, never a sell card (AlchPlan)
		boolean alchOn = live && config.alchMode() && !off();
		sellNow = typingBuy || alchOn ? null : cand;
		pinSell = sellNow == null ? null : sellNow.holding.itemId;

		Suggestion c = null;
		String sub = "";
		String hint = "";
		// ⭐ ALL SLOTS IN USE (8, or 3 free-to-play): nothing can be placed (user, 2026-10-31: "when all slots are full
		// its should say wait not buy sunfire splinters"). A finished offer frees its slot once
		// collected, so that comes first. A card nobody can act on is let go — the next one is
		// priced fresh when a slot frees — unless it is being typed in right now.
		boolean full = snap != null && snap.offers.size() >= FlipSidebarConfig.geSlots(config);
		if (live && off())
		{
			// PAUSED = no guidance at all, sells included (user, 2026-10-31: "paused were still
			// highlighting stuff. should be doing nothing"); the overlays check the setting too
			sellNow = null;
			pinSell = null;
			c = new Suggestion();
			c.action = "NONE";
			c.note = otherEconomy ? OTHER_ECONOMY : "Paused";
			c.asOf = now / 1000;
			hint = otherEconomy ? "back on a normal world, it carries on" : "press Resume to get flips and guidance again";
		}
		else if (live && full)
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
			c.note = collect ? "Collect your finished offers to free a slot" : "All " + FlipSidebarConfig.geSlots(config) + " GE slots are in use";
			c.asOf = now / 1000;
			hint = collect ? "the Collect button is lit in the GE" : "the next flip shows when a slot frees up";
		}
		else if (alchOn)
		{
			// ⭐ HIGH ALCH MODE: the card buys the plan's next item not already in a GE offer (and not skipped)
			if (!typingBuy)
			{
				pin = null;
			}
			AlchPlan.Plan plan = alchPlanNow(now);
			AlchPlan.Row next = null;
			long nextQty = 0;
			boolean noCoins = false;
			long coins = snap == null ? Long.MAX_VALUE : alchCoins();    // the coins on hand NOW, within the alch budget
			long least = Math.max(1, config.minProfit());        // a buy worth less: "not enough coins" (AlchPlan)
			for (AlchPlan.Row r : plan == null ? List.<AlchPlan.Row>of() : plan.rows)
			{
				if (!inOffer(r.item.id) && !alchSkipped.contains(r.item.id))
				{
					long q = r.price <= 0 ? r.qty : Math.min(r.qty, coins / r.price);
					if (q > 0 && q * r.each >= least)
					{
						next = r;
						nextQty = q;
						break;
					}
					noCoins = true;
				}
			}
			c = new Suggestion();
			c.asOf = now / 1000;
			if (next == null)
			{
				c.action = "NONE";
				boolean skippedSome = plan != null && plan.rows.stream().anyMatch(r -> alchSkipped.contains(r.item.id)
					&& !inOffer(r.item.id));
				// the slider's budget, not the coins, is what stops the next buy (1.60.74: 0 = nothing, the far right = no limit)
				int capK = Math.max(0, config.alchSpendK());
				long cap = capK * 1_000L;
				boolean budget = snap != null && capK < FlipSidebarConfig.ALCH_NO_LIMIT_K && coins < Math.max(0, flipCash(snap))
					&& (capK == 0 || noCoins || (plan != null && plan.rows.isEmpty() && !plan.more.isEmpty()));
				// what to cast now, by name (the panel's "To alch" lists all of it)
				AlchHeld first = null;
				for (AlchHeld h : view == null ? List.<AlchHeld>of() : view.alchHeld)
				{
					if (h.left > 0)
					{
						first = h;
						break;
					}
				}
				String castOn = first == null ? "" : String.format(" on %s: %,d left", first.name, first.left);
				c.note = plan == null ? "Getting prices for High alch mode..."
					: budget && capK == 0 ? "Spend on alching is 0 - nothing is bought: cast High Level Alchemy on what you have"
						+ (first == null ? "" : castOn)
					: budget ? String.format("Your alch budget (%s) is in use - cast High Level Alchemy%s", gp(cap),
						first == null ? " to free it" : castOn)
					: plan.rows.isEmpty() ? "Nothing pays to alch right now" + (first == null ? "" : " - cast High Level Alchemy" + castOn)
					: noCoins ? "Not enough coins on hand for the plan's next buy"
					: skippedSome ? "You skipped the rest of the plan - cast High Level Alchemy" + (first == null ? " on what you have" : castOn)
					: "The plan is bought - cast High Level Alchemy" + castOn;
				hint = plan == null ? ""
					: budget ? "or drag the alch budget slider up - the spell and the item to alch are outlined"
					: plan.rows.isEmpty() ? ""
					: noCoins ? "bring coins to your inventory, or cast High Level Alchemy on what you have"
					: "the spell and the item to alch are outlined";
			}
			else
			{
				c.action = "BUY";
				c.itemId = next.item.id;
				c.name = next.item.name;
				c.quantity = (int) Math.min(Integer.MAX_VALUE, nextQty);      // what the coins on hand buy
				c.price = (int) next.price;
				c.profit = next.each * nextQty;
				double mins = next.minutes();       // the card's "Fills in" (ASSUMED: a tenth of what trades)
				c.fillMinutes = mins < 0 || next.qty <= 0 ? mins : mins * nextQty / next.qty;
				c.note = String.format("High alch: +%,d a cast (alch %,d - paid %,d - nature rune %,d)", next.each,
					next.item.highAlch, next.price, plan.nature);
				sub = String.format("plan +%s · %s an hour of casting · %,d casts", gp(plan.profit()),
					gp(plan.perHour(Math.max(1, config.alchCastsPerHour()))), plan.casts());
				// ONE STEP AT A TIME (1.60.75; user, 2026-11-07: "when should i be buying and when should i be alching bc the buy
				// cards up and the alchs are highlighted. this modes a little confusing"): the buy first — it fills while you
				// cast — and the spell lights up once nothing is left to buy right now
				hint = "Step 1: place this buy. Step 2: alch while it fills - the spellbook lights up when it is time to cast";
			}
		}
		else if (live && sellNow != null)
		{
			SellRow r = sellNow;
			shownSell = r;
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
			c.note = "";              // a sell at the market after a fall is just "Sell" (user, 2026-10-31)
			long alch = r.cut ? FlipEngine.alchEach(engine.highAlch(r.holding.itemId),
				live(String.valueOf(FlipEngine.NATURE_RUNE))[1], r.market, r.qty, r.holding.itemId) : 0;
			HoldOrSell.Verdict hv = verdicts.get(r.holding.itemId);
			hint = hv != null && !letGo.contains(r.holding.itemId) ? hv.text
				: alch > 0 ? String.format("under what you paid %d min+ after the buy (or your login) - sell at the market%s",
				FlipEngine.SELL_PATIENCE_MS / 60_000, alchWords(alch, r.market, r.qty, r.holding.itemId))
				: r.cut && !letGo.contains(r.holding.itemId)
				? String.format("under what you paid %d min+ after the buy (or your login) - selling at the market frees the slot and "
					+ "the coins (Hold keeps it)", FlipEngine.SELL_PATIENCE_MS / 60_000)
				: r.cut ? ""
				: r.ask > r.market
				? String.format("the market (%,d) is under what you paid + tax - listed at break-even until %d min after "
					+ "the buy (or your login), then at the market", r.market, FlipEngine.SELL_PATIENCE_MS / 60_000)
				: "selling first frees cash for the next buy";
		}
		else if (live && config.sellOnly())
		{
			if (!typingBuy)
			{
				pin = null;
			}
			c = new Suggestion();
			c.action = "NONE";
			c.note = "Sell only - no new buys";
			c.asOf = now / 1000;
			hint = "what you bought shows here to sell; turn off Sell only in the settings to buy again";
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
				shownPick = p;
				c.action = "BUY";
				c.itemId = p.itemId;
				c.name = p.name;
				c.quantity = (int) p.qty;
				c.price = (int) p.bid;
				c.sellPrice = (int) p.ask;
				c.profit = p.total;
				c.likely = p.swing ? Long.MIN_VALUE : p.likely;          // a swing sells at its day's high on purpose
				c.fillMinutes = p.fillMinutes;
				String proof = learner.provenWhy(p.itemId, snap == null ? List.<GameSnapshot.Done>of() : snap.history,
					snap == null ? Map.<Integer, long[]>of() : snap.record, now);
				c.note = p.deep ? String.format("Multi-day dip: %s - it waits in your inventory (Holding) to sell at %,d (+1%% "
					+ "after tax), up to %d days", p.boostWhy, p.ask, FlipEngine.DEEP_HOLD_MS / 86_400_000L)
					: p.swing ? String.format("Swing: low in its day - it waits in your inventory (Holding) to sell at "
					+ "%,d (+1%% after tax), up to %dh", p.ask, Swing.HOLD_MS / 3_600_000L)
					: gone != null ? gone : !p.firstWarning().isEmpty() || proof.isEmpty() ? p.firstWarning()
					: "Proven: " + proof;
				c.asOf = p.asOf;
				sub = String.format("%s/hr · %.0f%% ROI · %s in", gp((long) p.perHour()), p.roi(), gp(p.committed()));
				String limit = limitText(p.itemId, now);
				hint = limit.isEmpty() ? "kept until you place or skip" : "kept until you place or skip · " + limit;
				// why it ranks where it does (a dip, its daily cycle) — its own line, never hidden behind a risk note
				if (!p.boostWhy.isEmpty() && !p.deep)
				{
					hint = "↑ " + p.boostWhy + " · " + hint;
				}
			}
		}
		card = c;
		journalCard(c, shownPick, shownSell);

		// ⭐ no verdict until the averages AND the latest trades are in (or 30 s after the averages): at the start the
		// averages came first and a buy got RAISE, then CANCEL the same second when the trades arrived (in-game 20:13:53)
		boolean priced = live && five != null && hour != null && (latest != null || now - pricesAt > 30_000);
		FlipEngine.dearFromTrades = config.dearFromTrades();
		List<OfferAdvice> adv = snap == null ? Collections.emptyList()
			: OfferAdvice.advise(snap.offers, priced ? five : null, priced ? hour : null, priced ? latest : null, paid(snap, now),
				firstBids(firstBid, snap.offers, now), ignored, config.adjustWindow().minutes * 60_000L, now);
		south.values().removeIf(t -> now - t > SOUTH_MS);
		for (OfferAdvice a : adv)
		{
			if (OfferAdvice.CANCEL.equals(a.verdict) && a.offer.buy)
			{
				south.putIfAbsent(a.offer.itemId, now);
			}
		}
		// once told CANCEL, a buy is not told RAISE within the hour (OfferAdvice.stayCancelled)
		adv.replaceAll(a -> a.offer.buy && OfferAdvice.RAISE.equals(a.verdict) && south.containsKey(a.offer.itemId)
			? OfferAdvice.stayCancelled(a, south.get(a.offer.itemId), now) : a);
		adv = freeSlotForHeld(adv, live);       // after "south": that cancel is not a fall
		adv = alchHints(adv, live);
		adv = alchBuys(adv, live);
		adv = raisesYouCanPay(adv);
		if (live && !priced)
		{
			adv.replaceAll(OfferAdvice::waitingForPrices);
		}
		if (config.sellOnly())
		{
			adv.replaceAll(OfferAdvice::sellOnly);
		}
		journalAdvice(adv);
		List<Pick> next = new ArrayList<>();
		for (Pick p : config.sellOnly() ? List.<Pick>of() : pool())
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
			Set<Integer> ids = new HashSet<>();
			for (GameSnapshot.Holding h : snap.holdings)
			{
				ids.add(h.itemId);
				long market = live ? live(String.valueOf(h.itemId))[1] : 0;
				long even = h.basis > 0 ? FlipEngine.breakEven(h.basis, h.itemId) : 0;
				boolean park = live && parkedNow(h, market, even, now);
				boolean mine = snap.held.contains(h.itemId);
				// in the BANK, none in the inventory or an offer: a long-term hold (held by you: what the bank holds), or a
				// flip item SEEN going in (TradeLog.Bought.inBank — user, 2026-11-05: "regardless of them being long hold
				// items or not")
				int inBank = snap.bank.getOrDefault(h.itemId, 0);
				long flipsIn = snap.inBank.getOrDefault(h.itemId, 0L);
				boolean banked = live && !park && market > 0 && !inOffer(h.itemId) && snap.flipUnits(h.itemId) == 0
					&& (mine ? inBank > 0 || flipsIn > 0 : flipsIn > 0);
				// parked: what is in the inventory, not the whole record; banked: the flip units SEEN going into the bank,
				// else (a hold banked before the bank was watched) what the bank holds
				long qty = park ? Math.min(h.qty, snap.flipUnits(h.itemId))
					: banked ? Math.min(h.qty, flipsIn > 0 ? flipsIn : inBank) : h.qty;
				// how many of the bank's stack are flips and how many the player's own (user, 2026-11-05: "So you can see how
				// many of them are the flips and how many are the players own")
				String split = banked && inBank > qty ? String.format(" · %,d flips, %,d yours", qty, inBank - qty) : "";
				long goal = letGo.contains(h.itemId) ? even : goal(h, even, now);
				long[] sw = snap.swing.get(h.itemId);
				boolean swinging = sw != null && now < sw[1] && goal > even;
				double back = reachOf(h.itemId, even);
				String note = banked && !mine ? (market >= even
					? String.format("in your bank - take it out to sell it (now %,d)", market)
					: String.format("in your bank - under what you paid: break-even %,d, now %,d", even, market))
					: banked ? (market >= goal
					? String.format("back at %,d - take it out of the bank and sell it", goal)
					: String.format("held long-term in your bank - waiting for %,d (now %,d)%s", goal, market,
						h.lastBuy > 0 ? " · bought " + FlipSidebarSlotOverlay.clock(now - h.lastBuy) + " ago" : ""))
					: !park ? "" : market >= goal
					? String.format("back at %,d - sell it now, it is on the card", goal)
					: swinging ? String.format("swing - selling at %,d (now %,d) · up to %s more", goal, market,
						FlipSidebarSlotOverlay.clock(sw[1] - now))
					: String.format("%s - waiting for %,d (now %,d)%s%s", mine ? "held long-term by you" : "held", even, market,
						back < 0 ? "" : String.format(" · back there in %.0f%% of the last day%s", back * 100,
							back < FlipEngine.PARK_MIN_REACH ? " - it rarely comes back" : ""),
						h.lastBuy > 0 ? " · bought " + FlipSidebarSlotOverlay.clock(now - h.lastBuy) + " ago" : "");
				// HOLD OR SELL (1.60.72): an under-water item kept in the inventory or the bank says what to do, and why
				HoldOrSell.Verdict hv = verdicts.get(h.itemId);
				if (hv != null && (park || banked) && market < even && !swinging)
				{
					note = (mine ? "held by you - " : "") + hv.text
						+ (h.lastBuy > 0 ? " · bought " + FlipSidebarSlotOverlay.clock(now - h.lastBuy) + " ago" : "");
				}
				held.add(new Held(h.itemId, h.name, qty, h.basis, market,
					market <= 0 ? 0 : (market - FlipEngine.tax(market, h.itemId) - h.basis) * qty, note + split,
					(park || banked) && market >= goal, (park || banked) && mine, banked));
			}
			parked.keySet().retainAll(ids);
			letGo.retainAll(ids);
			held.sort((a, b) -> Long.compare(b.qty * b.paidEach, a.qty * a.paidEach));
			inBuys = inBuys(snap);
		}
		View v = new View(live, snap != null, statusNow(), c, sub, hint,
			snap == null ? 0 : snap.inventoryCoins, snap == null ? -1 : snap.bankCoins,
			Collections.unmodifiableList(adv), Collections.unmodifiableList(next),
			snap == null ? List.of() : snap.history, Collections.unmodifiableList(held), inBuys,
			snap == null ? 0 : snap.sessionStart, off(), blocked().size(), Math.max(0, config.maxCashMillions()) * 1_000_000L,
			c != null && "BUY".equals(c.action) ? limitNowLocked(c.itemId) : null, rowLimits(), scannedAt, scanning);
		v.holdingFull = holdingFull();
		v.inventoryItems = snap == null || !live ? 0 : itemsValue(snap.inventory);
		v.uncollected = uncollected(snap);
		// the bank's items count only when they are your long-term holds — the rest of a bank is not flipping money
		// (user, 2026-11-03, of the Robin Hood hat held long-term and banked: "that should cound as money towards the
		// cash total it still says bank 0")
		Map<Integer, Integer> heldInBank = new HashMap<>();
		for (int id : snap == null ? Set.<Integer>of() : snap.held)
		{
			// the flip units seen going in, never the player's own rest of the stack; a hold banked before the bank was
			// watched: what the bank holds
			long seenIn = snap.inBank.getOrDefault(id, 0L);
			int n = seenIn > 0 ? (int) Math.min(seenIn, snap.bank.getOrDefault(id, Integer.MAX_VALUE))
				: snap.bank.getOrDefault(id, 0);
			if (n > 0)
			{
				heldInBank.put(id, n);
			}
		}
		// ...and the flip items seen going into the bank (TradeLog.Bought.inBank), never the rest of the bank — user,
		// 2026-11-05, 410 Sanfew serums banked and missing from the cash total: "im looking for how im not in the 140m
		// plus range for cash"
		flipsInBank(snap).forEach((id, n) -> heldInBank.putIfAbsent(id, (int) Math.min(n, Integer.MAX_VALUE)));
		v.bankItems = !live ? 0 : itemsValue(heldInBank);
		v.off = notSuggested(System.currentTimeMillis());
		List<Integer> targets = new ArrayList<>();
		v.alchMode = live && config.alchMode();
		if (live && config.alchMode())
		{
			v.alch = alchPlanNow(System.currentTimeMillis());
			v.alchSettings = alchSettings();
			v.alchInUse = snap == null ? 0 : alchTied();
			for (AlchPlan.Row r : v.alch == null ? List.<AlchPlan.Row>of() : v.alch.rows)
			{
				if (snap != null && snap.inventory.getOrDefault(r.item.id, 0) > 0)
				{
					targets.add(r.item.id);
				}
			}
			for (int id : snap == null ? Set.<Integer>of() : snap.inventory.keySet())
			{
				// saved for alching counts whatever selling would pay (the user's choice)
				if (!targets.contains(id) && (alchWorth(id) || snap.forAlch.contains(id)))
				{
					targets.add(id);
				}
			}
			v.alchHeld = alchHeld(v.alch, targets);
		}
		else
		{
			alchCount.clear();
		}
		alchTargets = List.copyOf(targets);
		view = v;
	}

	/**
	 * Every item not suggested now, one row each — your Never suggest list first, then yours for a while, then the
	 * plugin's rests, soonest back first. An item in more than one keeps its first. Under the lock.
	 */
	private List<Off> notSuggested(long now)
	{
		List<Off> out = new ArrayList<>();
		Set<Integer> seen = new HashSet<>();
		for (int id : blocked())
		{
			if (seen.add(id))
			{
				out.add(new Off(id, nameOf(id), 0, "your Never suggest list (the settings)", Off.NEVER));
			}
		}
		learner.offByYou(now).entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e ->
		{
			if (seen.add(e.getKey()))
			{
				out.add(new Off(e.getKey(), nameOf(e.getKey()), e.getValue(), "you chose to keep it off", Off.YOURS));
			}
		});
		learner.restingUntil(now).entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e ->
		{
			if (seen.add(e.getKey()))
			{
				String w = learner.restWhy(e.getKey());
				int at = w == null ? -1 : w.indexOf(": ");
				out.add(new Off(e.getKey(), nameOf(e.getKey()), e.getValue(),
					at < 0 ? "a problem item (resting)" : w.substring(at + 2), Off.REST));
			}
		});
		engine.spikedNow().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
		{
			if (seen.add(e.getKey()))
			{
				out.add(new Off(e.getKey(), nameOf(e.getKey()), 0, FlipEngine.spikeWhy(e.getValue()[0], e.getValue()[1]),
					Off.SPIKE));
			}
		});
		return out;
	}

	private String nameOf(int itemId)
	{
		String n = engine.name(itemId);
		return n == null ? "Item " + itemId : n;
	}

	/** Right-click "Don't suggest for a day / a week": kept off until then, saved with the learning. */
	void keepOff(int itemId, long forMs)
	{
		synchronized (this)
		{
			learner.keepOff(itemId, System.currentTimeMillis() + forMs);
		}
		saveLearning();
		settingsChanged();
	}

	/** "Suggest again" on a Not suggested row: its rest and your keep-off end (the settings list is the panel's). */
	void suggestAgain(int itemId)
	{
		synchronized (this)
		{
			learner.suggestAgain(itemId, System.currentTimeMillis());
		}
		saveLearning();
		settingsChanged();
	}

	private synchronized void saveLearning()
	{
		if (learnSaver != null)
		{
			learnSaver.accept(learner);   // under the lock, as everywhere else: learn() changes it on the brain's thread
		}
	}

	/** High alch mode: the plan, made again at most every {@link #ALCH_EVERY_MS} or when its settings change. Under the lock. */
	private AlchPlan.Plan alchPlanNow(long now)
	{
		List<AlchPlan.Item> items = engine.alchItems();
		if (items.isEmpty() || hour == null)
		{
			return null;
		}
		AlchPlan.Settings s = alchSettings();
		String key = s.castsPerHour + "/" + s.hours + "/" + s.offer + "/" + s.minProfit + "/" + s.maxItems + "/"
			+ s.freeToPlay + "/" + s.cash + "/" + snap.limitUse.size() + "/" + limitSum() + "/" + s.minTotal + "/" + s.waiting;
		if (alchPlan == null || now - alchAt >= ALCH_EVERY_MS || !key.equals(alchKey))
		{
			Map<Integer, AlchPlan.Quote> q = AlchPlan.quotes(latest, five, hour, now / 1000);
			AlchPlan.Quote n = q.get(AlchPlan.NATURE_RUNE);
			alchPlan = n == null || n.now <= 0 ? null : AlchPlan.plan(items, q, n.now, s);
			alchAt = now;
			alchKey = key;
		}
		return alchPlan;
	}

	/**
	 * What you hold to alch: the inventory's alch items ({@code targets}) and what alch buy offers still bring (the whole
	 * offer until it is collected). "Cast" counts what the total has dropped by since the item showed up; more bought
	 * adds to it, so casting before a second buy is kept. Under the lock.
	 */
	private List<AlchHeld> alchHeld(AlchPlan.Plan plan, List<Integer> targets)
	{
		if (snap == null)
		{
			return List.of();
		}
		long nature = live(String.valueOf(FlipEngine.NATURE_RUNE))[1];
		long least = Math.max(1, config.alchMinProfit());
		Map<Integer, Long> buying = new HashMap<>();
		Map<Integer, Long> offerPrice = new HashMap<>();
		for (GameSnapshot.Offer o : snap.offers)
		{
			long ha = engine.highAlch(o.itemId);
			if (o.buy && !o.cancelled && ha > 0 && nature > 0 && ha - o.price - nature >= least)
			{
				buying.merge(o.itemId, (long) (o.complete ? o.filled : o.total), Long::sum);
				offerPrice.put(o.itemId, o.price);
			}
		}
		List<Integer> ids = new ArrayList<>(targets);
		for (int id : buying.keySet())
		{
			if (!ids.contains(id))
			{
				ids.add(id);
			}
		}
		Map<Integer, Long> planPrice = new HashMap<>();
		for (AlchPlan.Row r : plan == null ? List.<AlchPlan.Row>of() : plan.rows)
		{
			planPrice.put(r.item.id, r.price);
		}
		Map<Integer, Long> paid = new HashMap<>();
		for (GameSnapshot.Holding h : snap.holdings)
		{
			paid.put(h.itemId, h.basis);
		}
		List<AlchHeld> out = new ArrayList<>();
		for (int id : ids)
		{
			long left = snap.inventory.getOrDefault(id, 0);
			long coming = buying.getOrDefault(id, 0L);
			long now = left + coming;
			long[] c = alchCount.computeIfAbsent(id, k -> new long[]{now, now});
			if (now > c[0])
			{
				c[1] += now - c[0];           // bought more: the casts so far stay counted
			}
			c[0] = now;
			long price = paid.getOrDefault(id, 0L) > 0 ? paid.get(id) : offerPrice.containsKey(id) ? offerPrice.get(id)
				: planPrice.containsKey(id) ? planPrice.get(id) : live(String.valueOf(id))[1];
			long ha = engine.highAlch(id);
			out.add(new AlchHeld(id, nameOf(id), left, coming, Math.max(0, c[1] - now), ha - price - nature));
		}
		alchCount.keySet().retainAll(ids);
		return out;
	}

	/**
	 * High alch mode's coins to buy with now: the coins on hand (flipCash), at most what is left of "Spend on alching at
	 * most" after what alching already ties up (alchTied) — user, 2026-11-03: "Aching shouldnt be speming all our cash
	 * just a little", "some kinda of dragable bar for it ... so it makes reasonable alch offers". Under the lock.
	 */
	private long alchCoins()
	{
		long coins = Math.max(0, flipCash(snap));
		int capK = Math.max(0, config.alchSpendK());
		// 1.60.74 (user, 2026-11-07: "alch mode i need to be able to set to zero gp. like if i dont have 1m then i should
		// have that be my minimum to spend"): in thousands; 0 = nothing bought; the far right = no limit; never more
		// than the coins on hand
		return capK >= FlipSidebarConfig.ALCH_NO_LIMIT_K ? coins : Math.min(coins, Math.max(0, capK * 1_000L - alchTied()));
	}

	/**
	 * Coins alching ties up now: its buy offers (what they cost in all — bought and still waiting) and the alch items in
	 * the inventory (the plan's, and the others worth alching: alchTargets) at the plan's price, else today's. Casting
	 * frees it. Under the lock.
	 */
	private long alchTied()
	{
		Map<Integer, Long> paid = new HashMap<>();
		for (AlchPlan.Row r : alchPlan == null ? List.<AlchPlan.Row>of() : alchPlan.rows)
		{
			paid.put(r.item.id, r.price);
		}
		Set<Integer> ids = new HashSet<>(paid.keySet());
		ids.addAll(alchTargets);
		long tied = 0;
		for (GameSnapshot.Offer o : snap.offers)
		{
			if (o.buy && ids.contains(o.itemId))
			{
				tied += (long) (o.complete || o.cancelled ? o.filled : o.total) * o.price;
			}
		}
		for (int id : ids)
		{
			int n = snap.inventory.getOrDefault(id, 0);
			Long p = paid.get(id);
			tied += n <= 0 ? 0 : n * (p != null ? p : live(String.valueOf(id))[1]);
		}
		return tied;
	}

	/** Units bought in the open buy-limit windows, all items: a change re-plans (a buy filled). Under the lock. */
	private long limitSum()
	{
		long sum = 0;
		for (long[] u : snap == null ? List.<long[]>of() : snap.limitUse.values())
		{
			sum += u[0];
		}
		return sum;
	}

	/** High alch mode's settings, with the buy limits used (TradeLog.limitUse) and the coins to spend. Under the lock. */
	private AlchPlan.Settings alchSettings()
	{
		AlchPlan.Settings s = new AlchPlan.Settings();
		s.castsPerHour = Math.max(1, config.alchCastsPerHour());
		s.hours = Math.max(1, config.alchHours());
		s.offer = config.alchBuy() == FlipSidebarConfig.AlchBuy.OFFER;
		s.minProfit = Math.max(1, config.alchMinProfit());
		s.maxItems = Math.max(1, Math.min(8, config.alchMaxItems()));
		s.freeToPlay = config.freeToPlay();
		s.cash = snap == null ? 0 : alchCoins();
		s.used = snap == null ? Map.of() : snap.limitUse;
		s.minTotal = Math.max(0, config.minProfit());
		Map<Integer, Long> waiting = new HashMap<>();
		for (GameSnapshot.Offer o : snap == null ? List.<GameSnapshot.Offer>of() : snap.offers)
		{
			if (o.buy && !o.complete && !o.cancelled)
			{
				waiting.merge(o.itemId, (long) Math.max(0, o.total - o.filled), Long::sum);
			}
		}
		s.waiting = waiting;
		Set<Integer> skip = blocked();
		skip.addAll(learner.offByYou(System.currentTimeMillis()).keySet());   // your "Don't suggest for a day / a week"
		s.skip = skip;
		return s;
	}

	/**
	 * High alch mode: the inventory items worth alching (alchWorth), plan items first — read every frame by
	 * FlipSidebarAlchOverlay without the lock. Empty when the mode is off.
	 */
	List<Integer> alchTargets()
	{
		return alchTargets;
	}

	/**
	 * High alch mode: an item worth alching — in the plan, or High Level Alchemy minus a nature rune pays more than
	 * selling it at today's insta-buy after tax. Under the lock.
	 */
	private boolean alchWorth(int itemId)
	{
		if (alchPlan != null && alchPlan.rows.stream().anyMatch(r -> r.item.id == itemId))
		{
			return true;
		}
		long ha = engine.highAlch(itemId);
		long nature = live(String.valueOf(FlipEngine.NATURE_RUNE))[1];
		long market = live(String.valueOf(itemId))[1];
		return ha > 0 && nature > 0 && market > 0 && ha - nature > market - FlipEngine.tax(market, itemId);
	}

	/** A losing sell LOWERED to the market: "or alch it" when High Level Alchemy pays more (FlipEngine.alchEach). Under the lock. */
	private List<OfferAdvice> alchHints(List<OfferAdvice> adv, boolean live)
	{
		if (!live)
		{
			return adv;
		}
		long nature = live(String.valueOf(FlipEngine.NATURE_RUNE))[1];
		List<OfferAdvice> out = new ArrayList<>(adv.size());
		for (OfferAdvice a : adv)
		{
			long left = a.offer.total - a.offer.filled;
			long each = a.offer.buy || !OfferAdvice.LOWER.equals(a.verdict) ? 0
				: FlipEngine.alchEach(engine.highAlch(a.offer.itemId), nature, a.target, left, a.offer.itemId);
			out.add(each <= 0 ? a : OfferAdvice.withMore(a, alchWords(each, a.target, left, a.offer.itemId)));
		}
		return out;
	}

	/**
	 * ⭐ A buy that pays to ALCH at its own price is judged as an alch buy, not by a sale it is not made for: in High alch
	 * mode it WAITs (with what a cast makes at its price), and in either mode it is never CANCELLED for "no profit" —
	 * 2026-11-02 19:13-19:17 (journal) every High alch mode buy (Onyx ascension bolts, Earth battlestaff, Air
	 * battlestaff) got "Cancel offer" at once, and four were cancelled. A finished or cancelled one is still collected;
	 * an ignored one is left alone. Under the lock.
	 */
	private List<OfferAdvice> alchBuys(List<OfferAdvice> adv, boolean live)
	{
		long nature = live ? live(String.valueOf(FlipEngine.NATURE_RUNE))[1] : 0;
		if (nature <= 0)
		{
			return adv;
		}
		long least = Math.max(1, config.alchMinProfit());
		boolean alchMode = config.alchMode();
		List<OfferAdvice> out = new ArrayList<>(adv.size());
		for (OfferAdvice a : adv)
		{
			GameSnapshot.Offer o = a.offer;
			long ha = engine.highAlch(o.itemId);
			long each = ha - o.price - nature;
			boolean judge = alchMode || OfferAdvice.CANCEL.equals(a.verdict);
			if (!o.buy || ha <= 0 || each < least || !judge || a.ignored() || OfferAdvice.COLLECT.equals(a.verdict))
			{
				out.add(a);
				continue;
			}
			OfferAdvice w = OfferAdvice.alchBuy(o, String.format("alch buy: +%,d a cast at your price (alch %,d - paid %,d - "
				+ "nature rune %,d) - cast High Level Alchemy on it", each, ha, o.price, nature));
			w.expected = each * o.total;
			out.add(w);
		}
		return out;
	}

	/** " - or ALCH it: 2,050 each after a nature rune, against 1,595 here (1,031 items, ~52 min of casting)". */
	private static String alchWords(long each, long market, long qty, int itemId)
	{
		return String.format(" - or ALCH it: %,d each after a nature rune, against %,d here (%,d items, ~%d min of casting)",
			each, market - FlipEngine.tax(market, itemId), qty, Math.max(1, qty * 60 / 1_200));
	}

	/** {insta-sell, insta-buy} now for an item (FlipEngine.quoteLive); 0 = unknown. */
	synchronized long[] quote(int itemId)
	{
		return live(String.valueOf(itemId));
	}

	/**
	 * The inventory's items (coins not) if all sold now after the 2% tax, at the same price as Holding's "now" — for the
	 * cash line (user, 2026-11-03: "On cash its not counting the value if the items in the inventory"). Items with no
	 * trades to value them on count 0.
	 */
	private synchronized long itemsValue(Map<Integer, Integer> inventory)
	{
		long value = 0;
		for (Map.Entry<Integer, Integer> e : inventory.entrySet())
		{
			int id = e.getKey();
			long market = id == ItemID.COINS ? 0 : live(String.valueOf(id))[1];
			if (market > 0)
			{
				value += (market - FlipEngine.tax(market, id)) * e.getValue();
			}
		}
		return value;
	}

	/** {insta-sell, insta-buy} now: each side's fresh last trade, else its 5-minute average (FlipEngine.quoteLive). */
	private synchronized long[] live(String key)
	{
		return FlipEngine.quoteLive(item(hour, key), item(five, key), item(latest, key), System.currentTimeMillis() / 1000);
	}

	private synchronized JsonObject latestItem(String key)
	{
		return item(latest, key);
	}

	/**
	 * What a held item sells for now (the sell card): the live quote's ask — for an expensive item of the "Expensive
	 * items (1m+)" setting (FlipEngine.dearTrade, by what was paid) its last insta-buy if within FlipEngine.DEAR_FRESH_S,
	 * as it was priced to sell at (FlipEngine.judgeDear).
	 */
	private long sellMarket(String key, long basis)
	{
		long market = live(key)[1];
		if (FlipEngine.dearTrade(basis))
		{
			JsonObject l = latestItem(key);
			long hi = l == null ? 0 : Research.num(l, "high");
			if (hi > 0 && System.currentTimeMillis() / 1000 - Research.num(l, "highTime") <= FlipEngine.DEAR_FRESH_S)
			{
				return hi;
			}
		}
		return market;
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

	/** 6900000 -> "6.90m". */
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
