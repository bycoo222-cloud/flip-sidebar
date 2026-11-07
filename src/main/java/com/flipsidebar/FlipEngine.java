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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import net.runelite.api.gameval.ItemID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The brain: scans the whole Grand Exchange and returns the flips worth doing.
 * A port of the Flip Sidebar's flip_advisor + flip_research + its scan, INCLUDING the calibration
 * on the user's 958 real Copilot flips (2026-10-31):
 * <ul>
 *   <li>quantity = a slice of the hour's trading (AdjustWindow.volumeShare) — big slices sat
 *       unfilled and lost money;</li>
 *   <li>buy-sell gap 1-15% (under 1% lost 36% of the time, over 15% lost 42%);</li>
 *   <li>liquid items only: 9m+ gp traded an hour (90% of the winning flips);</li>
 *   <li>margins under 10gp each only on items trading 50k+ units an hour (else 81% lost);</li>
 *   <li>bid AT the 5-minute average insta-sell, ask AT the 5-minute average insta-buy — where the
 *       user's Copilot flips actually traded (median 0.00% off both). ⚠ Never off a single recent
 *       trade: v1.11-1.16 bid at the HIGHER of the latest sale and the 5-minute average, and
 *       in-game 2026-10-31 that bought Aether catalyst at 269 (typical insta-sell 255-262, -182k)
 *       and Ranarr seed at 24,004 (typical 23,500, -84k) — one sale near the top set the price.</li>
 * </ul>
 * BLOCKING (network): run only on the plugin's engine thread.
 */
class FlipEngine
{
	private static final Logger log = LoggerFactory.getLogger(FlipEngine.class);

	static final Set<Integer> NEVER_FLIP = Set.of(13190);         // Old school bond: 10% to re-trade
	private static final Set<Integer> TAX_EXEMPT = Set.of(13190);
	/**
	 * Share of the {@code budget} passed in that one flip may use. FlipBrain now passes the cash for ONE buy
	 * (FlipBrain.flipCash), so all of it; it was 0.25 of all the cash until 1.32.0.
	 */
	static final double MAX_POSITION_FRAC = 1.0;
	/** GP an hour on the slower side that makes an item busy whatever its unit count (the learned gp minimum if higher). */
	static volatile double BUSY_GP_HR = 25_000_000;
	/** Units an hour on the slower side such an item still needs (12 = one every 5 minutes). */
	static volatile long BUSY_MIN_UNITS = 12;
	/**
	 * ⭐ The least share of 5-minute periods an item must trade in, whatever the Risk setting. MEASURED
	 * 2026-10-31 on the plugin's 81 flips (Research rebuilt from the wiki just before each buy,
	 * tools/flip_features.py): under 0.90 — 18 flips, 50% lost, -168k; 0.90 or more — 63 flips, 22% lost,
	 * +638k. Risk Low/Med/High allowed 0.60 / 0.30 / 0.10. User: "i need it to make more money ... its
	 * struggling". Learned from the flips from now on (FlipLearner) — this is the starting value.
	 */
	static volatile double activityFloor = 0.90;
	/**
	 * ⭐ The LEARNED rules (activityFloor, flowFloor / gpFloor) judge only items under this price each — the range they
	 * were learned on: FlipLearner's 118 measured flips (2026-11-01) had a median price of 261gp, 90% under 8k, 3 at
	 * 100k+. Dearer items get the Risk setting's own thresholds (calibrated on the user's Copilot flips), and there they
	 * were the SAFEST flips: of 1,342 Copilot flips, 100k-1m lost 11% (+35.8k a flip); 1m+ sold within a day, 244 flips,
	 * lost 6% (+44k a flip) — against 20% under 1k. In-game 2026-11-01, 4 slots empty and 127m in the inventory:
	 * "Nothing worth doing right now"; the 85% activity rule alone rejected 40 of the 60 items moving the most gp.
	 */
	static volatile long LEARNED_UNDER = 100_000;
	/**
	 * ⭐ An item under {@link #LEARNED_UNDER} is not bought at or over this share of its last hour's average insta-buy
	 * (Research.hourAsk) — the margin is then only a short price jump (1.56.0). User, 2026-11-02, of the Green dragonhide
	 * card (bought 1,603 to sell at 1,795, a 5-minute jump; the hour's average sell 1,555 — sold at 1,548, -63,920):
	 * "id use data to answer it ... In tests would flips like that be successful?". MEASURED (whole-market replay,
	 * 17,930 flips on 510 items, 10-28 to 11-02, the v0.42.0 sell rule, spike_replay2.py): bought at 0.99-1.00 of the
	 * hour's average insta-buy 34% lost, 1.00-1.03 31-36%, 1.03+ 50% — every bucket from 0.99 lost money in BOTH halves
	 * of the data; skipping them removes 697 flips (3.9%) worth -4.03m (-2.03m and -2.00m). Under 0.97: 6% lost,
	 * +2.6-3.0%. NOT measured: items 100k+ (4 such flips); 8 items made most of the loss (Ring of recoil, Snape grass
	 * seed, Ascension shards ...). A high SELL price alone (the card's ask over the hour) did NOT lose money.
	 */
	static volatile double HOUR_ASK_MAX = 0.99;
	/** The least units an hour the slower side must trade — learned by FlipLearner; 0 = no minimum. */
	static volatile double flowFloor;
	/** ...or this much GP an hour (units x price) on the slower side — learned by FlipLearner; 0 = no such alternative. */
	static volatile double gpFloor;
	/** Item -> {replayed flips, lost, profit a flip} in the whole market's last day (MarketReplay); empty until it ran. */
	static volatile Map<Integer, long[]> replayed = Map.of();
	/**
	 * Items the user's own flips proved (FlipLearner.proven: maxed out, made money, a third or fewer lost): researched
	 * first and judged without the rules learned from OTHER items (activity floor, volume, the whole-market replay) —
	 * their own results outweigh them. The margin rules still apply, and they RANK like any pick, by profit per
	 * slot-hour: MEASURED live 2026-11-01, 150m and 8 slots, ranking the user's 12 proven items first filled all 8 with
	 * rune orders — 26.4m out, +374k a round — where the normal ranking put out 33.2m for +484k (Master wand +95k,
	 * Awakener's orb +67k, Hydra leather +63k pushed out by Chaos and Law runes at +18k).
	 */
	static volatile Set<Integer> proven = Set.of();
	/**
	 * ⭐ A PROVEN item's buy takes this share of an hour's trading on its slower side (others: the re-price window's,
	 * AdjustWindow.volumeShare), if it still fills within the window's slack — else the window's. User, 2026-11-05:
	 * "Im give 140m to use" — "If we dont utilize the money we have it wont help people who are flipping with a large
	 * volume of cash" ("C yes"). MEASURED that day (11-05 06:07-09:08): ~10m of the 140m in the GE on average, slots
	 * nearly always full at ~2m each; 54% of 180 buy cards were capped by the 0.10 slice, 1% by cash; the user's buys
	 * filled at ~1.06x the hour's volume an hour (0.10 in ~6 minutes). NOT measured: whether a 0.20 slice fills as
	 * fast for its share — the slow-buy and slow-sell timing is scaled by {@link #provenScale} so it is not re-priced
	 * for being bigger.
	 */
	static volatile double PROVEN_SHARE = 0.20;

	/** How much bigger a proven item's slice is than the window's (1 = the same): its offers' slow timing is scaled by it. */
	static double provenScale(AdjustWindow window)
	{
		return Math.max(1, PROVEN_SHARE / window.volumeShare);
	}
	/** An item the whole-market replay rates under this a flip (3+ replayed flips) is not suggested (MarketReplay doc). */
	static volatile long REPLAY_MIN_EACH = 5_000;
	/**
	 * ⭐ A SELL WAITS AT BREAK-EVEN this long after the last buy before it is sold at the market under water (the
	 * "Wait at break-even" setting's default). Two measurements, 2026-10-31:
	 * (1) per LOSING flip — the plugin's 18 losing flips sold ~5 minutes in; replayed with a wait at break-even:
	 *     as sold -826k · 30 min -576k · 1 hour -418k · 2 hours -323k (loss_replay.py) — longer loses less per flip;
	 * (2) per WHOLE DAY with 7 GE slots — a slot waiting is a slot not flipping (portfolio_replay.py, 150m cash):
	 *     0 min +3.81m · 15 min +4.65m · 30 min +4.70m · 1 hour +3.66m · 2 hours +4.64m.
	 * Slots are the scarce thing (user: "i need it to use the money"), so 30 minutes (was 1 hour until 1.35.0). The
	 * "Wait at break-even" setting that changed it was removed in 1.45.1 (user: "yes remove it") — an under-water sell
	 * with its buy price on record is HELD since 1.44.0 (FlipBrain.underWater); this wait is left for one without.
	 * ⭐ 1.57.0: back to 60 MINUTES. (3) 2026-11-02, the whole market, 5 days, 8 SLOTS filled by the plugin's ranking
	 * (rules_slots.py, ~/.cache/claude-gs/rules): items 100-100k, 30 min 114k / 126k gp an hour (the two halves of the
	 * data), 60 min 157k / 139k, lost 11-12% -> 6-7%; all items 270k / 221k -> 302k / 226k — 60 wins in both halves,
	 * both ranges (measurement (2) was one day). The user's session 05:08-06:35 the same day: Magic logs -75,764, Redwood
	 * logs -33,991, Air orb -16,848 sold at the market by the 30-minute rule.
	 */
	static volatile long SELL_PATIENCE_MS = 120 * 60_000L;
	/*
	 * ⭐ 1.60.40: 2 HOURS, counted from the buy or this login, whichever is later (patienceFrom), and an under-water sell
	 * KEEPS ITS PRICE and its place in the line meanwhile (OfferAdvice.keepPlace) — no re-list to break-even. User,
	 * 2026-11-07 07:2x, of Magic logs told "sell at the market" at -68,544 (bought 04:45 at 600, the hour ran out while
	 * logged out 05:45-07:07; on sale 17 min): "why would we not be holding them for a little" — "literally waited a
	 * couple minutes the light wen green over the magic logs" — "if we had had them in at earlier we could of beat the
	 * other and hit the recovery swing". MEASURED (trade log, the 18 sells at a loss in the 29 h before, wiki /timeseries
	 * 5m after each sale): the 5-minute insta-buy average was back at break-even within 1 h for 11 of the 16 watched that
	 * long, within 2 h for 12 of 16 (most within 30 min); those 12 lost -1,123,070. Not back: Red chinchompa (a dump),
	 * Spectral spirit shield, Twinflame staff, Twisted buckler. With measurements (1) and (2) above, 2 hours lost less.
	 */
	/** Epoch ms of this login (FlipSidebarPlugin, LOGIN_SCREEN → LOGGED_IN); 0 = not known. */
	static volatile long sessionStart;

	/** When an under-water sell's patience (SELL_PATIENCE_MS) started: the buy, or this login if later. */
	static long patienceFrom(long lastBuy)
	{
		return Math.max(lastBuy, sessionStart);
	}
	/**
	 * ⭐ PARKED (1.41.0) — HELD (1.44.0): an under-water buy is kept in the INVENTORY — no GE slot — until its insta-buy
	 * is back at break-even; selling it at a loss is the user's choice (FlipBrain.underWater, sellAtLoss). User,
	 * 2026-11-01, of 22,552 Soul runes bought at 303 with the insta-buy swinging 296-311 all day: "what if we parked the
	 * items in the inventory used the slot and sold them later? an actual hold". MEASURED (tools/patience_replay.py: 2,096
	 * buys on 149 liquid items under 100k whose next insta-buy was already under break-even, 40 hours of the whole
	 * market, each with 8 hours after it; gross for a 5m order — parked costs no slot): its insta-buy at break-even in
	 * 20%+ of the previous day's 5-minute blocks — sold at once -52k, parked 2h -9k (80% back), 8h +0.3k (92% back);
	 * 5-20% — -60k, 8h -20k (76% back); under 5% — at once -70k, any wait worse (-73k to -79k).
	 */
	/** Under this share of the last day's blocks at break-even, a held item's row says "it rarely comes back". */
	static volatile double PARK_MIN_REACH = 0.05;

