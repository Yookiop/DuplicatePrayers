package com.duplicateprayers;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(DuplicatePrayersConfig.GROUP)
public interface DuplicatePrayersConfig extends Config
{
	String GROUP = "duplicateprayers";

	@ConfigItem(
		keyName = "prioritizeOriginalPrayersWhenReordering",
		name = "Show hidden prayers while reordering",
		description = "When RuneLite prayer reordering is enabled, temporarily gives original prayers priority over duplicates so hidden prayers can be shown and configured.",
		position = 0
	)
	default boolean prioritizeOriginalPrayersWhenReordering()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hiddenPrayerSwaps",
		name = "Swap duplicates into hidden slots",
		description = "One per line: duplicate shown in the right-click menu -> hidden prayer. Example: Protect from Melee (duplicate 1) -> Mystic Might",
		position = 1
	)
	default String hiddenPrayerSwaps()
	{
		return "";
	}
}
