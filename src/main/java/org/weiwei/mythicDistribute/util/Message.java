package org.weiwei.mythicDistribute.util;


import lombok.Getter;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.weiwei.mythicDistribute.Main;

import java.io.File;
import java.text.MessageFormat;
import java.util.List;

public enum Message {

    MESSAGE__PREFIX,
    MESSAGE__RELOAD,
    MESSAGE__SUB_NOT_FOUND,
    MESSAGE__NO_PERMISSION,
    MESSAGE__UNKNOWN_COMMAND,


    SUB_COMMAND__RELOAD,

    SEND_PRIZES__PLAYER__TOP,
    SEND_PRIZES__PLAYER__PARTICIPANTS,
    SEND_PRIZES__BROADCAST__KILL,

    ;


    @Getter
    private static YamlConfiguration messages;

    @Getter
    private static Tool tool = new Tool();

    /**
     * 加載Message類
     */
    public static void loadMessage() {
        File file = new File(Main.getInst().getDataFolder(), "Message.yml");

        if (!file.exists()) {
            Main.getInst().getLogger().info("Create Message.yml");
            Main.getInst().saveResource("Message.yml", true);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        tool.setConfig(messages);
    }

    /**
     * 讀取訊息
     */
    public static String getMsg(Message loc, Object... args) {
        return tool.getString(loc.toString(), args);
    }
    /**
     * 讀取List
     */
    public static List<String> getStringList(Message loc, Object... args) {
        return tool.getStringList(loc.toString(), args);
    }

    /**
     * 發送訊息給玩家
     */
    public static void send(LivingEntity entity, Message loc, Object... args) {
        tool.send(entity, loc.toString(), args);
    }

    /**
     * 發送ActionBar訊息給玩家
     */
    public static void sendActionBar(LivingEntity entity, Message loc, Object... args) {
        String message = getMsg(loc, args);
        if (entity instanceof Player player){
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
        } else {
            entity.sendMessage(ColorConversion.translateHexCodes(message));
        }
    }

    /**
     * 發送List訊息給玩家
     */
    public static void sendList(LivingEntity entity, Message loc, Object... args) {
        List<String> list = tool.getStringList(loc.toString(), args);
        list.forEach(entity::sendMessage);
    }


    /**
     * 發送訊息給玩家(帶前綴)
     */
    public static void sendWithPrefix(CommandSender entity, Message loc, Object... args) {
        String prefix = getMsg(MESSAGE__PREFIX);
        String message = getMsg(loc, args);
        entity.sendMessage(ColorConversion.translateHexCodes(prefix + message));
    }

    public static void send(CommandSender entity, Message loc, Object... args) {
        String message = getMsg(loc, args);
        entity.sendMessage(ColorConversion.translateHexCodes(message));
    }


    public static class Tool {
        YamlConfiguration configuration;

        // 加載設定
        public void setConfig(YamlConfiguration configuration) {
            this.configuration = configuration;
        }

        // 讀取訊息
        public String getString(String loc, Object... args) {
            String path = getStringLoc(loc);
            String raw = configuration.getString(path);
            if (raw == null) return "§c" + loc + " §7is not found";
            return ColorConversion.translateHexCodes(MessageFormat.format(raw, args));
        }

        // 讀取List
        public List<String> getStringList(String loc, Object... args) {
            String path = getStringLoc(loc);
            List<String> raw = configuration.getStringList(path);
            if (raw == null || raw.isEmpty()) return List.of("§c" + loc + " §7is not found");
            return raw.stream()
                    .map(s -> ColorConversion.translateHexCodes(MessageFormat.format(s, args)))
                    .toList();
        }

        // 發送訊息給玩家
        public void send(LivingEntity entity, String loc, Object... args) {
            String path = getStringLoc(loc);
            String raw = configuration.getString(path);
            if (raw == null) return;
            entity.sendMessage(ColorConversion.translateHexCodes(MessageFormat.format(raw, args)));
        }

        private String getStringLoc(String loc) {
            return loc.replaceAll("__", ".");
        }


    }
}