	/** quoteLive: the last trades count when both are at most this old (seconds)... */
	static volatile long LATEST_FRESH_S = 120;
	/**
	 * ...or this old for an item at {@link #LEARNED_UNDER}+ each: it trades a few times in 5 minutes, so the 5-minute
	 * average lags where it trades. In-game 2026-11-01 (user: "trades are slow. a lot of waiting with nothing
	 * happening"): 6 bids, 0 filled after 5-6 minutes, every one UNDER the item's last sale 1-5 minutes earlier —
	 * Mage's book 3,301,876 vs 3,311,773, Serpentine helm 2,621,351 vs 2,629,208, Hallowfell 1,421,935 vs 1,438,000,
	 * Dragon pickaxe 548,872 vs 549,643, Brimstone ring 1,332,632 vs 1,337,299, Aranea boots 614,126 vs 615,330 — 1-5
	 * insta-sells each in those 5 minutes went to higher bids. Cheap items keep 2 minutes (one sale near the top cost
	 * 182k on Aether catalyst, 2026-10-31).
	 */
	static volatile long LATEST_FRESH_DEAR_S = 10 * 60;
	/** quoteLive: with no insta-buys in 5 minutes, a last insta-buy this old (seconds) still beats a higher hour average. */
	static volatile long LATEST_STALE_ASK_S = 30 * 60;
	/** ...and no further than this from the 5-minute average (a farther one is an odd trade). */
	static volatile double LATEST_MAX_MOVE = 0.03;
	/**
	 * ⭐ AN ITEM OVER 100k ({@link #LEARNED_UNDER}) needs this margin after tax, % of the bid, BOTH at the quote now
	 * AND as the median of its last 48 traded 5-minute periods (Research.medianMargin). On 2026-11-01 the plugin's
	 * 1m+ flips sold at exactly break-even 4 times in 9 (Saradomin godsword, Zorya's tome, Rondache, Osmumten's
	 * fang): dear items trade 1-3 times in 5 minutes, so one block's prices swing 1-2% — about the 2% tax. MEASURED
	 * (tools/dear_backtest.py, one-unit flips of every 100k+ item over 40 hours of the whole market, Risk High, sells
	 * lowered after 5 minutes): the rule before — any margin now — 7,891 flips, 4% lost, 18% at break-even, median
	 * +0.48%, +2.60% of the cash a slot-hour; this rule at 0.5% — 4,665 flips, 2% lost, 12% at break-even, median
	 * +1.23%, +4.65%. By price at 0.5% (same run): 1-5m median +0.83%, 5-20m +0.81%, 20m+ +0.30% — the user's own
	 * Copilot flips at 1m+ made +0.92%, +0.48% and +0.30% (244 flips, 7% lost). Higher bars make more a flip and
	 * fewer flips (1%: 1-5m +1.39%, about half the 0.5% count); 0.5% matches what Copilot did with the user's money.
	 */
	static volatile double DEAR_MIN_MARGIN_PCT = 0.5;
	/**
	 * ⭐ EXPENSIVE ITEMS FROM THE LATEST TRADES (setting "Expensive items (1m+)", 1.60.34, off by default). User,
	 * 2026-11-05: "We just need to be able to use the money we have effectively. If this is truly the highest gp per
	 * hour we xan get fine i just think it isnt"; 2026-11-06 "Just do it". With this on, an item whose last insta-sell
	 * is {@value #DEAR_TRADES_MIN}+ is judged by {@link #judgeDear} on its LATEST trades (the wiki's /latest), never the
	 * 5-minute averages: one an hour or a few trade, so the averages miss them — live 2026-11-05 22:58 (harness
	 * WhyCheck, 21.9m a buy) the normal judge rejected 58 of the 60 items that could hold a 5m+ buy, and the plugin had
	 * shown no 5m+ card since 19:04. Buy ONE {@value #DEAR_BID_OVER_PCT}% over the last insta-sell (1.60.37; 1.60.36
	 * 0.25%, before it + 1 gp), sell at the last insta-buy; both trades within {@value #DEAR_FRESH_S} s,
	 * {@value #DEAR_MIN_TRADES}+ units each way in the last 2 hours, trading now (DEAR_MIN_5M_TRADES, 1.60.37), the 0.5%
	 * margin after tax now; the buy waits up to DEAR_BUY_WAIT_MS for a seller (10 min since 1.60.36; was 30); the sell is
	 * re-priced to the last insta-buy every DEAR_SELL_STEP_MS, never under cost, until SELL_PATIENCE_MS after the buy
	 * (OfferAdvice.dear; 1.60.37 — it kept its price until then), then the normal 60-min rule sells at the market. MODEL
	 * (priced at + 1 gp, buys waiting 30 min;
	 * dear_prints.py cut + dear_slots.py,
	 * ~/.cache/claude-gs/rules; the collector's trades 11-01 → 11-06, 481 items, 4.8 days, halves 1st|2nd): 8 slots of
	 * these items only, ranked by profit each x trades an hour, +459k|+389k an hour at 146m (93m in use), +535k|+543k at
	 * 500m. ASSUMED there: a trade at our exact price is ours (no queue), one trade = one unit, no other flipper on
	 * the item. NOT tested in game.
	 */
	static volatile boolean dearFromTrades;
	static volatile long DEAR_TRADES_MIN = 1_000_000;
	static volatile long DEAR_FRESH_S = 30 * 60;
	static volatile long DEAR_MIN_TRADES = 4;
	/**
	 * An expensive item's buy waits this long for a seller before it is cancelled (OfferAdvice.dear). 10 min since 1.60.36
	 * (was 30). MEASURED 2026-11-06 (journal offers × Flipping Copilot's logged suggestions, run side by side 17:59-19:15):
	 * Copilot's 1m+ buys at its own price that filled did so within 2 min (3 of 10: 0.2, 1.1, 1.9 min) and it cancelled
	 * the rest after 3-14 min; ours that day (21 buys, + 1 gp) filled 2 within 30 min (2.4 and 23.9 min) and 13 sat the
	 * whole 30 min unfilled, holding a slot and the cash. Small samples; the 23.9-min kind of fill is given up.
	 */
	static volatile long DEAR_BUY_WAIT_MS = 10 * 60_000L;
	/**
	 * An expensive item's bid over its last insta-sell, in % (at least 1 gp) — 0.10 since 1.60.37 (1.60.36: 0.25, before
	 * it + 1 gp). MEASURED 2026-11-06, Flipping Copilot's whole evening session 17:59-21:48 (journal offers at placement):
	 * its 52 1m+ bids sat a median +0.10% over the last insta-sell (+0.24% in its first 75 minutes, the 1.60.36 number);
	 * its bids that filled within 10 min +0.10%, those it cancelled +0.09% — the bid's size did not decide the fill, the
	 * item's trading did (DEAR_MIN_5M_TRADES). The 0.5% margin is judged at this bid.
	 */
	static volatile double DEAR_BID_OVER_PCT = 0.10;
	/**
	 * ⭐ AN EXPENSIVE ITEM TRADING NOW (1.60.37; user, 2026-11-06: "we need to at least match our competition"): a card
	 * only for an item with this many units traded in the latest 5-minute block (/5m highPriceVolume + lowPriceVolume)
	 * and a last insta-sell within {@link #DEAR_NOW_S} s. MEASURED 2026-11-06 (journal, 73 1m+ buys: Copilot's 52 from
	 * 17:59, ours 21 from 06:12; 5-minute block and last insta-sell as logged at placement): 3+ traded and an insta-sell
	 * within 10 min — 44 buys, 18 filled within 10 min (41%); the other 29, 5 (17%). Copilot's picks had a median 3.5
	 * traded and a 2.6-min-old insta-sell, ours 1 and 6.4 min; it filled 22 of 52 within 10 min, we 1 of 21. An
	 * insta-sell within 3 min did no better than within 10 (34% vs 29% for the rest). NOT known: whether our picks that
	 * pass fill like Copilot's — ours that passed filled 1 of 10 (morning; Copilot's 17 of 34, evening).
	 */
	static volatile long DEAR_MIN_5M_TRADES = 3;
	/**
	 * ...and its last insta-sell AND last insta-buy (the ask it sells at) at most this old, seconds (DEAR_MIN_5M_TRADES).
	 * The insta-buy too since 1.60.40: 2026-11-07 07:22 a Craw's bow (u) card bought 2 at 11,204,157 priced to sell at an
	 * insta-buy of 11,900,000 from 20 min before (+915,686 on the card); one filled at 07:25 and the next insta-buy was
	 * 11,242,010 — no profit after tax, the rest cancelled.
	 */
	static volatile long DEAR_NOW_S = 10 * 60;
	/**
	 * ⭐ HOW MANY of an expensive item (1.60.39; until then always ONE): half the units traded in its latest 5-minute block
	 * (rounded up, at least 1), no more than this much gp in the offer (at least one unit), the buy limit left and the cash
	 * for one buy. User, 2026-11-07: "It seems like weve been stuck for days not using weath". MEASURED 2026-11-06/07
	 * (journal, offers at placement): Flipping Copilot's evening kept a median 102m in BUYING/SELLING offers with 47.7m
	 * coins in the inventory; ours 44.4m (11-07 night) and 63.3m (11-06 morning) with 98.1m / 80.0m idle. Under 1m both
	 * buy the whole buy limit (median 99% / 100% of it) — the gap is the 1m+ items: Copilot's 51 1m+ offers were a median
	 * 18.2m (75th percentile 30.3m; its 17 of 2+ units a median 23.2m), 2-7 units in 17 of 51; ours 1 unit in 45 of 45.
	 * Where the gp did not cap it, its quantity ran about half the 5-minute block's trades (Amulet of fury 7 at 10 trades,
	 * Crystal armour seed 5 at 9, Twisted buckler 3 at 5, Twinflame staff 2 at 3). 30m = its 75th percentile: with it
	 * Osmumten's fang, Sanguinesti staff and Bandos godsword come out at 2 as Copilot bought them (25m gave 1). NOT tested
	 * in game; more units = more to sell if the price falls (the floor and the hour rule as for one).
	 */
	static volatile long DEAR_OFFER_GP = 45_000_000L;   // 1.60.41: 45m (was 30m), user: "why not put in of 3 of them?" — live in rules.json
	/**
	 * ⭐ AN EXPENSIVE ITEM THAT JUST FLIPPED AT A PROFIT is offered again (1.60.37): one sold at a profit within this long
	 * is researched first and its card ranked by that flip's own time from buy to sale (Pick.fillMinutes), not the 2-hour
	 * trade pace — it still has to pass every rule. MEASURED 2026-11-06 (Copilot's session, journal): it bought the same
	 * 1m+ item again right after a profitable flip, and those buys filled within 10 min — Twinflame staff 3 of 3, Twisted
	 * buckler 3 of 3, Brimstone ring 2 of 2, Bandos godsword 2 of 2, Primordial boots 2 of 2.
	 */
	static volatile long DEAR_AGAIN_MS = 60 * 60_000L;
	/** Expensive items sold at a profit within DEAR_AGAIN_MS: item → {sold at epoch ms, minutes from buy to sale}. Set by FlipBrain before a scan. */
	static volatile Map<Integer, long[]> dearWins = Map.of();
	/**
	 * ⭐ AN EXPENSIVE ITEM'S SELL with nothing sold this long since it was listed (or last filled) is re-priced to the last
	 * insta-buy, never under what it cost (OfferAdvice.dear; 1.60.37 — until then it kept its price for the first hour).
	 * MEASURED 2026-11-06 (Copilot's session, 63 1m+ sell listings, journal): it listed at the last insta-buy (median
	 * +0.00%) and re-listed at the new last insta-buy a median 5.0 min later (0.3-106), a median -0.10% step; 1-7 listings
	 * an item. It also sold under cost (Venator ring -267k, Bandos godsword -66k) — the floor here does not.
	 */
	static volatile long DEAR_SELL_STEP_MS = 5 * 60_000L;
	/** The scan researches at most this many expensive items (by profit each), besides the others. */
	static volatile int DEAR_MAX_RESEARCH = 40;
	/**
	 * ⭐ A DUMP: no buy card for an item whose latest 5-minute block (/5m) had this many times more units insta-sold
	 * (lowPriceVolume) than insta-bought (highPriceVolume), each + 1 (1.60.38; {@link #dumping}). User, 2026-11-07, after a
	 * session that lost 708k: "I need this to make as much money as flipper co pilot" (Flipping Copilot's plugin has a
	 * server dump-alert feed, DumpsStreamController). Red chinchompa 2026-11-07 01:08: 21,980 insta-sold vs 1,000
	 * insta-bought in the block, bid 1,212 on the 5-minute average over the last insta-buy (1,200); 7,000 filled in 33 s,
	 * the hour had ~503k insta-sold (the wiki's /1h; 25-75k in each of the 9 hours before), sold at 1,057 at 02:12 = -1,232,000 (back at
	 * 1,161-1,199 by 05:00). MEASURED (197 flips 11-05 → 11-07 in the trade log, each matched to its buy's placement in
	 * the journal, mk at placement — Copilot's and Flip Sidebar's buys alike): 10x+ — 17 flips, -1,556,268 in all, 9 lost,
	 * median -24; the other 180 +5,325,087, 10 lost. At 5x: 24 flips -1,105,155; at 3x: 30 flips -900,493. NOT known: how
	 * it does out of these three days; the bulk items it would also skip made about +113k of the 17's total (less the
	 * chinchompa and a -437k Superior dragon bones).
	 */
	static volatile long DUMP_RATIO = 10;
	/**
	 * ⭐ NOT ON A SPIKE (1.60.41): no expensive-item buy more than this % over its day's usual insta-sell
	 * (Research.daySellMedian: the median 5-minute insta-sell average of the last 24 hours, 24+ blocks). User, 2026-11-07,
	 * of a Dinh's bulwark card (buy 2 at 8,920,369, sell at a single 9,400,000 insta-buy) after it climbed from ~8.55-8.75m
	 * to 9.42m an hour since 01:00 on 14 trades an hour (30-40 before): "we shouldnt buy stuff like that. Its swinging up
	 * but its clearly not going to be a profit sale". MEASURED (47 1m+ flips in the trade log, bid vs the median wiki /1h
	 * insta-sell of the 24 h before the buy): under it 18 flips +948,936; 0-1% over 15 flips -483,756; 1-3% over 12 flips
	 * +577,358; 3%+ over 2 flips (Venator ring -267,438, Archers ring 0) — the Dinh's bulwark bid was +3.9%. Two flips:
	 * thin; it blocks few (2 of 47).
	 */
	static volatile double DAY_PREMIUM_MAX_PCT = 3.0;
	/**
	 * ⭐ THE GOAL, as a share of the cash (1.60.44): gp per hour / the coins in flipping, in % — shown on the top card
	 * (green at it). User, 2026-11-07: "Our goal here is gp per hour no matter wat the cash it should be making good roi.
	 * Thats the key here we need to have a goal for that. Wats 500k per hour with 150m invested equate to?" — 0.33%.
	 * Flipping Copilot's 2026-11-06 evening: 499k an hour = 0.34% of ~146m, 0.49% of the ~102m it kept in offers.
	 */
	static volatile double ROI_GOAL_PCT_HOUR = 0.33;
	/**
	 * ⭐ DIPS RANK HIGHER, SPIKES ARE NOT BOUGHT — items under 1m (1.60.44). User, 2026-11-07: "I want all of it". MEASURED
	 * (NOTES.md "BIG BRAIN FINDINGS": every item's /1h history 10-08 → 11-07, items 100 gp+ trading 5m+ a day; learned on
	 * the first 15 days, judged on the last 15; bought at the hour's insta-sell average, sold at the first hour 1%+ over
	 * break-even within 24 h, else hour 24; vs the same items at a random hour): 3%+ under the previous 24 h median —
	 * 100-10k +6.59% vs +4.18% (lost 3% vs 8%), 10k-1m +7.45% vs +5.29% (1% vs 5%); 6%+ under — edge +5.07 / +4.81 points;
	 * 3%+ OVER — edge -0.95 / -0.61, lost 12% vs 7% / 9% vs 4%. 1m+: both tiny (judgeDear keeps its own spike rule).
	 * The boost (rank × 1 + DIP_BOOST_PER_PCT × the dip in %, at most DIP_BOOST_MAX) follows those medians: a 3% dip
	 * made ~1.5x the random flip, a 6% one ~1.8-1.9x. ⚠ The % assume fills at hourly averages; the edge is the part to
	 * trust. Research.daySellMedian is the median 5-minute insta-sell of the last 24 h (≈ the hourly one).
	 */
	static volatile double DIP_MIN_PCT = 3.0;
	static volatile double DIP_BOOST_PER_PCT = 0.15;
	static volatile double DIP_BOOST_MAX = 2.0;
	/**
	 * ⭐ DAILY CYCLES (1.60.44): items whose daily low/high hour HELD — from profiles.json (FlipSidebarPlugin.profilesTick,
	 * built by ~/flip-finder/bigbrain/build_profiles.py: the low hour the same ±2 h in both halves of the last 30 days, and
	 * buying at the low hour / selling at the high hour beat the same hours + 12 h by 0.5+ points on the second half, made
	 * money and won 60%+ of days — 178 items of 1,711 on 2026-11-07). item → {low hour UTC, high hour UTC}. Within an hour
	 * of its low a buy ranks × CYCLE_BOOST, within an hour of its high × CYCLE_PENALTY — those two sizes are JUDGEMENT,
	 * not measured; the cycles are. Items under 1m only (1m+ cycles did not beat the placebo: +0.03% vs -0.11%).
	 */
	static volatile Map<Integer, int[]> cycles = Map.of();
	/** profiles.json's 30-day level per item: the median daily insta-sell average of the last 30 days (multi-day dips). */
	static volatile Map<Integer, Long> avg30 = Map.of();
	static volatile double CYCLE_BOOST = 1.25;
	static volatile double CYCLE_PENALTY = 0.75;
	/** A deep swing (Swing.findDeep): at least this % under the 30-day level (FlipEngine.avg30). */
	static volatile double DEEP_DIP_PCT = 10.0;
	/** ...and kept this long for its +1% after tax, then held like any buy. */
	static volatile long DEEP_HOLD_MS = 7 * 86_400_000L;
	/** Equipment watch (FlipBrain.judgeGear): a good time to buy after this many days without a new low (judgement)... */
	static volatile long GEAR_FLOOR_DAYS = 7;
	/** ...while still within this % of that low. */
	static volatile double GEAR_NEAR_LOW_PCT = 10.0;

