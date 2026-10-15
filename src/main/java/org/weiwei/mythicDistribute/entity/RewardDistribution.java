package org.weiwei.mythicDistribute.entity;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RewardDistribution {

    private String name;            // 顯示名稱
    private boolean broadcast;      // 是否廣播
    private boolean discord;        // 是否發送到 Discord
    private List<DropReward> drop = new ArrayList<>(); // BOSS 死亡掉落
    private Strategy strategy;      // 獎勵策略
    private DailyFirst dailyFirst;  // 每日首殺加碼（沒設定就是 null）
    private Party party;            // 一起打的人（沒設定就是 null）

    @Data
    public static class Strategy {
        private List<RewardGroup> top = new ArrayList<>();
        private ParticipantsReward participants;
    }

    @Data
    public static class RewardGroup {
        private int rank; // 排名
        private List<String> command = new ArrayList<>();
    }

    @Data
    public static class ParticipantsReward {
        private int min_damage;
        private List<String> command = new ArrayList<>();
    }

    /**
     * 每日首殺加碼：同一個 group（通常是同一座副本的主王、進化型、隱藏王）每人每天只領一次，
     * 條件跟參與獎相同（傷害達 min_damage，或拿到排名獎），台灣時間午夜重置。
     */
    @Data
    public static class DailyFirst {
        private String group;
        private List<String> command = new ArrayList<>();
        private String message;
    }

    /**
     * 一起打的人：前 3 名加上總傷害達 min_damage 的在線玩家，整場只送一次名單。
     * 指令裡的 %players% 換成空白分隔的玩家名（傷害高的在前），%boss% 換成怪物 ID。
     * 用途：新手任務 BOSS 步驟「傷害夠就算」（task credit）、帶新人獎勵（ref admin party）。
     */
    @Data
    public static class Party {
        private int min_damage;
        private List<String> command = new ArrayList<>();
    }

    @Data
    public static class DropReward {
        private String itemId;
        private double chance;
    }
}
