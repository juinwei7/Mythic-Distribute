package org.weiwei.mythicDistribute.distribute;

import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.weiwei.mythicDistribute.Main;
import org.weiwei.mythicDistribute.entity.RewardDistribution;
import org.weiwei.mythicDistribute.entity.RewardDistributionManager;
import org.weiwei.mythicDistribute.util.ColorConversion;
import org.weiwei.mythicDistribute.util.Message;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 頭目墓碑（參考仙境傳說 MVP 墓碑）：設了 tombstone 的頭目倒下時，在原地立一塊石碑，
 * 寫倒下時間、幾人討伐、戰鬥多久、MVP（傷害第 1 名）、最後一擊與下次出現時段；右鍵看傷害前五名。
 * 同一隻頭目再出生、或再倒下換新碑時，舊碑就拆掉。碑是持久實體，重啟後還在；
 * 沒載入的舊碑等區塊載入時（EntitiesLoadEvent）比對 tomb_id，不是目前那塊就拆。
 * 每隻頭目的倒下次數、目前碑的位置與前五名記在 tombstones.yml。
 */
public final class Tombstone implements Listener {

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final String FILE_NAME = "tombstones.yml";
    private static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private static final List<String> DEFAULT_LINES = List.of(
            "&6&l%boss_name%之墓",
            "&7倒下於 &f%time%",
            "&f%players% &7人討伐 · 戰鬥 &f%duration%",
            "&eMVP &f%mvp% &7（傷害 %mvp_share%）",
            "&7最後一擊 &f%last_hit%",
            "&8%next%",
            "&8右鍵看傷害前五名");
    private static final String DEFAULT_TOP_HEADER = "&8&m          &r &6%boss_name%之墓 &7· %time% &8&m          ";
    private static final String DEFAULT_TOP_LINE = "&e第 %rank% 名 &f%player% &7總傷 &c%damage% &7（%share%）";

    private static NamespacedKey keyBoss;
    private static NamespacedKey keyTomb;

