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

/**
 * How often you come back to re-price your offers. It sets how big a slice of an hour's trading
 * one offer may take: 30m = 0.10 is MEASURED on the user's flips (up to 0.10x of the hour filled
 * in 9-12 minutes; 0.25-0.5x lost money 26% of the time). The others are judgement around it.
 * 5m was 0.05 until 1.43.0 — half the measured-safe slice; live 2026-11-01 (harness SeqCheck, 8 slots from 150m):
 * 0.05 30.1m out, +708k a round · 0.083 35.5m, +835k · 0.10 36.9m, +869k (user: "were using very little money").
 */
public enum AdjustWindow
{
	FIVE_MINUTES("5m", 5, 0.10),
	THIRTY_MINUTES("30m", 30, 0.10),
	TWO_HOURS("2h", 120, 0.20),
	EIGHT_HOURS("8h", 480, 0.25);

	private final String label;
	final int minutes;
	final double volumeShare;

	AdjustWindow(String label, int minutes, double volumeShare)
	{
		this.label = label;
		this.minutes = minutes;
		this.volumeShare = volumeShare;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
