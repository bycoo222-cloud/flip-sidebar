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
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.util.Text;

/**
 * ⭐ The HOLDING tab in the bank (user, 2026-11-01, with a picture of the bank's tag-tab column: "this is where i want
 * the held items to end up a tab over on the left side. it would make it easy" — "with some kind of icon next it").
 * A tab named {@value #TAG} in that column holds the LONG-TERM holds (TradeLog.held: right-click a Holding row → Hold
 * long-term, then bank it); each one also carries its amber / green dot (FlipSidebarItemOverlay).
 *
 * <p>What is in the tab is answered live: RuneLite's Bank Tags asks {@link #isHeld} for each bank item, so nothing
 * is written into the player's own item tags and a hold that ends leaves the tab by itself. The tab's place in the
 * column and its picture are Bank Tags settings (group "banktags", keys "tagtabs" and "icon_holding"): put there
 * when missing, and taken out again when the "Holding bank tab" setting is turned off — but only if this plugin put
 * them there ({@link #ADDED_KEY}), so a "holding" tab the player made is never removed. While Bank Tags itself is
 * turned off, its settings are not written at all ({@link #sync}).</p>
 *
 * <p>Written for this plugin, not taken from another one (user: "we have to be different enough"): its own tag name,
 * picture and structure. Only the Bank Tags settings keys are shared, being Bank Tags' own format.</p>
 */
final class HoldingBankTab
{
	static final String TAG = "holding";
	private static final String BANK_TAGS_GROUP = "banktags";
	private static final String TAB_ORDER_KEY = "tagtabs";
	private static final String ICON_KEY = "icon_" + TAG;
	/** In this plugin's own group: the tab entry and picture were added by this plugin. */
	private static final String ADDED_KEY = "holdingTabAdded";
	/** The tab's picture: a pile of coins. */
	private static final int PICTURE = ItemID.COINS_10000;

	private final TagManager tagManager;
	private final ConfigManager configManager;
	private final ItemManager itemManager;
	private final PluginManager pluginManager;
	/** The long-term holds (unnoted item ids). Written by the plugin, read by Bank Tags on the client thread. */
	private volatile Set<Integer> holds = Set.of();
	private boolean registered;

	@Inject
	HoldingBankTab(TagManager tagManager, ConfigManager configManager, ItemManager itemManager,
		PluginManager pluginManager)
	{
		this.tagManager = tagManager;
		this.configManager = configManager;
		this.itemManager = itemManager;
		this.pluginManager = pluginManager;
	}

	/**
	 * The "Holding bank tab" setting, at the start and whenever it or Bank Tags is turned on or off: the tab goes in the
	 * column only while Bank Tags is on, so its settings are never written while that plugin is off (rules: "Plugins
	 * which modify other plugin's configs"; Flipping Copilot's Portfolio tab has the same guard). The setting off takes
	 * the tab out again (if this plugin added it).
	 */
	void sync(boolean setting)
	{
		if (!setting)
		{
			setShown(false);
		}
		else if (!bankTagsOn())
		{
			stop();                                // the tag goes; the column is left as it is
		}
		else
		{
			setShown(true);
		}
	}

	/** Bank Tags turned on in the plugin list (its saved on/off, so it is already right while it is being toggled). */
	private boolean bankTagsOn()
	{
		for (Plugin p : pluginManager.getPlugins())
		{
			if (p instanceof BankTagsPlugin)
			{
				return pluginManager.isPluginEnabled(p);
			}
		}
		return false;
	}

	/** On puts the tab in the column, off takes it out (if this plugin added it). */
	private void setShown(boolean shown)
	{
		if (shown)
		{
			if (!registered)
			{
				tagManager.registerTag(TAG, this::isHeld);
				registered = true;
			}
			addToColumn();
		}
		else
		{
			stop();
			removeFromColumn();
		}
	}

	/** The long-term holds now. Bank Tags asks again whenever the bank is redrawn (a deposit redraws it). */
	void setHolds(Set<Integer> now)
	{
		if (!now.equals(holds))
		{
			holds = Set.copyOf(now);
		}
	}

	/** The plugin stops: the tag goes, the tab entry stays where the player has it (it is back at the next start). */
	void stop()
	{
		if (registered)
		{
			tagManager.unregisterTag(TAG);
			registered = false;
		}
	}

	/** Bank Tags: is this bank item a long-term hold? Client thread (canonicalize needs it). */
	private boolean isHeld(int itemId)
	{
		Set<Integer> h = holds;
		return !h.isEmpty() && h.contains(itemManager.canonicalize(itemId));
	}

	private void addToColumn()
	{
		List<String> order = tabOrder();
		if (!order.contains(TAG))
		{
			order.add(TAG);                        // at the bottom of the column
			configManager.setConfiguration(BANK_TAGS_GROUP, TAB_ORDER_KEY, Text.toCSV(order));
			configManager.setConfiguration(FlipSidebarConfig.GROUP, ADDED_KEY, true);
		}
		if (configManager.getConfiguration(BANK_TAGS_GROUP, ICON_KEY) == null)
		{
			configManager.setConfiguration(BANK_TAGS_GROUP, ICON_KEY, PICTURE);
		}
	}

	private void removeFromColumn()
	{
		if (!Boolean.parseBoolean(configManager.getConfiguration(FlipSidebarConfig.GROUP, ADDED_KEY)))
		{
			return;                                // the player's own "holding" tab, or never added
		}
		List<String> order = tabOrder();
		if (order.remove(TAG))
		{
			configManager.setConfiguration(BANK_TAGS_GROUP, TAB_ORDER_KEY, Text.toCSV(order));
		}
		configManager.unsetConfiguration(BANK_TAGS_GROUP, ICON_KEY);
		configManager.unsetConfiguration(FlipSidebarConfig.GROUP, ADDED_KEY);
	}

	private List<String> tabOrder()
	{
		String csv = configManager.getConfiguration(BANK_TAGS_GROUP, TAB_ORDER_KEY);
		return new ArrayList<>(csv == null || csv.isEmpty() ? List.of() : Text.fromCSV(csv));
	}
}
