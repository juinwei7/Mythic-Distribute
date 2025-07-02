package org.weiwei.mythicDistribute.distribute;

import lombok.Getter;

import java.util.*;
import java.util.stream.IntStream;

@Getter
public class DamageCalculation {

    // 玩家對每個 Boss 的傷害紀錄
    private static final Map<UUID, Map<UUID, DamageCalculation>> bossDamageMap = new HashMap<>();
    private final UUID bossUUID;
    private final UUID playerUUID;
    private double maxDamage = 0;
    private double minDamage = Double.MAX_VALUE;
    private double totalDamage = 0;
    private int hitCount = 0;

    public DamageCalculation(UUID bossId, UUID playerId) {
        this.bossUUID = bossId;
        this.playerUUID = playerId;
    }

    /**
     * 增加玩家對 Boss 的傷害紀錄
     *
     * @param bossId   Boss UUID
     * @param playerId 玩家 UUID
     * @param damage   傷害值
     */
    public static void addDamage(UUID bossId, UUID playerId, double damage) {
        bossDamageMap.putIfAbsent(bossId, new HashMap<>());
        Map<UUID, DamageCalculation> playerDamageMap = bossDamageMap.get(bossId);
        DamageCalculation damCal = playerDamageMap.getOrDefault(playerId, new DamageCalculation(bossId, playerId));
        damCal.totalDamage += damage;
        damCal.hitCount++;
        if (damCal.maxDamage < damage) damCal.maxDamage = damage;
        if (damage < damCal.minDamage) damCal.minDamage = damage;
        playerDamageMap.put(playerId, damCal);
    }

    /**
     * 獲取玩家對 Boss 的總傷害
     *
     * @param playerId 玩家 UUID
     * @param bossId   Boss UUID
     * @return 傷害值
     */
    public static double getTotalDamage(UUID playerId, UUID bossId) {
        DamageCalculation cal = getPlayerDamageInfo(playerId, bossId);
        return cal == null ? 0.0 : cal.getTotalDamage();
    }

    /**
     * 獲取玩家對 Boss 的資訊
     *
     * @param playerId 玩家 UUID
     * @param bossId   Boss UUID
     * @return DamageCalculation 對象
     */
    public static DamageCalculation getPlayerDamageInfo(UUID playerId, UUID bossId) {
        return bossDamageMap.getOrDefault(bossId, new HashMap<>()).get(playerId);
    }

    /**
     * 清除某個 Boss 的所有傷害紀錄
     *
     * @param bossId Boss UUID
     */
    public static void clearDamage(UUID bossId) {
        bossDamageMap.remove(bossId);
    }

    /**
     * 進行排名計算 （並回傳總傷害）
     *
     * @param bossId Boss UUID
     *               返回按傷害排序的玩家 UUID 列表
     */
    public static List<DamageCalculation> calculateRankings(UUID bossId) {
        Map<UUID, DamageCalculation> playerDamageMap = bossDamageMap.getOrDefault(bossId, new HashMap<>());
        List<DamageCalculation> rankings = new ArrayList<>(playerDamageMap.values());
        rankings.sort((a, b) -> Double.compare(b.getTotalDamage(), a.getTotalDamage()));
        return rankings;
    }

    /**
     * 獲取平均傷害
     *
     * @return 平均每次攻擊的傷害
     */
    public double getAverageDamage() {
        return hitCount == 0 ? 0.0 : totalDamage / hitCount;
    }

    /**
     * 獲取實際最小傷害（如果從未攻擊過，回傳 0）
     */
    public double getMinDamage() {
        return hitCount == 0 ? 0.0 : minDamage;
    }

    /**
     * 獲得排名
     */
    public int getRank() {
        List<DamageCalculation> rankings = calculateRankings(this.bossUUID);
        return IntStream.range(0, rankings.size())
                .filter(i -> rankings.get(i).getPlayerUUID().equals(this.playerUUID))
                .findFirst()
                .orElse(-2) + 1; // 若未找到返回 -1
    }

    /**
     * 獲取參與度（對生物造成總百分比傷害）
     */
    public double getParticipation() {
        List<DamageCalculation> rankings = calculateRankings(this.bossUUID);
        if (rankings.isEmpty()) return 0.0;
        double totalDamage = rankings.stream().mapToDouble(DamageCalculation::getTotalDamage).sum();
        return totalDamage == 0 ? 0.0 : (this.totalDamage / totalDamage) * 100;
    }
}
