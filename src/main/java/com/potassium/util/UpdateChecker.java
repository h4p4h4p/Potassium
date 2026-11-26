package com.potassium.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.potassium.Potassium;
import com.potassium.command.CommandManager;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Checks the GitHub release feed once per game launch.
 *
 * The request runs on a daemon thread; a release server being slow or unreachable must never
 * stall the client tick. The result is only turned into a chat message from the client thread,
 * and only once, so a missing player or a later world join cannot lose or repeat it.
 */
public final class UpdateChecker {

    private static final String LATEST_RELEASE =
            "https://api.github.com/repos/h4p4h4p/Potassium/releases/latest";

    private static volatile boolean started;
    private static volatile String notice;
    private static boolean delivered;

    private UpdateChecker() {
    }

    public static void start() {
        if (started) return;
        started = true;

        Thread thread = new Thread(() -> {
            try {
                notice = check();
            } catch (Throwable ignored) {
                // Offline, rate-limited or a changed response: an update check is never worth
                // an error in chat or a logged stack trace.
            }
        }, "Potassium-Update-Check");
        thread.setDaemon(true);
        thread.start();
    }

    /** Delivers a result found by the background thread, once the player can see chat. */
    public static void tick() {
        if (notice == null || delivered) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        delivered = true;
        CommandManager.sendMessage(notice);
    }

    private static String check() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("User-Agent", "Potassium/" + Potassium.VERSION);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        if (connection.getResponseCode() != 200) return null;

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), Charset.forName("UTF-8")))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }

        JsonObject release = new JsonParser().parse(body.toString()).getAsJsonObject();
        String tag = release.has("tag_name") ? release.get("tag_name").getAsString() : null;
        String url = release.has("html_url") ? release.get("html_url").getAsString() : null;
        if (tag == null || url == null) return null;
        if (!isNewer(tag, Potassium.VERSION)) return null;

        return "\u00a7ePotassium \u00a7f" + tag + " \u00a77is available: \u00a7b" + url;
    }

    /**
     * Compares the numeric part of a release tag with the running version. A suffix such as
     * "-beta" only matters after the numbers, and this client ships betas, so it is ignored.
     */
    private static boolean isNewer(String remote, String current) {
        int[] remoteParts = numbers(remote);
        int[] currentParts = numbers(current);
        if (remoteParts.length == 0 || currentParts.length == 0) return false;

        int length = Math.max(remoteParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int a = i < remoteParts.length ? remoteParts[i] : 0;
            int b = i < currentParts.length ? currentParts[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] numbers(String version) {
        if (version == null) return new int[0];
        String cleaned = version.replaceFirst("^[^0-9]*", "").replaceFirst("[^0-9.].*$", "");
        if (cleaned.isEmpty()) return new int[0];
        String[] parts = cleaned.split("\\.");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                values[i] = 0;
            }
        }
        return values;
    }
}
