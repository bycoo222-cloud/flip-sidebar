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
import java.text.NumberFormat;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The current flip, drawn while the GE is open: what to do, the quantity and the price to type
 * (yellow, like the GE's own coin text), the planned sell price and the profit.
 */
class FlipSidebarOverlay extends OverlayPanel
{
	private static final Color ORANGE = new Color(0xFF981F);
	private static final Color YELLOW = new Color(0xFFD84A);
	private static final Color GREEN = new Color(0x4FD16A);
	private static final Color RED = new Color(0xFF5A52);
	private static final Color DIM = new Color(0x9A9A9A);
	private static final Color AMBER = new Color(0xFFB000);
	private static final long STALE_SECONDS = 120;

	private final FlipSidebarPlugin plugin;
	private final FlipSidebarConfig config;
	private final NumberFormat number = NumberFormat.getIntegerInstance(Locale.US);

	@Inject
	FlipSidebarOverlay(FlipSidebarPlugin plugin, FlipSidebarConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		FlipSidebarPlugin.Modify m = plugin.modifying();
		if (m != null)
		{
			return renderModify(graphics, m);
		}
		// an offer for the user's own pick is open: its numbers, not the card's
		Suggestion own = plugin.isGeOpen() ? plugin.offerSuggestion() : null;
		Suggestion s = own != null && own.manual ? own : plugin.getSuggestion();
		if (s == null || (!config.showOutsideGe() && !plugin.isGeOpen()))
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder().text("Flip Sidebar").color(ORANGE).build());

		if (!("BUY".equals(s.action) || "SELL".equals(s.action)))
		{
			// NONE, or all 8 slots in use: WAIT / COLLECT
			String label = "WAIT".equals(s.action) ? "Wait" : "COLLECT".equals(s.action) ? "Collect" : null;
			if (label != null)
			{
				panelComponent.getChildren().add(LineComponent.builder().left(label).leftColor(ORANGE).build());
			}
			panelComponent.getChildren().add(LineComponent.builder()
				.left(s.note == null || s.note.isEmpty() ? "Nothing worth doing right now" : s.note)
				.leftColor(DIM).build());
			return super.render(graphics);
		}

		boolean buy = "BUY".equals(s.action);
		if (s.manual && s.discouraged)
		{
			// not to be placed: say why, and nothing to type
			panelComponent.getChildren().add(LineComponent.builder().left("Your pick").leftColor(AMBER)
				.right(s.name == null ? "?" : s.name).build());
			panelComponent.getChildren().add(LineComponent.builder().left(s.note).leftColor(RED).build());
			return super.render(graphics);
		}
		if (s.manual)
		{
			panelComponent.getChildren().add(LineComponent.builder().left("Your pick").leftColor(AMBER).build());
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left(buy ? "Buy" : "Sell").leftColor(buy ? GREEN : ORANGE)
			.right(s.name == null ? "?" : s.name).build());
		if (buy)
		{
			// the GE buy limit left — to judge whether more of a flip that is going well can be bought
			// (user, 2026-10-31: "above the flip sidebar quanity can you please add the remaining ge limit")
			long[] lim = plugin.limitNow(s.itemId);
			if (lim != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Limit left").right(number.format(lim[0]) + " / " + number.format(lim[1]))
					.rightColor(lim[0] == 0 ? RED : Color.WHITE).build());
				if (lim[2] > 0)
				{
					panelComponent.getChildren().add(LineComponent.builder().left("Resets in")
						.right(FlipSidebarSlotOverlay.clock(lim[2] - System.currentTimeMillis())).rightColor(DIM).build());
				}
			}
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Quantity").right(number.format(s.quantity)).rightColor(YELLOW).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Price").right(number.format(s.price)).rightColor(YELLOW).build());
		if (buy && s.sellPrice > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Then sell at").right(number.format(s.sellPrice)).build());
		}
		if (!(s.manual && !buy && s.profit == 0))       // a sell with no buy price on record: unknown
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Profit").right((s.profit >= 0 ? "+" : "") + number.format(s.profit))
				.rightColor(s.profit >= 0 ? GREEN : RED).build());
		}
		if (s.manual && s.note != null && !s.note.isEmpty())
		{
			panelComponent.getChildren().add(LineComponent.builder().left(s.note).leftColor(ORANGE).build());
		}

		long age = System.currentTimeMillis() / 1000 - s.asOf;
		if (s.asOf > 0 && age > STALE_SECONDS)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left((age / 60) + "m old - prices may have moved").leftColor(ORANGE).build());
		}
		return super.render(graphics);
	}

	/** Re-placing an offer after "Modify offer": what to type, and that only Confirm puts it back. */
	private Dimension renderModify(Graphics2D graphics, FlipSidebarPlugin.Modify m)
	{
		panelComponent.getChildren().add(TitleComponent.builder().text("Flip Sidebar").color(ORANGE).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(m.target <= 0 ? "Modify" : m.target > m.oldPrice ? "Raise" : "Lower").leftColor(AMBER)
			.right((m.buy ? "Buy " : "Sell ") + (m.name == null ? "?" : m.name)).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Quantity").right(number.format(m.quantity)).rightColor(YELLOW).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Price").right(m.target > 0 ? number.format(m.target) : "your choice").rightColor(YELLOW).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Was").right(number.format(m.oldPrice)).leftColor(DIM).rightColor(DIM).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Confirm to put it back -").leftColor(ORANGE).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("leaving now cancels it").leftColor(ORANGE).build());
		return super.render(graphics);
	}
}
