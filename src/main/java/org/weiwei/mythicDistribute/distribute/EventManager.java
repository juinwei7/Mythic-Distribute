package org.weiwei.mythicDistribute.distribute;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import io.lumine.mythic.core.items.MythicItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.weiwei.mythicDistribute.Main;
import org.weiwei.mythicDistribute.entity.RewardDistribution;
import org.weiwei.mythicDistribute.entity.RewardDistributionManager;
import org.weiwei.mythicDistribute.util.ColorConversion;
import org.weiwei.mythicDistribute.util.Config;
import org.weiwei.mythicDistribute.util.Message;
import org.weiwei.playerdata.api.PlayerDataRegistry;
import org.weiwei.playerdata.api.service.DiscordHookService;
import org.weiwei.playerdata.api.service.PlayerDataService;

import java.awt.*;
import java.util.*;
import java.util.List;


public class EventManager implements Listener {

    public static void register() {
        Bukkit.getPluginManager().registerEvents(new EventManager(), Main.getInst());
    }

    static DiscordHookService discordHookService  = PlayerDataRegistry.getDiscordHookService();

    // 玩家對 Boss 傷害時紀錄
    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (!(event.getDamager() instanceof Player player)) return;

        // 如果不是 MythicMob，則不處理
        boolean isMythicMob = MythicBukkit.inst().getMobManager().isMythicMob(target);
        if (!isMythicMob) return;

        Optional<ActiveMob> opt = MythicBukkit.inst().getMobManager().getActiveMob(target.getUniqueId());
        if (opt.isEmpty()) return;
        ActiveMob activeMob = opt.get();
        String bossId = activeMob.getType().getInternalName();

        if (!RewardDistributionManager.hasReward(bossId)) return;

