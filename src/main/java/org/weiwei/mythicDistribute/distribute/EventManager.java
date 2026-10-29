package org.weiwei.mythicDistribute.distribute;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import io.lumine.mythic.core.items.MythicItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
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
        DailyFirstTracker.load();
        FeaturedBonus.load();
        Bukkit.getPluginManager().registerEvents(new EventManager(), Main.getInst());
    }

    static DiscordHookService discordHookService  = PlayerDataRegistry.getDiscordHookService();

    // 有記錄傷害、還沒發過獎的 BOSS（實體 UUID → 怪物 ID）
    private static final Map<UUID, String> trackedBosses = new HashMap<>();

    // 玩家對 Boss 傷害時紀錄（近戰，以及玩家射出的弓箭、三叉戟等投射物）
    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        Player player = damagingPlayer(event.getDamager());
        if (player == null) return;

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
        trackedBosses.put(activeMob.getUniqueId(), bossId);
    }

    private static Player damagingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    // BOSS 死亡事件
    @EventHandler
    public void onMythicMobDeath(MythicMobDeathEvent event) {
        ActiveMob mob = event.getMob();
        trackedBosses.remove(mob.getUniqueId());
        String killerName = event.getKiller() instanceof Player killer ? killer.getName() : "未知玩家";
        distribute(mob.getType().getInternalName(), mob.getUniqueId(), killerName, event.getDrops());
    }

    /**
     * 保底：MythicMobs 偶爾會漏處理死亡（它的非同步時鐘在死亡事件輪到它之前先把怪註銷），
     * 這時不會有 MythicMobDeathEvent。MM 的死亡處理是 HIGH，到 HIGHEST 還留在 trackedBosses 的就是被漏掉的頭目，改由這裡發獎。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        UUID mobUuid = event.getEntity().getUniqueId();
        String bossId = trackedBosses.remove(mobUuid);
        if (bossId == null) return;

        Player killer = event.getEntity().getKiller();
        String killerName = killer != null ? killer.getName() : "未知玩家";
        Main.getInst().getLogger().warning("MythicMobs 沒有處理 " + bossId + " 的死亡（擊殺者：" + killerName + "），改由保底發放獎勵");

        // MM 正常處理時會依 PreventOtherDrops 清掉原版掉落物，這裡比照
        boolean preventOtherDrops = MythicBukkit.inst().getMobManager().getMythicMob(bossId)
                .map(mythicMob -> mythicMob.getPreventOtherDrops())
                .orElse(false);
        if (preventOtherDrops) {
            event.getDrops().clear();
        }
        distribute(bossId, mobUuid, killerName, event.getDrops());
    }

    private void distribute(String mobId, UUID mobUuid, String killerName, List<ItemStack> drops) {
        // 沒有配置獎勵的 BOSS 就跳過
        if (!RewardDistributionManager.hasReward(mobId)) return;

        RewardDistribution distribution = RewardDistributionManager.getReward(mobId);
        List<RewardDistribution.RewardGroup> topList = distribution.getStrategy().getTop();

        handleDrops(drops, distribution);

        // 傷害排名列表
        List<DamageCalculation> damageList = DamageCalculation.calculateRankings(mobUuid);
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

        // 每日首殺加碼（本週加成副本跑兩次）
        handleDailyFirst(distribution, damageList, rewarded);

        // 本週加成副本：每人每天前幾次多發一份
        FeaturedBonus.handle(distribution, damageList, rewarded);

        // 一起打的人（整場送一次名單）
        handleParty(mobId, distribution, damageList);

        // 發送廣播訊息
        handleBossResult(Message.SEND_PRIZES__BROADCAST__KILL, killerName, distribution, damageList, distribution.isBroadcast(), distribution.isDiscord());

        // 最後清除紀錄
        DamageCalculation.clearDamage(mobUuid);
    }

    /**
     * 每日首殺加碼：拿到排名獎、或總傷害達參與門檻的在線玩家，每個 group（副本）每天第一次多發一份。
     * 參考 Wynncraft Raid 的「每日首通加碼、不鎖次數」：之後照常打、照常領原本的獎勵。
     */
    private void handleDailyFirst(RewardDistribution distribution, List<DamageCalculation> damageList, Set<UUID> topRewarded) {
        RewardDistribution.DailyFirst dailyFirst = distribution.getDailyFirst();
        if (dailyFirst == null || dailyFirst.getCommand() == null || dailyFirst.getCommand().isEmpty()) return;

        RewardDistribution.ParticipantsReward pr = distribution.getStrategy().getParticipants();
        int repeat = FeaturedBonus.dailyFirstRepeat(dailyFirst.getGroup());
        for (DamageCalculation dc : damageList) {
            UUID playerId = dc.getPlayerUUID();
            boolean eligible = topRewarded.contains(playerId) || (pr != null && dc.getTotalDamage() >= pr.getMin_damage());
            if (!eligible) continue;

            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;
            if (!DailyFirstTracker.tryClaim(dailyFirst.getGroup(), playerId)) continue;

            for (int i = 0; i < repeat; i++) {
                for (String cmd : dailyFirst.getCommand()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
                }
            }
            if (dailyFirst.getMessage() != null && !dailyFirst.getMessage().isBlank()) {
                String suffix = repeat > 1 ? "&d（本週加成 ×" + repeat + "）" : "";
                player.sendMessage(ColorConversion.translateHexCodes(dailyFirst.getMessage() + suffix));
            }
            Main.getInst().getLogger().info("每日首殺加碼：" + player.getName() + "（" + dailyFirst.getGroup() + (repeat > 1 ? "，本週加成 ×" + repeat : "") + "）");
        }
    }

    /**
     * 一起打的人：傷害前 3 名，加上總傷害達 party.min_damage 的在線玩家（依傷害高到低排）。
     * 每條指令只執行一次，%players% 換成整份名單，讓收指令的外掛看得到「誰跟誰一起打」。
     */
    private void handleParty(String mobId, RewardDistribution distribution, List<DamageCalculation> damageList) {
        RewardDistribution.Party party = distribution.getParty();
        if (party == null || party.getCommand() == null || party.getCommand().isEmpty()) return;

        List<String> names = new ArrayList<>();
        for (int i = 0; i < damageList.size(); i++) {
            DamageCalculation dc = damageList.get(i);
            if (i >= 3 && dc.getTotalDamage() < party.getMin_damage()) continue;
            Player player = Bukkit.getPlayer(dc.getPlayerUUID());
            if (player == null || !player.isOnline()) continue;
            names.add(player.getName());
        }
        if (names.isEmpty()) return;

        String players = String.join(" ", names);
        for (String cmd : party.getCommand()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%players%", players).replace("%boss%", mobId));
        }
        Main.getInst().getLogger().info("一起打的人：" + mobId + " → " + players);
    }

    private void handleDrops(List<ItemStack> drops, RewardDistribution distribution) {
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
                drops.add(itemStack);
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
