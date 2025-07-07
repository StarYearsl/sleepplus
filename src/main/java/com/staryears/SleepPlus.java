package com.staryears.sleepplus;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SleepPlus extends JavaPlugin implements Listener {

    private double sleepPercentage;
    private boolean enableTimeoutSkip;
    private int timeoutSeconds;
    private Map<UUID, Long> sleepingPlayers = new HashMap<>();
    private BukkitTask sleepCheckTask;
    private File customConfigFile;
    private YamlConfiguration customConfig;

    @Override
    public void onEnable() {
        // 创建配置文件夹
        File configFolder = new File(getDataFolder(), "SleepPlus");
        if (!configFolder.exists()) {
            configFolder.mkdirs();
        }

        // 创建配置文件
        customConfigFile = new File(configFolder, "config.yml");
        if (!customConfigFile.exists()) {
            // 保存默认配置到插件根目录
            saveDefaultConfig();
            // 复制到SleepPlus子文件夹
            try {
                saveResource("config.yml", false);
                java.nio.file.Files.copy(
                        new File(getDataFolder(), "config.yml").toPath(),
                        customConfigFile.toPath()
                );
            } catch (Exception e) {
                getLogger().severe("无法创建配置文件: " + e.getMessage());
            }
        }

        // 从SleepPlus子文件夹加载配置
        customConfig = YamlConfiguration.loadConfiguration(customConfigFile);

        // 如果配置为空，加载默认值
        if (customConfig.getKeys(false).isEmpty()) {
            customConfig.set("sleep-percentage", 50.0);
            customConfig.set("enable-timeout-skip", true);
            customConfig.set("timeout-seconds", 30);
            try {
                customConfig.save(customConfigFile);
            } catch (IOException e) {
                getLogger().severe("无法保存默认配置: " + e.getMessage());
            }
        }

        // 加载配置
        loadConfig();

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(this, this);

        // 启动睡眠检查任务
        startSleepCheckTask();

        getLogger().info("SleepPlus 插件已启用!");
    }

    @Override
    public void onDisable() {
        // 停止所有任务
        if (sleepCheckTask != null) {
            sleepCheckTask.cancel();
        }

        // 清除睡眠玩家列表
        sleepingPlayers.clear();

        getLogger().info("SleepPlus 插件已禁用!");
    }

    private void loadConfig() {
        // 从自定义配置中读取设置
        sleepPercentage = customConfig.getDouble("sleep-percentage", 50.0);
        enableTimeoutSkip = customConfig.getBoolean("enable-timeout-skip", true);
        timeoutSeconds = customConfig.getInt("timeout-seconds", 30);

        getLogger().info("配置已加载: 睡眠比例=" + sleepPercentage + "%, 超时跳过=" +
                enableTimeoutSkip + ", 超时时间=" + timeoutSeconds + "秒");
    }

    private void startSleepCheckTask() {
        // 取消现有任务
        if (sleepCheckTask != null) {
            sleepCheckTask.cancel();
        }

        // 创建新任务，每秒检查一次
        sleepCheckTask = getServer().getScheduler().runTaskTimer(this, this::checkSleepStatus, 20L, 20L);
    }

    private void checkSleepStatus() {
        // 遍历所有世界
        for (World world : Bukkit.getWorlds()) {
            // 只检查主世界
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }

            // 如果不是夜晚或者暴风雨，不需要检查
            long time = world.getTime();
            if ((time < 12541 || time > 23458) && !world.isThundering()) {
                continue;
            }

            // 获取在该世界的玩家数量
            int totalPlayers = 0;
            for (Player player : world.getPlayers()) {
                if (!player.isSleepingIgnored()) {
                    totalPlayers++;
                }
            }

            // 如果没有玩家，跳过
            if (totalPlayers == 0) {
                continue;
            }

            // 计算正在睡觉的玩家数量
            int sleepingCount = 0;
            for (UUID playerId : sleepingPlayers.keySet()) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.getWorld().equals(world) && player.isSleeping()) {
                    sleepingCount++;
                }
            }

            // 计算所需的睡眠玩家数量
            int requiredSleeping = (int) Math.ceil(totalPlayers * (sleepPercentage / 100.0));

            // 检查是否达到睡眠百分比
            if (sleepingCount >= requiredSleeping && requiredSleeping > 0) {
                skipNight(world);
                continue;
            }

            // 检查睡眠超时
            if (enableTimeoutSkip && sleepingCount > 0) {
                boolean shouldSkip = false;
                long currentTime = System.currentTimeMillis();

                for (Map.Entry<UUID, Long> entry : sleepingPlayers.entrySet()) {
                    UUID playerId = entry.getKey();
                    long sleepStartTime = entry.getValue();

                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.getWorld().equals(world) && player.isSleeping()) {
                        long sleepDuration = (currentTime - sleepStartTime) / 1000; // 转换为秒
                        if (sleepDuration >= timeoutSeconds) {
                            shouldSkip = true;
                            break;
                        }
                    }
                }

                if (shouldSkip) {
                    skipNight(world);
                }
            }
        }
    }

    private void skipNight(World world) {
        // 设置时间为早晨
        world.setTime(1000);

        // 如果在下雨，停止下雨
        if (world.hasStorm()) {
            world.setStorm(false);
            world.setThundering(false);
        }

        // 通知所有玩家
        Bukkit.broadcastMessage("§a[SleepPlus] §f夜晚已被跳过，早上好！");
    }

    @EventHandler
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        // 如果玩家成功进入床
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            Player player = event.getPlayer();
            // 记录玩家开始睡觉的时间
            sleepingPlayers.put(player.getUniqueId(), System.currentTimeMillis());

            // 通知玩家睡眠状态
            World world = player.getWorld();
            int totalPlayers = 0;
            for (Player p : world.getPlayers()) {
                if (!p.isSleepingIgnored()) {
                    totalPlayers++;
                }
            }

            int sleepingCount = 0;
            for (UUID playerId : sleepingPlayers.keySet()) {
                Player p = Bukkit.getPlayer(playerId);
                if (p != null && p.getWorld().equals(world) && p.isSleeping()) {
                    sleepingCount++;
                }
            }

            int requiredSleeping = (int) Math.ceil(totalPlayers * (sleepPercentage / 100.0));

            Bukkit.broadcastMessage(String.format(
                    "§a[SleepPlus] §f%s 正在睡觉。(%d/%d, 需要: %d)",
                    player.getName(), sleepingCount, totalPlayers, requiredSleeping));
        }
    }

    @EventHandler
    public void onPlayerBedLeave(PlayerBedLeaveEvent event) {
        // 玩家离开床时，从睡眠列表中移除
        sleepingPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 玩家退出服务器时，从睡眠列表中移除
        sleepingPlayers.remove(event.getPlayer().getUniqueId());
    }
}
