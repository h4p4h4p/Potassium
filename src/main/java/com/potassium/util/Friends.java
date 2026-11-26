package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Names the client must never attack.
 *
 * The list lives beside the normal config, one name per line, because it is shared by every
 * profile and outlives module settings. Matching is case-insensitive: Minecraft names are
 * unique that way, and a name copied from chat may have different capitalisation.
 */
public final class Friends {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Set<String> NAMES = new LinkedHashSet<>();

    private Friends() {
    }

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/potassium-friends.txt");
    }

    public static void load() {
        NAMES.clear();
        File file = file();
        if (!file.exists()) return;
        try {
            for (String line : Files.readAllLines(file.toPath(), Charset.forName("UTF-8"))) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                if (VALID_NAME.matcher(name).matches()) NAMES.add(name.toLowerCase(Locale.ROOT));
            }
        } catch (IOException ignored) {
        }
    }

    public static void save() {
        File file = file();
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            List<String> lines = new ArrayList<>(NAMES);
            Collections.sort(lines);
            Files.write(file.toPath(), lines, Charset.forName("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    /** True when the entity is a player on the friend list. */
    public static boolean isFriend(EntityPlayer player) {
        return player != null && NAMES.contains(player.getName().toLowerCase(Locale.ROOT));
    }

    /** Adds a name; returns false when the name is invalid or already listed. */
    public static boolean add(String name) {
        String clean = clean(name);
        if (clean == null) return false;
        boolean added = NAMES.add(clean.toLowerCase(Locale.ROOT));
        if (added) save();
        return added;
    }

    /** Removes a name; returns false when it was not listed. */
    public static boolean remove(String name) {
        String clean = clean(name);
        if (clean == null) return false;
        boolean removed = NAMES.remove(clean.toLowerCase(Locale.ROOT));
        if (removed) save();
        return removed;
    }

    public static void clear() {
        NAMES.clear();
        save();
    }

    public static List<String> getNames() {
        List<String> names = new ArrayList<>(NAMES);
        Collections.sort(names);
        return names;
    }

    public static boolean isValidName(String name) {
        return clean(name) != null;
    }

    private static String clean(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        return VALID_NAME.matcher(trimmed).matches() ? trimmed : null;
    }
}
