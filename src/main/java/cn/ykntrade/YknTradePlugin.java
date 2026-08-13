package cn.ykntrade;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class YknTradePlugin extends JavaPlugin {
    private TradeManager tradeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> registration =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            getLogger().severe("未找到 Vault 经济服务。请安装 Vault 和一个经济插件。插件已停用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        tradeManager = new TradeManager(this, registration.getProvider());
        getServer().getPluginManager().registerEvents(tradeManager, this);
        getServer().getPluginManager().registerEvents(new DropProtectionListener(this), this);
        getLogger().info("YknTrade 已启用，经济服务: " + registration.getProvider().getName());
    }

    @Override
    public void onDisable() {
        if (tradeManager != null) {
            tradeManager.shutdown();
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("该命令只能由玩家执行。");
            return true;
        }
        if (tradeManager == null) {
            player.sendMessage("§c交易插件当前不可用。");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("admin")) {
            tradeManager.openAdminHistory(player);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("accept")) {
            tradeManager.acceptRequest(player, args[1]);
            return true;
        }
        player.sendMessage("§e请蹲下并右键另一名玩家来发起交易。");
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("ykntrade.admin")
                && "admin".regionMatches(true, 0, args[0], 0, args[0].length())) {
            return List.of("admin");
        }
        return List.of();
    }
}
