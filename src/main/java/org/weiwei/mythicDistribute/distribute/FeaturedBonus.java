package org.weiwei.mythicDistribute.distribute;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.weiwei.mythicDistribute.Main;
import org.weiwei.mythicDistribute.entity.RewardDistribution;
import org.weiwei.mythicDistribute.util.ColorConversion;
import org.weiwei.mythicDistribute.util.Config;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 每週加成副本：本週是哪座由大廳 huanlanCore 輪替，這裡讀 %huanlan-core_featured_dungeon_id%（代號同 daily_first.group）。
 * - 加成副本的每日首殺加碼跑 featured.daily_first_repeat 次
 * - 每人每天每座副本前 featured.daily_cap 次擊殺（排名獎或傷害達參與門檻）多發 featured.groups.<group>.command
 * 讀不到（沒裝 PlaceholderAPI、huanlanCore 沒載入、本週沒有加成）就當作沒有加成，原本的獎勵照發。
 * 次數記在本分流的 featured-daily.yml（同 daily-first.yml，台灣時間換日清空，重啟不會重複發）。
 */
final class FeaturedBonus {

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final String FILE_NAME = "featured-daily.yml";

    private static LocalDate date;
    private static final Map<String, Map<UUID, Integer>> countByGroup = new HashMap<>();

    private FeaturedBonus() {
    }

    static void load() {
        countByGroup.clear();
        date = LocalDate.now(ZONE);
        File file = file();
        if (!file.isFile()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (!date.toString().equals(yaml.getString("date"))) return; // 檔案是前幾天的，今天從頭算
        ConfigurationSection counts = yaml.getConfigurationSection("counts");
        if (counts == null) return;
        for (String group : counts.getKeys(false)) {
            ConfigurationSection players = counts.getConfigurationSection(group);
            if (players == null) continue;
            Map<UUID, Integer> map = new HashMap<>();
            for (String uuid : players.getKeys(false)) {
                try {
                    map.put(UUID.fromString(uuid), players.getInt(uuid));
                } catch (IllegalArgumentException ignored) {
                    // 壞掉的紀錄略過
                }
            }
            countByGroup.put(group, map);
        }
    }

    private static ConfigurationSection section() {
        return Config.getConfig().getConfigurationSection("featured");
    }

    /** 本週加成副本代號；沒有加成回傳 null */
    static String currentGroup() {
        ConfigurationSection section = section();
        if (section == null || !section.getBoolean("enabled", false)) return null;
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return null;
        String value;
        try {
            value = PlaceholderHook.parse(section.getString("placeholder", "%huanlan-core_featured_dungeon_id%"));
        } catch (Throwable throwable) {
            return null;
        }
        if (value == null) return null;
        value = value.trim();
        // 沒有加成是空字串；huanlanCore 沒載入時 PlaceholderAPI 會原樣回傳 %…%
        return value.isEmpty() || value.contains("%") ? null : value;
    }

    /** 每日首殺加碼要跑幾次：加成副本 daily_first_repeat 次，其他 1 次 */
    static int dailyFirstRepeat(String group) {
        if (group == null || !group.equals(currentGroup())) return 1;
        return Math.max(1, section().getInt("daily_first_repeat", 2));
    }

    /** 加成副本的 BOSS 倒下時，符合資格的在線玩家每天前幾次多發一份 */
    static void handle(RewardDistribution distribution, List<DamageCalculation> damageList, Set<UUID> topRewarded) {
        RewardDistribution.DailyFirst dailyFirst = distribution.getDailyFirst();
        if (dailyFirst == null || dailyFirst.getGroup() == null) return;
        String group = dailyFirst.getGroup();
        if (!group.equals(currentGroup())) return;

        ConfigurationSection section = section();
        List<String> commands = section.getStringList("groups." + group + ".command");
        if (commands.isEmpty()) return;
        int cap = Math.max(1, section.getInt("daily_cap", 3));
        String reward = section.getString("groups." + group + ".reward", "");
        String message = section.getString("message", "");

        RewardDistribution.ParticipantsReward pr = distribution.getStrategy().getParticipants();
        for (DamageCalculation dc : damageList) {
            UUID playerId = dc.getPlayerUUID();
            boolean eligible = topRewarded.contains(playerId) || (pr != null && dc.getTotalDamage() >= pr.getMin_damage());
            if (!eligible) continue;

            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;
            int count = tryCount(group, playerId, cap);
            if (count == 0) continue;

            for (String cmd : commands) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
            }
            if (message != null && !message.isBlank()) {
                player.sendMessage(ColorConversion.translateHexCodes(message
                        .replace("%count%", String.valueOf(count))
                        .replace("%cap%", String.valueOf(cap))
                        .replace("%reward%", reward)));
            }
            Main.getInst().getLogger().info("本週加成：" + player.getName() + "（" + group + "）今天第 " + count + "/" + cap + " 次");
        }
    }

    /** 還沒到上限就記一次並回傳今天第幾次；已到上限回傳 0 */
    private static int tryCount(String group, UUID playerId, int cap) {
        LocalDate today = LocalDate.now(ZONE);
        if (!today.equals(date)) {
            date = today;
            countByGroup.clear();
        }
        Map<UUID, Integer> counts = countByGroup.computeIfAbsent(group, key -> new HashMap<>());
        int current = counts.getOrDefault(playerId, 0);
        if (current >= cap) return 0;
        counts.put(playerId, current + 1);
        save();
        return current + 1;
    }

    private static void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("date", date.toString());
        for (Map.Entry<String, Map<UUID, Integer>> group : countByGroup.entrySet()) {
            for (Map.Entry<UUID, Integer> entry : group.getValue().entrySet()) {
                yaml.set("counts." + group.getKey() + "." + entry.getKey(), entry.getValue());
            }
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

    /** 只有確定 PlaceholderAPI 已啟用才會載入這個類別，沒裝也不會 NoClassDefFoundError */
    private static final class PlaceholderHook {
        static String parse(String text) {
            return PlaceholderAPI.setPlaceholders((OfflinePlayer) null, text);
        }
    }
}
