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
		name = "Token do plugin",
		description = "Gera o token em event.rspt.pt/me e cola-o aqui.",
		secret = true,
		warning = "Este plugin envia o teu nome de jogador, os drops dos eventos, uma captura de ecrã"
			+ " e o teu endereço IP para event.rspt.pt, um servidor de terceiros não controlado"
			+ " nem verificado pelos developers do RuneLite.\n\n"
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
		name = "Mensagem no chat",
		description = "Mostra uma mensagem no chat quando um drop conta para um evento.",
		position = 1
	)
	default boolean chatMessage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "popup",
		name = "Popup no ecrã",
		description = "Mostra um popup no jogo quando um drop conta para um evento.",
		position = 2
	)
	default boolean popup()
	{
		return true;
	}
}
