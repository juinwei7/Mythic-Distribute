package org.weiwei.mythicDistribute.distribute;

import org.bukkit.configuration.file.YamlConfiguration;
import org.weiwei.mythicDistribute.Main;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 每日首殺紀錄：記錄今天每個 group（副本）哪些玩家已領過加碼，台灣時間換日就清空。
 * 每座副本的 BOSS 只出現在自己的分流，所以記在本分流的 daily-first.yml 就夠了；寫檔是為了分流當天重啟不會重複發。
 */
final class DailyFirstTracker {

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final String FILE_NAME = "daily-first.yml";

    private static LocalDate date;
    private static final Map<String, Set<UUID>> claimedByGroup = new HashMap<>();

    private DailyFirstTracker() {
    }

    static void load() {
        claimedByGroup.clear();
        date = LocalDate.now(ZONE);
        File file = file();
        if (!file.isFile()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (!date.toString().equals(yaml.getString("date"))) return; // 檔案是前幾天的，今天從頭算
        if (yaml.getConfigurationSection("claimed") == null) return;
        for (String group : yaml.getConfigurationSection("claimed").getKeys(false)) {
            Set<UUID> players = new HashSet<>();
            for (String uuid : yaml.getStringList("claimed." + group)) {
                try {
                    players.add(UUID.fromString(uuid));
                } catch (IllegalArgumentException ignored) {
                    // 壞掉的紀錄略過
                }
            }
            claimedByGroup.put(group, players);
        }
    }

    /** 這位玩家今天還沒領過這個 group 就記下並回傳 true；已領過回傳 false。 */
    static boolean tryClaim(String group, UUID playerId) {
        LocalDate today = LocalDate.now(ZONE);
        if (!today.equals(date)) {
            date = today;
            claimedByGroup.clear();
        }
        boolean added = claimedByGroup.computeIfAbsent(group, key -> new HashSet<>()).add(playerId);
        if (added) save();
        return added;
    }

    private static void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("date", date.toString());
        for (Map.Entry<String, Set<UUID>> entry : claimedByGroup.entrySet()) {
            yaml.set("claimed." + entry.getKey(), entry.getValue().stream().map(UUID::toString).toList());
        }
        try {
            yaml.save(file());
        } catch (IOException exception) {
            Main.getInst().getLogger().warning("無法寫入 " + FILE_NAME + "：" + exception.getMessage());
        }
    }

    private static File file() {
        return new File(Main.getInst().getDataFolder(), FILE_NAME);
    }
}
