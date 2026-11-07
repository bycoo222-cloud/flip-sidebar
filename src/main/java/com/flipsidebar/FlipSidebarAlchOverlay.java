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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * ⭐ HIGH ALCH MODE's outlines (1.59.0; user, 2026-11-02: "the high alch icon that puts it into high alch mode changes the
 * ui and then it does the [alch] thing highlighting the [spell] and the item" — "its a spell its in the spell book and
 * you have to be on the nornal spell book"). On the normal spellbook: the High Level Alchemy spell is outlined; once it
 * is selected, the inventory item to alch (FlipBrain.alchTargets: the plan's items first) is outlined; with the spellbook
 * closed, its tab (1.60.74).
 *
 * <p>DRAWING ONLY (docs/PLUGIN_RULES.md §9): nothing is cast or clicked, no click zone moved or resized, the spellbook
 * is not resized, no menu option changed — Jagex's guidelines and RuneLite's rejected features allow outlines.</p>
 */
class FlipSidebarAlchOverlay extends Overlay
{
	private static final Color EDGE = new Color(255, 176, 0, 230);
	private static final Color FILL = new Color(255, 176, 0, 45);
	/** VarbitID.SPELLBOOK: 0 = the normal (standard) spellbook — the only one with High Level Alchemy. */
	private static final int STANDARD_SPELLBOOK = 0;

	private final Client client;
	private final FlipSidebarPlugin plugin;
	private final FlipSidebarConfig config;
	private final ItemManager itemManager;

	@Inject
	FlipSidebarAlchOverlay(Client client, FlipSidebarPlugin plugin, FlipSidebarConfig config, ItemManager itemManager)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.alchMode() || plugin.guidanceOff() || !config.livePrices()
			|| client.getVarbitValue(VarbitID.SPELLBOOK) != STANDARD_SPELLBOOK)
		{
			return null;
		}
		Widget selected = client.isWidgetSelected() ? client.getSelectedWidget() : null;
		boolean casting = selected != null && selected.getId() == InterfaceID.MagicSpellbook.HIGH_ALCHEMY;
		// ⭐ ONE STEP AT A TIME (1.60.75; user, 2026-11-07: "when should i be buying and when should i be alching bc the buy
		// cards up and the alchs are highlighted. this modes a little confusing"): while the card has a GE step (a buy to
		// place, offers to collect) the spellbook, the spell and the item stay dark — the GE (its slots, or the clerks
		// while it is closed) is the step. A spell already picked still shows its item, so a cast in hand is finished.
		Suggestion card = plugin.getSuggestion();
		String step = card == null || card.action == null ? "" : card.action;
		if (!casting && ("BUY".equals(step) || "COLLECT".equals(step)))
		{
			return null;
		}
		if (casting)
		{
			draw(g, itemToAlch());               // the spell is chosen: the item it goes on
			return null;
		}
		Widget spell = client.getWidget(InterfaceID.MagicSpellbook.HIGH_ALCHEMY);
		if (plugin.alchTargets().isEmpty())
		{
			return null;
		}
		if (spell != null && !spell.isHidden())
		{
			draw(g, spell);
		}
		else
		{
			// ⭐ the spellbook closed: its tab is outlined (1.60.74; user, 2026-11-07: "on alch mode can you highlight the spell
			// book?") — in whichever of the three layouts is on screen
			draw(g, magicTab());
		}
		return null;
	}

	/** The Magic tab's button in the layout on screen (fixed, resizable classic or resizable modern); null if none. */
	private Widget magicTab()
	{
		for (int id : new int[]{InterfaceID.ToplevelOsrsStretch.STONE6, InterfaceID.ToplevelPreEoc.STONE6, InterfaceID.Toplevel.STONE6})
		{
			Widget w = client.getWidget(id);
			if (w != null && !w.isHidden() && w.getBounds() != null && w.getBounds().width > 0)
			{
				return w;
			}
		}
		return null;
	}

	/** The first inventory slot holding an item worth alching — the plan's items first. Null if none. */
	private Widget itemToAlch()
	{
		Widget inv = client.getWidget(InterfaceID.Inventory.ITEMS);
		List<Integer> targets = plugin.alchTargets();
		if (inv == null || inv.isHidden() || targets.isEmpty())
		{
			return null;
		}
		Widget[] slots = inv.getDynamicChildren();
		for (int want : targets)
		{
			for (Widget w : slots == null ? new Widget[0] : slots)
			{
				if (w != null && w.getItemQuantity() > 0 && w.getItemId() > 0 && itemManager.canonicalize(w.getItemId()) == want)
				{
					return w;
				}
			}
		}
		return null;
	}

	private static void draw(Graphics2D g, Widget w)
	{
		if (w == null || w.isHidden())
		{
			return;
		}
		Rectangle r = w.getBounds();
		if (r == null || r.width <= 0)
		{
			return;
		}
		g.setColor(FILL);
		g.fill(r);
		g.setColor(EDGE);
		g.setStroke(new BasicStroke(2));
		g.draw(r);
	}
}
