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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Map;
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
 *
 * <p>In the BANK, a long-term hold (FlipBrain.Held.banked) is marked with a dot on its slot — amber while it waits,
 * green once it is back at break-even — and has the same tooltip. User, 2026-11-01: "a little icon next to each item in
 * the bank so u know its a flip item. then they just have you hold it long term".</p>
 *
 * <p>In the INVENTORY (also the GE's and the bank's side panel) every flip item gets the same dot (user, 2026-11-06:
 * "small badge on inventory and bank"): green when selling it now at the tooltip's "now" price gets back at least what
 * was paid after the tax, amber when not, grey when it has no recent trades to judge by. Setting "Dots on flip items".</p>
 */
class FlipSidebarItemOverlay extends Overlay
{
	private static final int[] CONTAINERS = {InterfaceID.Inventory.ITEMS, InterfaceID.GeOffersSide.ITEMS,
		InterfaceID.Bankside.ITEMS};
	private static final Color WAITING = new Color(255, 176, 46);
	private static final Color BACK = new Color(80, 220, 100);
	/** A flip item with no trades in the last hours to price it on. */
	private static final Color NO_PRICE = new Color(160, 160, 160);
	private static final int DOT = 7;

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
		// true: the mouse is on a marked bank item and its tooltip is up — the inventory's dots are still drawn
		boolean tips = !bank(g, mouse) && config.inventoryTooltip() && mouse != null;
		Map<Integer, FlipBrain.Held> dots = config.flipItemDots() ? plugin.flipItemsHeld() : Map.of();
		if (!tips && dots.isEmpty())
		{
			return null;
		}
		for (int id : CONTAINERS)
		{
			Widget items = client.getWidget(id);
			Widget[] kids = items == null || items.isHidden() ? null : items.getDynamicChildren();
			for (Widget w : kids == null ? new Widget[0] : kids)
			{
				if (w.getItemId() <= 0 || w.isHidden())
				{
					continue;
				}
				Rectangle r = w.getBounds();
				boolean over = tips && r.contains(mouse.getX(), mouse.getY());
				// noted counts as unnoted; looked up only when the id itself is not a flip item (this runs every frame)
				int item = over || !(dots.isEmpty() || dots.containsKey(w.getItemId()))
					? plugin.canonical(w.getItemId()) : w.getItemId();
				FlipBrain.Held h = dots.get(item);
				if (h != null)
				{
					// the tooltip's sum for one unit: today's price after the tax, against what was paid
					dot(g, r, h.market <= 0 ? NO_PRICE
						: h.market - FlipEngine.tax(h.market, item) >= h.paidEach ? BACK : WAITING);
				}
				if (over)
				{
					tooltip(item, Math.max(1, w.getItemQuantity()));
					tips = false;         // one tooltip
				}
			}
		}
		return null;
	}

	/** The flip-item dot, in the slot's top-right corner (the top-left is the game's stack count). */
	private static void dot(Graphics2D g, Rectangle r, Color c)
	{
		int x = r.x + r.width - DOT - 1;
		int y = r.y + 1;
		g.setColor(c);
		g.fillOval(x, y, DOT, DOT);
		g.setColor(Color.BLACK);
		g.drawOval(x, y, DOT, DOT);
	}

	/**
	 * Dots on the long-term holds and the deposited flip items (TradeLog.Bought.inBank) in the open bank (only on slots in view: the bank scrolls); the tooltip of the one under
	 * the mouse. True when that tooltip was added.
	 */
	private boolean bank(Graphics2D g, Point mouse)
	{
		Widget view = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (view == null || view.isHidden() || items == null)
		{
			return false;
		}
		Map<Integer, Boolean> holds = plugin.bankHolds();
		Widget[] kids = holds.isEmpty() ? null : items.getDynamicChildren();
		Rectangle in = view.getBounds();
		boolean tip = false;
		for (Widget w : kids == null ? new Widget[0] : kids)
		{
			// bank slots hold unnoted items; a placeholder has quantity 0
			Boolean back = w.isHidden() || w.getItemQuantity() <= 0 ? null : holds.get(w.getItemId());
			Rectangle r = back == null ? null : w.getBounds();
			if (r == null || !in.contains(r.getCenterX(), r.getCenterY()))
			{
				continue;
			}
			if (config.flipItemDots())
			{
				dot(g, r, back ? BACK : WAITING);
			}
			if (!tip && mouse != null && config.inventoryTooltip() && r.contains(mouse.getX(), mouse.getY()))
			{
				// the flip units, not the whole stack: the rest of it can be the player's own (TradeLog.Bought.inBank)
				long flips = plugin.bankHoldQty(w.getItemId());
				int n = (int) Math.min(w.getItemQuantity(), flips > 0 ? flips : w.getItemQuantity());
				tooltip(w.getItemId(), n, w.getItemQuantity() - n);
				tip = true;
			}
		}
		return tip;
	}

	private void tooltip(int itemId, int qty)
	{
		tooltip(itemId, qty, 0);
	}

	/** ...{@code yours}: the rest of the bank's stack, the player's own (user, 2026-11-05: "how many of them are the flips"). */
	private void tooltip(int itemId, int qty, int yours)
	{
		long paid = plugin.paidEach(itemId);
		if (paid <= 0)
		{
			return;                   // not bought through the GE recently: nothing to say
		}
		long even = FlipEngine.breakEven(paid, itemId);
		long market = plugin.market(itemId);
		StringBuilder t = new StringBuilder("Flip Sidebar: " + plugin.itemName(itemId))
			.append(yours > 0 ? String.format("</br>%,d flips · %,d yours", qty, yours) : "")
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
