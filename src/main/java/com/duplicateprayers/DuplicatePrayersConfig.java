// DuplicatePrayersConfig.java
package com.duplicateprayers;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(DuplicatePrayersConfig.GROUP)
public interface DuplicatePrayersConfig extends Config
{
	String GROUP = "duplicateprayers";

	@ConfigItem(
			keyName = "hiddenPrayers",
			name = "Hidden Prayers (Internal)",
			description = "List with hidden prayer IDs",
			hidden = true
	)
	default String hiddenPrayers() { return ""; }

	@ConfigItem(
			keyName = "hiddenPrayers",
			name = "",
			description = ""
	)
	void hiddenPrayers(String key);
}