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

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Hover an item you bought — in the inventory, or the GE's own inventory — for what you paid,
 * its break-even after the 2% tax and what selling all of it now would make (Copilot's inventory
 * tooltip). Only items the trade log has a buy price for; noted counts as unnoted.
 */
class FlipSidebarItemOverlay extends Overlay
{
	private static final int[] CONTAINERS = {InterfaceID.Inventory.ITEMS, InterfaceID.GeOffersSide.ITEMS};

	private final Client client;
	private final FlipSidebarPlugin plugin;
	private final FlipSidebarConfig config;
	private final TooltipManager tooltipManager;

	@Inject
	FlipSidebarItemOverlay(Client client, FlipSidebarPlugin plugin, FlipSidebarConfig config, TooltipManager tooltipManager)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Point mouse = client.getMouseCanvasPosition();
		if (!config.inventoryTooltip() || mouse == null)
		{
			return null;
		}
		for (int id : CONTAINERS)
		{
			Widget items = client.getWidget(id);
			Widget[] kids = items == null || items.isHidden() ? null : items.getDynamicChildren();
			for (Widget w : kids == null ? new Widget[0] : kids)
			{
				Rectangle r = w.getItemId() > 0 && !w.isHidden() ? w.getBounds() : null;
				if (r != null && r.contains(mouse.getX(), mouse.getY()))
				{
					tooltip(plugin.canonical(w.getItemId()), Math.max(1, w.getItemQuantity()));
					return null;
				}
			}
		}
		return null;
	}

	private void tooltip(int itemId, int qty)
	{
		long paid = plugin.paidEach(itemId);
		if (paid <= 0)
		{
			return;                   // not bought through the GE recently: nothing to say
		}
		long even = FlipEngine.breakEven(paid, itemId);
		long market = plugin.market(itemId);
		StringBuilder t = new StringBuilder("Flip Sidebar: " + plugin.itemName(itemId))
			.append(String.format("</br>Paid %,d each · break-even %,d", paid, even));
		if (market > 0)
		{
			long all = (market - FlipEngine.tax(market, itemId) - paid) * qty;
			t.append(String.format("</br>Now %,d → <col=%s>%s%s</col> if all %,d sold, after tax", market,
				all >= 0 ? "4fd16a" : "ff5a52", all >= 0 ? "+" : "-", FlipBrain.gp(Math.abs(all)), qty));
		}
		tooltipManager.add(new Tooltip(t.toString()));
	}
}
