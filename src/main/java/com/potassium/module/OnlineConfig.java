package com.potassium.module;

import com.potassium.Potassium;
import com.potassium.command.CommandManager;
import com.potassium.settings.StringSetting;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Loads a config from a URL into the running client.
 *
 * <p>Downloading and applying both happen off the render thread: a slow host must not stall a
 * frame, and applying a config touches every module. The file is limited in size so a wrong
 * link cannot pull an arbitrarily large response into memory. The module switches itself off
 * after applying, leaving the client on the downloaded config rather than a loader row.</p>
 */
public class OnlineConfig extends Module {

    private static final int MAX_BYTES = 512 * 1024;

    private final StringSetting urlSetting = new StringSetting("URL", "")
            .withPlaceholder("https://.../config.cfg");

    private final Minecraft mc = Minecraft.getMinecraft();

    private volatile boolean downloading;
    private volatile String downloaded;
    private volatile String failure;
    /** Bumped on every enable so a download started before a disable cannot apply late. */
    private volatile int generation;

    public OnlineConfig() {
        super("OnlineConfig", Category.CONFIGURATIONS);
        settings.add(urlSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void onEnable() {
        downloaded = null;
        failure = null;
        int request = ++generation;

        String url = urlSetting.getValue() == null ? "" : urlSetting.getValue().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            CommandManager.sendMessage("\u00a7cSet the Online Config URL to a direct .cfg link.");
            setEnabled(false);
            return;
        }

        downloading = true;
        Thread thread = new Thread(() -> {
            String text = null;
            String error = null;
            try {
                text = download(url);
                if (text == null || text.trim().isEmpty()) {
                    error = "The config file was empty.";
                } else if (text.contains("<!DOCTYPE html>") || text.contains("<html")) {
                    error = "That link returned a web page, not a config file.";
                }
            } catch (Throwable ignored) {
                error = "Could not download the config.";
            }
            if (request != generation) return;
            failure = error;
            if (error == null) downloaded = text;
            downloading = false;
        }, "Potassium-Config-Download");
        thread.setDaemon(true);
        thread.start();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled()) return;
        if (downloading) return;

        String error = failure;
        String text = downloaded;
        failure = null;
        downloaded = null;

        if (error != null) {
            CommandManager.sendMessage("\u00a7c" + error);
            setEnabled(false);
            return;
        }
        if (text == null) return;

        boolean applied = Potassium.INSTANCE != null
                && Potassium.INSTANCE.configManager != null
                && Potassium.INSTANCE.configManager.importText(text,
                        Potassium.INSTANCE.moduleManager, Potassium.INSTANCE.hudManager);
        if (applied) {
            CommandManager.sendMessage("\u00a7aOnline config applied. Previous config saved as potassium.cfg.bak.");
        } else {
            CommandManager.sendMessage("\u00a7cThe downloaded config could not be applied.");
        }
        setEnabled(false);
    }

    private String download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("User-Agent", "Potassium/" + Potassium.VERSION);
        if (connection.getResponseCode() != 200) {
            throw new IllegalStateException("HTTP " + connection.getResponseCode());
        }

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), Charset.forName("UTF-8")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
                if (body.length() > MAX_BYTES) {
                    throw new IllegalStateException("Config too large");
                }
            }
        }
        return body.toString();
    }

    @Override
    public void onDisable() {
        // Invalidate an in-flight request; a config fetched for the previous enable must not
        // be applied after the module was switched off and on again.
        generation++;
        downloading = false;
        downloaded = null;
        failure = null;
    }
}
