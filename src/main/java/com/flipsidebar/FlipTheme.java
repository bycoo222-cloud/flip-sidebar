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
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * ⭐ THEMES (user, 2026-11-04: "how hard would it be to add the themes" → colours and fonts; Classic stays the default).
 * The panel's and the Statistics window's colours and fonts. ⚠ Profit is always green and a loss always red, in every
 * theme: on a money tool those two must never change meaning.
 */
final class FlipTheme
{
	/** The theme in use (the "Look" setting); read when the panel or window is built. */
	static volatile FlipTheme now = of(FlipSidebarConfig.Look.CLASSIC);

	Color background;
	Color card;
	Color hover;
	Color text;
	Color dim;
	/** Profit, buys. */
	Color green;
	/** Losses, sells under water. */
	Color red;
	/** Titles and accents. */
	Color orange;
	/** The numbers you type into the GE. */
	Color yellow;
	/** Warnings. */
	Color amber;
	/** The mode banner: High alch mode / flipping. */
	Color alchBg;
	Color flipBg;
	/** A font family for everything (the original app's smooth font); null = RuneScape's own fonts. */
	String family;
	/** Card corners (FlipUi). */
	int radius = 8;
	/** Buttons, bar tracks. */
	Color chip;
	/** A thin outline round cards; null = none. */
	Color edge;
	/** The top card's gradient. */
	Color heroTop;
	Color heroBottom;
	/** A soft glow on the top card's number (Ectoplasm, Infernal); null = none. */
	Color glow;
	/** Dots of light in the top card; null = none. */
	Color motes;
	/** Molten rock in the top card, embers under the cards (Infernal). */
	boolean lava;
	/** The game's bevelled stone, text with its black shadow (Old School). */
	boolean bevel;
	boolean shadow;
	Color bevelLight;
	Color bevelDark;

	static FlipTheme of(FlipSidebarConfig.Look look)
	{
		FlipTheme t = new FlipTheme();
		// Classic: what the panel always looked like
		t.background = ColorScheme.DARK_GRAY_COLOR;
		t.card = ColorScheme.DARKER_GRAY_COLOR;
		t.hover = ColorScheme.DARKER_GRAY_HOVER_COLOR;
		t.text = Color.WHITE;
		t.dim = new Color(0x9A9A9A);
		t.green = new Color(0x4FD16A);
		t.red = new Color(0xFF5A52);
		t.orange = new Color(0xFF981F);
		t.yellow = new Color(0xFFD84A);
		t.amber = new Color(0xFFB000);
		t.alchBg = new Color(0x6B4A00);
		t.flipBg = new Color(0x1E4D2B);
		t.radius = 6;
		t.chip = new Color(0x3C3C3C);
		t.heroTop = new Color(0x2E2E2E);
		t.heroBottom = new Color(0x1C1C1C);
		switch (look)
		{
			case MODERN:
				// the original standalone app's look (flip_sidebar.py): a smooth font on one calm surface, only the
				// numbers carry colour (named "Modern", user 2026-11-04: "so others know what it is")
				t.background = new Color(0x1B1B1B);
				t.card = new Color(0x262626);
				t.hover = new Color(0x333333);
				t.text = new Color(0xE8E8E8);
				t.dim = new Color(0x8C8C8C);
				t.amber = new Color(0xFFB02E);
				t.flipBg = new Color(0x1F3A26);
				t.alchBg = new Color(0x5C451A);
				t.family = available("Noto Sans", "Segoe UI", "Helvetica Neue", "Arial");
				t.radius = 10;
				t.chip = new Color(0x333333);
				t.edge = new Color(0x2E2E2E);
				t.heroTop = new Color(0x2C2C2C);
				t.heroBottom = new Color(0x1F1F1F);
				break;
			case OLD_SCHOOL:
				t.background = new Color(0x3E3529);
				t.card = new Color(0x2B241C);
				t.hover = new Color(0x3A3025);
				t.dim = new Color(0xC8B898);
				t.green = new Color(0x2ED52E);
				t.red = new Color(0xFF3B30);
				t.yellow = new Color(0xFFFF00);
				t.alchBg = new Color(0x5A4010);
				t.flipBg = new Color(0x2E4A1E);
				t.radius = 0;
				t.chip = new Color(0x4A3E2F);
				t.bevel = true;
				t.shadow = true;
				t.bevelLight = new Color(0x5C4F3D);
				t.bevelDark = new Color(0x16120D);
				t.heroTop = t.card;
				t.heroBottom = t.card;
				break;
			case ECTOPLASM:
				t.background = new Color(0x0D1213);
				t.card = new Color(0x151F21);
				t.hover = new Color(0x1E2E30);
				t.text = new Color(0xE6FFF6);
				t.dim = new Color(0x7F9C98);
				t.green = new Color(0x6EF0B4);
				t.red = new Color(0xFF6B8A);
				t.orange = new Color(0x9EFFD6);
				t.yellow = new Color(0xD8FF8A);
				t.amber = new Color(0xFFC86E);
				t.alchBg = new Color(0x3A2E5A);
				t.flipBg = new Color(0x1E3A36);
				t.radius = 10;
				t.chip = new Color(0x1E2E30);
				t.edge = new Color(0x213636);
				t.heroTop = new Color(0x1E3A36);
				t.heroBottom = new Color(0x0F181A);
				t.glow = new Color(0x6EF0B4);
				t.motes = new Color(0x9EFFD6);
				break;
			case INFERNAL:
				t.background = new Color(0x120A08);
				t.card = new Color(0x1F120E);
				t.hover = new Color(0x2E1A12);
				t.text = new Color(0xFFE0C0);
				t.dim = new Color(0xB08C78);
				t.green = new Color(0x7CDB5A);
				t.red = new Color(0xFF4A30);
				t.orange = new Color(0xFF6A1A);
				t.yellow = new Color(0xFFB040);
				t.amber = new Color(0xFFA02A);
				t.alchBg = new Color(0x5A2A06);
				t.flipBg = new Color(0x2A3A12);
				t.radius = 10;
				t.chip = new Color(0x2E1A12);
				t.edge = new Color(0x4A2210);
				t.heroTop = new Color(0x2A140C);
				t.heroBottom = new Color(0x120805);
				t.glow = new Color(0xFF7020);
				t.motes = new Color(0xFFC060);
				t.lava = true;
				break;
			case CLASSIC:
			default:
				break;
		}
		return t;
	}

	/** The first of these font families this computer has; null when none (RuneScape's fonts then). */
	static String available(String... families)
	{
		try
		{
			java.util.List<String> have = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
			for (String f : families)
			{
				if (have.contains(f))
				{
					return f;
				}
			}
			return "SansSerif";              // Java's own smooth font is everywhere
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	Font bold()
	{
		return family == null ? FontManager.getRunescapeBoldFont() : new Font(family, Font.BOLD, 13);
	}

	Font plain()
	{
		return family == null ? FontManager.getRunescapeFont() : new Font(family, Font.PLAIN, 12);
	}

	Font small()
	{
		return family == null ? FontManager.getRunescapeSmallFont() : new Font(family, Font.PLAIN, 11);
	}
}