	/** Reads profiles.json's {"items": {"id": {lowH, highH, avg30, ...}}} into {@link #cycles} and {@link #avg30}. */
	/** "built" (epoch ms) of the profiles in use; a file or download no newer is not applied again (1.60.52). */
	static volatile long profilesBuilt;

	/** {@link #applyProfiles} when {@code root} is newer than the profiles in use; true when applied. */
	static boolean applyProfilesIfNewer(JsonObject root)
	{
		long built = root != null && root.has("built") ? root.get("built").getAsLong() : 0;
		if (built <= profilesBuilt)
		{
			return false;
		}
		applyProfiles(root);
		profilesBuilt = built;
		return true;
	}

	static void applyProfiles(JsonObject root)
	{
		Map<Integer, int[]> c = new HashMap<>();
		Map<Integer, Long> a = new HashMap<>();
		JsonObject items = root == null || !root.has("items") ? new JsonObject() : root.getAsJsonObject("items");
		for (Map.Entry<String, JsonElement> e : items.entrySet())
		{
			try
			{
				int id = Integer.parseInt(e.getKey());
				JsonObject o = e.getValue().getAsJsonObject();
				if (o.has("lowH") && o.has("highH"))
				{
					c.put(id, new int[]{Math.floorMod(o.get("lowH").getAsInt(), 24), Math.floorMod(o.get("highH").getAsInt(), 24)});
				}
				if (o.has("avg30") && o.get("avg30").getAsLong() > 0)
				{
					a.put(id, o.get("avg30").getAsLong());
				}
			}
			catch (RuntimeException ex)
			{
				// one bad row is skipped, not the file
			}
		}
		cycles = Map.copyOf(c);
		avg30 = Map.copyOf(a);
	}

	/** True when UTC hour {@code h} is within an hour of {@code target}. */
	static boolean nearHour(int h, int target)
	{
		int d = Math.abs(h - target);
		return Math.min(d, 24 - d) <= 1;
	}

