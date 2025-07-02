package org.weiwei.mythicDistribute.entity;

import org.bukkit.configuration.file.YamlConfiguration;
import org.weiwei.mythicDistribute.Main;

import java.io.File;
import java.util.*;

public class RewardDistributionManager {

    // 獎勵分配管理器
    private static final Map<String, RewardDistribution> bossRewardMap = new HashMap<>();

    // 載入配置
    private static void loadConfig() {
        File file = new File(Main.getInst().getDataFolder(), "RewardDistribution.yml");
        if (!file.exists()) {
            Main.getInst().getLogger().info("Create RewardDistribution.yml");
            Main.getInst().saveResource("RewardDistribution.yml", true);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Set<String> keys = yaml.getConfigurationSection("boss_rewards").getKeys(false);
        for (String bossId : keys) {
            RewardDistribution config = new RewardDistribution();

            config.setName(yaml.getString("boss_rewards." + bossId + ".name"));
            config.setBroadcast(yaml.getBoolean("boss_rewards." + bossId + ".broadcast", false));
            config.setDiscord(yaml.getBoolean("boss_rewards." + bossId + ".discord", false));
            RewardDistribution.Strategy strategy = new RewardDistribution.Strategy();

            // TOP 區塊
            List<RewardDistribution.RewardGroup> topList = new ArrayList<>();
            if (yaml.contains("boss_rewards." + bossId + ".strategy.top")) {
                Set<String> topKeys = yaml.getConfigurationSection("boss_rewards." + bossId + ".strategy.top").getKeys(false);
                for (String rankKey : topKeys) {
                    try {
                        int rank = Integer.parseInt(rankKey);
                        RewardDistribution.RewardGroup group = new RewardDistribution.RewardGroup();
                        group.setCommand(yaml.getStringList("boss_rewards." + bossId + ".strategy.top." + rankKey + ".command"));
                        group.setRank(rank);
                        topList.add(group);
                    } catch (NumberFormatException e) {
                        Main.getInst().getLogger().warning("無法解析 boss_rewards." + bossId + ".strategy.top." + rankKey + " 為整數，已略過。");
                    }
                }
                // 排序（升序）
                topList.sort(Comparator.comparingInt(RewardDistribution.RewardGroup::getRank));
            }
            strategy.setTop(topList);

            // PARTICIPANTS 區塊
            if (yaml.contains("boss_rewards." + bossId + ".strategy.participants")) {
                RewardDistribution.ParticipantsReward pr = new RewardDistribution.ParticipantsReward();
                pr.setMin_damage(yaml.getInt("boss_rewards." + bossId + ".strategy.participants.min_damage"));
                pr.setCommand(yaml.getStringList("boss_rewards." + bossId + ".strategy.participants.command"));
                strategy.setParticipants(pr);
            }

            config.setStrategy(strategy);
            bossRewardMap.put(bossId, config);
            Main.getInst().getLogger().info("載入獎勵配置成功: " + bossId);
        }
    }

    // 重新載入
    public static void reload() {
        bossRewardMap.clear();
        loadConfig();
    }

    // 判斷是否有獎勵配置
    public static boolean hasReward(String bossId) {
        return bossRewardMap.containsKey(bossId);
    }

    // 獲取獎勵配置
    public static RewardDistribution getReward(String bossId) {
        return bossRewardMap.get(bossId);
    }

    // 安全獲取不可修改的 top list
    public static List<RewardDistribution.RewardGroup> getTopRewards(String bossId) {
        RewardDistribution config = bossRewardMap.get(bossId);
        if (config == null) return Collections.emptyList();
        return Collections.unmodifiableList(config.getStrategy().getTop());
    }
}
