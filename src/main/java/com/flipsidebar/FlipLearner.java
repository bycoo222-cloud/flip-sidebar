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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The plugin learning from the account's own flips (user, 2026-10-31: "i want to get it to the point its self
 * learning" — "i need it to make more money ... its struggling"). Two things, both from finished flips only:
 * <ul>
 *   <li>how each flip's item looked JUST BEFORE the buy — rebuilt from the wiki's 5-minute history with the
 *       same measures Research takes (FlipBrain fetches it, one flip at a time) — and from those, the least
 *       trading activity that made money: {@link FlipEngine#activityFloor};</li>
 *   <li>items to rest: one that lost at least 2 of its last 3 flips, and is down on them, is not suggested
 *       for a day; nor is one whose flip SAT ({@link #SAT_MS}+ LOGGED IN, outside the bank, from buy to sold — {@link
 *       #sat}); one that sat and sold at a loss, or sat twice in a week, is not suggested for a week — {@link
 *       #learnResting}.</li>
 * </ul>
 * Everything stays on this computer (RuneLite's settings, per account). Not thread-safe: FlipBrain uses it under
 * its own lock. Saved with Gson (FlipSidebarPlugin), so only plain fields.
 */
final class FlipLearner
{
	/** Fewer measured flips than this: keep the starting floor (0.90 — measured on 81 flips, 2026-10-31). */
	static final int MIN_SAMPLES = 30;
	/** A floor must keep at least this many flips, and drop none or at least MIN_DROPPED (too few to judge). */
	static final int MIN_KEPT = 20;
	static final int MIN_DROPPED = 5;
	static final double FLOOR_LOW = 0.50;
	static final double FLOOR_HIGH = 0.95;
	static final double START_FLOOR = 0.90;
	/** Candidate trading-volume minimums, units an hour on the slower side. */
	static final double[] FLOW_CUTS = {0, 1_000, 5_000, 10_000, 25_000, 50_000, 100_000, 250_000};
	/** Candidate GP-an-hour alternatives to it (0 = none): a dear item busy in coins passes too. */
	static final double[] GP_CUTS = {0, 25_000_000, 50_000_000, 100_000_000, 250_000_000};
	/** An item that lost at least 2 of its last 3 flips (and is down on them) rests this long after the last loss. */
	static final long REST_MS = 24 * 3_600_000L;
	/**
	 * ⭐ A flip that took this long from its buy to its last sale SAT — on hold, or hard to sell — and its item rests
	 * {@link #REST_MS} from that sale, whatever it made. User, 2026-11-01, of Pharaoh's sceptre (bought 14:56, sold
	 * 19:36 at +3k after 7 relists, on the card again at 19:51): "Either way it should be avoiding items that have sat
	 * on hold and are trouble to sell". MEASURED on the user's 334 flips rebuilt from the GE's trade history (09-27 to
	 * 11-01, FIFO, includes some non-flip trades): the 116 that took 2h+ total -10.7m, 48 lost. The user's choice over
	 * the cost seen in the same data: the next flip of an item within a day after one that sat 60+ min made +535k, 0
	 * lost (10 cases — too few to judge either way). ⏭ Score it: how often a rested item's card would have paid.
	 */
	static final long SAT_MS = 2 * 3_600_000L;
	/**
	 * The rules the saved rests were made by: below {@link #RULES} they are dropped once and made again ({@link #fixed}).
	 * 2 (1.60.30): "sat" = logged-in time outside the bank ({@link #sat}), not buy-to-sale clock time — the rests made
	 * from the clock benched items for the user's logouts (Dragon javelin tips, Soul rune, Regen bracelet: 5.2h, 0.6-0.7h
	 * of it logged in) and from a wrong record (Chaos rune).
	 */
	static final int RULES = 2;
	/**
	 * ⭐ A KNOWN PROBLEM ITEM rests this long from its last sale: one whose flip SAT and sold at a loss, or that sat
	 * {@link #SAT_TIMES} times in this long. User, 2026-11-01, after Ascension shards (bought 10-31 21:08, sat 4.7h,
	 * -369k) were bought again at 16:00 the next day — 11,953 at 1,450, held at -1.07m: "this projects useless is we
	 * suggest known problem items that wipe all our profits ... idk why we would buy them again" — and "after holding
	 * the item for a long time and finally getting rid of the item that is troublesom ... itll just suggest to buy more
	 * right after". The user's rule, NOT measured as a gain: the trade log's last 26 hours are too short to show one.
	 */
	static final long WEEK_MS = 7 * REST_MS;
	/**
	 * ⭐ A WEAK EXPENSIVE ITEM rests too (1.60.71): one worth {@link #WEAK_MIN_EACH_GP}+ each whose last 2 flips of the past
	 * day made, together, under {@link #WEAK_PCT}% of what they cost — rests a day from the later sale. User, 2026-11-07
	 * (session 2): "weve been going hard on the twisted buckler ... We've done a lot do them" — Twisted buckler: Fri
	 * -55,155 and +87,766 (+32,611 on 53.8m = 0.06%), then 3 more bought Sat 15:07 for 27.36m that sat at break-even for
	 * an hour+. BACKTESTED on the user's 200 flips (Fri 18:12 - Sat 16:20): for ANY item the rule would have blocked 6
	 * flips that made +207,197 (Atlatl dart +99k, Dragonstone bolts +56k, Ruby necklace +41k) — worse than not having it;
	 * for items 1m+ each it blocks 2 that made +1 (Bandos tassets +1, Sanguinesti staff 0) and today's buckler buy. Too
	 * few cases to call it a gain — the user's rule; both numbers live in rules.json ("weakPairPct", "weakMinEachGp").
	 */
	static volatile double WEAK_PCT = 0.10;
	static volatile long WEAK_MIN_EACH_GP = 1_000_000L;
	static final int SAT_TIMES = 2;
	/** The wiki keeps ~30 hours of 5-minute history: older flips cannot be measured any more. */
	static final long MEASURABLE_MS = 28 * 3_600_000L;
	/** Buy-limit hits are kept this long. */
	static final long LIMIT_KEEP_MS = 30L * 24 * 3_600_000L;
	/** A flip with no buy time is measured this long before its sale (Copilot's median flip took 23 minutes). */
	static final long LEAD_MS = 20 * 60_000L;
	/** Stored for a flip whose history was too thin to measure — so it is not fetched again. */
	static final double[] UNMEASURABLE = {-1};

	/** Flip id (GameSnapshot.Done.id) -> {contiguity, share of the last 4h with no margin, median margin % of price, slower side units an hour}. */
	private Map<String, double[]> measures = new LinkedHashMap<>();
	/**
	 * ⭐ THE TEAM'S FLIPS — a team brain merged from players' exports instead of a server (user, 2026-11-03: "ok lets do
	 * that"): "who|flip id" -> {contiguity, no-margin share, median margin %, slower side units an hour, profit, buy price
	 * each} — what each flip looked like just before its buy AND what it made, so the floors learn from them as well as
	 * from this account's own (which need its trade history for the profit). Set by importing a team brain
	 * (BrainFile "team"); never trimmed with the own measures.
	 */
	private Map<String, double[]> team = new LinkedHashMap<>();
	/** Bumped when {@link #team} is replaced: the plugin saves the team's flips to their own file only then. Not saved. */
	private transient int teamVersion;
	private double floor = START_FLOOR;
	/** Learned least units an hour on the slower side; 0 = no minimum (until learned). */
	private double flowFloor;
	/** ...or this much GP an hour on the slower side (0 = no such alternative). */
	private double gpFloor;
	/** Item -> rests until, epoch ms. */
	private Map<Integer, Long> resting = new HashMap<>();
	/** {@link #RULES} the rests were made by; 0 = before 1.60.30 (or none made yet). Set by {@link #learn}. */
	private int rules;
	/** Item -> why it rests, in words — for the Not suggested list, as the flip behind it can leave the trade log. */
	private Map<Integer, String> restWhy = new HashMap<>();
	/**
	 * ⭐ Item -> when the user chose "Suggest again" (Not suggested, 1.56.0): a rest is not put back from a sale before
	 * then; a newer sale can rest it again. User, 2026-11-02: "Lets say I see those shards start going up in price
	 * consistently and I want to pull them off the list ... I want to have that list transparent and editable".
	 */
	private Map<Integer, Long> lifted = new HashMap<>();
	/** Item -> kept off by the user until, epoch ms (right-click "Don't suggest for a day / a week", 1.56.0). */
	private Map<Integer, Long> offByYou = new HashMap<>();
	/**
	 * Item -> {bought at, sold at, profit, logged-in ms it sat} of each buy of it that SAT, the past {@link #WEEK_MS} — one
	 * per buy (its sells share the buy time; profit = its sat sells'). Kept here because the trade log keeps only its
	 * newest 200 flips (and the flip archive is read after the login).
	 */
	private Map<Integer, List<long[]>> satFlips = new HashMap<>();
	/** Item -> name, for the satFlips whose flips have left the trade log. */
	private Map<Integer, String> satNames = new HashMap<>();
	/**
	 * Item -> when its GE buy limit was used up, epoch ms, oldest first — one per 4-hour window (user, 2026-10-31:
	 * "i want it to track items we constantly hit buy limit on"). Kept {@link #LIMIT_KEEP_MS}.
	 */
	private Map<Integer, List<Long>> limitHits = new HashMap<>();
	/** The windows already counted: "item@window ends at", so one window counts once. */
	private Set<String> limitWindows = new java.util.HashSet<>();
	/** What it learned, in words, for the Statistics window. Rebuilt by {@link #learn}; not saved. */
	private transient List<String> lines;

	/** A learner loaded from an old or hand-edited save can miss fields. */
	/** Flips measured (BrainFile's summary). */
	int measured()
	{
		return measures == null ? 0 : measures.size();
	}

	/** The team's flips held (BrainFile's summary). */
	int teamSize()
	{
		return team == null ? 0 : team.size();
	}

	/**
	 * This learner with the team brain {@code t} in: its flips replace the team's held before (never this account's own),
	 * and its rests are added — the longer rest of an item wins. For an import (BrainFile "team").
	 */
	FlipLearner withTeam(FlipLearner t)
	{
		fixed();
		FlipLearner in = t.fixed();
		team = new LinkedHashMap<>(in.team);
		teamVersion++;
		for (Map.Entry<Integer, Long> e : in.resting.entrySet())
		{
			if (e.getValue() > resting.getOrDefault(e.getKey(), 0L))
			{
				resting.put(e.getKey(), e.getValue());
				String why = in.restWhy.get(e.getKey());
				if (why != null)
				{
					restWhy.put(e.getKey(), why);
				}
			}
		}
		return this;
	}

	/**
	 * A team brain's flips from one player's learning and trade log: each of their measured flips with what it made
	 * (part sales of one buy added up), keyed "who|flip id"; plus the team flips that brain already held. For the merge
	 * (TeamBrain).
	 */
	static Map<String, double[]> teamFlips(FlipLearner l, java.util.List<TradeLog.Flip> flips, String who)
	{
		FlipLearner f = l == null ? new FlipLearner() : l.fixed();
		Map<String, double[]> out = new LinkedHashMap<>(f.team);
		for (Map.Entry<String, double[]> e : ownFlips(f, flips).entrySet())
		{
			out.put(who + "|" + e.getKey(), e.getValue());
		}
		return out;
	}

	/**
	 * This account's own measured flips with what they made, keyed by flip id ("offer@boughtAt"; part sales of one buy
	 * added up): {contiguity, no-margin share, median margin %, slower side units an hour, profit, buy price each} —
	 * what a team brain and the team sync share (no names, no coins).
	 */
	static Map<String, double[]> ownFlips(FlipLearner l, java.util.List<TradeLog.Flip> flips)
	{
		FlipLearner f = l == null ? new FlipLearner() : l.fixed();
		Map<String, double[]> sum = new LinkedHashMap<>();
		for (TradeLog.Flip x : flips == null ? java.util.List.<TradeLog.Flip>of() : flips)
		{
			String id = (x.offer == null ? x.itemId + ":" + x.qty : x.offer) + "@" + (x.boughtAt > 0 ? x.boughtAt : x.at);
			double[] m = f.measures.get(id);
			if (m == null || m.length != 4)
			{
				continue;
			}
			double[] t = sum.computeIfAbsent(id, k -> new double[]{m[0], m[1], m[2], m[3], 0, x.buyEach});
			t[4] += x.profit;
		}
		return sum;
	}

	/** The team's flips from the sync (TeamSync) or a saved team file: they replace the team flips held. */
	void setTeamFlips(Map<String, double[]> flips)
	{
		fixed();
		team = new LinkedHashMap<>(flips);
		teamVersion++;
	}

	/** The team's flips held, read-only (saved to their own file). */
	Map<String, double[]> teamMap()
	{
		return java.util.Collections.unmodifiableMap(team == null ? Map.of() : team);
	}

	int teamVersion()
	{
		return teamVersion;
	}

	/**
	 * Saved to RuneLite's settings WITHOUT the team's flips — thousands of them do not belong there; they go to their own
	 * file in the plugin's folder. Under the brain's lock (not thread-safe).
	 */
	String jsonWithoutTeam(Gson gson)
	{
		Map<String, double[]> t = team;
		team = new LinkedHashMap<>();
		try
		{
			return gson.toJson(this);
		}
		finally
		{
			team = t;
		}
	}

	/** Items resting now or before (BrainFile's summary). */
	int restingCount()
	{
		return resting == null ? 0 : resting.size();
	}

	FlipLearner fixed()
	{
		if (measures == null)
		{
			measures = new LinkedHashMap<>();
		}
		if (team == null)
		{
			team = new LinkedHashMap<>();
		}
		if (resting == null)
		{
			resting = new HashMap<>();
		}
		if (restWhy == null)
		{
			restWhy = new HashMap<>();
		}
		if (lifted == null)
		{
			lifted = new HashMap<>();
		}
		if (offByYou == null)
		{
			offByYou = new HashMap<>();
		}
		if (satFlips == null)
		{
			satFlips = new HashMap<>();
		}
		if (satNames == null)
		{
			satNames = new HashMap<>();
		}
		if (rules < RULES)
		{
			// made by older rules: made again at the next learn, from the flips (FlipBrain gives it the week's)
			resting.clear();
			restWhy.clear();
			satFlips.clear();
			satNames.clear();
			rules = RULES;
		}
		if (limitHits == null)
		{
			limitHits = new HashMap<>();
		}
		if (limitWindows == null)
		{
			limitWindows = new java.util.HashSet<>();
		}
		if (floor < FLOOR_LOW || floor > FLOOR_HIGH)
		{
			floor = START_FLOOR;
		}
		if (flowFloor < 0 || flowFloor > FLOW_CUTS[FLOW_CUTS.length - 1])
		{
			flowFloor = 0;
		}
		if (gpFloor < 0 || gpFloor > GP_CUTS[GP_CUTS.length - 1])
		{
			gpFloor = 0;
		}
		return this;
	}

	double floor()
	{
		return floor;
	}

	double flowFloor()
	{
		return flowFloor;
	}

	double gpFloor()
	{
		return gpFloor;
	}

	/** The buy limit of {@code itemId} is used up in the window ending {@code resetsAt}: count it once. True when new. */
	boolean noteLimitHit(int itemId, long resetsAt, long nowMs)
	{
		if (!limitWindows.add(itemId + "@" + resetsAt))
		{
			return false;
		}
		limitHits.computeIfAbsent(itemId, k -> new ArrayList<>()).add(nowMs);
		long old = nowMs - LIMIT_KEEP_MS;
		limitHits.values().forEach(l -> l.removeIf(t -> t < old));
		limitHits.values().removeIf(List::isEmpty);
		limitWindows.removeIf(w -> Long.parseLong(w.substring(w.indexOf('@') + 1)) < old);
		return true;
	}

	/** Item -> {times maxed in the last 24 hours, times maxed in all kept, last maxed epoch ms}. */
	Map<Integer, long[]> limitHits(long nowMs)
	{
		Map<Integer, long[]> out = new HashMap<>();
		limitHits.forEach((id, l) ->
		{
			long day = l.stream().filter(t -> nowMs - t <= 24 * 3_600_000L).count();
			out.put(id, new long[]{day, l.size(), l.isEmpty() ? 0 : l.get(l.size() - 1)});
		});
		return out;
	}

	/**
	 * ⭐ PROVEN items — user, 2026-11-01: "items that frequently are hitting buy limit should be cycled in as they are
	 * known profit": its GE buy limit maxed out in the last 30 days, 2+ finished flips that made money in all, and no
	 * more than a third of them lost. The scan researches them first and judges them without the rules learned from
	 * other items; they rank like any pick (FlipEngine.proven says why). MEASURED that day: the 13 items maxed out lost 5 of 52
	 * flips — 3 of 49 without Aether catalyst (2 of 3 lost: not proven) — against 27 of 129 for all the user's flips.
	 */
	Set<Integer> proven(List<GameSnapshot.Done> history, long nowMs)
	{
		return proven(history, Map.of(), nowMs);
	}

	/**
	 * ...judged on {@code record} — every flip of the account, all time, per item {flips, lost, profit}
	 * (GameSnapshot.record) — when there is one, else on {@code history}. Proven too, without the buy limit maxed: a
	 * KNOWN-SAFE item, {@link #SAFE_FLIPS}+ flips that made money in all, a third or fewer lost. User, 2026-11-05: "We
	 * should also be cycling threw buy limit items. Known safe flips" — "b i feel we should have already". MEASURED
	 * that day: 52 items with 3+ flips and none lost were bought 1.3 times a day each (6 buy-limit windows); in the
	 * 36 minutes a slot sat open on "Nothing worth doing" that morning, a median of 7 of them were open to buy.
	 */
	Set<Integer> proven(List<GameSnapshot.Done> history, Map<Integer, long[]> record, long nowMs)
	{
		Set<Integer> out = new java.util.HashSet<>();
		for (Map.Entry<Integer, long[]> e : (record.isEmpty() ? record(history) : record).entrySet())
		{
			long[] r = e.getValue();
			if (r[2] <= 0 || r[1] * 3 > r[0])
			{
				continue;
			}
			if (r[0] >= SAFE_FLIPS || (r[0] >= 2 && maxed(e.getKey(), nowMs)))
			{
				out.add(e.getKey());
			}
		}
		return out;
	}

	/** A known-safe item has at least this many flips (proven). */
	static final int SAFE_FLIPS = 3;

	private boolean maxed(int itemId, long nowMs)
	{
		List<Long> hits = limitHits.get(itemId);
		return hits != null && hits.stream().anyMatch(t -> nowMs - t <= LIMIT_KEEP_MS);
	}

	/** "maxed out 2x · 7 flips, 0 lost, +144k" (or "7 flips, 0 lost, +144k") for a proven item, else "". */
	String provenWhy(int itemId, List<GameSnapshot.Done> history, long nowMs)
	{
		return provenWhy(itemId, history, Map.of(), nowMs);
	}

	String provenWhy(int itemId, List<GameSnapshot.Done> history, Map<Integer, long[]> record, long nowMs)
	{
		long[] r = (record.isEmpty() ? record(history) : record).get(itemId);
		long[] h = limitHits(nowMs).get(itemId);
		if (r == null || !proven(history, record, nowMs).contains(itemId))
		{
			return "";
		}
		String flips = String.format("%d flips, %d lost, %s", r[0], r[1], (r[2] >= 0 ? "+" : "") + FlipBrain.gp(r[2]));
		return h == null || !maxed(itemId, nowMs) ? flips : String.format("maxed out %dx · %s", h[1], flips);
	}

	/** Item -> {finished flips, lost, profit} over {@code history}. */
	static Map<Integer, long[]> record(List<GameSnapshot.Done> history)
	{
		Map<Integer, long[]> out = new HashMap<>();
		for (GameSnapshot.Done d : history)
		{
			long[] r = out.computeIfAbsent(d.itemId, k -> new long[3]);
			r[0]++;
			r[1] += d.profit < 0 ? 1 : 0;
			r[2] += d.profit;
		}
		return out;
	}

	/** Items resting now. */
	Set<Integer> resting(long nowMs)
	{
		resting.values().removeIf(until -> until <= nowMs);
		restWhy.keySet().retainAll(resting.keySet());
		lifted.values().removeIf(at -> nowMs - at > WEEK_MS);    // no sale before it can rest anything any more
		return resting.keySet();
	}

	/** Items resting now -> until, epoch ms. */
	Map<Integer, Long> restingUntil(long nowMs)
	{
		resting(nowMs);
		return Collections.unmodifiableMap(resting);
	}

	/** Why an item rests, in words ("Resting X until ...: why."); null = not known. */
	String restWhy(int itemId)
	{
		return restWhy.get(itemId);
	}

	/** Items the user keeps off now -> until, epoch ms. */
	Map<Integer, Long> offByYou(long nowMs)
	{
		offByYou.values().removeIf(until -> until <= nowMs);
		return Collections.unmodifiableMap(offByYou);
	}

	/** "Don't suggest for a day / a week": kept off until {@code untilMs}. */
	void keepOff(int itemId, long untilMs)
	{
		offByYou.put(itemId, untilMs);
	}

	/** "Suggest again": its rest and the user's own keep-off end now; a rest comes back only from a newer sale. */
	void suggestAgain(int itemId, long nowMs)
	{
		resting.remove(itemId);
		restWhy.remove(itemId);
		offByYou.remove(itemId);
		lifted.put(itemId, nowMs);
	}

	List<String> lines()
	{
		return lines == null ? List.of() : lines;
	}

	/** The newest finished flip not measured yet (and not tried in {@code tried}), or null. */
	GameSnapshot.Done nextToMeasure(List<GameSnapshot.Done> history, long nowMs, Set<String> tried)
	{
		for (GameSnapshot.Done d : history)
		{
			String id = d.id();
			if (d.at > 0 && nowMs - d.at <= MEASURABLE_MS && !measures.containsKey(id) && !tried.contains(id))
			{
				return d;
			}
		}
		return null;
	}

	void put(String flipId, double[] measured)
	{
		measures.put(flipId, measured);
		while (measures.size() > TradeLog.FLIPS_KEPT * 2)
		{
			measures.remove(measures.keySet().iterator().next());
		}
	}

	/** Epoch seconds the flip is measured AT: just before its buy. */
	static long before(GameSnapshot.Done d)
	{
		return (d.boughtAt > 0 ? d.boughtAt : d.at - LEAD_MS) / 1000;
	}

	/**
	 * The item as Research would have seen it at {@code beforeS}, from a /timeseries 5-minute answer:
	 * {contiguity, no-margin share of the last 4h, median margin % of price, slower side units an hour};
	 * {@link #UNMEASURABLE} when the history is too thin.
	 */
	static double[] measure(int itemId, JsonArray series, long beforeS)
	{
		JsonArray upTo = new JsonArray();
		long price = 0;
		for (JsonElement e : series)
		{
			if (e.isJsonObject() && Research.num(e.getAsJsonObject(), "timestamp") <= beforeS)
			{
				upTo.add(e);
				long lo = Research.num(e.getAsJsonObject(), "avgLowPrice");
				price = lo > 0 ? lo : price;
			}
		}
		Research r = Research.of(itemId, upTo);
		if (!r.ok || price <= 0)
		{
			return UNMEASURABLE;
		}
		return new double[]{r.contiguity, r.sampled > 0 ? (double) r.negativeBuckets / r.sampled : 0,
			r.medianMargin * 100.0 / price, Math.min(r.buyFlowHr, r.sellFlowHr)};
	}

	/** Learn again from the finished flips (newest first). Cheap: no network. */
	void learn(List<GameSnapshot.Done> history, long nowMs)
	{
		learn(history, List.of(), nowMs);
	}

	/**
	 * ...with {@code week}: the account's flips of the last week, newest first (GameSnapshot.week, from the flip archive)
	 * — what items rest by, as the trade log keeps only its newest 200 flips (11-05: back to 11-04 07:04). Empty =
	 * {@code history}.
	 */
	void learn(List<GameSnapshot.Done> history, List<GameSnapshot.Done> week, long nowMs)
	{
		List<String> out = new ArrayList<>();
		learnFloor(history, out);
		learnFlow(history, out);
		learnResting(week.isEmpty() ? history : week, nowMs, out);
		rules = RULES;            // its rests are this code's (an old save's were dropped at load, in fixed)
		lines = out;
	}

	/**
	 * A flip that SAT: {@link #SAT_MS}+ logged in, some of it unsold outside the bank, from its buy to this sale
	 * (GameSnapshot.Done.heldMs). Not measured (before 1.60.30) = not judged: the clock time from buy to sale counted the
	 * user's logouts and bank time.
	 */
	static boolean sat(GameSnapshot.Done f)
	{
		return f.heldMs >= SAT_MS;
	}

	/** The activity floor that made the most money over the measured flips. */
	private void learnFloor(List<GameSnapshot.Done> history, List<String> out)
	{
		List<double[]> samples = samples(history, 0);         // {contiguity, profit}
		if (samples.size() < MIN_SAMPLES)
		{
			out.add(String.format("Trading activity: an item under 100k must trade in %.0f%% of 5-minute periods (measured on 81 "
				+ "flips, 2026-10-31). Learning it from your flips: %d of %d measured so far.", floor * 100, samples.size(), MIN_SAMPLES));
			return;
		}
		double[] cuts = new double[(int) Math.round((FLOOR_HIGH - FLOOR_LOW) / 0.05) + 1];
		for (int i = 0; i < cuts.length; i++)
		{
			cuts[i] = FLOOR_LOW + i * 0.05;
		}
		floor = bestCut(samples, cuts, floor);
		long[] under = tally(samples, floor, false);
		long[] over = tally(samples, floor, true);
		out.add(String.format("Trading activity: items under 100k only if they trade in %.0f%% or more of 5-minute periods — "
				+ "learned from %d %s at that price: under %.0f%% %d flips, %d lost, %+,d · %.0f%% or more %d flips, "
				+ "%d lost, %+,d. Dearer items use the Risk setting and need a margin of 0.5%% or more after the tax, now and "
				+ "usually - thinner ones sold at break-even 4 times in your first 9 flips at 1m+.",
			floor * 100, samples.size(), whose(samples.size()), floor * 100, under[0], under[1], under[2], floor * 100, over[0],
			over[1], over[2]));
	}

	/**
	 * The trading VOLUME the slower side must have (units an hour) that made the most money over the measured
	 * flips. Both sources agreed on 2026-10-31: the plugin's 81 flips — 100k+ an hour 24% lost, +14k a flip;
	 * 10k-100k 36% lost, -7.6k a flip — and the whole-market replay (tools/market_backtest.py, 3,231 replayed
	 * flips) — 100k+ 1% lost, 39.7k per slot-hour; 10k-100k 8% lost, 9.1k.
	 */
	private void learnFlow(List<GameSnapshot.Done> history, List<String> out)
	{
		// {slower side units an hour, profit, slower side GP an hour (units x the flip's buy price)}
		List<double[]> samples = new ArrayList<>();
		for (GameSnapshot.Done d : history)
		{
			double[] m = measures.get(d.id());
			if (m != null && m.length >= 4 && d.buyEach < FlipEngine.LEARNED_UNDER)
			{
				samples.add(new double[]{m[3], d.profit, m[3] * d.buyEach});
			}
		}
		for (Map.Entry<String, double[]> e : team.entrySet())
		{
			double[] t = e.getValue();
			if (t.length == 6 && t[5] < FlipEngine.LEARNED_UNDER && !mine(e.getKey()))
			{
				samples.add(new double[]{t[3], t[4], t[3] * t[5]});
			}
		}
		if (samples.size() < MIN_SAMPLES)
		{
			return;                   // the activity line already says how many are measured
		}
		// busy in UNITS (bulk) OR busy in GP (dear items): MEASURED 2026-10-31 on 87 flips — units 250k+ alone kept
		// 40 flips +986k; gp 100m+ alone 36 flips +710k (5% lost, ~3.9m cash a flip); EITHER kept 53 flips, 7 lost,
		// +992k with ~3.0m cash a flip, dropped 34 flips, 15 lost, -339k (tools/volume_units_vs_gp.py). More flips
		// pass, so more of the cash works (user: "i want it to use the whole cash stack").
		double bestUnits = flowFloor;
		double bestGp = gpFloor;
		long bestProfit = Long.MIN_VALUE;
		long bestKept = -1;
		for (double u : FLOW_CUTS)
		{
			for (double g : u <= 0 ? new double[]{0} : GP_CUTS)
			{
				long profit = 0;
				long kept = 0;
				for (double[] s : samples)
				{
					if (passes(s[0], s[2], u, g))
					{
						profit += (long) s[1];
						kept++;
					}
				}
				long dropped = samples.size() - kept;
				if (kept < MIN_KEPT || (dropped > 0 && dropped < MIN_DROPPED))
				{
					continue;
				}
				if (profit > bestProfit || (profit == bestProfit && kept > bestKept))
				{
					bestProfit = profit;
					bestKept = kept;
					bestUnits = u;
					bestGp = g;
				}
			}
		}
		flowFloor = bestUnits;
		gpFloor = bestGp;
		if (flowFloor <= 0)
		{
			out.add("Trading volume (items under 100k): no minimum - on your flips a minimum did not pay.");
			return;
		}
		long[] keep = new long[3];
		long[] drop = new long[3];
		for (double[] s : samples)
		{
			long[] t = passes(s[0], s[2], flowFloor, gpFloor) ? keep : drop;
			t[0]++;
			t[1] += s[1] < 0 ? 1 : 0;
			t[2] += (long) s[1];
		}
		out.add(String.format("Trading volume: items under 100k need %,.0f+ trading an hour on the slower side%s — learned "
				+ "from %d %s: kept %d flips, %d lost, %+,d · dropped %d flips, %d lost, %+,d.", flowFloor,
			gpFloor > 0 ? String.format(", or %s+ gp an hour of it (busy dear items)", FlipBrain.gp((long) gpFloor)) : "",
			samples.size(), whose(samples.size()), keep[0], keep[1], keep[2], drop[0], drop[1], drop[2]));
	}

	/** The volume rule: busy in units, or (with a gp minimum) busy in gp. */
	static boolean passes(double unitsHr, double gpHr, double unitsCut, double gpCut)
	{
		return unitsCut <= 0 || unitsHr >= unitsCut || (gpCut > 0 && gpHr >= gpCut);
	}

	/** {measure k, profit} for every measured flip — this account's, then the team's. */
	private List<double[]> samples(List<GameSnapshot.Done> history, int k)
	{
		List<double[]> out = new ArrayList<>();
		for (GameSnapshot.Done d : history)
		{
			double[] m = measures.get(d.id());
			if (m != null && m.length == 4 && d.buyEach < FlipEngine.LEARNED_UNDER)
			{
				out.add(new double[]{m[k], d.profit});
			}
		}
		for (Map.Entry<String, double[]> e : team.entrySet())
		{
			double[] t = e.getValue();
			if (t.length == 6 && t[5] < FlipEngine.LEARNED_UNDER && !mine(e.getKey()))
			{
				out.add(new double[]{t[k], t[4]});
			}
		}
		return out;
	}

	/** A team flip that is one of this account's own (its export went into the team brain): counted once, as its own. */
	private boolean mine(String teamKey)
	{
		int bar = teamKey.indexOf('|');
		return bar >= 0 && measures.containsKey(teamKey.substring(bar + 1));
	}

	/** How many of the team's flips count (under 100k) — for the learned lines. */
	private int teamCounted()
	{
		int n = 0;
		for (Map.Entry<String, double[]> e : team.entrySet())
		{
			double[] t = e.getValue();
			n += t.length == 6 && t[5] < FlipEngine.LEARNED_UNDER && !mine(e.getKey()) ? 1 : 0;
		}
		return n;
	}

	/** "your flips" or "flips (12 yours, 380 the team's)". */
	private String whose(int all)
	{
		int teamN = teamCounted();
		return teamN == 0 ? "your flips" : String.format("flips (%d yours, %d the team's)", all - teamN, teamN);
	}

	/**
	 * Of the candidate cuts, the one whose kept flips (measure >= cut) made the most money — a cut must keep
	 * MIN_KEPT flips and drop none or MIN_DROPPED (too few on one side to judge by); ties go to the lower cut.
	 */
	private static double bestCut(List<double[]> samples, double[] cuts, double current)
	{
		double best = current;
		long bestProfit = Long.MIN_VALUE;
		for (double c : cuts)
		{
			long[] over = tally(samples, c, true);
			long dropped = samples.size() - over[0];
			if (over[0] < MIN_KEPT || (dropped > 0 && dropped < MIN_DROPPED))
			{
				continue;
			}
			if (over[2] > bestProfit)
			{
				bestProfit = over[2];
				best = c;
			}
		}
		return best;
	}

	/** {flips, lost, profit} over the samples at or above (or under) {@code c}. */
	private static long[] tally(List<double[]> samples, double c, boolean atOrAbove)
	{
		long[] t = new long[3];
		for (double[] s : samples)
		{
			if ((s[0] >= c) == atOrAbove)
			{
				t[0]++;
				t[1] += s[1] < 0 ? 1 : 0;
				t[2] += (long) s[1];
			}
		}
		return t;
	}

	/**
	 * Rest an item that lost at least 2 of its last 3 flips of the past day and is down on them — or one of whose last 3
	 * flips of the past day SAT ({@link #sat}): until a day after that sale. A known problem item —
	 * a buy of it sat and sold at a loss, or {@link #SAT_TIMES} of its buys sat — until {@link #WEEK_MS} after that sale.
	 * The longest rest wins; one never shortens another.
	 */
	private void learnResting(List<GameSnapshot.Done> history, long nowMs, List<String> out)
	{
		Map<Integer, List<GameSnapshot.Done>> byItem = new LinkedHashMap<>();
		for (GameSnapshot.Done d : history)
		{
			if (d.at > 0 && nowMs - d.at <= REST_MS)
			{
				List<GameSnapshot.Done> l = byItem.computeIfAbsent(d.itemId, k -> new ArrayList<>());
				if (l.size() < 3)
				{
					l.add(d);         // newest first
				}
			}
		}
		SimpleDateFormat hm = new SimpleDateFormat("HH:mm");
		SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd");
		SimpleDateFormat date = new SimpleDateFormat("EEE d MMM HH:mm");
		String today = day.format(new Date(nowMs));
		Map<Integer, Long> until = new LinkedHashMap<>();
		Map<Integer, String> why = new HashMap<>();
		Map<Integer, String> names = new HashMap<>();
		for (List<GameSnapshot.Done> l : byItem.values())
		{
			int lost = 0;
			long sum = 0;
			long lastLoss = 0;
			for (GameSnapshot.Done d : l)
			{
				sum += d.profit;
				if (d.profit < 0)
				{
					lost++;
					lastLoss = Math.max(lastLoss, d.at);
				}
			}
			GameSnapshot.Done d = l.get(0);
			String name = d.name == null ? "item " + d.itemId : d.name;
			names.put(d.itemId, name);
			if (lost >= 2 && sum < 0)
			{
				long u = lastLoss + REST_MS;
				rest(until, why, d.itemId, u, lastLoss, nowMs, String.format("Resting %s until %s%s: lost %d of its last %d flips (%+,d).",
					name, today.equals(day.format(new Date(u))) ? "" : "tomorrow ", hm.format(new Date(u)), lost, l.size(), sum));
			}
			if (l.size() >= 2 && WEAK_PCT > 0)
			{
				long cost = 0;
				long made = 0;
				boolean dear = true;
				for (int i = 0; i < 2; i++)
				{
					GameSnapshot.Done f = l.get(i);
					cost += f.buyEach * f.qty;
					made += f.profit;
					dear &= f.buyEach >= WEAK_MIN_EACH_GP;
				}
				if (dear && cost > 0 && made * 100.0 / cost < WEAK_PCT)
				{
					long u = d.at + REST_MS;
					rest(until, why, d.itemId, u, d.at, nowMs, String.format("Resting %s until %s%s: its last 2 flips made %+,d on %s "
						+ "bought (%.2f%%) - too little for the cash it ties up.", name, today.equals(day.format(new Date(u))) ? "" : "tomorrow ",
						hm.format(new Date(u)), made, FlipBrain.gp(cost), made * 100.0 / cost));
				}
			}
			GameSnapshot.Done sat = null;
			for (GameSnapshot.Done f : l)
			{
				if (sat(f) && (sat == null || f.at > sat.at))
				{
					sat = f;
				}
			}
			if (sat != null)
			{
				long u = sat.at + REST_MS;
				rest(until, why, d.itemId, u, sat.at, nowMs, String.format("Resting %s until %s%s: it sat %.1fh (logged in, not in the "
					+ "bank) before it sold (%+,d) - hard to sell.", name, today.equals(day.format(new Date(u))) ? "" : "tomorrow ",
					hm.format(new Date(u)), sat.heldMs / 3_600_000.0, sat.profit));
			}
		}
		rememberSat(history, nowMs, names);
		for (Map.Entry<Integer, List<long[]>> e : satFlips.entrySet())
		{
			List<long[]> l = e.getValue();
			String name = names.getOrDefault(e.getKey(), satNames.getOrDefault(e.getKey(), "item " + e.getKey()));
			long[] last = null;
			long[] lostOne = null;
			for (long[] x : l)
			{
				last = last == null || x[1] > last[1] ? x : last;
				lostOne = x[2] < 0 && (lostOne == null || x[1] > lostOne[1]) ? x : lostOne;
			}
			if (lostOne != null)
			{
				long u = lostOne[1] + WEEK_MS;
				rest(until, why, e.getKey(), u, lostOne[1], nowMs, String.format("Resting %s until %s: a problem item - it sat %.1fh "
					+ "(logged in, not in the bank) and sold at a loss (%+,d).", name, date.format(new Date(u)),
					(lostOne.length > 3 ? lostOne[3] : lostOne[1] - lostOne[0]) / 3_600_000.0, lostOne[2]));
			}
			if (l.size() >= SAT_TIMES)
			{
				long u = last[1] + WEEK_MS;
				rest(until, why, e.getKey(), u, last[1], nowMs, String.format("Resting %s until %s: a problem item - it sat on hold %d "
					+ "times this week.", name, date.format(new Date(u)), l.size()));
			}
		}
		for (Map.Entry<Integer, Long> e : until.entrySet())
		{
			if (e.getValue() >= resting.getOrDefault(e.getKey(), 0L))
			{
				resting.put(e.getKey(), e.getValue());
				restWhy.put(e.getKey(), why.get(e.getKey()));
				out.add(why.get(e.getKey()));
			}
		}
	}

	/**
	 * A rest for {@code itemId} until {@code u}, unless over, a longer one is already found, or the sale behind it
	 * ({@code soldAt}) came before the user chose "Suggest again" (lifted).
	 */
	private void rest(Map<Integer, Long> until, Map<Integer, String> why, int itemId, long u, long soldAt, long nowMs,
		String line)
	{
		if (u > nowMs && u > until.getOrDefault(itemId, 0L) && soldAt > lifted.getOrDefault(itemId, 0L))
		{
			until.put(itemId, u);
			why.put(itemId, line);
		}
	}

	/**
	 * Every buy that SAT, into {@link #satFlips}: what the trade log has now replaces what was seen of the same buy before
	 * (its sells grow); older ones stay until {@link #WEEK_MS} after their sale. Fills {@code names} from the history.
	 */
	private void rememberSat(List<GameSnapshot.Done> history, long nowMs, Map<Integer, String> names)
	{
		Map<Integer, Map<Long, long[]>> now = new HashMap<>();
		for (GameSnapshot.Done f : history)
		{
			if (f.name != null)
			{
				names.putIfAbsent(f.itemId, f.name);
			}
			if (f.boughtAt > 0 && sat(f) && nowMs - f.at <= WEEK_MS)
			{
				long[] x = now.computeIfAbsent(f.itemId, k -> new HashMap<>())
					.computeIfAbsent(f.boughtAt, k -> new long[]{f.boughtAt, 0, 0, 0});
				x[1] = Math.max(x[1], f.at);
				x[2] += f.profit;
				x[3] = Math.max(x[3], f.heldMs);
				if (f.name != null)
				{
					satNames.put(f.itemId, f.name);
				}
			}
		}
		for (Map.Entry<Integer, Map<Long, long[]>> e : now.entrySet())
		{
			List<long[]> l = satFlips.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
			l.removeIf(x -> e.getValue().containsKey(x[0]));
			l.addAll(e.getValue().values());
		}
		for (List<long[]> l : satFlips.values())
		{
			l.removeIf(x -> nowMs - x[1] > WEEK_MS);
		}
		satFlips.values().removeIf(List::isEmpty);
		satNames.keySet().retainAll(satFlips.keySet());
	}
}
