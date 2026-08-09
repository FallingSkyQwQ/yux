package com.example.homeplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home 插件 Java 对照版（04-§11）：与 samples/home-plugin 的 Yux 版功能等价，
 * 用于对比行数与可读性。
 */
public class HomePlugin extends JavaPlugin implements Listener {

    private final Map<String, Map<String, Long>> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        registerCommands();
        // 每 5 分钟落盘所有在线玩家（对应 Yux 版 task repeat(interval:6000)）
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    HomeData data = HomeData.load(getDataFolder(), p.getName());
                    if (data != null) data.save(getDataFolder());
                }
            }
        }.runTaskTimer(this, 0, 6000);
    }

    private void registerCommands() {
        getCommand("sethome").setExecutor(this::onSetHome);
        getCommand("home").setExecutor(this::onHome);
        getCommand("home").setTabCompleter(this::onHomeTab);
        getCommand("delhome").setExecutor(this::onDelHome);
        getCommand("homes").setExecutor(this::onHomes);
    }

    // ── 事件（对应 Yux 版 event PlayerJoin/Quit/Death）──────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        String welcome = getConfig().getString("welcomeMessage", "欢迎 $name 回来！")
            .replace("$name", player.getName());
        player.sendMessage(welcome);
        player.playSound(player.getLocation(), getConfig().getString("teleportSound", "entity.enderman.teleport"), 1.0f, 1.0f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        getLogger().info(e.getPlayer().getName() + " 离开");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getEntity().sendMessage("你的家数据已保留");
    }

    // ── 命令（对应 Yux 版 command "/sethome" 等）────────────────────────────

    private boolean onSetHome(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        String name = args.length > 0 ? args[0] : "default";
        HomeData data = HomeData.loadOrEmpty(getDataFolder(), player.getName());
        int maxHomes = getConfig().getInt("maxHomes", 5);
        if (data.homes.size() >= maxHomes && !data.homes.containsKey(name)) {
            player.sendMessage("已达最大家数 " + maxHomes);
            return true;
        }
        data.homes.put(name, LocationData.from(player.getLocation()));
        data.save(getDataFolder());
        player.sendMessage("已设置家：" + name);
        return true;
    }

    private boolean onHome(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        String name = args.length > 0 ? args[0] : "default";
        HomeData data = HomeData.loadOrEmpty(getDataFolder(), player.getName());
        LocationData loc = data.homes.get(name);
        if (loc == null) {
            player.sendMessage("没有名为 " + name + " 的家");
            return true;
        }
        if (cooldownActive(player)) {
            player.sendMessage("冷却中");
            return true;
        }
        player.teleport(loc.toLocation());
        player.sendMessage("已传送");
        return true;
    }

    private List<String> onHomeTab(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        return new ArrayList<>(HomeData.loadOrEmpty(getDataFolder(), player.getName()).homes.keySet());
    }

    private boolean onDelHome(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        String name = args.length > 0 ? args[0] : "default";
        HomeData data = HomeData.loadOrEmpty(getDataFolder(), player.getName());
        if (!data.homes.containsKey(name)) {
            player.sendMessage("没有名为 " + name + " 的家");
            return true;
        }
        data.homes.remove(name);
        data.save(getDataFolder());
        player.sendMessage("已删除家：" + name);
        return true;
    }

    private boolean onHomes(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        HomeData data = HomeData.loadOrEmpty(getDataFolder(), player.getName());
        player.sendMessage("你的家：" + String.join(", ", data.homes.keySet()));
        return true;
    }

    // ── 冷却（对应 Yux 版 cooldownActive）────────────────────────────────────

    private boolean cooldownActive(Player player) {
        long now = System.currentTimeMillis();
        Map<String, Long> map = cooldowns.computeIfAbsent(player.getName(), k -> new HashMap<>());
        Long last = map.get(player.getName());
        int cooldownSec = getConfig().getInt("homeCooldown", 30);
        if (last == null) {
            map.put(player.getName(), now);
            return false;
        }
        if (now - last < cooldownSec * 1000L) return true;
        map.put(player.getName(), now);
        return false;
    }
}

/** 位置数据（对应 Yux 版 data LocationData）。 */
class LocationData {
    String world;
    double x, y, z;
    float yaw, pitch;

    static LocationData from(Location loc) {
        LocationData d = new LocationData();
        d.world = loc.getWorld().getName();
        d.x = loc.getX();
        d.y = loc.getY();
        d.z = loc.getZ();
        d.yaw = loc.getYaw();
        d.pitch = loc.getPitch();
        return d;
    }

    Location toLocation() {
        World w = Bukkit.getWorld(world);
        return new Location(w, x, y, z, yaw, pitch);
    }
}

/** 玩家家数据（对应 Yux 版 data HomeData）。 */
class HomeData {
    String player;
    Map<String, LocationData> homes = new HashMap<>();

    void save(File dataFolder) {
        File file = new File(dataFolder, "data/" + player + ".yml");
        file.getParentFile().mkdirs();
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("player", player);
        for (Map.Entry<String, LocationData> e : homes.entrySet()) {
            LocationData d = e.getValue();
            yml.set("homes." + e.getKey() + ".world", d.world);
            yml.set("homes." + e.getKey() + ".x", d.x);
            yml.set("homes." + e.getKey() + ".y", d.y);
            yml.set("homes." + e.getKey() + ".z", d.z);
            yml.set("homes." + e.getKey() + ".yaw", (double) d.yaw);
            yml.set("homes." + e.getKey() + ".pitch", (double) d.pitch);
        }
        try {
            yml.save(file);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    static HomeData loadOrEmpty(File dataFolder, String player) {
        HomeData d = load(dataFolder, player);
        if (d == null) {
            d = new HomeData();
            d.player = player;
        }
        return d;
    }

    static HomeData load(File dataFolder, String player) {
        File file = new File(dataFolder, "data/" + player + ".yml");
        if (!file.isFile()) return null;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        HomeData d = new HomeData();
        d.player = yml.getString("player", player);
        for (String key : yml.getConfigurationSection("homes").getKeys(false)) {
            LocationData loc = new LocationData();
            loc.world = yml.getString("homes." + key + ".world");
            loc.x = yml.getDouble("homes." + key + ".x");
            loc.y = yml.getDouble("homes." + key + ".y");
            loc.z = yml.getDouble("homes." + key + ".z");
            loc.yaw = (float) yml.getDouble("homes." + key + ".yaw");
            loc.pitch = (float) yml.getDouble("homes." + key + ".pitch");
            d.homes.put(key, loc);
        }
        return d;
    }
}
