package com.tinsl.discordrpc.core;

import com.tinsl.discordrpc.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaceholderEngine {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(\\w+)}");

    private final ModConfig config;
    private final Map<String, PlaceholderResolver> resolvers = new HashMap<>();

    @FunctionalInterface
    private interface PlaceholderResolver {
        String resolve();
    }

    public PlaceholderEngine(ModConfig config) {
        this.config = config;
        registerDefaults();
    }

    private void registerDefaults() {
        Minecraft mc = Minecraft.getInstance();

        resolvers.put("player", () -> {
            LocalPlayer p = mc.player;
            return p != null ? p.getName().getString() : "Steve";
        });

        resolvers.put("server", () -> {
            if (config.isHideServerIp()) return "A Server";
            ServerData data = mc.getCurrentServer();
            if (data != null) return data.name;
            if (mc.getSingleplayerServer() != null) return "Singleplayer";
            return "Unknown";
        });

        resolvers.put("server_ip", () -> {
            if (config.isHideServerIp()) return "Hidden";
            ServerData data = mc.getCurrentServer();
            return data != null ? data.ip : "localhost";
        });

        resolvers.put("dimension", () -> {
            ClientLevel level = mc.level;
            if (level == null) return "Unknown";
            Identifier key = level.dimension().identifier();
            return switch (key.getPath()) {
                case "overworld" -> "Overworld";
                case "the_nether" -> "The Nether";
                case "the_end" -> "The End";
                default -> formatId(key.getPath());
            };
        });

        resolvers.put("biome", () -> {
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) return "Unknown";
            BlockPos pos = p.blockPosition();
            Holder<Biome> biomeHolder = mc.level.getBiome(pos);
            return biomeHolder.unwrapKey()
                    .map(k -> formatId(k.identifier().getPath()))
                    .orElse("Unknown");
        });

        resolvers.put("health", () -> {
            LocalPlayer p = mc.player;
            return p != null ? String.valueOf(Math.round(p.getHealth())) : "20";
        });

        resolvers.put("max_health", () -> {
            LocalPlayer p = mc.player;
            return p != null ? String.valueOf(Math.round(p.getMaxHealth())) : "20";
        });

        resolvers.put("hunger", () -> {
            LocalPlayer p = mc.player;
            return p != null ? String.valueOf(p.getFoodData().getFoodLevel()) : "20";
        });

        resolvers.put("x", () -> {
            if (config.isHideCoordinates()) return "???";
            LocalPlayer p = mc.player;
            return p != null ? String.valueOf(Math.round(p.getX())) : "0";
        });

        resolvers.put("y", () -> {
            if (config.isHideCoordinates()) return "???";
            LocalPlayer p = mc.player;
            return p != null ? String.valueOf(Math.round(p.getY())) : "0";
        });

        resolvers.put("z", () -> {
            if (config.isHideCoordinates()) return "???";
            LocalPlayer p = mc.player;
            return p != null ? String.valueOf(Math.round(p.getZ())) : "0";
        });

        resolvers.put("world", () -> {
            if (mc.getSingleplayerServer() != null) {
                return mc.getSingleplayerServer().getWorldData().getLevelName();
            }
            ServerData data = mc.getCurrentServer();
            return data != null ? data.name : "Unknown";
        });

        resolvers.put("online", () -> {
            ClientPacketListener handler = mc.getConnection();
            if (handler != null) {
                return String.valueOf(handler.getOnlinePlayers().size());
            }
            return "0";
        });

        resolvers.put("max_players", () -> {
            if (mc.getSingleplayerServer() != null) {
                return String.valueOf(mc.getSingleplayerServer().getMaxPlayers());
            }
            ClientPacketListener handler = mc.getConnection();
            if (handler != null) {
                return String.valueOf(handler.getOnlinePlayers().size());
            }
            return "0";
        });

        resolvers.put("fps", () -> String.valueOf(mc.getFps()));

        resolvers.put("version", () -> {
            try {
                return net.minecraft.SharedConstants.getCurrentVersion().name();
            } catch (Throwable t) {
                return "26.1";
            }
        });

        resolvers.put("day", () -> {
            ClientLevel level = mc.level;
            return level != null ? String.valueOf(level.getOverworldClockTime() / 24000L) : "0";
        });

        resolvers.put("difficulty", () -> {
            ClientLevel level = mc.level;
            if (level == null) return "Unknown";
            return level.getDifficulty().getDisplayName().getString();
        });

        resolvers.put("gamemode", () -> {
            if (mc.gameMode == null) return "Unknown";
            return switch (mc.gameMode.getPlayerMode()) {
                case SURVIVAL -> "Survival";
                case CREATIVE -> "Creative";
                case ADVENTURE -> "Adventure";
                case SPECTATOR -> "Spectator";
            };
        });

        resolvers.put("held_item", () -> {
            LocalPlayer p = mc.player;
            if (p == null) return "Nothing";
            ItemStack stack = p.getMainHandItem();
            return stack.isEmpty() ? "Nothing" : stack.getHoverName().getString();
        });

        resolvers.put("xp_level", () -> {
            LocalPlayer p = mc.player;
            return p != null ? String.valueOf(p.experienceLevel) : "0";
        });

        resolvers.put("ping", () -> {
            LocalPlayer p = mc.player;
            ClientPacketListener handler = mc.getConnection();
            if (p != null && handler != null) {
                var info = handler.getPlayerInfo(p.getUUID());
                return info != null ? String.valueOf(info.getLatency()) : "0";
            }
            return "0";
        });

        resolvers.put("weather", () -> {
            ClientLevel level = mc.level;
            if (level == null) return "Clear";
            if (level.isThundering()) return "Thunder";
            if (level.isRaining()) return "Rain";
            return "Clear";
        });

        resolvers.put("time", () -> {
            ClientLevel level = mc.level;
            if (level == null) return "0:00";
            long time = level.getOverworldClockTime() % 24000;
            int hours = (int) ((time / 1000 + 6) % 24);
            int minutes = (int) ((time % 1000) * 60 / 1000);
            return String.format("%d:%02d", hours, minutes);
        });

        resolvers.put("armor", () -> {
            LocalPlayer p = mc.player;
            return p != null ? String.valueOf(p.getArmorValue()) : "0";
        });

        resolvers.put("modcount", () -> String.valueOf(ModList.get().size()));
    }

    public String resolve(String template) {
        if (template == null || template.isEmpty()) return "";

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase();
            PlaceholderResolver resolver = resolvers.get(key);
            String replacement = resolver != null ? resolver.resolve() : "{" + key + "}";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private static String formatId(String id) {
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }
}
