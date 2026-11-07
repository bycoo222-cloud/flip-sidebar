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
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * ⭐ THE GE CLERKS OUTLINED (1.60.73) while the Grand Exchange is closed and the card has something to do — collect,
 * sell or buy — so the next step is where you look. User, 2026-11-07: "Why no grand exchange clerk highlight? Thought
 * we wanted that" (it was never built: no record of it before). Green for Collect, the card's blue for a buy or a sell.
 * Only the clerk nearest you (1.60.76). The "Outline the GE clerks" setting turns it off. Only draws: nothing is clicked.
 */
class FlipSidebarClerkOverlay extends Overlay
{
	static final String CLERK = "Grand Exchange Clerk";
	private static final Color TASK = new Color(0, 190, 255, 230);
	private static final Color COLLECT = new Color(80, 220, 100, 230);

	private final Client client;
	private final FlipSidebarPlugin plugin;
	private final FlipSidebarConfig config;
	private final ModelOutlineRenderer outline;

	@Inject
	FlipSidebarClerkOverlay(Client client, FlipSidebarPlugin plugin, FlipSidebarConfig config, ModelOutlineRenderer outline)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.outline = outline;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.clerkHighlight() || plugin.isGeOpen() || client.getTopLevelWorldView() == null)
		{
			return null;
		}
		Suggestion s = plugin.getSuggestion();
		String action = s == null || s.action == null ? "" : s.action;
		Color c = "COLLECT".equals(action) ? COLLECT : "BUY".equals(action) || "SELL".equals(action) ? TASK : null;
		if (c == null)
		{
			return null;              // nothing to do at the GE: no outline
		}
		// only the NEAREST clerk (1.60.76; user, 2026-11-07, seeing all four lit: "can we highlight just one of the ge guys
		// not all 4?")
		Player me = client.getLocalPlayer();
		LocalPoint here = me == null ? null : me.getLocalLocation();
		NPC nearest = null;
		int best = Integer.MAX_VALUE;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || !CLERK.equalsIgnoreCase(npc.getName()) || npc.getLocalLocation() == null)
			{
				continue;
			}
			int d = here == null ? 0 : here.distanceTo(npc.getLocalLocation());
			if (d < best)
			{
				best = d;
				nearest = npc;
			}
		}
		if (nearest != null)
		{
			outline.drawOutline(nearest, 2, c, 4);
		}
		return null;
	}
}
