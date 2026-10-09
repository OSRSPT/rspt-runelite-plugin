package pt.rspt;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin wrapper around the two event.rspt.pt endpoints used by the plugin.
 * All requests are asynchronous (OkHttp enqueue); callbacks run on an OkHttp thread.
 * The server URL is fixed and not user configurable.
 */
@Slf4j
@Singleton
class RsptApi
{
	private static final HttpUrl BASE_URL = HttpUrl.get("https://event.rspt.pt");
	private static final HttpUrl WATCHLIST_URL = BASE_URL.resolve("/api/plugin/watchlist");
	private static final HttpUrl DROP_URL = BASE_URL.resolve("/api/plugin/drop");
	private static final String TOKEN_HEADER = "X-RSPT-Token";
	private static final MediaType JPEG = MediaType.parse("image/jpeg");

	/** HTTP status passed to callbacks when the request never got a response (network error). */
	static final int NETWORK_ERROR = -1;

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	RsptApi(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	/**
	 * GET /api/plugin/watchlist: the item ids that currently count for this player's events.
	 */
	void fetchWatchlist(String token, BiConsumer<Integer, WatchlistResponse> callback)
	{
		Request request = new Request.Builder()
			.url(WATCHLIST_URL)
			.header(TOKEN_HEADER, token)
			.get()
			.build();

		enqueue(request, WatchlistResponse.class, callback);
	}

	/**
	 * POST /api/plugin/drop as multipart/form-data with a JSON "payload" part and a JPEG "screenshot" part.
	 */
	void sendDrop(String token, String payloadJson, byte[] screenshotJpeg, BiConsumer<Integer, DropResponse> callback)
	{
		RequestBody body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("payload", payloadJson)
			.addFormDataPart("screenshot", "drop.jpg", RequestBody.create(JPEG, screenshotJpeg))
			.build();

		Request request = new Request.Builder()
			.url(DROP_URL)
			.header(TOKEN_HEADER, token)
			.post(body)
			.build();

		enqueue(request, DropResponse.class, callback);
	}

	private <T> void enqueue(Request request, Class<T> type, BiConsumer<Integer, T> callback)
	{
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("RSPT request to {} failed", request.url().encodedPath(), e);
				callback.accept(NETWORK_ERROR, null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					callback.accept(response.code(), parse(body, type));
				}
			}
		});
	}

	@Nullable
	private <T> T parse(@Nullable ResponseBody body, Class<T> type)
	{
		if (body == null)
		{
			return null;
		}

		try
		{
			return gson.fromJson(body.charStream(), type);
		}
		catch (JsonParseException e)
		{
			log.debug("RSPT: could not parse response as {}", type.getSimpleName(), e);
			return null;
		}
	}

	// Response shapes, deserialized with the injected Gson.

	static class WatchlistResponse
	{
		boolean ok;
		String user;
		List<Integer> items;
		/** Number of open events the items come from (missing on older servers). */
		Integer events;
		@SerializedName("refresh_seconds")
		Integer refreshSeconds;
		String error;
	}

	static class DropResponse
	{
		boolean ok;
		List<DropResult> results;
		boolean duplicate;
		String error;
	}

	static class DropResult
	{
		String event;
		String tile;
		String item;
		String status;
		int amount;
	}
}