        // 儲存傷害
        DamageCalculation.addDamage(activeMob.getUniqueId(), player.getUniqueId(), event.getFinalDamage());
    }

    // BOSS 死亡事件
    @EventHandler
    public void onMythicMobDeath(MythicMobDeathEvent event) {
        ActiveMob mob = event.getMob();
        String mobId = mob.getType().getInternalName();

        // 沒有配置獎勵的 BOSS 就跳過
        if (!RewardDistributionManager.hasReward(mobId)) return;

        RewardDistribution distribution = RewardDistributionManager.getReward(mobId);
        List<RewardDistribution.RewardGroup> topList = distribution.getStrategy().getTop();

        handleDrops(event, distribution);

        // 傷害排名列表
        List<DamageCalculation> damageList = DamageCalculation.calculateRankings(mob.getUniqueId());
        if (damageList.isEmpty()) return;

        // 已得獎的玩家 UUID 清單（避免重複得獎）
        Set<UUID> rewarded = new HashSet<>();

        // 分發 top 排名獎勵
        for (RewardDistribution.RewardGroup rewardGroup : topList) {
            int rank = rewardGroup.getRank();
            if (damageList.size() >= rank) {
                DamageCalculation dc = damageList.get(rank - 1); // 排名從 1 開始
                UUID playerId = dc.getPlayerUUID();
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    for (String cmd : rewardGroup.getCommand()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
                    }
                    rewarded.add(playerId); // 加入已領獎清單
                    sendMessage(player, Message.SEND_PRIZES__PLAYER__TOP, distribution, dc);
                }
            }
        }

        // 分發參加獎勵
        RewardDistribution.ParticipantsReward pr = distribution.getStrategy().getParticipants();
        if (pr != null && pr.getCommand() != null && !pr.getCommand().isEmpty()) {
            for (DamageCalculation dc : damageList) {
                UUID playerId = dc.getPlayerUUID();
                if (rewarded.contains(playerId)) continue; // 跳過已得 top 獎的玩家
                if (dc.getTotalDamage() >= pr.getMin_damage()) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        for (String cmd : pr.getCommand()) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
                        }
                        sendMessage(player, Message.SEND_PRIZES__PLAYER__PARTICIPANTS, distribution, dc);
                    }
                }
            }
        }

        // 發送廣播訊息
        Player killPlayer = event.getKiller() instanceof Player ? (Player) event.getKiller() : null;
        String killPlayerName = killPlayer != null ? killPlayer.getName() : "未知玩家";
        handleBossResult(Message.SEND_PRIZES__BROADCAST__KILL, killPlayerName, distribution, damageList, distribution.isBroadcast(), distribution.isDiscord());

        // 最後清除紀錄
        DamageCalculation.clearDamage(mob.getUniqueId());
    }

    private void handleDrops(MythicMobDeathEvent event, RewardDistribution distribution) {
        if (distribution.getDrop() == null || distribution.getDrop().isEmpty()) return;

        for (RewardDistribution.DropReward drop : distribution.getDrop()) {
            if (drop.getItemId() == null || drop.getItemId().isBlank()) continue;
            if (Math.random() > drop.getChance()) continue;

            Optional<MythicItem> mythicItem = MythicBukkit.inst().getItemManager().getItem(drop.getItemId());
            if (mythicItem.isEmpty()) {
                Main.getInst().getLogger().warning("找不到 MythicMobs 物品: " + drop.getItemId());
                continue;
            }

            ItemStack itemStack = BukkitAdapter.adapt(mythicItem.get().generateItemStack(1));
            if (itemStack != null) {
                event.getDrops().add(itemStack);
            }
        }
    }

    // 發送玩家獎勵訊息
    private void sendMessage(Player player, Message message, RewardDistribution rewardDistribution, DamageCalculation damage) {
        if (player == null || !player.isOnline()) return;
        List<String> messageList = Message.getStringList(message);
        for (String line : messageList) {
            String replaced = line
                    .replace("%boss_name%", rewardDistribution.getName())
                    .replace("%rank%", damage.getRank() > 0 ? String.valueOf(damage.getRank()) : "N/A")
                    .replace("%player_name%", player.getName())
                    .replace("%total_damage%", String.format("%.2f", damage.getTotalDamage()))
                    .replace("%max_damage%", String.format("%.2f", damage.getMaxDamage()))
                    .replace("%contribution%", String.format("%.2f%%", damage.getParticipation()))
                    .replace("%average_damage%", String.format("%.2f", damage.getAverageDamage()))
                    .replace("%hit_count%", String.valueOf(damage.getHitCount()));
            player.sendMessage(ColorConversion.translateHexCodes(replaced));
        }
    }

    // 發送 BOSS 獎勵廣播
    private void sendBroadcast(Message message, String killName, RewardDistribution rewardDistribution, List<DamageCalculation> damageList) {
        List<String> discordLines = new ArrayList<>();
        List<String> broadcastMessage = Message.getStringList(message);

        for (String line : broadcastMessage) {
            line = line
                    .replace("%killer_name%", killName)
                    .replace("%boss_name%", rewardDistribution.getName());

            if (line.contains("%rank_lines%")) {
                Bukkit.broadcastMessage("§6⬇️ 排行榜：");
                discordLines.add("📊 排行榜：");

                for (int i = 0; i < damageList.size(); i++) {
                    DamageCalculation dc = damageList.get(i);
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(dc.getPlayerUUID());
                    String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : "未知玩家";

                    String rankStr = "§e🏅 第 " + (i + 1) + " 名：§a" + playerName +
                            " §7| 總傷 §c" + String.format("%.1f", dc.getTotalDamage()) +
                            " §7| 最大單擊 §6" + String.format("%.1f", dc.getMaxDamage());

                    String discordStr = "🏅 第 " + (i + 1) + " 名：**" + playerName + "**｜總傷害 `" +
                            String.format("%.1f", dc.getTotalDamage()) + "`｜最大單擊 `" +
                            String.format("%.1f", dc.getMaxDamage()) + "`";

                    Bukkit.broadcastMessage(rankStr);
                    discordLines.add(discordStr);
                }

            } else {
                Bukkit.broadcastMessage(ColorConversion.translateHexCodes(line));
                discordLines.add(ColorConversion.translateHexCodes(line));
            }
        }

        String discordMessage = String.join("\n", discordLines);

        // 發送 Discord 通知
        discordHookService.sendEmbed(
                Config.getConfig().getString(Config.DISCORD_CHANNEL),
                "BOSS 獎勵通知",
                "玩家 " + killName + " 成功擊敗了 BOSS " + rewardDistribution.getName(),
                discordMessage,
                Color.CYAN
        );
    }

    // 發送 BOSS 獎勵廣播
    public void handleBossResult(Message message, String killerName, RewardDistribution rewardDistribution,
                                 List<DamageCalculation> damageList, boolean broadcastInGame, boolean sendToDiscord) {

        List<String> rawMessage = Message.getStringList(message);

        if (broadcastInGame) {
            sendInGameBroadcast(rawMessage, killerName, rewardDistribution, damageList);
        }

        if (sendToDiscord) {
            sendDiscordBroadcast(rawMessage, killerName, rewardDistribution, damageList);
        }
    }

    private void sendInGameBroadcast(List<String> lines, String killName, RewardDistribution rewardDistribution,
                                     List<DamageCalculation> damageList) {
        for (String line : lines) {
            if (line.contains("%rank_lines%")) {
                for (int i = 0; i < damageList.size(); i++) {
                    DamageCalculation dc = damageList.get(i);
                    OfflinePlayer p = Bukkit.getOfflinePlayer(dc.getPlayerUUID());
                    String name = p.getName() != null ? p.getName() : "未知玩家";

                    String msg = "§e🏅 第 " + (i + 1) + " 名：§a" + name +
                            " §7| 總傷 §c" + String.format("%.1f", dc.getTotalDamage()) +
                            " §7| 最大單擊 §6" + String.format("%.1f", dc.getMaxDamage());

                    Bukkit.broadcastMessage(msg);
                }
            } else {
                Bukkit.broadcastMessage(ColorConversion.translateHexCodes(
                        line.replace("%killer_name%", killName).replace("%boss_name%", rewardDistribution.getName())
                ));
            }
        }
    }

    private void sendDiscordBroadcast(List<String> lines, String killName, RewardDistribution rewardDistribution,
                                      List<DamageCalculation> damageList) {
        List<String> dcLines = new ArrayList<>();

        for (String line : lines) {
            if (line.contains("%rank_lines%")) {
                for (int i = 0; i < damageList.size(); i++) {
                    DamageCalculation dc = damageList.get(i);
                    OfflinePlayer p = Bukkit.getOfflinePlayer(dc.getPlayerUUID());
                    String name = p.getName() != null ? p.getName() : "未知玩家";

                    String msg = "🏅 第 " + (i + 1) + " 名：**" + name + "**｜總傷害 `" +
                            String.format("%.1f", dc.getTotalDamage()) + "`｜最大單擊 `" +
                            String.format("%.1f", dc.getMaxDamage()) + "`";

                    dcLines.add(msg);
                }
            } else {
                String parsed = line.replace("%killer_name%", killName).replace("%boss_name%", rewardDistribution.getName());
                dcLines.add(ChatColor.stripColor(parsed) );
            }
        }

        String message = String.join("\n", dcLines);

        discordHookService.sendEmbed(
                Config.getConfig().getString(Config.DISCORD_CHANNEL),
                "🎖 BOSS 擊殺通知",
                "玩家 **" + killName + "** 成功擊敗了 **" + rewardDistribution.getName() + "**！",
                message,
                Color.CYAN
        );
    }
}
