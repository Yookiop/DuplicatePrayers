package com.duplicateprayers;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class DuplicatePrayersPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DuplicatePrayersPlugin.class);
		RuneLite.main(args);
	}
}
