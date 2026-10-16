package com.autotranslate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod(AutoTranslate.MODID)
public class AutoTranslate {
    public static final String MODID = "autotranslate";
    static final Logger LOGGER = LogUtils.getLogger();

    private static final Pattern CHAT_LINE = Pattern.compile("^<([^>]+)>\\s*(.+)$", Pattern.DOTALL);

    private static final String ENDPOINT = "https://translate-pa.googleapis.com/v1/translate";
    private static final String API_KEY = "AIzaSyDLEeFI5OtFBwYBIoK_jj5m32rZK5CkCXA";
    private static final Gson GSON = new Gson();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "AutoTranslate");
        thread.setDaemon(true);
        return thread;
    });

    public AutoTranslate() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        String plain = event.getMessage().getString();
        if (plain.isBlank()) {
            return;
        }

        Matcher matcher = CHAT_LINE.matcher(plain);
        String prefix = matcher.matches() ? "<" + matcher.group(1) + "> " : "";
        String content = matcher.matches() ? matcher.group(2) : plain;
        if (content.isBlank()) {
            return;
        }

        translate(content, translation -> {
            if (translation == null || normalize(translation).equals(normalize(content))) {
                return;
            }
            Component line = Component.literal(prefix + singleLine(translation)).withStyle(ChatFormatting.GRAY);
            Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().gui.getChat().addMessage(line));
        });
    }

    private static void translate(String text, Consumer<String> callback) {
        EXECUTOR.execute(() -> {
            String translation = null;
            try {
                translation = request(text);
            } catch (Exception exception) {
                LOGGER.warn("Failed to translate \"{}\"", text, exception);
            }
            callback.accept(translation);
        });
    }

    private static String request(String text) throws Exception {
        String params = "params.client=" + encode("gtx")
                + "&dataTypes=" + encode("TRANSLATION")
                + "&key=" + encode(API_KEY)
                + "&query.sourceLanguage=" + encode("auto")
                + "&query.targetLanguage=" + encode("en")
                + "&query.text=" + encode(text);

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + "?" + params))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            return null;
        }

        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        return json != null && json.has("translation") ? json.get("translation").getAsString() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase();
    }

    private static String singleLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
