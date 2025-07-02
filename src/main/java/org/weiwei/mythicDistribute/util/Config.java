package org.weiwei.mythicDistribute.util;


import lombok.Getter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.weiwei.mythicDistribute.Main;

import java.io.File;

public class Config {

    public static final String DISCORD_CHANNEL = "DISCORD_CHANNEL.CHANNEL_ID";


    @Getter
    private static YamlConfiguration config;


    public static void loadConfig() {
        File file = new File(Main.getInst().getDataFolder(), "Config.yml");
        if (!file.exists()) {
            Main.getInst().getLogger().info("Create Config.yml");
            Main.getInst().saveResource("Config.yml", true);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

}