	/** A UTC hour in the player's own clock, "8pm" style. */
	static String localHour(int utcHour)
	{
		java.time.ZonedDateTime z = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).withHour(utcHour)
			.withZoneSameInstant(java.time.ZoneId.systemDefault());
		int h = z.getHour();
		return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? "am" : "pm");
	}
	static volatile double GAP_MIN_PCT = 1.0;
	static volatile double GAP_WARN_PCT = 8.0;
	static volatile double GAP_MAX_PCT = 15.0;
	static volatile long MIN_HOURLY_GP = 9_000_000L;
	static volatile long SMALL_MARGIN_MIN_UNITS_HR = 50_000L;
	/**
	 * ⭐ The least profit each after tax a flip may have (1.60.31). MEASURED 2026-11-05 (journal 11-01..11-05, buys under 1k
	 * each priced by their real flips, tools/levers/bymargin.py): 1gp each 55 buys, 31% filled at all, 902 gp per slot-minute
	 * (Ancient essence: 29 buys at 13, ONE filled, 290 slot-minutes) · 2gp 64%, 5,887 · 3-5gp 1,458 · 6-10gp 2,858. At a 1gp
	 * margin the bid sits in a long queue of buyers at the same price.
	 */
	static volatile long MIN_EACH = 2;
	private static final long MAPPING_MAX_AGE_MS = 24L * 3600 * 1000;

	/** Hears a scan as it goes — FlipBrain's status line and its early pick. */
	interface Progress
	{
		void checked(int done, int total);

		void found(Pick pick);
	}

	private final WikiPrices prices;
	/**
	 * ⭐ THE WHOLE MARKET'S LAST DAY (FlipBrain's MarketMemory, built from the bulk /5m calls — one request for every
	 * item): research reads an item's 5-minute history from it instead of one /timeseries request per item (series5m).
	 * 1.60.43, a release blocker found 2026-11-07: every 3-minute scan fetched up to 150 + 40 per-item series (~50+
	 * requests a minute per user) and the User-Agent names the plugin — enough users and the wiki could block them all.
	 * Same thread as the scan (FlipBrain's single engine thread). Null = none yet.
	 */
	volatile MarketMemory memory;
	/** Research reads MarketMemory only once it holds this many of the day's 288 blocks (12 hours)... */
	static volatile int MEMORY_MIN_BLOCKS = 144;
	/** ...and its newest block is at most this old (seconds); else the wiki, cached (WikiPrices). */
	static volatile long MEMORY_MAX_AGE_S = 15 * 60;
	private JsonArray mapping;
	private long mappingAt;
	/** Replaced whole, never changed in place: FlipBrain reads them from other threads. */
	private volatile Map<Integer, String> names = new HashMap<>();
	/** Lower-case name -> id, for the "Never suggest" list typed in the settings. */
	private volatile Map<String, Integer> byName = new HashMap<>();
	private volatile Map<Integer, Long> limits = new HashMap<>();
	/** Item -> its High Level Alchemy value (the wiki mapping's "highalch"; absent = none / not loaded). */
	private volatile Map<Integer, Long> alchs = new HashMap<>();
	/** Every item with a High Level Alchemy value — what High alch mode plans from (AlchPlan). */
	private volatile List<AlchPlan.Item> alchItems = List.of();
	/** Members-only items (the wiki mapping's "members"). */
	private volatile Set<Integer> members = new HashSet<>();
	/** Every item with a High Level Alchemy value (empty before the item list loads). */
	List<AlchPlan.Item> alchItems()
	{
		return alchItems;
	}

	/** Its High Level Alchemy value; 0 = none or not loaded. */
	long highAlch(int itemId)
	{
		return alchs.getOrDefault(itemId, 0L);
	}

	/**
	 * ⭐ ALCH a losing sell instead (1.57.0; user, 2026-11-02: "that's good [alching] bad flips when possible"): what High
	 * Level Alchemy pays each, after a nature rune at {@code natureRune} — when it beats {@code market} after the GE tax
	 * and the item count is at most {@link #ALCH_MAX_ITEMS}; else 0. MEASURED (rules_replay3.py, the whole market, 5 days,
	 * the plugin's rules): alching beat the sale on 85 / 103 losing flips a half; of {@value #ALCH_MAX_ITEMS} items or
	 * fewer it saved +2.16m / +3.13m (jewellery, bows, bolts). The player casts it; the plugin only says so.
	 */
	static long alchEach(long highAlch, long natureRune, long market, long qty, int itemId)
	{
		long floor = highAlch - natureRune;
		return highAlch > 0 && natureRune > 0 && qty > 0 && qty <= ALCH_MAX_ITEMS && floor > market - tax(market, itemId)
			? floor : 0;
	}

	/** At most this many to alch (about an hour of casting at ~1,200 an hour) — the replay counted only these. */
	static final long ALCH_MAX_ITEMS = 1_200;
	/** The nature rune's item id (gameval ItemID.NATURERUNE). */
	static final int NATURE_RUNE = ItemID.NATURERUNE;

	/** A members' item (the wiki mapping), false before it loads. */
	boolean members(int itemId)
	{
		return members.contains(itemId);
	}

	/** Free-to-play account: members' items are never a pick — a free player can't buy them (FlipSidebarConfig). */
	volatile boolean freeToPlay;

	/**
	 * ⭐ SPIKE GUARD (user, 2026-11-04, after the Robin Hood hat: "we bought it while a meme was going around hence the
	 * price spike then it lost popularity" — the card said BUY at 4,908,498 on "-9.6% vs the previous 3h"; it sold at
	 * 2,879,183, −2,075,546). The research only sees the last ~30 hours, so a slide off a spike reads as a dip. No card
	 * while the buy price is over {@value #SPIKE_MAX}x the item's middle price of the last {@value #MONTH_DAYS} whole
	 * days. MEASURED (rule replay, only prices from before each buy): the plugin's 638 flips +2.86m (2 blocked, both
	 * losses: the hat at 2.2x, Masori mask at 1.5x); the 1,324 Copilot flips before it +0.20m (29 blocked, 19 were
	 * losses). At 1.3x the Copilot flips lost 0.49m; at 2x it helped less (+0.06m).
	 */
	static volatile double SPIKE_MAX = 1.5;
	/** Pick.rejected when the cash for one buy does not pay for one item. */
	static final String NO_CASH = "not enough cash for one";
	static final int MONTH_DAYS = 30;
	/** Fewer whole days known = a new item: not judged (skipping new items LOST 2.96m on the Copilot flips). */
	static final int MONTH_MIN_DAYS = 20;
	/** A 30-day level is fetched again after this long. */
	static final long MONTH_LEVEL_MAX_AGE_MS = 6 * 3600_000L;
	/** {@link #monthLevel(int)} when the history could not be fetched: the scan skips the item (fails closed). */
	static final long LEVEL_UNKNOWN = -1;
	/** Item -> {30-day level (0 = a new item), fetched epoch ms}. */
	private final Map<Integer, long[]> monthLevels = new ConcurrentHashMap<>();
	/** Items the last scan kept off for a spike -> {buy price, 30-day level}; replaced whole. */
	private volatile Map<Integer, long[]> spikedNow = Map.of();

	/**
	 * The middle price of the last {@value #MONTH_DAYS} whole days from the wiki's 24-hour /timeseries (oldest first):
	 * the median of each day's (average insta-buy + average insta-sell) / 2 — one side when only one traded. The day
	 * still running at {@code nowS} is left out. 0 = under {@value #MONTH_MIN_DAYS} days known (a new item).
	 */
	static long monthLevel(JsonArray daily, long nowS)
	{
		List<Double> mids = new ArrayList<>();
		for (JsonElement e : daily == null ? new JsonArray() : daily)
		{
			JsonObject d = e.getAsJsonObject();
			long ts = Research.num(d, "timestamp");
			long hi = Research.num(d, "avgHighPrice");
			long lo = Research.num(d, "avgLowPrice");
			if (ts <= 0 || ts + 86_400 > nowS || (hi <= 0 && lo <= 0))
			{
				continue;
			}
			mids.add(hi > 0 && lo > 0 ? (hi + lo) / 2.0 : Math.max(hi, lo));
		}
		if (mids.size() < MONTH_MIN_DAYS)
		{
			return 0;
		}
		List<Double> last = new ArrayList<>(mids.subList(Math.max(0, mids.size() - MONTH_DAYS), mids.size()));
		Collections.sort(last);
		int n = last.size();
		return Math.round(n % 2 == 1 ? last.get(n / 2) : (last.get(n / 2 - 1) + last.get(n / 2)) / 2);
	}

	/** True when {@code bid} is over {@value #SPIKE_MAX}x a known 30-day level. */
	static boolean spiked(long bid, long level)
	{
		return level > 0 && bid > level * SPIKE_MAX;
	}

	/** E.g. "price 2.2x its 30-day level (2.27m) - a spike; buys like this lost". */
	static String spikeWhy(long bid, long level)
	{
		return String.format("price %.1fx its 30-day level (%s) - a spike; buys like this lost", (double) bid / level,
			FlipBrain.gp(level));
	}

	/**
	 * The item's 30-day level, fetched at most every 6 hours — blocking, on the brain's thread like the scan's research.
	 * A failed fetch: the last level known, else {@link #LEVEL_UNKNOWN} (not remembered — the next scan tries again).
	 */
	long monthLevel(int itemId)
	{
		long now = System.currentTimeMillis();
		long[] had = monthLevels.get(itemId);
		if (had != null && now - had[1] < MONTH_LEVEL_MAX_AGE_MS)
		{
			return had[0];
		}
		try
		{
			long level = monthLevel(prices.timeseries(itemId, "24h"), now / 1000);
			monthLevels.put(itemId, new long[]{level, now});
			return level;
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Flip Sidebar: 30-day level of {} failed", itemId, e);
			return had != null ? had[0] : LEVEL_UNKNOWN;
		}
	}

	/** {@link #monthLevel(int)} on OkHttp's pool, for an item you picked; never fails ({@link #LEVEL_UNKNOWN} instead). */
	CompletableFuture<Long> monthLevelAsync(int itemId)
	{
		long[] had = monthLevels.get(itemId);
		if (had != null && System.currentTimeMillis() - had[1] < MONTH_LEVEL_MAX_AGE_MS)
		{
			return CompletableFuture.completedFuture(had[0]);
		}
		return prices.getAsync(WikiPrices.seriesPath(itemId, "24h")).thenApply(j ->
		{
			long now = System.currentTimeMillis();
			long level = monthLevel(j.getAsJsonObject().getAsJsonArray("data"), now / 1000);
			monthLevels.put(itemId, new long[]{level, now});
			return level;
		}).exceptionally(e -> had != null ? had[0] : LEVEL_UNKNOWN);
	}

	/** Items the last scan kept off for a spike: item -> {buy price, 30-day level}. */
	Map<Integer, long[]> spikedNow()
	{
		return spikedNow;
	}

	@Inject
	FlipEngine(WikiPrices prices)
	{
		this.prices = prices;
	}

	static long tax(long sellPrice, int itemId)
	{
		if (sellPrice <= 50 || TAX_EXEMPT.contains(itemId))
		{
			return 0;
		}
		return Math.min((long) (sellPrice * 0.02), 5_000_000L);
	}

	String name(int itemId)
	{
		return names.get(itemId);
	}

	/** An item's id from its name (any case), or null when unknown or the names are not loaded yet. */
	Integer idOf(String name)
	{
		return name == null ? null : byName.get(name.trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * Item lookup: ids of tradeable items whose name contains {@code query} (any case) — names that
	 * START with it first, then the shorter names; at most {@code max}. Empty before the first scan
	 * has loaded the names, or for fewer than 2 letters.
	 */
	List<Integer> search(String query, int max)
	{
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		Map<Integer, String> all = names;
		if (q.length() < 2)
		{
			return List.of();
		}
		List<Integer> hits = new ArrayList<>();
		for (Map.Entry<Integer, String> e : all.entrySet())
		{
			if (e.getValue().toLowerCase(Locale.ROOT).contains(q))
			{
				hits.add(e.getKey());
			}
		}
		hits.sort(Comparator.comparing((Integer id) -> !all.get(id).toLowerCase(Locale.ROOT).startsWith(q))
			.thenComparingInt(id -> all.get(id).length())
			.thenComparing(all::get));
		return hits.size() > max ? new ArrayList<>(hits.subList(0, max)) : hits;
	}

	/** Item names are loaded (the first scan's mapping), so lookup can search. */
	boolean hasNames()
	{
		return !names.isEmpty();
	}

	/** The GE buy limit, or 0 when unknown (before the first scan). */
	long limit(int itemId)
	{
		return limits.getOrDefault(itemId, 0L);
	}

	/** What is left of a buy limit after {@code used} this window; -1 when the limit is unknown. */
	static long remaining(long limit, long used)
	{
		return limit <= 0 ? -1 : Math.max(0, limit - used);
	}

	/**
	 * [bid, ask]: the 5-minute AVERAGE insta-sell and insta-buy; a side with no trades in those 5
	 * minutes falls back to the hour's average. Never a single trade. MEASURED 2026-10-31 on the
	 * user's 120 most recent finished Copilot flips: their buy price was a median 0.00% off the
	 * 5-minute average insta-sell (middle half -0.23% .. +0.11%) and -0.03% off the hour's; their
	 * sell price 0.00% off the 5-minute average insta-buy and +0.36% off the hour's
	 * (each flip matched to the wiki's 5-minute and hourly prices at the minute it traded).
	 */
	/**
	 * [bid, ask] NOW: each side's last trade when it is at most {@link #LATEST_FRESH_S} old and within
	 * {@link #LATEST_MAX_MOVE} of its 5-minute average, else that average ({@link #quote}). The averages run
	 * 3-8 minutes behind (MEASURED 2026-10-31 17:52: the newest /5m block was 17:45; Blood rune's last
	 * insta-sell — the top buy offer — was 339 a minute earlier against a 334 average, and a buy at 333 sat
	 * unfilled). User: "it needs to be faster ... we need an edge". The last insta-sell is where a buy fills
	 * now, the last insta-buy where a sell fills now. A last trade far off the average is an odd one and is
	 * ignored (pricing off any single trade is what lost money before 1.17.0). ASSUMED, not yet measured:
	 * that these fill faster without losing more — to be scored against History.
	 */
	static long[] quoteLive(JsonObject hour, JsonObject five, JsonObject latest, long nowS)
	{
		long[] q = quote(hour, five);
		long hourLo = hour == null ? 0 : Research.num(hour, "avgLowPrice");
		long hourHi = hour == null ? 0 : Research.num(hour, "avgHighPrice");
		long ask = lastTrade(latest, "high", q[1], hourHi, nowS);
		// no insta-buys in the 5 minutes: the ask fell back to the HOUR's average, which lags a falling market —
		// take the last insta-buy of the past half hour when it is lower. In-game 2026-10-31: Awakener's orb
		// listed at 151,530 (the hour's average) while it last sold for ~140,000; its 5-minute blocks had no
		// insta-buys more often than not.
		long last = latest == null ? 0 : Research.num(latest, "high");
		if ((five == null || Research.num(five, "avgHighPrice") <= 0) && last > 0 && last < ask
			&& nowS - Research.num(latest, "highTime") <= LATEST_STALE_ASK_S)
		{
			ask = last;
		}
		return new long[]{lastTrade(latest, "low", q[0], hourLo, nowS), ask};
	}

	/**
	 * [bid, ask] for JUDGING A BUY: the bid now (quoteLive), and the LOWER of the ask's average and its last
	 * trade — one high trade never makes a flip look better than it is (the measured loss cause: a margin
	 * already gone at buy time).
	 */
	static long[] quoteBuy(JsonObject hour, JsonObject five, JsonObject latest, long nowS)
	{
		long[] avg = quote(hour, five);
		long[] now = quoteLive(hour, five, latest, nowS);
		return new long[]{now[0], avg[1] > 0 && now[1] > 0 ? Math.min(avg[1], now[1]) : avg[1]};
	}

	/**
	 * One side's last trade if fresh and near its 5-minute average {@code avg} OR its hour average
	 * {@code hourAvg}; else {@code avg}. The hour counts too because ONE dump drags a 5-minute block down:
	 * in-game 2026-10-31, ~509k Blood runes sold 18:05-18:10 pulled its average insta-sell to 323 while the
	 * market went straight back to 339 (hour 338) — 339 was thrown out as "odd", the card bid 323 and sat.
	 */
	private static long lastTrade(JsonObject latest, String side, long avg, long hourAvg, long nowS)
	{
		if (latest == null || avg <= 0)
		{
			return avg;
		}
		long last = Research.num(latest, side);
		boolean fresh = last > 0 && nowS - Research.num(latest, side + "Time")
			<= (avg >= LEARNED_UNDER ? LATEST_FRESH_DEAR_S : LATEST_FRESH_S);
		boolean near = Math.abs(last - avg) <= avg * LATEST_MAX_MOVE
			|| (hourAvg > 0 && Math.abs(last - hourAvg) <= hourAvg * LATEST_MAX_MOVE);
		return fresh && near ? last : avg;
	}

	static long[] quote(JsonObject hour, JsonObject five)
	{
		long lo = five == null ? 0 : Research.num(five, "avgLowPrice");
		long hi = five == null ? 0 : Research.num(five, "avgHighPrice");
		if (lo <= 0 && hour != null)
		{
			lo = Research.num(hour, "avgLowPrice");
		}
		if (hi <= 0 && hour != null)
		{
			hi = Research.num(hour, "avgHighPrice");
		}
		return new long[]{lo, hi};
	}

	/**
	 * What one unit bought at {@code bid} makes if sold at today's ask ({@code quote[1]}) after the
	 * tax; {@code Long.MIN_VALUE} when there is no ask to judge by.
	 */
	static long marginNow(long bid, long[] quote, int itemId)
	{
		return quote[1] <= 0 ? Long.MIN_VALUE : quote[1] - tax(quote[1], itemId) - bid;
	}

	/** The lowest sell price that does not lose money after the 2% tax on what was paid. */
	static long breakEven(long paidEach, int itemId)
	{
		// from the closed form (paid / 0.98, or paid + the 5m tax cap) less a few gp of rounding, then up 1 gp at a
		// time: counting up from what was paid took 5 million steps on a 1b item, on every frame of a slot hover
		long p = TAX_EXEMPT.contains(itemId) ? paidEach + 1
			: Math.max(paidEach + 1, Math.min(paidEach * 100 / 98, paidEach + 5_000_000L) - 5);
		while (p - tax(p, itemId) <= paidEach)
		{
			p++;
		}
		return p;
	}

	static long qtyFor(long budget, long price, long limit, long buyFlowHr, long sellFlowHr, double share)
	{
		if (price <= 0)
		{
			return 0;
		}
		long q = (long) (budget * MAX_POSITION_FRAC) / price;
		if (limit > 0)
		{
			q = Math.min(q, limit);
		}
		// a SLICE of what trades in an hour, sized to the slower side — a flip is only done
		// when it has been bought AND sold
		q = Math.min(q, Math.max(1, (long) (Math.min(buyFlowHr, sellFlowHr) * share)));
		return Math.max(0, q);
	}

	/** The least share of 5-minute periods an item at {@code price} each must trade in: the learned floor only under LEARNED_UNDER. */
	static double activityNeeded(RiskLevel risk, long price)
	{
		return Math.max(risk.minContiguity, price < LEARNED_UNDER ? activityFloor : 0);
	}

	/** One researched item -> a Pick, or null if it fails the risk profile. */
	static Pick evaluate(int itemId, String name, long limit, long[] quote, long budget, RiskLevel risk,
		AdjustWindow window, Research r, long nowS)
	{
		Pick p = judge(itemId, name, limit, quote, budget, risk, window, r, nowS);
		return p == null || p.rejected != null ? null : p;
	}

	/**
	 * Like {@link #evaluate}, but an item that fails the risk profile is still priced — the same
	 * bid, ask and quantity — with {@code Pick.rejected} saying why. For an item the user picked
	 * themselves (user, 2026-10-31: "if the user manually wants to buy a specific item it still
	 * needs to input a good item quantity and price"). Null only when there is no price at all.
	 */
	static Pick judge(int itemId, String name, long limit, long[] quote, long budget, RiskLevel risk,
		AdjustWindow window, Research r, long nowS)
	{
		long buy = quote[0];
		long sell = quote[1];
		if (buy <= 0 || sell <= 0)
		{
			return null;
		}
		boolean known = r != null && r.ok;
		boolean own = proven.contains(itemId);
		double activity = own ? risk.minContiguity : activityNeeded(risk, buy);
		String rejected = null;
		if (NEVER_FLIP.contains(itemId))
		{
			rejected = "bonds are never a flip - 10% to make one tradeable again";
		}
		else if (!known)
		{
			rejected = r == null ? "no trade history" : r.why;
		}
		else if (r.contiguity < activity)
		{
			rejected = String.format("trades in only %.0f%% of 5-minute periods - thin items lose (this needs %.0f%%)",
				r.contiguity * 100, activity * 100);
		}
		else if (!own && replayed.containsKey(itemId) && replayed.get(itemId)[0] >= 3 && replayed.get(itemId)[2] < REPLAY_MIN_EACH)
		{
			long[] rv = replayed.get(itemId);
			rejected = String.format("weak across the whole market's last day: %d replayed flips, %d lost, %+,d a flip",
				rv[0], rv[1], rv[2]);
		}
		else if (!own && buy < LEARNED_UNDER && !FlipLearner.passes(Math.min(r.buyFlowHr, r.sellFlowHr),
			(double) Math.min(r.buyFlowHr, r.sellFlowHr) * buy, flowFloor, gpFloor))
		{
			rejected = String.format("only %,d an hour trade on its slower side - low-volume items lost more for you (this needs %,.0f%s)",
				Math.min(r.buyFlowHr, r.sellFlowHr), flowFloor, gpFloor > 0 ? ", or " + FlipBrain.gp((long) gpFloor) + " gp an hour" : "");
		}
		else if (r.sampled > 0 && (double) r.negativeBuckets / r.sampled > risk.maxNegativeFrac)
		{
			rejected = String.format("no margin after tax in %d of the last %d 5-minute periods",
				r.negativeBuckets, r.sampled);
		}
		// AT the averages, where the user's Copilot flips traded. (Until 1.16.7 one step inside the
		// book on top: on a 84 -> 87 Chaos rune that cost 2 of the 3gp margin.)
		long bid = buy;
		long ask = sell;
		long each = (ask - bid) - tax(ask, itemId);
		double gap = (ask - bid) * 100.0 / Math.max(bid, 1);
		if (rejected == null && each <= 0)
		{
			rejected = "no margin after the 2% tax right now";
		}
		else if (rejected == null && each < MIN_EACH)
		{
			rejected = String.format("%dgp each - at a %dgp margin the queue at that price is long: 1 in 3 such buys "
				+ "filled at all", each, each);
		}
		else if (rejected == null && gap < GAP_MIN_PCT)
		{
			rejected = String.format("buy-sell gap %.1f%% is under %.0f%% - too thin", gap, GAP_MIN_PCT);
		}
		else if (rejected == null && gap > GAP_MAX_PCT)
		{
			rejected = String.format("buy-sell gap %.1f%% is over %.0f%% - 42%% of such flips lost", gap, GAP_MAX_PCT);
		}
		else if (rejected == null && bid < LEARNED_UNDER && r.hourAsk > 0 && bid >= r.hourAsk * HOUR_ASK_MAX)
		{
			rejected = String.format("the buy price %,d is at what it sold for over the last hour (%,d) - only a short "
				+ "price jump makes this margin; such buys lost money in the whole-market replay", bid, r.hourAsk);
		}
		else if (rejected == null && bid >= LEARNED_UNDER
			&& Math.min(each, r.medianMargin) * 100.0 / bid < DEAR_MIN_MARGIN_PCT)
		{
			rejected = String.format("margin after tax %.2f%% now, %.2f%% usually - an item over 100k needs %.1f%% both "
				+ "ways (the 2%% tax eats thinner ones)", each * 100.0 / bid, r.medianMargin * 100.0 / bid, DEAR_MIN_MARGIN_PCT);
		}
		else if (rejected == null && each < risk.minMargin && (r.buyFlowHr + r.sellFlowHr) < SMALL_MARGIN_MIN_UNITS_HR)
		{
			rejected = String.format("%dgp each on an item trading under %,d an hour", each, SMALL_MARGIN_MIN_UNITS_HR);
		}
		else if (rejected == null && bid < DEAR_TRADES_MIN && r.daySellMedian > 0
			&& bid * 100.0 / r.daySellMedian - 100 > DAY_PREMIUM_MAX_PCT)
		{
			// ⭐ NOT ON A SPIKE, every item under 1m too (1.60.44; FlipEngine.DIP_BOOST_PER_PCT has the measurement)
			rejected = String.format("on a spike: the bid is %+.1f%% over its day's usual insta-sell (%,d) - bought this high, "
				+ "items did worse than at a random hour", bid * 100.0 / r.daySellMedian - 100, r.daySellMedian);
		}
		// with no trade flow known, size on cash and the buy limit only
		long qty = known ? qtyFor(budget, bid, limit, r.buyFlowHr, r.sellFlowHr, window.volumeShare)
			: Math.min((long) (budget * MAX_POSITION_FRAC) / Math.max(bid, 1), limit > 0 ? limit : Long.MAX_VALUE);
		double fill = known ? Math.max(r.fillMinutes(qty, true), r.fillMinutes(qty, false)) : -1;
		if (own && known && PROVEN_SHARE > window.volumeShare)
		{
			// a proven item's bigger slice (PROVEN_SHARE), if it still fills in time
			long big = qtyFor(budget, bid, limit, r.buyFlowHr, r.sellFlowHr, PROVEN_SHARE);
			double bigFill = Math.max(r.fillMinutes(big, true), r.fillMinutes(big, false));
			if (big > qty && bigFill <= window.minutes * risk.fillSlack * 2)
			{
				qty = big;
				fill = bigFill;
			}
		}
		if (qty <= 0 && rejected == null)
		{
			rejected = NO_CASH;
		}
		if (rejected == null && fill > window.minutes * risk.fillSlack * 2)
		{
			rejected = String.format("~%.0f min to fill - too slow for a %s re-price", fill, window);
		}

		Pick p = new Pick();
		p.itemId = itemId;
		p.name = name;
		p.bid = bid;
		p.ask = ask;
		p.qty = Math.max(0, qty);
		p.each = each;
		p.total = each * p.qty;
		p.fillMinutes = fill;
		p.asOf = nowS;
		p.research = r;
		p.limit = limit;
		p.rejected = rejected;
		// ⭐ a DIP ranks higher (Pick.rank; DIP_BOOST_PER_PCT) — items under 1m only
		double dip = r.daySellMedian > 0 ? 100 - bid * 100.0 / r.daySellMedian : 0;
		if (bid < DEAR_TRADES_MIN && dip >= DIP_MIN_PCT)
		{
			p.boost = Math.min(DIP_BOOST_MAX, 1 + DIP_BOOST_PER_PCT * dip);
			p.boostWhy = String.format("%.1f%% under its day's usual price", dip);
			p.warnings.add(String.format("%.1f%% under its day's usual insta-sell (%,d) - dips like this came back more often "
				+ "than not", dip, r.daySellMedian));
		}
		// ⭐ its daily cycle (cycles): buying near its usual low ranks higher, near its usual high lower — under 1m only
		int[] cyc = bid < DEAR_TRADES_MIN ? cycles.get(itemId) : null;
		if (cyc != null)
		{
			int h = (int) ((nowS / 3600) % 24);
			if (nearHour(h, cyc[0]))
			{
				p.boost *= CYCLE_BOOST;
				p.boostWhy = (p.boostWhy.isEmpty() ? "" : p.boostWhy + " · ") + "near its usual daily low (" + localHour(cyc[0]) + ")";
				p.warnings.add(String.format("around its usual daily low (cheapest ~%s, dearest ~%s)", localHour(cyc[0]),
					localHour(cyc[1])));
			}
			else if (nearHour(h, cyc[1]))
			{
				p.boost *= CYCLE_PENALTY;
				p.boostWhy = (p.boostWhy.isEmpty() ? "" : p.boostWhy + " · ") + "near its usual daily HIGH (" + localHour(cyc[1]) + ") - ranked lower";
				p.warnings.add(String.format("around its usual daily HIGH (dearest ~%s, cheapest ~%s)", localHour(cyc[1]),
					localHour(cyc[0])));
			}
		}
		// display only: at the last hour's average insta-buy when the ask is over it (Pick.likely)
		if (known && r.hourAsk > 0 && r.hourAsk < ask)
		{
			p.likely = (r.hourAsk - tax(r.hourAsk, itemId) - bid) * p.qty;
		}
		if (gap > GAP_WARN_PCT)
		{
			p.warnings.add(String.format("gap %.1f%% is wide - 13%% of such flips lost money", gap));
		}
		if (known && r.trendKnown && Math.abs(r.trendPct) >= 2)
		{
			p.warnings.add(String.format("buy price %+.1f%% vs the previous 3h", r.trendPct));
		}
		else if (known && !r.trendKnown)
		{
			p.warnings.add("not enough history to judge the trend");
		}
		if (known && r.medianMargin > 0 && each > r.medianMargin * 2)
		{
			p.warnings.add(String.format("margin %d is %.1fx its 4h median (%d) - may close before you fill",
				each, (double) each / r.medianMargin, r.medianMargin));
		}
		if (known && r.marginMin <= 0)
		{
			p.warnings.add("margin dipped to " + r.marginMin + " at its worst in the last 4h");
		}
		if (r != null && spiked(bid, r.monthLevel))
		{
			p.warnings.add(0, spikeWhy(bid, r.monthLevel));   // an item you picked yourself (the card's never get here): first
		}
		return p;
	}

	/** An item the "Expensive items (1m+)" scan judges: its hour/5-minute bid or its last insta-sell is 1m+. */
	static boolean dearItem(long b, JsonObject latest)
	{
		return b >= DEAR_TRADES_MIN || (latest != null && Research.num(latest, "low") >= DEAR_TRADES_MIN);
	}

	/** True when an offer or a buy at {@code price} is an expensive item of the "Expensive items (1m+)" setting. */
	static boolean dearTrade(long price)
	{
		return dearFromTrades && price >= DEAR_TRADES_MIN;
	}

	/**
	 * An expensive item's quote from its LATEST trades (/latest): {bid = last insta-sell + {@value #DEAR_BID_OVER_PCT}%
	 * (at least 1 gp), ask = last insta-buy,
	 * seconds since the insta-sell, seconds since the insta-buy}; null when either is missing or older than
	 * {@link #DEAR_FRESH_S}.
	 */
	static long[] dearQuote(JsonObject latest, long nowS)
	{
		if (latest == null)
		{
			return null;
		}
		long hi = Research.num(latest, "high");
		long lo = Research.num(latest, "low");
		long hiAge = nowS - Research.num(latest, "highTime");
		long loAge = nowS - Research.num(latest, "lowTime");
		if (hi <= 0 || lo <= 0 || hiAge > DEAR_FRESH_S || loAge > DEAR_FRESH_S)
		{
			return null;
		}
		long over = Math.max(1, Math.round(lo * DEAR_BID_OVER_PCT / 100));
		return new long[]{lo + over, hi, Math.max(0, loAge), Math.max(0, hiAge)};
	}

	/**
	 * An expensive item judged on its latest trades ({@link #dearFromTrades}): ONE unit at {@code q} ({@link #dearQuote}),
	 * {@link #DEAR_MIN_TRADES}+ units each way in the last 2 hours (Research.instaSells2h / instaBuys2h) and
	 * {@link #DEAR_MIN_MARGIN_PCT}% after tax now, and trading now: {@link #DEAR_MIN_5M_TRADES}+ traded in {@code five}
	 * (its latest /5m block; null = none) and a last insta-sell within {@link #DEAR_NOW_S} s. {@code limit} = what is
	 * left of the buy limit (0 = none known). The slot time it ranks by (Pick.perHour) is the wait for the next
	 * insta-sell plus the next insta-buy at the last 2 hours' pace — or, for one that flipped at a profit within
	 * DEAR_AGAIN_MS ({@link #dearWins}), that flip's own buy-to-sale time when shorter. Never null.
	 */
	static Pick judgeDear(int itemId, String name, long limit, long[] q, JsonObject five, long budget, Research r, long nowS)
	{
		long bid = q[0];
		long ask = q[1];
		long each = ask - tax(ask, itemId) - bid;
		long sold = r == null ? 0 : r.instaSells2h;
		long bought = r == null ? 0 : r.instaBuys2h;
		long traded5 = traded5m(five);
		String rejected = null;
		if (NEVER_FLIP.contains(itemId))
		{
			rejected = "bonds are never a flip - 10% to make one tradeable again";
		}
		else if (r == null)
		{
			rejected = "no trade history";
		}
		else if (sold < DEAR_MIN_TRADES || bought < DEAR_MIN_TRADES)
		{
			rejected = String.format("expensive item: %d sold and %d bought in the last 2 hours - it needs %d each way",
				sold, bought, DEAR_MIN_TRADES);
		}
		else if (traded5 < DEAR_MIN_5M_TRADES || q[2] > DEAR_NOW_S || q[3] > DEAR_NOW_S)
		{
			rejected = String.format("expensive item not trading now: %d traded in the last 5 minutes, last insta-sell %s "
				+ "ago, last insta-buy %s ago - it needs %d and both within %d min", traded5, ago(q[2]), ago(q[3]),
				DEAR_MIN_5M_TRADES, DEAR_NOW_S / 60);
		}
		else if (r.daySellMedian > 0 && bid * 100.0 / r.daySellMedian - 100 > DAY_PREMIUM_MAX_PCT)
		{
			rejected = String.format("expensive item on a spike: the bid is %+.1f%% over its day's usual insta-sell (%,d) - "
				+ "it needs %.1f%% or less", bid * 100.0 / r.daySellMedian - 100, r.daySellMedian, DAY_PREMIUM_MAX_PCT);
		}
		else if (each * 100.0 / bid < DEAR_MIN_MARGIN_PCT)
		{
			rejected = String.format("expensive item: %.2f%% after tax on its latest trades - it needs %.1f%%",
				each * 100.0 / bid, DEAR_MIN_MARGIN_PCT);
		}
		long qty = dearQty(bid, budget, limit, traded5);
		if (qty <= 0 && rejected == null)
		{
			rejected = NO_CASH;
		}
		Pick p = new Pick();
		p.itemId = itemId;
		p.name = name;
		p.bid = bid;
		p.ask = ask;
		p.qty = qty;
		p.each = each;
		p.total = each * qty;
		// the whole quantity's slot time (Pick.sized scales it): each unit at the last 2 hours' pace, both ways
		p.fillMinutes = (120.0 / Math.max(1, sold) + 120.0 / Math.max(1, bought)) * Math.max(1, qty);
		long[] win = dearWins.get(itemId);
		boolean again = win != null && nowS * 1000 - win[0] <= DEAR_AGAIN_MS;
		if (again)
		{
			p.fillMinutes = Math.min(p.fillMinutes, Math.max(1, win[1]) * Math.max(1, qty));
		}
		p.asOf = nowS;
		p.research = r;
		p.limit = limit;
		p.rejected = rejected;
		p.dear = true;
		p.warnings.add(String.format("expensive item: priced on its latest trades (sold %s ago, bought %s ago) - "
			+ "%d sold and %d bought in 2h, %d in the last 5 min; its buy waits up to %d min for a seller", ago(q[2]),
			ago(q[3]), sold, bought, traded5, DEAR_BUY_WAIT_MS / 60_000));
		if (again)
		{
			p.warnings.add(String.format("flipped at a profit %s ago in %d min - offered again",
				ago(Math.max(0, nowS - win[0] / 1000)), win[1]));
		}
		return p;
	}

	/**
	 * An expensive item's quantity ({@link #DEAR_OFFER_GP}): half of {@code traded5} rounded up, at least 1; at most
	 * DEAR_OFFER_GP of gp (at least one unit), {@code limit} (0 = none known) and {@code budget}. 0 = the cash buys none.
	 */
	static long dearQty(long bid, long budget, long limit, long traded5)
	{
		if (bid <= 0 || bid > (long) (budget * MAX_POSITION_FRAC))
		{
			return 0;
		}
		long q = Math.max(1, (traded5 + 1) / 2);
		q = Math.min(q, Math.max(1, DEAR_OFFER_GP / bid));
		q = Math.min(q, (long) (budget * MAX_POSITION_FRAC) / bid);
		return limit > 0 ? Math.min(q, limit) : q;
	}

	/** True when a /5m block ({@code five}; null = not known, false) looks like a dump ({@link #DUMP_RATIO}). */
	static boolean dumping(JsonObject five)
	{
		return five != null && Research.num(five, "lowPriceVolume") + 1
			>= DUMP_RATIO * (Research.num(five, "highPriceVolume") + 1);
	}

	/**
	 * ⭐ LIVE RULES (1.60.41): the tunable numbers above, by name, in plain units — read from plugin-data/flip-sidebar/
	 * rules.json every minute (FlipSidebarPlugin.rulesTick), so they change while the user plays, without a restart.
	 * User, 2026-11-07: "You could be updating the brain live while I just keep going. On the fly adjustments until its
	 * perfect. Limit restarting runelite". A missing or unreadable key keeps its value.
	 */
	static JsonObject rules()
	{
		JsonObject o = new JsonObject();
		o.addProperty("sellPatienceMin", SELL_PATIENCE_MS / 60_000);
		o.addProperty("dumpRatio", DUMP_RATIO);
		o.addProperty("dearMinMarginPct", DEAR_MIN_MARGIN_PCT);
		o.addProperty("dearBidOverPct", DEAR_BID_OVER_PCT);
		o.addProperty("dearFreshMin", DEAR_FRESH_S / 60);
		o.addProperty("dearNowMin", DEAR_NOW_S / 60);
		o.addProperty("dearMinTrades2h", DEAR_MIN_TRADES);
		o.addProperty("dearMin5mTrades", DEAR_MIN_5M_TRADES);
		o.addProperty("dearBuyWaitMin", DEAR_BUY_WAIT_MS / 60_000);
		o.addProperty("dearSellStepMin", DEAR_SELL_STEP_MS / 60_000);
		o.addProperty("dearAgainMin", DEAR_AGAIN_MS / 60_000);
		o.addProperty("dearOfferGp", DEAR_OFFER_GP);
		o.addProperty("dayPremiumMaxPct", DAY_PREMIUM_MAX_PCT);
		o.addProperty("perBuyShare", FlipBrain.AUTO_PER_BUY_SHARE);
		o.addProperty("roiGoalPctHour", ROI_GOAL_PCT_HOUR);
		o.addProperty("dipMinPct", DIP_MIN_PCT);
		o.addProperty("dipBoostPerPct", DIP_BOOST_PER_PCT);
		o.addProperty("dipBoostMax", DIP_BOOST_MAX);
		o.addProperty("cycleBoost", CYCLE_BOOST);
		o.addProperty("cyclePenalty", CYCLE_PENALTY);
		o.addProperty("deepDipPct", DEEP_DIP_PCT);
		o.addProperty("deepHoldDays", DEEP_HOLD_MS / 86_400_000L);
		o.addProperty("gearFloorDays", GEAR_FLOOR_DAYS);
		o.addProperty("gearNearLowPct", GEAR_NEAR_LOW_PCT);
		// 1.60.45: every other decision number (user: "Why not all")
		o.addProperty("learnedUnderGp", LEARNED_UNDER);
		o.addProperty("dearTradesMinGp", DEAR_TRADES_MIN);
		o.addProperty("gapMinPct", GAP_MIN_PCT);
		o.addProperty("gapWarnPct", GAP_WARN_PCT);
		o.addProperty("gapMaxPct", GAP_MAX_PCT);
		o.addProperty("minEachGp", MIN_EACH);
		o.addProperty("minHourlyGp", MIN_HOURLY_GP);
		o.addProperty("busyGpHr", BUSY_GP_HR);
		o.addProperty("busyMinUnits", BUSY_MIN_UNITS);
		o.addProperty("smallMarginMinUnitsHr", SMALL_MARGIN_MIN_UNITS_HR);
		o.addProperty("hourAskMax", HOUR_ASK_MAX);
		o.addProperty("provenShare", PROVEN_SHARE);
		o.addProperty("replayMinEachGp", REPLAY_MIN_EACH);
		o.addProperty("parkMinReach", PARK_MIN_REACH);
		o.addProperty("latestFreshSec", LATEST_FRESH_S);
		o.addProperty("latestFreshDearSec", LATEST_FRESH_DEAR_S);
		o.addProperty("latestStaleAskSec", LATEST_STALE_ASK_S);
		o.addProperty("latestMaxMove", LATEST_MAX_MOVE);
		o.addProperty("spikeMax30d", SPIKE_MAX);
		o.addProperty("dearMaxResearch", DEAR_MAX_RESEARCH);
		o.addProperty("memoryMinBlocks", MEMORY_MIN_BLOCKS);
		o.addProperty("memoryMaxAgeSec", MEMORY_MAX_AGE_S);
		o.addProperty("raisePct", OfferAdvice.RAISE_PCT * 100);
		o.addProperty("bailPct", OfferAdvice.BAIL_PCT * 100);
		o.addProperty("chasePct", OfferAdvice.CHASE_PCT * 100);
		o.addProperty("emptyBuyMin", OfferAdvice.EMPTY_BUY_MS / 60_000);
		o.addProperty("emptyWindowMin", OfferAdvice.EMPTY_WINDOW_MS / 60_000);
		o.addProperty("swingLowQ", Swing.LOW_Q);
		o.addProperty("swingHighQ", Swing.HIGH_Q);
		o.addProperty("swingExitPct", Swing.EXIT_PCT);
		o.addProperty("swingMinPct", Swing.MIN_PCT);
		o.addProperty("swingMaxSpreadPct", Swing.MAX_SPREAD_PCT);
		o.addProperty("swingMinActive", Swing.MIN_ACTIVE);
		o.addProperty("swingHoldHours", Swing.HOLD_MS / 3_600_000L);
		o.addProperty("swingFlowShare", Swing.FLOW_SHARE);
		o.addProperty("deepFlowShare", Swing.DEEP_FLOW_SHARE);
		o.addProperty("holdMaxHours", FlipBrain.HOLD_MAX_MS / 3_600_000L);
		o.addProperty("scanEverySec", FlipBrain.SCAN_EVERY_MS / 1000);
		o.addProperty("maxResearch", FlipBrain.MAX_RESEARCH);
		o.addProperty("southMin", FlipBrain.SOUTH_MS / 60_000);
		o.addProperty("soldHoldSec", FlipBrain.SOLD_HOLD_MS / 1000);
		o.addProperty("weakPairPct", FlipLearner.WEAK_PCT);
		o.addProperty("weakMinEachGp", FlipLearner.WEAK_MIN_EACH_GP);
		o.addProperty("leftBuyMin", OfferAdvice.LEFT_BUY_MS / 60_000);
		o.addProperty("holdMinWeekPct", HoldOrSell.MIN_WEEK_SHARE * 100);
		return o;
	}

	/** Sets the {@link #rules} found in {@code o} (each kept within sane bounds); returns {@link #rules} after. */
	static JsonObject applyRules(JsonObject o)
	{
		SELL_PATIENCE_MS = ruleLong(o, "sellPatienceMin", SELL_PATIENCE_MS / 60_000, 0, 24 * 60) * 60_000;
		DUMP_RATIO = ruleLong(o, "dumpRatio", DUMP_RATIO, 2, 1_000);
		DEAR_MIN_MARGIN_PCT = ruleDouble(o, "dearMinMarginPct", DEAR_MIN_MARGIN_PCT, 0, 20);
		DEAR_BID_OVER_PCT = ruleDouble(o, "dearBidOverPct", DEAR_BID_OVER_PCT, 0, 5);
		DEAR_FRESH_S = ruleLong(o, "dearFreshMin", DEAR_FRESH_S / 60, 1, 24 * 60) * 60;
		DEAR_NOW_S = ruleLong(o, "dearNowMin", DEAR_NOW_S / 60, 1, 24 * 60) * 60;
		DEAR_MIN_TRADES = ruleLong(o, "dearMinTrades2h", DEAR_MIN_TRADES, 0, 1_000);
		DEAR_MIN_5M_TRADES = ruleLong(o, "dearMin5mTrades", DEAR_MIN_5M_TRADES, 0, 1_000);
		DEAR_BUY_WAIT_MS = ruleLong(o, "dearBuyWaitMin", DEAR_BUY_WAIT_MS / 60_000, 1, 24 * 60) * 60_000;
		DEAR_SELL_STEP_MS = ruleLong(o, "dearSellStepMin", DEAR_SELL_STEP_MS / 60_000, 1, 24 * 60) * 60_000;
		DEAR_AGAIN_MS = ruleLong(o, "dearAgainMin", DEAR_AGAIN_MS / 60_000, 0, 24 * 60) * 60_000;
		DEAR_OFFER_GP = ruleLong(o, "dearOfferGp", DEAR_OFFER_GP, 1_000_000, 2_000_000_000);
		DAY_PREMIUM_MAX_PCT = ruleDouble(o, "dayPremiumMaxPct", DAY_PREMIUM_MAX_PCT, 0, 100);
		FlipBrain.AUTO_PER_BUY_SHARE = ruleDouble(o, "perBuyShare", FlipBrain.AUTO_PER_BUY_SHARE, 0.05, 1.0);
		ROI_GOAL_PCT_HOUR = ruleDouble(o, "roiGoalPctHour", ROI_GOAL_PCT_HOUR, 0.01, 10);
		DIP_MIN_PCT = ruleDouble(o, "dipMinPct", DIP_MIN_PCT, 0, 50);
		DIP_BOOST_PER_PCT = ruleDouble(o, "dipBoostPerPct", DIP_BOOST_PER_PCT, 0, 1);
		DIP_BOOST_MAX = ruleDouble(o, "dipBoostMax", DIP_BOOST_MAX, 1, 5);
		CYCLE_BOOST = ruleDouble(o, "cycleBoost", CYCLE_BOOST, 1, 5);
		CYCLE_PENALTY = ruleDouble(o, "cyclePenalty", CYCLE_PENALTY, 0, 1);
		DEEP_DIP_PCT = ruleDouble(o, "deepDipPct", DEEP_DIP_PCT, 3, 80);
		DEEP_HOLD_MS = ruleLong(o, "deepHoldDays", DEEP_HOLD_MS / 86_400_000L, 1, 30) * 86_400_000L;
		GEAR_FLOOR_DAYS = ruleLong(o, "gearFloorDays", GEAR_FLOOR_DAYS, 1, 60);
		GEAR_NEAR_LOW_PCT = ruleDouble(o, "gearNearLowPct", GEAR_NEAR_LOW_PCT, 0, 100);
		LEARNED_UNDER = ruleLong(o, "learnedUnderGp", LEARNED_UNDER, 1_000, 100_000_000);
		DEAR_TRADES_MIN = ruleLong(o, "dearTradesMinGp", DEAR_TRADES_MIN, 100_000, 1_000_000_000);
		GAP_MIN_PCT = ruleDouble(o, "gapMinPct", GAP_MIN_PCT, 0, 50);
		GAP_WARN_PCT = ruleDouble(o, "gapWarnPct", GAP_WARN_PCT, 0, 100);
		GAP_MAX_PCT = ruleDouble(o, "gapMaxPct", GAP_MAX_PCT, 1, 200);
		MIN_EACH = ruleLong(o, "minEachGp", MIN_EACH, 1, 1_000_000);
		MIN_HOURLY_GP = ruleLong(o, "minHourlyGp", MIN_HOURLY_GP, 0, 10_000_000_000L);
		BUSY_GP_HR = ruleDouble(o, "busyGpHr", BUSY_GP_HR, 0, 1e11);
		BUSY_MIN_UNITS = ruleLong(o, "busyMinUnits", BUSY_MIN_UNITS, 0, 1_000_000);
		SMALL_MARGIN_MIN_UNITS_HR = ruleLong(o, "smallMarginMinUnitsHr", SMALL_MARGIN_MIN_UNITS_HR, 0, 100_000_000);
		HOUR_ASK_MAX = ruleDouble(o, "hourAskMax", HOUR_ASK_MAX, 0.5, 2);
		PROVEN_SHARE = ruleDouble(o, "provenShare", PROVEN_SHARE, 0, 1);
		REPLAY_MIN_EACH = ruleLong(o, "replayMinEachGp", REPLAY_MIN_EACH, 0, 100_000_000);
		PARK_MIN_REACH = ruleDouble(o, "parkMinReach", PARK_MIN_REACH, 0, 1);
		LATEST_FRESH_S = ruleLong(o, "latestFreshSec", LATEST_FRESH_S, 10, 3_600);
		LATEST_FRESH_DEAR_S = ruleLong(o, "latestFreshDearSec", LATEST_FRESH_DEAR_S, 10, 7_200);
		LATEST_STALE_ASK_S = ruleLong(o, "latestStaleAskSec", LATEST_STALE_ASK_S, 60, 86_400);
		LATEST_MAX_MOVE = ruleDouble(o, "latestMaxMove", LATEST_MAX_MOVE, 0, 1);
		SPIKE_MAX = ruleDouble(o, "spikeMax30d", SPIKE_MAX, 1, 10);
		DEAR_MAX_RESEARCH = (int) ruleLong(o, "dearMaxResearch", DEAR_MAX_RESEARCH, 0, 300);
		MEMORY_MIN_BLOCKS = (int) ruleLong(o, "memoryMinBlocks", MEMORY_MIN_BLOCKS, 0, 288);
		MEMORY_MAX_AGE_S = ruleLong(o, "memoryMaxAgeSec", MEMORY_MAX_AGE_S, 60, 7_200);
		OfferAdvice.RAISE_PCT = ruleDouble(o, "raisePct", OfferAdvice.RAISE_PCT * 100, 0, 20) / 100;
		OfferAdvice.BAIL_PCT = ruleDouble(o, "bailPct", OfferAdvice.BAIL_PCT * 100, 0, 50) / 100;
		OfferAdvice.CHASE_PCT = ruleDouble(o, "chasePct", OfferAdvice.CHASE_PCT * 100, 0, 50) / 100;
		OfferAdvice.EMPTY_BUY_MS = ruleLong(o, "emptyBuyMin", OfferAdvice.EMPTY_BUY_MS / 60_000, 1, 240) * 60_000;
		OfferAdvice.EMPTY_WINDOW_MS = ruleLong(o, "emptyWindowMin", OfferAdvice.EMPTY_WINDOW_MS / 60_000, 1, 240) * 60_000;
		Swing.LOW_Q = ruleDouble(o, "swingLowQ", Swing.LOW_Q, 0, 1);
		Swing.HIGH_Q = ruleDouble(o, "swingHighQ", Swing.HIGH_Q, 0, 1);
		Swing.EXIT_PCT = ruleDouble(o, "swingExitPct", Swing.EXIT_PCT, 0, 100);
		Swing.MIN_PCT = ruleDouble(o, "swingMinPct", Swing.MIN_PCT, 0, 100);
		Swing.MAX_SPREAD_PCT = ruleDouble(o, "swingMaxSpreadPct", Swing.MAX_SPREAD_PCT, 0, 100);
		Swing.MIN_ACTIVE = ruleDouble(o, "swingMinActive", Swing.MIN_ACTIVE, 0, 1);
		Swing.HOLD_MS = ruleLong(o, "swingHoldHours", Swing.HOLD_MS / 3_600_000L, 1, 24 * 30) * 3_600_000L;
		Swing.FLOW_SHARE = ruleDouble(o, "swingFlowShare", Swing.FLOW_SHARE, 0, 1);
		Swing.DEEP_FLOW_SHARE = ruleDouble(o, "deepFlowShare", Swing.DEEP_FLOW_SHARE, 0, 1);
		FlipBrain.HOLD_MAX_MS = ruleLong(o, "holdMaxHours", FlipBrain.HOLD_MAX_MS / 3_600_000L, 1, 24 * 30) * 3_600_000L;
		// never faster than a minute, never more research than 300 a scan: the wiki's load (1.60.43)
		FlipBrain.SCAN_EVERY_MS = ruleLong(o, "scanEverySec", FlipBrain.SCAN_EVERY_MS / 1000, 60, 3_600) * 1000;
		FlipBrain.MAX_RESEARCH = (int) ruleLong(o, "maxResearch", FlipBrain.MAX_RESEARCH, 0, 300);
		FlipBrain.SOUTH_MS = ruleLong(o, "southMin", FlipBrain.SOUTH_MS / 60_000, 0, 24 * 60) * 60_000;
		FlipBrain.SOLD_HOLD_MS = ruleLong(o, "soldHoldSec", FlipBrain.SOLD_HOLD_MS / 1000, 0, 86_400) * 1000;
		FlipLearner.WEAK_PCT = ruleDouble(o, "weakPairPct", FlipLearner.WEAK_PCT, 0, 5);
		FlipLearner.WEAK_MIN_EACH_GP = ruleLong(o, "weakMinEachGp", FlipLearner.WEAK_MIN_EACH_GP, 0, 2_000_000_000L);
		OfferAdvice.LEFT_BUY_MS = ruleLong(o, "leftBuyMin", OfferAdvice.LEFT_BUY_MS / 60_000, 0, 24 * 60) * 60_000;
		HoldOrSell.MIN_WEEK_SHARE = ruleDouble(o, "holdMinWeekPct", HoldOrSell.MIN_WEEK_SHARE * 100, 0, 100) / 100;
		return rules();
	}

	private static long ruleLong(JsonObject o, String key, long was, long min, long max)
	{
		try
		{
			return o != null && o.has(key) ? Math.max(min, Math.min(max, o.get(key).getAsLong())) : was;
		}
		catch (RuntimeException e)
		{
			return was;
		}
	}

	private static double ruleDouble(JsonObject o, String key, double was, double min, double max)
	{
		try
		{
			double v = o != null && o.has(key) ? o.get(key).getAsDouble() : was;
			return Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : was;
		}
		catch (RuntimeException e)
		{
			return was;
		}
	}

	/**
	 * Item {@code id}'s 5-minute history for Research: from {@link #memory} when it holds the item, 12+ hours of the day
	 * and a fresh newest block — no request; else the wiki's /timeseries (cached in WikiPrices). Engine thread only.
	 */
	JsonArray series5m(int id, long nowS) throws IOException
	{
		MarketMemory m = memory;
		if (m != null && m.holds(id) && m.blocks() >= MEMORY_MIN_BLOCKS && nowS - m.newest() <= MEMORY_MAX_AGE_S)
		{
			return m.asSeries(id);
		}
		return prices.timeseries(id, "5m");
	}

	/** Units traded in a /5m block (high + low volume); 0 for none. */
	static long traded5m(JsonObject five)
	{
		return five == null ? 0 : Research.num(five, "highPriceVolume") + Research.num(five, "lowPriceVolume");
	}

	/**
	 * The expensive items sold at a profit within DEAR_AGAIN_MS of {@code nowMs} ({@link #dearWins}): item → {sold at,
	 * minutes from its buy to the sale, at least 1}. A flip whose buy time is not known is left out.
	 */
	static Map<Integer, long[]> dearWins(List<GameSnapshot.Done> history, long nowMs)
	{
		Map<Integer, long[]> out = new HashMap<>();
		for (GameSnapshot.Done d : history)
		{
			if (d.sellEach < DEAR_TRADES_MIN || d.profit <= 0 || d.boughtAt <= 0 || d.at < d.boughtAt
				|| nowMs - d.at > DEAR_AGAIN_MS)
			{
				continue;
			}
			long[] was = out.get(d.itemId);
			if (was == null || d.at > was[0])
			{
				out.put(d.itemId, new long[]{d.at, Math.max(1, (d.at - d.boughtAt) / 60_000)});
			}
		}
		return Map.copyOf(out);
	}

	private static String ago(long s)
	{
		return s < 60 ? s + "s" : (s / 60) + " min";
	}

	/**
	 * Scan the GE: cheap filters over every item, then research every candidate that could make
	 * {@code minProfit} (the most promising first, at most {@code maxResearch}) and judge each.
	 * Returns only the picks at or above {@code minProfit}, best gp an hour first. {@code progress} hears each item checked and each pick found.
	 */
	List<Pick> scan(long budget, RiskLevel risk, AdjustWindow window, long minProfit, Set<Integer> busy,
		Map<Integer, Long> limitUsed, int maxResearch, Progress progress) throws IOException
	{
		long nowMs = System.currentTimeMillis();
		if (mapping == null || nowMs - mappingAt > MAPPING_MAX_AGE_MS)
		{
			mapping = prices.mapping();
			mappingAt = nowMs;
			Map<Integer, String> n = new HashMap<>();
			Map<Integer, Long> l = new HashMap<>();
			Map<Integer, Long> ha = new HashMap<>();
			List<AlchPlan.Item> hai = new ArrayList<>();
			Set<Integer> mem = new HashSet<>();
			for (JsonElement e : mapping)
			{
				JsonObject m = e.getAsJsonObject();
				int id = (int) Research.num(m, "id");
				n.put(id, m.has("name") ? m.get("name").getAsString() : String.valueOf(id));
				l.put(id, Research.num(m, "limit"));
				if (Research.num(m, "highalch") > 0)
				{
					ha.put(id, Research.num(m, "highalch"));
					hai.add(new AlchPlan.Item(id, n.get(id), Research.num(m, "highalch"), Research.num(m, "limit"),
						m.has("members") && m.get("members").getAsBoolean()));
				}
				if (m.has("members") && m.get("members").getAsBoolean())
				{
					mem.add(id);
				}
			}
			Map<String, Integer> b = new HashMap<>();
			for (Map.Entry<Integer, String> e : n.entrySet())
			{
				b.putIfAbsent(e.getValue().toLowerCase(Locale.ROOT), e.getKey());
			}
			names = n;
			byName = b;
			limits = l;
			alchs = ha;
			alchItems = List.copyOf(hai);
			members = mem;
		}
		JsonObject h1 = prices.data("/1h");
		JsonObject h5 = prices.data("/5m");
		long nowS = nowMs / 1000;
		long cap = (long) (budget * MAX_POSITION_FRAC);
		// "Expensive items (1m+)": judged on the latest trades (judgeDear), not in the loop below; no /latest = none
		// this scan (fails closed)
		JsonObject latest = null;
		if (dearFromTrades)
		{
			try
			{
				latest = prices.data("/latest");
			}
			catch (IOException ex)
			{
				log.debug("Flip Sidebar: /latest failed - no expensive items this scan", ex);
			}
		}

		Set<Integer> mine = proven;
		List<Object[]> cand = new ArrayList<>();       // {score, id, quote, limit left, proven}
		for (Map.Entry<Integer, String> e : names.entrySet())
		{
			int id = e.getKey();
			String key = String.valueOf(id);
			if (busy.contains(id) || NEVER_FLIP.contains(id) || !h1.has(key) || (freeToPlay && members.contains(id)))
			{
				continue;
			}
			JsonObject v = h1.getAsJsonObject(key);
			if (dumping(h5.has(key) ? h5.getAsJsonObject(key) : null))
			{
				continue;                 // someone is dumping it (DUMP_RATIO): never a buy
			}
			long[] q = quote(v, h5.has(key) ? h5.getAsJsonObject(key) : null);
			long b = q[0];
			long s = q[1];
			long bv = Research.num(v, "lowPriceVolume");
			long sv = Research.num(v, "highPriceVolume");
			if (latest != null && dearItem(b, latest.has(key) ? latest.getAsJsonObject(key) : null))
			{
				continue;             // an expensive item: judged on its latest trades (below)
			}
			// an item that trades few UNITS but much GP — at any price — is not dropped for under 120 units an hour;
			// the learned volume rule (units OR gp) and the research judge it like any other (user, 2026-10-31, 133m in
			// the inventory and 15.8m out with 5-minute flips: "i want to use the 5m settings and still use my money" —
			// "this game has a lot of items... across all the price ranges")
			// at LEARNED_UNDER+ each, one every 5 minutes: MIN_HOURLY_GP and the judge (activity, fill time) do the rest
			long minUnits = b >= LEARNED_UNDER || (double) Math.min(bv, sv) * b >= Math.max(gpFloor, BUSY_GP_HR)
				? BUSY_MIN_UNITS : 120;
			if (b <= 0 || s <= 0 || Math.min(bv, sv) < minUnits || b > cap || (bv + sv) * b < MIN_HOURLY_GP)
			{
				continue;
			}
			long pe = (s - b) - tax(s, id);
			if (pe <= 0 || (b >= LEARNED_UNDER && pe * 100.0 / b < DEAR_MIN_MARGIN_PCT))
			{
				continue;             // the judge's margin rule for an item over 100k, before spending research on it
			}
			// ⚠ the LEARNED rules first, cheaply, on the hour's volumes — research only items that can pass. In-game
			// 2026-10-31 "nothing worth doing right now" with a GE slot open: the research budget went, by margin %,
			// to wide-margin thin items, every one rejected by the activity / volume / whole-market rules after it.
			boolean own = mine.contains(id);
			if (!own && b < LEARNED_UNDER && !FlipLearner.passes(Math.min(bv, sv), (double) Math.min(bv, sv) * b, flowFloor, gpFloor))
			{
				continue;
			}
			long[] rv = replayed.get(id);
			if (!own && rv != null && rv[0] >= 3 && rv[2] < REPLAY_MIN_EACH)
			{
				continue;
			}
			// sized to what is LEFT of the 4-hour buy limit; a used-up limit waits for its reset
			long left = remaining(limits.getOrDefault(id, 0L), limitUsed.getOrDefault(id, 0L));
			if (left == 0)
			{
				continue;
			}
			long lim = Math.max(0, left);
			long qq = Math.min(cap / b, Math.min(lim > 0 ? lim : Long.MAX_VALUE, Math.min(bv, sv)));
			if (qq <= 0 || pe * qq < minProfit)
			{
				continue;
			}
			cand.add(new Object[]{(double) (pe * qq) / Math.max(qq * b, 1), id, q, lim, own});
		}
		// proven items first (FlipLearner.proven), then the widest margin
		cand.sort((x, y) -> (boolean) x[4] != (boolean) y[4] ? Boolean.compare((boolean) y[4], (boolean) x[4])
			: Double.compare((double) y[0], (double) x[0]));

		List<Pick> picks = new ArrayList<>();
		Map<Integer, long[]> spikes = new HashMap<>();
		int n = Math.min(maxResearch, cand.size());
		for (int i = 0; i < n; i++)
		{
			if (Thread.currentThread().isInterrupted())
			{
				break;                // the plugin was turned off mid-scan
			}
			progress.checked(i, n);
			int id = (int) cand.get(i)[1];
			// the spike guard first (it also spares the 5-minute research): no 30-day check = no card (fails closed)
			long bid = ((long[]) cand.get(i)[2])[0];
			long level = monthLevel(id);
			if (level == LEVEL_UNKNOWN)
			{
				continue;
			}
			if (spiked(bid, level))
			{
				spikes.put(id, new long[]{bid, level});
				continue;
			}
			try
			{
				Research r = Research.of(id, series5m(id, nowS));
				r.monthLevel = level;
				Pick p = evaluate(id, names.get(id), (long) cand.get(i)[3], (long[]) cand.get(i)[2],
					budget, risk, window, r, nowS);
				if (p != null && p.total >= minProfit)
				{
					picks.add(p);
					progress.found(p);
				}
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("Flip Sidebar: research failed for {}", id, ex);
			}
		}
		// ⭐ EXPENSIVE ITEMS on their latest trades (dearFromTrades): a fresh quote under the cash for one buy and 0.5%
		// after tax now, the most profit each first; the spike guard as for the rest; then judgeDear on its 5-minute
		// series' last 2 hours of trades
		List<Object[]> dear = new ArrayList<>();       // {profit each, id, quote, limit left}
		for (Map.Entry<Integer, String> e : latest == null ? Map.<Integer, String>of().entrySet() : names.entrySet())
		{
			int id = e.getKey();
			String key = String.valueOf(id);
			if (busy.contains(id) || NEVER_FLIP.contains(id) || !latest.has(key) || (freeToPlay && members.contains(id)))
			{
				continue;
			}
			long[] q = dearQuote(latest.getAsJsonObject(key), nowS);
			if (q == null || q[0] < DEAR_TRADES_MIN || q[0] > cap)
			{
				continue;
			}
			// not trading now (judgeDear's rule, before spending research on it), or being dumped (DUMP_RATIO)
			JsonObject block = h5.has(key) ? h5.getAsJsonObject(key) : null;
			if (q[2] > DEAR_NOW_S || q[3] > DEAR_NOW_S || traded5m(block) < DEAR_MIN_5M_TRADES || dumping(block))
			{
				continue;
			}
			long pe = q[1] - tax(q[1], id) - q[0];
			if (pe * 100.0 / q[0] < DEAR_MIN_MARGIN_PCT || pe < minProfit)
			{
				continue;
			}
			long left = remaining(limits.getOrDefault(id, 0L), limitUsed.getOrDefault(id, 0L));
			if (left == 0)
			{
				continue;
			}
			dear.add(new Object[]{pe, id, q, Math.max(0, left)});
		}
		// one that just flipped at a profit (dearWins) first, then the most profit each
		Map<Integer, long[]> wins = dearWins;
		dear.sort((x, y) -> wins.containsKey((int) x[1]) != wins.containsKey((int) y[1])
			? Boolean.compare(wins.containsKey((int) y[1]), wins.containsKey((int) x[1]))
			: Long.compare((long) y[0], (long) x[0]));
		for (int i = 0; i < Math.min(DEAR_MAX_RESEARCH, dear.size()); i++)
		{
			if (Thread.currentThread().isInterrupted())
			{
				break;
			}
			int id = (int) dear.get(i)[1];
			long[] q = (long[]) dear.get(i)[2];
			long level = monthLevel(id);
			if (level == LEVEL_UNKNOWN)
			{
				continue;
			}
			if (spiked(q[0], level))
			{
				spikes.put(id, new long[]{q[0], level});
				continue;
			}
			try
			{
				Research r = Research.of(id, series5m(id, nowS));
				r.monthLevel = level;
				Pick p = judgeDear(id, names.get(id), (long) dear.get(i)[3], q,
					h5.has(String.valueOf(id)) ? h5.getAsJsonObject(String.valueOf(id)) : null, budget, r, nowS);
				if (p.rejected == null && p.total >= minProfit)
				{
					picks.add(p);
					progress.found(p);
				}
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("Flip Sidebar: research failed for {}", id, ex);
			}
		}
		progress.checked(n, n);
		spikedNow = Map.copyOf(spikes);
		picks.sort(Comparator.comparingDouble(Pick::rank).reversed());
		return Collections.unmodifiableList(picks);
	}
}