    private static final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MythicDistribute-Tombstone");
        thread.setDaemon(true);
        return thread;
    });

    /** 頭目 ID → 墓碑紀錄 */
    private static final Map<String, Record> records = new HashMap<>();

    static final class Record {
        int count;
        String tombId;           // 目前立著的碑；null＝沒有
        String world;
        double x, y, z;
        long time;
        List<String> entities = new ArrayList<>();
        List<String> top = new ArrayList<>();
    }

    /** 墓碑上要寫的內容（死亡當下算好） */
    record Data(String bossName, Location deathLocation, long deathMillis, int players, long durationMillis,
                String mvp, double mvpShare, String lastHit, String next, List<String> topLines, Location faceTo) {
    }

    private Tombstone() {
    }

    static void register() {
        keyBoss = new NamespacedKey(Main.getInst(), "tomb_boss");
        keyTomb = new NamespacedKey(Main.getInst(), "tomb_id");
        load();
        Bukkit.getPluginManager().registerEvents(new Tombstone(), Main.getInst());
        // 外掛啟用前就載入的區塊收不到 EntitiesLoadEvent，啟用時掃一次
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                removeIfStale(entity);
            }
        }
    }

    // ───────────── 立碑 ─────────────

    /** 頭目倒下時呼叫（主執行緒）。damageList 已依傷害排好。 */
    static void onBossDeath(String bossId, RewardDistribution distribution, Location deathLocation,
                            String lastHit, List<DamageCalculation> damageList, long firstHitMillis) {
        RewardDistribution.Tombstone config = distribution.getTombstone();
        if (config == null || deathLocation == null || deathLocation.getWorld() == null || damageList.isEmpty()) return;

        long now = System.currentTimeMillis();
        double total = damageList.stream().mapToDouble(DamageCalculation::getTotalDamage).sum();
        DamageCalculation first = damageList.get(0);
        String mvp = nameOf(first.getPlayerUUID());

        String header = textOrDefault("TOMBSTONE.TOP_HEADER", DEFAULT_TOP_HEADER);
        String lineTemplate = textOrDefault("TOMBSTONE.TOP_LINE", DEFAULT_TOP_LINE);
        List<String> topLines = new ArrayList<>();
        topLines.add(header.replace("%boss_name%", distribution.getName()).replace("%time%", formatTime(now)));
        for (int i = 0; i < Math.min(5, damageList.size()); i++) {
            DamageCalculation dc = damageList.get(i);
            topLines.add(lineTemplate
                    .replace("%rank%", String.valueOf(i + 1))
                    .replace("%player%", nameOf(dc.getPlayerUUID()))
                    .replace("%damage%", String.format("%.0f", dc.getTotalDamage()))
                    .replace("%share%", formatShare(total == 0 ? 0 : dc.getTotalDamage() / total * 100)));
        }

        Location faceTo = null;
        Player mvpPlayer = Bukkit.getPlayer(first.getPlayerUUID());
        if (mvpPlayer != null && mvpPlayer.getWorld().equals(deathLocation.getWorld())) faceTo = mvpPlayer.getLocation();

        Data data = new Data(distribution.getName(), deathLocation.clone(), now, damageList.size(),
                firstHitMillis > 0 ? now - firstHitMillis : 0, mvp,
                total == 0 ? 0 : first.getTotalDamage() / total * 100, lastHit, config.getNext(), topLines, faceTo);

        // 等死亡事件跑完、頭目實體清掉再立碑
        Bukkit.getScheduler().runTask(Main.getInst(), () -> place(bossId, data, true));
    }

    /** 拆掉舊碑、立新碑，回傳新碑位置；找不到地面（掉進虛空）就不立，回傳 null。counted＝算進倒下次數（預覽不算）。 */
    static Location place(String bossId, Data data, boolean counted) {
        Location base = findGround(data.deathLocation());
        removeTomb(bossId);
        Record record = records.computeIfAbsent(bossId, key -> new Record());
        if (counted) record.count++;
        if (base == null) {
            save();
            return null;
        }

        if (data.faceTo() != null) {
            double dx = data.faceTo().getX() - base.getX();
            double dz = data.faceTo().getZ() - base.getZ();
            if (dx * dx + dz * dz > 0.01) {
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                base.setYaw(Math.round(yaw / 45f) * 45f);
            }
        }

        String tombId = UUID.randomUUID().toString();
        World world = base.getWorld();
        List<Entity> spawned = new ArrayList<>();

        // 碑座、碑身、碑頂、旁邊一盞燈籠；方塊展示原點在角落，平移 -寬/2 置中
        spawned.add(block(world, base, tombId, bossId, Material.POLISHED_ANDESITE, 1.0f, 0.2f, 0.7f, 0f, 0f, 0f));
        spawned.add(block(world, base, tombId, bossId, Material.POLISHED_DEEPSLATE, 0.72f, 1.05f, 0.18f, 0f, 0.2f, 0f));
        spawned.add(block(world, base, tombId, bossId, Material.POLISHED_DEEPSLATE, 0.86f, 0.12f, 0.26f, 0f, 1.25f, 0f));
        spawned.add(block(world, base, tombId, bossId, Material.SOUL_LANTERN, 0.42f, 0.42f, 0.42f, 0.42f, 0.2f, 0.12f));

        TextDisplay text = world.spawn(base.clone().add(0, 1.55, 0), TextDisplay.class, display -> {
            display.text(LEGACY.deserialize(String.join("\n", renderLines(data, record.count))));
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setLineWidth(260);
            display.setShadowed(true);
            display.setBackgroundColor(Color.fromARGB(120, 8, 12, 28));
            display.setBrightness(new Display.Brightness(15, 15));
            display.setViewRange(0.5f);
            tag(display, tombId, bossId);
        });
        spawned.add(text);

        Interaction hit = world.spawn(base.clone(), Interaction.class, interaction -> {
            interaction.setInteractionWidth(0.9f);
            interaction.setInteractionHeight(1.4f);
            interaction.setResponsive(true);
            tag(interaction, tombId, bossId);
        });
        spawned.add(hit);

        record.tombId = tombId;
        record.world = world.getName();
        record.x = base.getX();
        record.y = base.getY();
        record.z = base.getZ();
        record.time = data.deathMillis();
        record.entities = spawned.stream().map(entity -> entity.getUniqueId().toString()).toList();
        record.top = ColorConversion.translateHexCodes(data.topLines());
        save();
        Main.getInst().getLogger().info("墓碑：" + bossId + " 第 " + record.count + " 次倒下，立於 "
                + world.getName() + " " + (int) base.getX() + "," + (int) base.getY() + "," + (int) base.getZ());
        return base;
    }

    private static List<String> renderLines(Data data, int count) {
        Map<String, String> values = new HashMap<>();
        values.put("%boss_name%", data.bossName());
        values.put("%time%", formatTime(data.deathMillis()));
        values.put("%players%", String.valueOf(data.players()));
        values.put("%duration%", formatDuration(data.durationMillis()));
        values.put("%mvp%", data.mvp());
        values.put("%mvp_share%", formatShare(data.mvpShare()));
        values.put("%last_hit%", data.lastHit() == null ? "" : data.lastHit());
        values.put("%next%", data.next() == null ? "" : data.next());
        values.put("%count%", String.valueOf(count));

        List<String> templates = Message.getMessages().getStringList("TOMBSTONE.LINES");
        if (templates.isEmpty()) templates = DEFAULT_LINES;

        List<String> lines = new ArrayList<>();
        for (String template : templates) {
            String line = template;
            boolean skip = false;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!line.contains(entry.getKey())) continue;
                if (entry.getValue().isBlank()) {
                    skip = true; // 沒有最後一擊（非玩家擊殺）或沒設下次時段，整行不寫
                    break;
                }
                line = line.replace(entry.getKey(), entry.getValue());
            }
            if (!skip) lines.add(ColorConversion.translateHexCodes(line));
        }
        return lines;
    }

    private static BlockDisplay block(World world, Location base, String tombId, String bossId, Material material,
                                      float sx, float sy, float sz, float ox, float oy, float oz) {
        return world.spawn(base.clone(), BlockDisplay.class, display -> {
            display.setBlock(material.createBlockData());
            display.setTransformation(new Transformation(
                    new Vector3f(-sx / 2 + ox, oy, -sz / 2 + oz), new AxisAngle4f(),
                    new Vector3f(sx, sy, sz), new AxisAngle4f()));
            display.setBrightness(new Display.Brightness(11, 11));
            display.setViewRange(0.5f);
            tag(display, tombId, bossId);
        });
    }

    private static void tag(Entity entity, String tombId, String bossId) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(keyTomb, PersistentDataType.STRING, tombId);
        pdc.set(keyBoss, PersistentDataType.STRING, bossId);
    }

    /** 從死亡位置往下找地面（最多 24 格）；卡在方塊裡就往上找空位（最多 4 格）。 */
    private static Location findGround(Location death) {
        World world = death.getWorld();
        int bx = death.getBlockX(), bz = death.getBlockZ();
        int y = death.getBlockY();
        for (int up = 0; up < 4 && !world.getBlockAt(bx, y, bz).isPassable(); up++) y++;
        for (int down = 0; down < 24 && y > world.getMinHeight(); down++, y--) {
            Block below = world.getBlockAt(bx, y - 1, bz);
            if (below.getType().isSolid() && world.getBlockAt(bx, y, bz).isPassable()) {
                return new Location(world, bx + 0.5, y, bz + 0.5);
            }
        }
        return null;
    }

    // ───────────── 拆碑 ─────────────

    /** 拆掉這隻頭目目前立著的碑；沒載入的實體等區塊載入時再拆。 */
    static boolean removeTomb(String bossId) {
        Record record = records.get(bossId);
        if (record == null || record.tombId == null) return false;
        for (String uuid : record.entities) {
            try {
                Entity entity = Bukkit.getEntity(UUID.fromString(uuid));
                if (entity != null) entity.remove();
            } catch (IllegalArgumentException ignored) {
                // 壞掉的紀錄略過
            }
        }
        record.tombId = null;
        record.entities = new ArrayList<>();
        save();
        return true;
    }

    private static void removeIfStale(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        String tombId = pdc.get(keyTomb, PersistentDataType.STRING);
        if (tombId == null) return;
        String bossId = pdc.get(keyBoss, PersistentDataType.STRING);
        Record record = bossId == null ? null : records.get(bossId);
        if (record == null || !tombId.equals(record.tombId)) entity.remove();
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) removeIfStale(entity);
    }

    /** 頭目再出生：舊碑拆掉 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMythicSpawn(MythicMobSpawnEvent event) {
        String bossId = event.getMobType().getInternalName();
        RewardDistribution distribution = RewardDistributionManager.getReward(bossId);
        if (distribution == null || distribution.getTombstone() == null) return;
        if (removeTomb(bossId)) Main.getInst().getLogger().info("墓碑：" + bossId + " 再次出生，舊碑已拆");
    }

    // ───────────── 右鍵看前五名 ─────────────

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction interaction)) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        String bossId = interaction.getPersistentDataContainer().get(keyBoss, PersistentDataType.STRING);
        if (bossId == null) return;
        event.setCancelled(true);
        Record record = records.get(bossId);
        if (record == null || record.top.isEmpty()) return;
        record.top.forEach(event.getPlayer()::sendMessage);
    }

    // ───────────── 指令用 ─────────────

    /** 每隻頭目的倒下次數與目前的碑 */
    public static List<String> describe() {
        List<String> lines = new ArrayList<>();
        records.forEach((bossId, record) -> lines.add("§e" + bossId + " §7倒下 §f" + record.count + " §7次 · "
                + (record.tombId == null ? "§8沒有碑"
                : "§f" + record.world + " " + (int) record.x + "," + (int) record.y + "," + (int) record.z + " §7（" + formatTime(record.time) + "）")));
        return lines;
    }

    /** 拆碑；bossId 為 all 時全部拆，回傳拆了幾塊 */
    public static int clear(String bossId) {
        if (!"all".equalsIgnoreCase(bossId)) return removeTomb(bossId) ? 1 : 0;
        int removed = 0;
        for (String id : new ArrayList<>(records.keySet())) {
            if (removeTomb(id)) removed++;
        }
        return removed;
    }

    /** 用假資料立一塊預覽碑（不算倒下次數；下一次真的倒下或出生就換掉）。頭目沒開墓碑回傳 null。 */
    public static Location preview(String bossId, Location location, String playerName) {
        RewardDistribution distribution = RewardDistributionManager.getReward(bossId);
        if (distribution == null || distribution.getTombstone() == null) return null;
        long now = System.currentTimeMillis();
        String name = distribution.getName();
        List<String> top = new ArrayList<>();
        top.add(textOrDefault("TOMBSTONE.TOP_HEADER", DEFAULT_TOP_HEADER).replace("%boss_name%", name)
                .replace("%time%", formatTime(now)) + " &7（預覽）");
        top.add(textOrDefault("TOMBSTONE.TOP_LINE", DEFAULT_TOP_LINE).replace("%rank%", "1").replace("%player%", playerName)
                .replace("%damage%", "12345").replace("%share%", "100%"));
        Data data = new Data(name, location, now, 1, 83_000, playerName, 100, playerName,
                distribution.getTombstone().getNext(), top, null);
        return place(bossId, data, false);
    }

    // ───────────── 存檔 ─────────────

    private static void load() {
        records.clear();
        File file = file();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String bossId : yaml.getKeys(false)) {
            Record record = new Record();
            String path = bossId + ".";
            record.count = yaml.getInt(path + "count");
            record.tombId = yaml.getString(path + "tomb_id");
            record.world = yaml.getString(path + "world");
            record.x = yaml.getDouble(path + "x");
            record.y = yaml.getDouble(path + "y");
            record.z = yaml.getDouble(path + "z");
            record.time = yaml.getLong(path + "time");
            record.entities = new ArrayList<>(yaml.getStringList(path + "entities"));
            record.top = new ArrayList<>(yaml.getStringList(path + "top"));
            records.put(bossId, record);
        }
    }

    /** 在主執行緒組好內容，寫檔丟到非同步，死亡處理裡不做同步 I/O */
    private static void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Record> entry : records.entrySet()) {
            String path = entry.getKey() + ".";
            Record record = entry.getValue();
            yaml.set(path + "count", record.count);
            yaml.set(path + "tomb_id", record.tombId);
            yaml.set(path + "world", record.world);
            yaml.set(path + "x", record.x);
            yaml.set(path + "y", record.y);
            yaml.set(path + "z", record.z);
            yaml.set(path + "time", record.time);
            yaml.set(path + "entities", record.entities);
            yaml.set(path + "top", record.top);
        }
        String content = yaml.saveToString();
        File file = file();
        if (writer.isShutdown()) {
            write(file, content);
            return;
        }
        writer.execute(() -> write(file, content)); // 單一執行緒，照順序寫，舊內容不會蓋掉新內容
    }

    /** 外掛關閉時把還沒寫完的存檔寫完 */
    public static void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                Main.getInst().getLogger().warning(FILE_NAME + " 5 秒內沒寫完");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void write(File file, String content) {
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            Main.getInst().getLogger().warning("無法寫入 " + FILE_NAME + "：" + exception.getMessage());
        }
    }

    private static File file() {
        return new File(Main.getInst().getDataFolder(), FILE_NAME);
    }

    // ───────────── 格式 ─────────────

    private static String textOrDefault(String path, String fallback) {
        String value = Message.getMessages().getString(path);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nameOf(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : "未知玩家";
    }

    static String formatTime(long millis) {
        ZonedDateTime time = Instant.ofEpochMilli(millis).atZone(ZONE);
        return String.format("%d/%02d（%s）%02d:%02d", time.getMonthValue(), time.getDayOfMonth(),
                WEEKDAYS[time.getDayOfWeek().getValue() - 1], time.getHour(), time.getMinute());
    }

    static String formatDuration(long millis) {
        long seconds = Math.max(1, Math.round(millis / 1000.0));
        if (seconds < 60) return seconds + " 秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " 分 " + (seconds % 60) + " 秒";
        return (minutes / 60) + " 小時 " + (minutes % 60) + " 分";
    }

    private static String formatShare(double percent) {
        return Math.round(percent) + "%";
    }
}
