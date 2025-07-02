package org.weiwei.mythicDistribute.command;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.weiwei.mythicDistribute.util.Message;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子指令抽象類
 *
 * @author weiwei
 */
public abstract class SubCommand implements Comparable<SubCommand> {

    @Getter
    public static final Map<String, SubCommand> subCommandMap = new HashMap<>();

    public String cmd, arg = "", description = "";

    @Setter
    public double priority = 100;

    // 是否需要 OP 權限
    public boolean needOp = false;

    /**
     * @param cmd String
     */
    public SubCommand(String cmd, Message message, boolean needOp) {
        this.cmd = cmd;
        this.description = Message.getMsg(message);
        this.needOp = needOp;
    }

    /**
     * 註冊指令方法
     */
    public final void registerCommand() {
        if (!subCommandMap.containsKey(cmd)) {
            subCommandMap.put(cmd, this);
        }
    }


    /**
     * 执行指令抽象方法
     *
     * @param sender CommandSender
     * @param args   String[]
     */
    public abstract void onCommand(CommandSender sender, String[] args);

    /**
     * TAB執行方法
     *
     * @param sender CommandSender
     * @param args   String[]
     * @return List
     */
    public abstract List<String> onTabComplete(CommandSender sender, String[] args);

    /**
     * 判斷是否可用
     *
     * @param sender CommandSender
     * @return boolean
     */
    public boolean isUse(CommandSender sender) {
        if (sender == null) return false;
        if (!needOp) return true;
        if (!(sender instanceof Player player)) return true; // 控制台允許
        return player.isOp(); // 玩家需 OP
    }

    protected void setArg(String arg) {
        this.arg = arg;
    }

    @Override
    public int compareTo(SubCommand cmd) {
        return Double.compare(priority, cmd.priority);
    }

}
