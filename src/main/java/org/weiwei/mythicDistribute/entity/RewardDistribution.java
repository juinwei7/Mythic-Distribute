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
    private Strategy strategy;      // 獎勵策略

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
}
