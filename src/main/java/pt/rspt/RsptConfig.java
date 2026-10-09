package pt.rspt;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(RsptConfig.GROUP)
public interface RsptConfig extends Config
{
	String GROUP = "rspt";
	String TOKEN_KEY = "token";

	// The plugin does nothing until a token is entered, so the token itself is the opt-in.
	@ConfigItem(
		keyName = TOKEN_KEY,
		name = "Plugin token",
		description = "Generate your token at event.rspt.pt/me and paste it here.",
		secret = true,
		warning = "This plugin sends your player name, event drops and a screenshot to event.rspt.pt.\n\n"
			+ "This feature submits your IP address to a 3rd-party server not controlled or verified"
			+ " by RuneLite developers.",
		position = 0
	)
	default String token()
	{
		return "";
	}

	@ConfigItem(
		keyName = "chatMessage",
		name = "Chat message",
		description = "Show a chat message when a drop counts for an event.",
		position = 1
	)
	default boolean chatMessage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "popup",
		name = "Popup",
		description = "Show an in-game popup when a drop counts for an event.",
		position = 2
	)
	default boolean popup()
	{
		return true;
	}
}
