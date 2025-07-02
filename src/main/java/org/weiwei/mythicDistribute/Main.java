package org.weiwei.mythicDistribute;

import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import org.weiwei.mythicDistribute.command.MainCommand;
import org.weiwei.mythicDistribute.distribute.EventManager;
import org.weiwei.mythicDistribute.entity.RewardDistributionManager;
import org.weiwei.mythicDistribute.util.Config;
import org.weiwei.mythicDistribute.util.Message;

import java.util.Set;

public final class Main extends JavaPlugin {

    private static final String CMD = "mythicDistribute";

    @Getter
    private static Main inst;

    @Override
    public void onEnable() {
        inst = this;

        Message.loadMessage();
        Config.loadConfig();

        RewardDistributionManager.reload();

        // 註冊指令
        MainCommand mainCommand = new MainCommand();
        mainCommand.setup(Set.of(CMD));

        // 註冊事件管理器
        EventManager.register();
    }

    @Override
    public void onDisable() {

        getLogger().info("mythicDistribute 已關閉");
    }
}
