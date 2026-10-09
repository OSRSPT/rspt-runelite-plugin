package pt.rspt;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;

/**
 * Reports the local player's own loot to event.rspt.pt so RSPT clan event tiles are completed automatically.
 *
 * <p>Flow:
 * <ol>
 *   <li>With a personal token (generated at event.rspt.pt/me), fetch a watchlist of item ids that
 *       currently count for the player's events.</li>
 *   <li>On {@link LootReceived} (from the core Loot Tracker), if any item is on the watchlist, take a
 *       screenshot of the next frame and POST the drop (only the watched items) plus the screenshot.</li>
 *   <li>Show the server's verdict as a chat message and/or the in-game notification popup.</li>
 * </ol>
 * Nothing is sent without a token, outside the standard game mode, or for items not on the watchlist.
 */
@Slf4j
@PluginDescriptor(
	name = "RSPT",
	description = "Automatically completes RSPT event items (event.rspt.pt) when you get the drop.",
	tags = {"rspt", "clan", "bingo", "event", "loot", "portugal"}
)
public class RsptPlugin extends Plugin
{
	private static final String CHAT_PREFIX = "[RSPT] ";

	private static final int DEFAULT_REFRESH_SECONDS = 300;
	private static final int MIN_REFRESH_SECONDS = 60;
	private static final int MAX_REFRESH_SECONDS = 3600;

	/** Screenshots are downscaled to this longest edge and sent as JPEG to stay small. */
	private static final int MAX_SCREENSHOT_EDGE = 1600;
	private static final float JPEG_QUALITY = 0.85f;

	/** Client script that fills the notification display (interface 660) with a title and text. */
	private static final int NOTIFICATION_DISPLAY_INIT_SCRIPT = 3343;

	private static final String COL_EVENT = "<col=0b5fd6>";
	private static final String COL_APPROVED = "<col=0a8a0a>";
	private static final String COL_PENDING = "<col=c26100>";
	private static final String COL_ERROR = "<col=d01010>";
	private static final String COL_END = "</col>";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RsptConfig config;

	@Inject
	private RsptApi api;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	/** Canonical item ids that currently count for the player's events. Replaced atomically. */
	private volatile Set<Integer> watchlist = Collections.emptySet();
	/** True when the watchlist must be fetched as soon as the player is logged in. */
	private volatile boolean watchlistDue;
	private volatile long lastWatchlistFetchMillis;
	private volatile int refreshSeconds = DEFAULT_REFRESH_SECONDS;
	/** The token error is shown in chat once, until the token changes or the player logs in again. */
	private volatile boolean tokenErrorShown;
	private volatile boolean running;

	private final AtomicBoolean watchlistFetchInFlight = new AtomicBoolean();
	/** Bumped whenever the token changes or the plugin stops, so stale responses are ignored. */
	private final AtomicInteger generation = new AtomicInteger();

