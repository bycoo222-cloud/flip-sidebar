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
import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

@ConfigGroup(FlipSidebarConfig.GROUP)
public interface FlipSidebarConfig extends Config
{
	String GROUP = "flipsidebar";

	@ConfigItem(
		position = 0,
		keyName = "livePrices",
		name = "Live prices (find flips here)",
		description = "Fetch Grand Exchange prices from the OSRS Wiki (prices.runescape.wiki) so the plugin "
			+ "finds flips itself and shows them in its side panel - no Flip Sidebar program needed",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default boolean livePrices()
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
		return RiskLevel.MED;
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
		position = 3,
		keyName = "minProfit",
		name = "Minimum profit per flip",
		description = "Only suggest flips that make at least this many coins after the GE tax"
	)
	default int minProfit()
	{
		return 10_000;
	}

	@Range(min = 0)
	@ConfigItem(
		position = 4,
		keyName = "maxCash",
		name = "Use at most (coins)",
		description = "The most cash the plugin plans flips with. 0 = all your coins (inventory + bank)"
	)
	default int maxCash()
	{
		return 0;
	}

	@ConfigItem(
		position = 5,
		keyName = "highlight",
		name = "Highlight what to click",
		description = "In the GE, highlight the next thing to click for the current flip: an empty "
			+ "slot's Buy or Sell arrow, the item, the quantity and price buttons, then Confirm"
	)
	default boolean highlight()
	{
		return true;
	}

	@ConfigItem(
		position = 6,
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
		position = 7,
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
		position = 8,
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
		position = 9,
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
		position = 10,
		keyName = "exportState",
		name = "Share offers and coins with the sidebar program",
		description = "Only for the separate Flip Sidebar desktop program: write your GE offers, inventory "
			+ "and bank coins to local files it reads. Nothing leaves your computer"
	)
	default boolean exportState()
	{
		return false;
	}

	@ConfigItem(
		position = 11,
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
		position = 12,
		keyName = "fillKeyEnabled",
		name = "Fill quantity / price with a key",
		description = "The same as the click, with the key below instead. You still press Enter"
	)
	default boolean fillKeyEnabled()
	{
		return false;
	}

	@ConfigItem(
		position = 13,
		keyName = "fillKey",
		name = "Fill key",
		description = "The key that fills in the number (only while the GE quantity or price box is open)"
	)
	default Keybind fillKey()
	{
		return new Keybind(KeyEvent.VK_E, 0);
	}

	@ConfigItem(
		position = 14,
		keyName = "priceChart",
		name = "Price chart in the GE",
		description = "Beside the GE window, chart the open item's insta-buy and insta-sell prices with "
			+ "your offer and the card's prices. Needs live prices on, or the Flip Sidebar program running"
	)
	default boolean priceChart()
	{
		return true;
	}

	@ConfigItem(
		position = 15,
		keyName = "showOutsideGe",
		name = "Show the flip outside the GE",
		description = "Show the current flip box even when the Grand Exchange is closed"
	)
	default boolean showOutsideGe()
	{
		return false;
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

	/** Items never to suggest (right-click "Never suggest" in the panel): item ids, comma-separated. */
	@ConfigItem(
		keyName = "blockedItems",
		name = "",
		description = "",
		hidden = true
	)
	default String blockedItems()
	{
		return "";
	}

	static Set<Integer> parseBlocked(String csv)
	{
		Set<Integer> out = new LinkedHashSet<>();
		for (String k : parseIgnored(csv))
		{
			try
			{
				out.add(Integer.parseInt(k));
			}
			catch (NumberFormatException e)
			{
				// a hand-edited entry that is not a number: skipped
			}
		}
		return out;
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
