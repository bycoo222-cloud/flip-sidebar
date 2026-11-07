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

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(FlipSidebarConfig.GROUP)
public interface FlipSidebarConfig extends Config
{
	String GROUP = "flipsidebar";

	@ConfigItem(
		position = -1,
		keyName = "livePrices",
		name = "Live prices (find flips)",
		description = "Fetch Grand Exchange prices from the OSRS Wiki (prices.runescape.wiki) so the plugin "
			+ "can find flips, price your offers and draw price graphs. Off: it finds nothing",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default boolean livePrices()
	{
		return false;
	}

	/**
	 * ⭐ DATA SYNC (TeamSync; user, 2026-11-04: "cloudflare for sure want it automatic", "Call it data sync or something
	 * like that"). Off by default, with RuneLite's third-party warning.
	 */
	@ConfigItem(
		position = 0,
		keyName = "dataSync",
		name = "Data sync",
		// shown since the Worker went live (2026-11-04, user: Data sync before the release)
		description = "Share what your finished flips taught the plugin with the other players who turn this on, and learn "
			+ "from theirs - what each item looked like before the buy and what the flip made - and your Grand Exchange "
			+ "offers (item, price, quantity, when they filled or were cancelled) for the server's analysis. No names, no "
			+ "coins: a random id. Every 10-30 minutes, with Live prices on. Logged in with the panel's Log in button: "
			+ "also every finished flip and your character's name, for your private page on the website. Off: nothing is sent",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default boolean dataSync()
	{
		return false;
	}

	@ConfigItem(
		position = 0,
		keyName = "bigBrain",
		name = "Price patterns (server)",
		description = "Get the plugin server's analysis of every item's price history (rebuilt every 6 hours): when each item is usually "
			+ "cheapest and dearest in the day (only where that held over the last month) and its 30-day level - used for "
			+ "daily cycles and multi-day dips. Needs Live prices. Off: none (or only what this PC built itself)",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default boolean bigBrain()
	{
		return false;
	}

	@ConfigItem(
		position = 0,
		keyName = "serverPrices",
		name = "Prices through the plugin's server",
		description = "Get the OSRS Wiki's prices through the plugin's server (a cached copy the wiki agreed to) instead of "
			+ "from the wiki directly - the wiki then sees one client instead of every player. If the server can't be "
			+ "reached, the wiki directly. Needs Live prices",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default boolean serverPrices()
	{
		return false;
	}

	@ConfigItem(
		position = 1,
		keyName = "riskLevel",
		name = "Risk",
		description = "Low: only items that trade steadily with a margin that holds. High: allows thinner, "
			+ "jumpier items for bigger margins"
	)
	default RiskLevel riskLevel()
	{
		// careful default for a new install (user, 2026-11-04: "careful defaults yes ... just leave my settings where
		// they are" — RuneLite keeps every value already saved in a profile, so only new installs start on Low)
		return RiskLevel.LOW;
	}

	@ConfigItem(
		position = 2,
		keyName = "adjustWindow",
		name = "Re-price every",
		description = "How often you come back to adjust your offers. Sets how big a slice of an hour's "
			+ "trading one offer may take, so it fills before you are back"
	)
	default AdjustWindow adjustWindow()
	{
		return AdjustWindow.THIRTY_MINUTES;
	}

	@Range(min = 0)
	@ConfigItem(
		position = 4,
		keyName = "minProfit",
		name = "Minimum profit per flip",
		description = "Only suggest flips that make at least this many coins after the GE tax"
	)
	default int minProfit()
	{
		return 10_000;
	}

	@Range(min = 0, max = 2000)
	@Units("m")
	@ConfigItem(
		position = 5,
		keyName = "maxCashMillions",
		name = "Use at most",
		description = "In millions: 10 = at most 10m in flips at once - buy offers plus what you have bought and not "
			+ "sold yet. The plugin only suggests buys that fit in what is left, and leaves the rest of your coins "
			+ "alone. 0 = all the coins in your inventory"
	)
	default int maxCashMillions()
	{
		// millions, not coins (user, 2026-10-31: "i cant say 10m ... im not typing a million zeros");
		// a new key, so a value saved as coins under the old "maxCash" is never read as millions
		return 0;
	}

	@Range(min = 0, max = 100_000)
	@Units("m")
	@ConfigItem(
		position = 5,
		keyName = "mostPerBuyMillions",
		name = "Most per buy",
		description = "In millions: the most one buy may cost. 0 = automatic (the default): about 30% of the coins you flip "
			+ "with (146m: ~44m a buy, 1b: 300m), never so little that your GE slots can't hold all your coins. A bigger "
			+ "buy gets fewer items; an item that costs more than this is not suggested (you can still pick it yourself). "
			+ "The status line shows the amount in use"
	)
	default int mostPerBuyMillions()
	{
		return 0;
	}

	/** How the panel and the Statistics window look (FlipTheme). */
	enum Look
	{
		CLASSIC("Classic"),
		MODERN("Modern"),
		OLD_SCHOOL("Old School"),
		ECTOPLASM("Ectoplasm"),
		INFERNAL("Infernal");

		private final String label;

		Look(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		position = -2,                 // the first setting (user, 2026-11-04: "i cant find the setting for the themes")
		keyName = "look",
		name = "Theme",
		description = "How the side panel and the Statistics window look. Classic (the default); Modern (a smooth font on "
			+ "one calm dark surface); Old School; Ectoplasm; Infernal. Profit is green and a loss red in every one"
	)
	default Look look()
	{
		return Look.CLASSIC;
	}

	@ConfigItem(
		position = -2,                 // under Theme (user, 2026-11-05: "Can I have an option for the graph on the top")
		keyName = "topGraph",
		name = "Top graph",
		description = "What the line on the panel's top card shows. Profit (the default): the session's profit after each "
			+ "flip. gp per hour: the session's gp per hour minute by minute, with a thin line for the last 15 minutes' pace "
			+ "- when the pace falls and the hour line goes flat, the session has levelled out"
	)
	default TopGraph topGraph()
	{
		return TopGraph.PROFIT;
	}

	/** What the top card's line shows (FlipUi.SessionCard). */
	enum TopGraph
	{
		PROFIT("Profit"),
		GP_PER_HOUR("gp per hour");

		private final String label;

		TopGraph(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		position = 6,
		keyName = "freeToPlay",
		name = "Free-to-play",
		description = "For a free-to-play account: plans for its 3 GE slots (members have 8) and only suggests buying "
			+ "free-to-play items - free players can't buy members' items on the GE"
	)
	default boolean freeToPlay()
	{
		return false;
	}

	@ConfigItem(
		position = 3,
		keyName = "swingTrades",
		name = "Swing trades",
		description = "Also use idle coins on swing trades: an item low in its day is bought, kept in your inventory "
			+ "(Holding - no GE slot) and sold when it is back at its usual price, up to 8 hours. On 4 days of the whole "
			+ "market: about +45k an hour more for 20-40m more coins at work; 10% of them lost (normal flips 7%)"
	)
	default boolean swingTrades()
	{
		return false;
	}

	@ConfigItem(
		position = 3,
		keyName = "dearFromTrades",
		name = "Expensive items (1m+)",
		description = "Also flip items of 1m or more, priced on their LATEST trades instead of 5-minute averages: buy "
			+ "a little over the last insta-sell (+0.1%) - half as many as traded in the last 5 minutes, up to 45m an offer - "
			+ "and sell at the last insta-buy. Needs 0.5% profit after "
			+ "tax, 4+ trades each way in the last 2 hours, and trading now: 3+ in the last 5 minutes and an insta-sell "
			+ "and an insta-buy within 10 min. One that just flipped at a profit is offered again first. Its buy waits up to 10 min for a "
			+ "seller; its sell follows the last insta-buy every 5 min, never under what it cost, for 2 hours after the "
			+ "buy or your login, then sells at the market. Off: these items follow the normal rules. New - not yet tested in game"
	)
	default boolean dearFromTrades()
	{
		return false;
	}

	@Range(min = 0, max = 28)
	@ConfigItem(
		position = 3,
		keyName = "holdLimit",
		name = "Most items held",
		description = "Holding: once this many items wait in your inventory (swing buys waiting for their sell price, "
			+ "and items you Hold), no new swing is bought. A losing buy is never kept: it is sold. Your own Hold always "
			+ "works. 0 = no swings"
	)
	default int holdLimit()
	{
		return 5;
	}

	@ConfigItem(
		position = 3,
		keyName = "holdingBankTab",
		name = "Holding bank tab",
		description = "A 'holding' tab in the bank's tag-tab column (needs RuneLite's Bank Tags plugin on) with your "
			+ "flip items in the bank - every one you deposit, and your long-term holds - each with a dot (green: back at "
			+ "what you paid). Off (the default) takes the tab out of the column again"
	)
	default boolean holdingBankTab()
	{
		return false;                              // user 2026-11-02: off by default (it writes Bank Tags' settings)
	}

	@ConfigItem(
		position = 7,
		keyName = "sellOnly",
		name = "Sell only",
		description = "End of a session: no new buys, and every open buy offer is told to cancel (what it bought is "
			+ "sold) - the plugin only tells you what to sell, re-price and collect, to empty your slots and get your "
			+ "coins back. Also a button in the panel"
	)
	default boolean sellOnly()
	{
		return false;
	}

	/** GE offer slots the account has: 3 free-to-play, 8 members (OSRS Wiki, Grand Exchange). */
	static int geSlots(FlipSidebarConfig config)
	{
		return config.freeToPlay() ? 3 : 8;
	}

	@ConfigItem(
		position = 8,
		keyName = "showFlipBox",
		name = "Show the flip box",
		description = "The box over the game with the flip to do (quantity, price, profit). The side panel "
			+ "shows the same"
	)
	default boolean showFlipBox()
	{
		return true;
	}

	@ConfigItem(
		position = 9,
		keyName = "highlight",
		name = "Highlight what to click",
		description = "In the GE, highlight the next thing to click for the current flip: an empty "
			+ "slot's Buy or Sell arrow, the item, the quantity and price buttons, then Confirm"
	)
	default boolean highlight()
	{
		return true;
	}

	// position 9 again: the list is ordered by position, then by name, so it stays right under "Highlight what to click"
	@ConfigItem(
		position = 9,
		keyName = "leftClickSlotAction",
		name = "Left-click cancel / re-price",
		description = "When a GE slot is lit red (cancel) or amber (re-price), left-clicking that slot does "
			+ "'Abort offer' or 'Modify offer' straight away - the same as choosing it from the slot's right-click "
			+ "menu. Other slots are not changed"
	)
	default boolean leftClickSlotAction()
	{
		return true;
	}

	@ConfigItem(
		position = 10,
		keyName = "slotTimers",
		name = "Offer timers",
		description = "On each GE slot, how long since the offer last filled (or was placed) - orange once "
			+ "it is longer than 'Re-price every'. Hover a slot for details and a sell's expected profit"
	)
	default boolean slotTimers()
	{
		return true;
	}

	@ConfigItem(
		position = 11,
		keyName = "inventoryTooltip",
		name = "Hover items for profit",
		description = "Hover an item you bought (inventory or the GE's) to see what you paid, its break-even "
			+ "and the profit if sold now"
	)
	default boolean inventoryTooltip()
	{
		return true;
	}

	@ConfigItem(
		position = 11,
		keyName = "flipItemDots",
		name = "Dots on flip items",
		description = "A small dot on the items you are flipping, in the inventory and the bank: green when selling "
			+ "now at today's price would at least get back what you paid after tax, amber when it would not, grey "
			+ "when it has no recent trades to judge by"
	)
	default boolean flipItemDots()
	{
		return true;
	}

	@ConfigItem(
		position = 12,
		keyName = "profitFlash",
		name = "Profit flash on sells",
		description = "Each time a sell offer fills - even partly - flash its GE slot with the profit just "
			+ "made after tax (green) or the loss (red)"
	)
	default boolean profitFlash()
	{
		return true;
	}

	@ConfigItem(
		position = 13,
		keyName = "quickPick",
		name = "One-click item in GE search",
		description = "Put the item to buy in the GE search's 'last searched' row, so it is one click "
			+ "instead of typing its name. The game's 'Show last searched' box must be ticked"
	)
	default boolean quickPick()
	{
		return true;
	}

	@ConfigItem(
		position = 14,
		keyName = "clickToFill",
		name = "Click to fill quantity / price",
		description = "When the GE asks for a quantity or price for the card's item (or an offer being "
			+ "re-placed), show a line to click that puts the number in the box. You still press Enter"
	)
	default boolean clickToFill()
	{
		return true;
	}

	@ConfigItem(
		position = 15,
		keyName = "fillKeyEnabled",
		name = "Fill quantity / price with a key",
		description = "The same as the click, with the key below instead. You still press Enter"
	)
	default boolean fillKeyEnabled()
	{
		return false;
	}

	@ConfigItem(
		position = 16,
		keyName = "fillKey",
		name = "Fill key",
		description = "The key that fills in the number (only while the GE quantity or price box is open)"
	)
	default Keybind fillKey()
	{
		return new Keybind(KeyEvent.VK_E, 0);
	}

	@ConfigItem(
		position = 17,
		keyName = "priceChart",
		name = "Price chart in the GE",
		description = "On the chat box, chart the open item's insta-buy and insta-sell prices with "
			+ "your offer and the card's prices. Needs Live prices on"
	)
	default boolean priceChart()
	{
		return true;
	}

	@ConfigItem(
		position = 18,
		keyName = "showOutsideGe",
		name = "Show the flip outside the GE",
		description = "Show the current flip box even when the Grand Exchange is closed"
	)
	default boolean showOutsideGe()
	{
		return false;
	}

	@ConfigItem(
		position = 18,
		keyName = "clerkHighlight",
		name = "Outline the GE clerks",
		description = "While the Grand Exchange is closed and the card has something to do (collect, sell or buy), the Grand "
			+ "Exchange clerks are outlined: green to collect, blue for a buy or a sell"
	)
	default boolean clerkHighlight()
	{
		return true;
	}

	/** Offers that are not flipping money (right-click "Ignore" in the panel): item|BUY|price|qty, comma-separated. */
	@ConfigItem(
		keyName = "ignoredOffers",
		name = "",
		description = "",
		hidden = true
	)
	default String ignoredOffers()
	{
		return "";
	}

	/** Paused from the panel: no new flips suggested, no scans. */
	@ConfigItem(
		keyName = "paused",
		name = "",
		description = "",
		hidden = true
	)
	default boolean paused()
	{
		return false;
	}

	/**
	 * Items never to suggest, BY NAME, one per line — editable here (user, 2026-10-31: "need somewere in the
	 * setting menu to delete or add items from that list"); the card's Never button adds to it. An entry
	 * that is a number is an item id (how the list was stored before).
	 */
	@ConfigItem(
		position = 19,
		keyName = "blockedItems",
		name = "Never suggest",
		description = "Items the plugin never suggests, by name, one per line. Delete a line to allow that item "
			+ "again, or type a name to add one. The card's Never button adds to this list"
	)
	default String blockedItems()
	{
		return "";
	}

	@ConfigItem(
		position = 20,
		keyName = "tradeJournal",
		name = "Trade journal",
		description = "Keeps a record of your GE offers and fills and of what the plugin suggested, on this computer "
			+ "only (plugin-data/flip-sidebar/<character>/journal, the last 60 days) - to measure and improve the "
			+ "advice. Nothing is sent anywhere"
	)
	default boolean tradeJournal()
	{
		return true;
	}

	/** How High alch mode buys its items. */
	enum AlchBuy
	{
		INSTANT("Buy now (insta-buy price)"),
		OFFER("Offer low (cheaper, slower)");

		private final String label;

		AlchBuy(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/**
	 * ⭐ HIGH ALCH MODE (1.59.0, merged from the Alch Finder plugin): the panel's High Alch button turns it on — the card
	 * then buys the alch plan's items (AlchPlan) and the High Level Alchemy spell and the item to alch are outlined
	 * (FlipSidebarAlchOverlay). Drawing only: nothing is cast or clicked for the player (docs/PLUGIN_RULES.md §9).
	 */
	@ConfigSection(
		name = "High alch mode",
		description = "Buy items to High Level Alchemy instead of flipping: the panel's High Alch button turns it on",
		position = 30,
		closedByDefault = true
	)
	String alchSection = "alchSection";

	/**
	 * The account login (Account; the panel's Log in button): the plugin's own token from the Worker, and the Discord name it
	 * belongs to. Empty = not logged in. Set and cleared by the panel only.
	 */
	@ConfigItem(keyName = "accountToken", name = "", description = "", hidden = true)
	default String accountToken()
	{
		return "";
	}

	@ConfigItem(keyName = "accountName", name = "", description = "", hidden = true)
	default String accountName()
	{
		return "";
	}

	/** The Equipment tab's watch list: item ids, comma-separated (FlipBrain.gearStep). Edited in the panel only. */
	@ConfigItem(keyName = "gearWatch", name = "", description = "", hidden = true)
	default String gearWatch()
	{
		return "";
	}

	/** Watched items already notified as a good time to buy (FlipSidebarPlugin), so a restart does not repeat it. */
	@ConfigItem(keyName = "gearNotified", name = "", description = "", hidden = true)
	default String gearNotified()
	{
		return "";
	}

	@ConfigItem(
		position = 31,
		keyName = "alchMode",
		name = "High alch mode",
		description = "On: the card buys the items of the alch plan, and the High Level Alchemy spell (normal spellbook) "
			+ "and the item to alch are outlined. The High Alch button at the top of the panel does the same",
		section = alchSection
	)
	default boolean alchMode()
	{
		return false;
	}

	/** "Spend on alching at most" at this many thousands = no limit (the slider's far right). */
	int ALCH_NO_LIMIT_K = 2_000_000;

	/** Before 1.60.74 (whole millions, 0 = no limit): read once to carry the user's setting over to alchSpendK. */
	@ConfigItem(
		keyName = "alchSpendMillions",
		name = "",
		description = "",
		hidden = true
	)
	default int alchSpendMillions()
	{
		return 10;
	}

	@Range(min = 0, max = ALCH_NO_LIMIT_K)
	@Units("k")
	@ConfigItem(
		position = 32,
		keyName = "alchSpendK",
		name = "Spend on alching at most",
		description = "Coins alching may tie up at once - its buy offers and the alch items in your inventory - in thousands "
			+ "(500 = 500k, 10000 = 10m). Casting frees it for the next buy. 0 = nothing: alch only what you already have. "
			+ "2000000 = no limit. Never more than the coins you have. The slider in the panel's alch plan sets the same",
		section = alchSection
	)
	default int alchSpendK()
	{
		return 10_000;
	}

	@Range(min = 1, max = 4)
	@ConfigItem(
		position = 33,
		keyName = "alchHours",
		name = "Hours to plan",
		description = "How many hours of casting the alch plan buys for (the GE buy limits reset every 4 hours)",
		section = alchSection
	)
	default int alchHours()
	{
		return 4;
	}

	@Range(min = 300, max = 1200)
	@ConfigItem(
		position = 34,
		keyName = "alchCastsPerHour",
		name = "Casts an hour",
		description = "High Level Alchemy casts you make in an hour. One cast takes 5 game ticks (3 seconds), so 1,200 "
			+ "is the most",
		section = alchSection
	)
	default int alchCastsPerHour()
	{
		return 1200;
	}

	@ConfigItem(
		position = 35,
		keyName = "alchBuy",
		name = "How to buy",
		description = "Buy now: at the insta-buy price, it fills at once. Offer low: at the insta-sell price, cheaper but "
			+ "it fills only as sellers come",
		section = alchSection
	)
	default AlchBuy alchBuy()
	{
		return AlchBuy.INSTANT;
	}

	@Range(min = 1, max = 100_000)
	@ConfigItem(
		position = 36,
		keyName = "alchMinProfit",
		name = "Least profit a cast",
		description = "An item is planned only if each cast makes at least this much, after its price and a nature rune",
		section = alchSection
	)
	default int alchMinProfit()
	{
		return 50;
	}

	@Range(min = 1, max = 8)
	@ConfigItem(
		position = 37,
		keyName = "alchMaxItems",
		name = "Items at most",
		description = "The alch plan buys at most this many different items - one GE slot each",
		section = alchSection
	)
	default int alchMaxItems()
	{
		return 8;
	}

	/** The "Never suggest" list as written: names (or old numeric ids), one per line or comma-separated. */
	static List<String> parseNames(String text)
	{
		Set<String> out = new LinkedHashSet<>();
		for (String k : (text == null ? "" : text).split("[,\\r\\n]+"))
		{
			if (!k.trim().isEmpty())
			{
				out.add(k.trim());
			}
		}
		return new ArrayList<>(out);
	}


	static Set<String> parseIgnored(String csv)
	{
		Set<String> out = new LinkedHashSet<>();
		for (String k : (csv == null ? "" : csv).split(","))
		{
			if (!k.trim().isEmpty())
			{
				out.add(k.trim());
			}
		}
		return out;
	}
}