	@Provides
	RsptConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RsptConfig.class);
	}

	@Override
	protected void startUp()
	{
		running = true;
		resetWatchlist();
	}

	@Override
	protected void shutDown()
	{
		running = false;
		generation.incrementAndGet();
		watchlist = Collections.emptySet();
		watchlistFetchInFlight.set(false);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN)
		{
			// Possibly a different account next time: forget everything and re-fetch on login.
			resetWatchlist();
		}
		else if (state == GameState.HOPPING)
		{
			// Keep the current watchlist across world hops, but refresh it once logged in again.
			watchlistDue = true;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (RsptConfig.GROUP.equals(event.getGroup()) && RsptConfig.TOKEN_KEY.equals(event.getKey()))
		{
			resetWatchlist();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// Cheap check each tick; a request is only made on login, on token change, or every refreshSeconds.
		if (!canReport())
		{
			return;
		}

		long now = System.currentTimeMillis();
		boolean refreshDue = now - lastWatchlistFetchMillis >= refreshSeconds * 1000L;
		if ((watchlistDue || refreshDue) && watchlistFetchInFlight.compareAndSet(false, true))
		{
			watchlistDue = false;
			lastWatchlistFetchMillis = now;
			fetchWatchlist(token());
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		Set<Integer> watched = watchlist;
		if (watched.isEmpty() || !canReport())
		{
			return;
		}

		// Only watched items are reported; noted items and placeholders are mapped to their base id.
		Map<Integer, Integer> matched = new TreeMap<>();
		for (ItemStack stack : event.getItems())
		{
			int id = itemManager.canonicalize(stack.getId());
			if (watched.contains(id))
			{
				matched.merge(id, stack.getQuantity(), Integer::sum);
			}
		}

		if (matched.isEmpty())
		{
			return;
		}

		JsonObject items = new JsonObject();
		matched.forEach((id, qty) -> items.addProperty(String.valueOf(id), qty));

		JsonObject payload = new JsonObject();
		payload.addProperty("drop_id", UUID.randomUUID().toString());
		payload.addProperty("rsn", Text.sanitize(client.getLocalPlayer().getName()));
		payload.addProperty("account_hash", String.valueOf(client.getAccountHash()));
		payload.addProperty("profile_type", RuneScapeProfileType.getCurrent(client).name());
		payload.addProperty("source", event.getName());
		payload.addProperty("kills", event.getAmount());
		payload.add("items", items);

		String token = token();
		int gen = generation.get();

		// Same approach as the core Screenshot plugin: grab the next rendered frame, then encode it
		// off the client thread.
		drawManager.requestNextFrameListener(image -> executor.execute(() -> encodeAndSend(image, token, gen, payload)));
	}

	/** True when we are allowed to talk to the server: token set, logged in, name known, standard game mode. */
	private boolean canReport()
	{
		if (token().isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}

		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return false;
		}

		// Skip Leagues, Deadman, beta worlds, etc.
		return RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD;
	}

	private String token()
	{
		String token = config.token();
		return token == null ? "" : token.trim();
	}

	private void resetWatchlist()
	{
		generation.incrementAndGet();
		watchlist = Collections.emptySet();
		watchlistDue = true;
		watchlistFetchInFlight.set(false);
		tokenErrorShown = false;
	}

	private void fetchWatchlist(String token)
	{
		int gen = generation.get();
		api.fetchWatchlist(token, (code, response) ->
		{
			if (gen != generation.get())
			{
				return;
			}
			watchlistFetchInFlight.set(false);

			if (code == 200 && response != null && response.ok)
			{
				Set<Integer> ids = new HashSet<>();
				if (response.items != null)
				{
					ids.addAll(response.items);
				}
				ids.remove(null);
				watchlist = Collections.unmodifiableSet(ids);
				refreshSeconds = clampRefresh(response.refreshSeconds);
				tokenErrorShown = false;
				log.debug("RSPT watchlist loaded: {} items, refresh every {}s", ids.size(), refreshSeconds);
			}
			else if (code == 401 || code == 403)
			{
				watchlist = Collections.emptySet();
				if (!tokenErrorShown)
				{
					tokenErrorShown = true;
					chat(COL_ERROR + Text.removeTags(errorText(response == null ? null : response.error, code)) + COL_END);
				}
			}
			else
			{
				log.debug("RSPT watchlist request failed with status {}", code);
			}
		});
	}

	/** Runs on the executor: encodes the frame as JPEG and posts the drop. The server requires the screenshot. */
	private void encodeAndSend(Image image, String token, int gen, JsonObject payload)
	{
		if (!running || gen != generation.get())
		{
			return;
		}

		byte[] jpeg;
		try
		{
			jpeg = toJpeg(image);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("RSPT: could not capture screenshot, drop not sent", e);
			clientThread.invokeLater(() -> chat(COL_ERROR + "Could not capture a screenshot. The drop was not sent." + COL_END));
			return;
		}

		api.sendDrop(token, gson.toJson(payload), jpeg, (code, response) ->
		{
			if (code == 200 && response != null && response.ok)
			{
				List<RsptApi.DropResult> results = response.results;
				if (results != null && !results.isEmpty())
				{
					clientThread.invokeLater(() -> showResults(results));
				}
			}
			else if (code >= 400 && code < 500)
			{
				String error = errorText(response == null ? null : response.error, code);
				clientThread.invokeLater(() -> chat(COL_ERROR + Text.removeTags(error) + COL_END));
			}
			else
			{
				log.debug("RSPT drop request failed with status {}", code);
			}
		});
	}

	private static byte[] toJpeg(Image image) throws IOException
	{
		if (image == null || image.getWidth(null) <= 0 || image.getHeight(null) <= 0)
		{
			throw new IOException("empty frame");
		}

		// Downscale so the longest edge is at most MAX_SCREENSHOT_EDGE, and draw onto an RGB canvas
		// because JPEG has no alpha channel. Keeps uploads well under the server's 2 MB limit.
		int width = image.getWidth(null);
		int height = image.getHeight(null);
		double scale = Math.min(1.0, (double) MAX_SCREENSHOT_EDGE / Math.max(width, height));
		int outWidth = Math.max(1, (int) Math.round(width * scale));
		int outHeight = Math.max(1, (int) Math.round(height * scale));

		BufferedImage rgb = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = rgb.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(image, 0, 0, outWidth, outHeight, null);
		}
		finally
		{
			graphics.dispose();
		}

		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
		if (!writers.hasNext())
		{
			throw new IOException("no JPEG writer");
		}

		ImageWriter writer = writers.next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(JPEG_QUALITY);

		// Encode fully in memory (no ImageIO disk cache, no files written).
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes))
		{
			writer.setOutput(out);
			writer.write(null, new IIOImage(rgb, null, null), param);
		}
		finally
		{
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	/** Client thread. */
	private void showResults(List<RsptApi.DropResult> results)
	{
		boolean anyApproved = false;
		for (RsptApi.DropResult result : results)
		{
			boolean approved = "approved".equals(result.status);
			anyApproved |= approved;

			if (config.chatMessage())
			{
				String event = COL_EVENT + clean(result.event) + COL_END + ": ";
				String item = clean(result.item);
				String tile = clean(result.tile);
				chat(event + (approved
					? COL_APPROVED + item + " counts for '" + tile + "'!" + COL_END
					: COL_PENDING + item + " sent for staff review ('" + tile + "')." + COL_END));
			}
		}

		if (config.popup())
		{
			showPopup(clean(results.get(0).event), anyApproved ? "Item accepted!" : "Sent for staff review");
		}
	}

	/** Client thread. Opens the game's own notification popup (same one used for collection log slots). */
	private void showPopup(String title, String text)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		final int componentId = client.isResized()
			? client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1
				? InterfaceID.ToplevelPreEoc.NOTIFICATIONS
				: InterfaceID.ToplevelOsrsStretch.NOTIFICATIONS
			: InterfaceID.Toplevel.MAINCRM;

		client.openInterface(componentId, InterfaceID.NOTIFICATION_DISPLAY, WidgetModalMode.MODAL_CLICKTHROUGH);
		client.runScript(NOTIFICATION_DISPLAY_INIT_SCRIPT, title, text, -1);
	}

	/** Client thread. Adds a local game message; nothing is ever typed or sent to the game server. */
	private void chat(String message)
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", CHAT_PREFIX + message, null);
		}
	}

	/** Server text is shown in game, so strip any formatting tags it might contain. */
	private static String clean(String text)
	{
		return text == null ? "" : Text.removeTags(text);
	}

	private static String errorText(String error, int code)
	{
		return error == null || error.isEmpty() ? "Server error (" + code + ")." : error;
	}

	private static int clampRefresh(Integer seconds)
	{
		if (seconds == null)
		{
			return DEFAULT_REFRESH_SECONDS;
		}
		return Math.max(MIN_REFRESH_SECONDS, Math.min(MAX_REFRESH_SECONDS, seconds));
	}
}
