package org.weiwei.mythicDistribute.command.sub;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.mythicDistribute.command.SubCommand;
import org.weiwei.mythicDistribute.distribute.Tombstone;

import java.util.List;

/**
 * 頭目墓碑管理：
 * tomb list                           每隻頭目倒下幾次、碑在哪
 * tomb clear <頭目ID|all>             拆碑
 * tomb preview <頭目ID> [世界 x y z]   用假資料立一塊預覽碑（主控台要給座標）
 */
public class TombCommand extends SubCommand {

    public TombCommand() {
        super("tomb", true);
        setArg("<list|clear|preview>");
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        switch (args[1].toLowerCase()) {
            case "list" -> {
                List<String> lines = Tombstone.describe();
                if (lines.isEmpty()) sender.sendMessage("§7還沒有任何墓碑紀錄");
                lines.forEach(sender::sendMessage);
            }
            case "clear" -> {
                if (args.length < 3) {
                    sender.sendMessage("§c用法：/mythicDistribute tomb clear <頭目ID|all>");
                    return;
                }
                sender.sendMessage("§a已拆除 " + Tombstone.clear(args[2]) + " 塊墓碑");
            }
            case "preview" -> {
                if (args.length < 3) {
                    sender.sendMessage("§c用法：/mythicDistribute tomb preview <頭目ID> [世界 x y z]");
                    return;
                }
                Location location;
                if (args.length >= 7) {
                    World world = Bukkit.getWorld(args[3]);
                    if (world == null) {
                        sender.sendMessage("§c找不到世界 " + args[3]);
                        return;
                    }
                    try {
                        location = new Location(world, Double.parseDouble(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6]));
                    } catch (NumberFormatException exception) {
                        sender.sendMessage("§c座標要是數字");
                        return;
                    }
                } else if (sender instanceof Player player) {
                    location = player.getLocation();
                } else {
                    sender.sendMessage("§c主控台要給座標：tomb preview <頭目ID> <世界> <x> <y> <z>");
                    return;
                }
                String name = sender instanceof Player player ? player.getName() : "HuanLan_AI";
                Location placed = Tombstone.preview(args[2], location, name);
                if (placed == null) {
                    sender.sendMessage("§c" + args[2] + " 沒有開墓碑，或附近找不到地面");
                    return;
                }
                sender.sendMessage("§a預覽碑立在 " + placed.getWorld().getName() + " "
                        + placed.getBlockX() + "," + placed.getBlockY() + "," + placed.getBlockZ());
            }
            default -> sender.sendMessage("§c用法：/mythicDistribute tomb <list|clear|preview>");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("list", "clear", "preview");
        return List.of();
    }
}
