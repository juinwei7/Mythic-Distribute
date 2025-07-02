package org.weiwei.mythicDistribute.command.sub;

import org.bukkit.command.CommandSender;
import org.weiwei.mythicDistribute.command.SubCommand;
import org.weiwei.mythicDistribute.entity.RewardDistributionManager;
import org.weiwei.mythicDistribute.util.Config;
import org.weiwei.mythicDistribute.util.Message;

import java.util.List;

public class ReloadCommand extends SubCommand {

    public ReloadCommand() {
        super("reload", Message.SUB_COMMAND__RELOAD, true);
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        Message.loadMessage();
        Config.loadConfig();
        RewardDistributionManager.reload();

        Message.sendWithPrefix(sender, Message.MESSAGE__RELOAD);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
