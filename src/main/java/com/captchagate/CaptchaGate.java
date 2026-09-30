package com.captchagate;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CaptchaGate extends JavaPlugin implements Listener {

    private static final String WORLD_NAME = "captcha_void";
    // No I / O to avoid look-alikes with 1 / 0
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private World captchaWorld;
    private Location platformSpawn;
    private int timeoutSeconds;
    private int codeLength;
    private boolean authMe = false;

    private static class Session {
        String code;
        Location origin;
        GameMode gameMode;
        String joinMessage;
        int remaining;
        BukkitTask task;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        timeoutSeconds = Math.max(5, getConfig().getInt("timeout-seconds", 30));
        codeLength = Math.max(3, Math.min(12, getConfig().getInt("code-length", 6)));

        setupWorld();
        getServer().getPluginManager().registerEvents(this, this);

        if (getServer().getPluginManager().getPlugin("AuthMe") != null) {
            hookAuthMe();
        }
        getLogger().info("CaptchaGate enabled." + (authMe ? " (waiting for AuthMe login)" : ""));
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session s = entry.getValue();
            if (s.task != null) s.task.cancel();
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) restore(p, s);
        }
        sessions.clear();
    }

    // ------------------------------------------------------------------ AuthMe hook

    /** Listens for AuthMe's LoginEvent via reflection so AuthMe isn't needed to compile. */
    @SuppressWarnings("unchecked")
    private void hookAuthMe() {
        try {
            final Class<? extends Event> cls = (Class<? extends Event>)
                    Class.forName("fr.xephi.authme.events.LoginEvent", true, getClass().getClassLoader());
            final Method getPlayer = cls.getMethod("getPlayer");

            getServer().getPluginManager().registerEvent(cls, this, EventPriority.MONITOR,
                    (listener, event) -> {
                        try {
                            if (!cls.isInstance(event)) return;
                            Player p = (Player) getPlayer.invoke(event);
                            if (p != null && p.isOnline()) startCaptcha(p, null);
                        } catch (Exception ex) {
                            getLogger().warning("AuthMe login hook error: " + ex.getMessage());
                        }
                    }, this);

            authMe = true;
        } catch (Exception ex) {
            authMe = false;
            getLogger().warning("AuthMe found but its LoginEvent could not be hooked: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ world

    private void setupWorld() {
        WorldCreator creator = new WorldCreator(WORLD_NAME);
        creator.generator(new VoidGenerator());
        creator.generateStructures(false);
        captchaWorld = creator.createWorld();

        captchaWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        captchaWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        captchaWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        captchaWorld.setTime(6000);

        // 7x7 platform at y=100
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                captchaWorld.getBlockAt(x, 100, z).setType(Material.POLISHED_BLACKSTONE);
            }
        }
        platformSpawn = new Location(captchaWorld, 0.5, 101, 0.5, 0f, 0f);
        captchaWorld.setSpawnLocation(platformSpawn);
    }

    // ------------------------------------------------------------------ helpers

    private boolean isPending(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codeLength; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String spaced(String code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }

    private void showCode(Player p, Session s) {
        p.sendTitle("§e§l" + spaced(s.code),
                "§7Type this in chat §8| §c" + s.remaining + "s",
                0, 40, 10);
    }

    private void restore(Player p, Session s) {
        p.setGameMode(s.gameMode);
        p.teleport(s.origin);
    }

    /** Makes the player visible to everyone who has finished the captcha (and vice versa). */
    private void reveal(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p) || isPending(o)) continue;
            o.showPlayer(this, p);
            p.showPlayer(this, o);
        }
    }

    // ------------------------------------------------------------------ start captcha

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // With AuthMe, the captcha starts after login instead (see hookAuthMe)
        if (authMe) return;

        if (startCaptcha(e.getPlayer(), e.getJoinMessage())) {
            e.setJoinMessage(null); // announce only after they pass
        }
    }

    private boolean startCaptcha(Player p, String joinMessage) {
        if (p.hasPermission("captchagate.bypass")) return false;
        if (isPending(p)) return false;

        Session s = new Session();
        s.code = generateCode();
        s.gameMode = p.getGameMode();
        s.remaining = timeoutSeconds;
        s.joinMessage = joinMessage;

        // If they logged out inside the captcha world (crash etc.), send them to main spawn afterwards
        if (p.getWorld().equals(captchaWorld)) {
            s.origin = Bukkit.getWorlds().get(0).getSpawnLocation();
        } else {
            s.origin = p.getLocation().clone();
        }

        sessions.put(p.getUniqueId(), s);

        // Hide this player from everyone, and hide other pending players from them
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            o.hidePlayer(this, p);
            if (isPending(o)) p.hidePlayer(this, o);
        }

        p.setGameMode(GameMode.ADVENTURE);
        p.teleport(platformSpawn);

        p.sendMessage("§e§lCAPTCHA §7» Type the code shown on your screen in chat.");
        p.sendMessage("§7You have §c" + timeoutSeconds + " seconds§7. A wrong answer kicks you.");
        showCode(p, s);

        s.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline() || !isPending(p)) {
                    cancel();
                    return;
                }
                s.remaining--;
                if (s.remaining <= 0) {
                    cancel();
                    p.kickPlayer("§cCaptcha timed out.");
                    return;
                }
                showCode(p, s);
            }
        }.runTaskTimer(this, 20L, 20L);

        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        if (s.task != null) s.task.cancel();
        e.setQuitMessage(null);
        restore(p, s); // so their saved position isn't the captcha platform
    }

    // ------------------------------------------------------------------ chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        // Pending players never see main chat
        e.getRecipients().removeIf(r -> sessions.containsKey(r.getUniqueId()));

        Player p = e.getPlayer();
        if (!sessions.containsKey(p.getUniqueId())) return;

        e.setCancelled(true);
        String msg = e.getMessage().trim();

        Bukkit.getScheduler().runTask(this, () -> {
            Session s = sessions.get(p.getUniqueId());
            if (s == null || !p.isOnline()) return;

            if (msg.equalsIgnoreCase(s.code)) {
                pass(p, s);
            } else {
                p.kickPlayer("§cWrong captcha.");
            }
        });
    }

    private void pass(Player p, Session s) {
        sessions.remove(p.getUniqueId());
        if (s.task != null) s.task.cancel();

        restore(p, s);
        reveal(p);

        p.sendTitle("§a§lVerified", "§7Welcome!", 0, 30, 10);
        p.sendMessage("§a§lCAPTCHA §7» Verified. Enjoy!");

        if (s.joinMessage != null) Bukkit.broadcastMessage(s.joinMessage);
    }

    // ------------------------------------------------------------------ lock-down while pending

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!isPending(e.getPlayer())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            e.setTo(fixed); // can look around, can't move
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (isPending(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (isPending(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isPending(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isPending(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (isPending(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isPending(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && isPending(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isPending(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && isPending(p)) e.setCancelled(true);
    }
}
