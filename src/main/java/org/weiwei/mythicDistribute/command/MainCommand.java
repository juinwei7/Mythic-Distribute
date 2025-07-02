package org.weiwei.mythicDistribute.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.weiwei.mythicDistribute.Main;
import org.weiwei.mythicDistribute.command.sub.ReloadCommand;
import org.weiwei.mythicDistribute.util.Message;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MainCommand implements CommandExecutor, TabCompleter {

    public MainCommand() {
        new ReloadCommand().registerCommand(); // 註冊 Reload 指令


    }

    public void setup(Set<String> commands) {
        for (String command : commands) {
            Main.getInst().getCommand(command).setExecutor(this);
            Main.getInst().getCommand(command).setTabCompleter(this);
        }
        Main.getInst().getLogger().info("註冊指令: " + commands.toString());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command arg1, String label, String[] args) {
        String cmd = args[0];
        SubCommand sub = SubCommand.getSubCommandMap().get(cmd);
        if (sub == null) {
            Message.sendWithPrefix(sender, Message.MESSAGE__SUB_NOT_FOUND);
            return true;
        }
        // 使用者檢查
        if (!sub.isUse(sender)) {
            Message.sendWithPrefix(sender, Message.MESSAGE__NO_PERMISSION);
            return true;
        }
        // 參數檢查
        if (args.length == 1 && sub.arg != null && !sub.arg.isEmpty()) {
            Message.send(sender, Message.MESSAGE__UNKNOWN_COMMAND, "&f/" + sub.cmd + " " + sub.arg);
            return true;
        }
        // 執行指令
        sub.onCommand(sender, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String s, String[] args) {
        List<SubCommand> subCommands = SubCommand.getSubCommandMap().values().stream().toList();
        if (args.length == 1) {
            return subCommands.stream()
                    .filter(sub -> sub.isUse(sender) && sub.cmd.startsWith(args[0].toLowerCase()))
                    .map(sub -> sub.cmd)
                    .collect(Collectors.toList());
        }
        return subCommands.stream()
                .filter(sub -> sub.cmd.equalsIgnoreCase(args[0]))
                .findFirst()
                .filter(sub -> sub.isUse(sender))
                .filter(sub -> sub.needOp)
                .map(sub -> sub.onTabComplete(sender, Arrays.copyOfRange(args, 1, args.length))) // 傳遞去除首個參數的 args
                .orElse(Collections.emptyList());
    }
}
