package de.popcornsmp.popcornsmp;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class PopcornSMPPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final int SPAWN_RADIUS = 10_000;
    private static final int MAX_SPAWN_ATTEMPTS = 40;
    private static final int RTP_COUNTDOWN_SECONDS = 3;
    private static final String PREFIX = ChatColor.GOLD + "" + ChatColor.BOLD + "PopcornSMP" + ChatColor.RESET + ChatColor.DARK_GRAY + " » " + ChatColor.RESET;
    private static final double MOVEMENT_TOLERANCE = 0.01;
    private static final Set<Material> UNSAFE_BLOCKS = EnumSet.of(
            Material.LAVA,
            Material.WATER,
            Material.KELP,
            Material.KELP_PLANT,
            Material.SEAGRASS,
            Material.TALL_SEAGRASS,
            Material.MAGMA_BLOCK,
            Material.CACTUS,
            Material.FIRE,
            Material.CAMPFIRE,
            Material.SOUL_FIRE,
            Material.SOUL_CAMPFIRE
    );

    private final Map<UUID, BukkitTask> pendingRandomTeleports = new HashMap<>();
    private final Set<UUID> frozenPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("rtp"), "Command /rtp not defined in plugin.yml").setExecutor(this);
        getLogger().info("PopcornSMP plugin enabled.");
    }

    @Override
    public void onDisable() {
        pendingRandomTeleports.values().forEach(BukkitTask::cancel);
        pendingRandomTeleports.clear();
        frozenPlayers.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.joinMessage(null);

        if (!player.hasPlayedBefore()) {
            World world = Objects.requireNonNull(Bukkit.getWorlds().get(0), "No default world loaded");
            Location spawn = findSpawnLocation(world);
            boolean randomSpawn = spawn != null;

            if (!randomSpawn) {
                spawn = world.getSpawnLocation();
            }

            player.getInventory().addItem(new ItemStack(Material.BREAD, 16));
            teleportPlayer(player, spawn, randomSpawn);
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        event.setFormat(PREFIX + ChatColor.GRAY + "%1$s" + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE + "%2$s");
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (!frozenPlayers.contains(playerId)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) {
            return;
        }

        Location freezePosition = from.clone();
        freezePosition.setYaw(to.getYaw());
        freezePosition.setPitch(to.getPitch());
        event.setTo(freezePosition);

        double deltaX = Math.abs(from.getX() - to.getX());
        double deltaY = Math.abs(from.getY() - to.getY());
        double deltaZ = Math.abs(from.getZ() - to.getZ());

        if (deltaX <= MOVEMENT_TOLERANCE && deltaY <= MOVEMENT_TOLERANCE && deltaZ <= MOVEMENT_TOLERANCE) {
            return;
        }

        cancelPendingTeleport(playerId);
        frozenPlayers.remove(playerId);
        player.sendMessage(PREFIX + ChatColor.RED + "Random Teleport abgebrochen, weil du dich bewegt hast.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rtp")) {
            return false;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Nur Spieler können diesen Befehl verwenden.");
            return true;
        }

        UUID playerId = player.getUniqueId();

        if (pendingRandomTeleports.containsKey(playerId)) {
            player.sendMessage(PREFIX + ChatColor.RED + "Ein Teleport läuft bereits – bitte warte einen Moment.");
            return true;
        }

        player.sendMessage(PREFIX + ChatColor.GRAY + "Du wirst in " + ChatColor.GOLD + "3 Sekunden" + ChatColor.GRAY + " teleportiert. Bewege dich nicht!");
        frozenPlayers.add(playerId);

        BukkitTask task = new BukkitRunnable() {
            private int secondsLeft = RTP_COUNTDOWN_SECONDS;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelAndCleanup();
                    return;
                }

                if (!frozenPlayers.contains(playerId)) {
                    cancelAndCleanup();
                    return;
                }

                if (secondsLeft <= 0) {
                    cancel();
                    pendingRandomTeleports.remove(playerId);
                    frozenPlayers.remove(playerId);
                    player.resetTitle();
                    prepareRandomTeleport(player);
                    return;
                }

                player.sendTitle(ChatColor.GOLD + secondsLeft + ChatColor.GRAY + " Sekunden bis zum Teleport",
                        ChatColor.YELLOW + "Bleib stehen!", 0, 20, 0);
                float pitch = 1.0f + (RTP_COUNTDOWN_SECONDS - secondsLeft) * 0.1f;
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.MASTER, 1.0f, pitch);
                secondsLeft--;
            }

            private void cancelAndCleanup() {
                cancel();
                pendingRandomTeleports.remove(playerId);
                frozenPlayers.remove(playerId);
                if (player.isOnline()) {
                    player.resetTitle();
                }
            }
        }.runTaskTimer(this, 0L, 20L);

        pendingRandomTeleports.put(playerId, task);
        return true;
    }

    private void prepareRandomTeleport(Player player) {
        if (!player.isOnline()) {
            return;
        }

        Location target = findSpawnLocation(player.getWorld());

        if (target == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "Es konnte aktuell keine sichere Position gefunden werden. Versuche es später erneut.");
            return;
        }

        performRandomTeleport(player, target);
    }

    private void teleportPlayer(Player player, Location location, boolean randomSpawn) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        Location target = location.clone();
        world.getChunkAtAsync(target.getBlockX() >> 4, target.getBlockZ() >> 4).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(this, () -> {
                    player.teleport(target);
                    sendWelcomeExperience(player, target, randomSpawn);
                })
        ).exceptionally(throwable -> {
            getLogger().warning("Failed to prepare random spawn chunk: " + throwable.getMessage());
            Bukkit.getScheduler().runTask(this, () -> {
                player.teleport(world.getSpawnLocation());
                sendWelcomeExperience(player, world.getSpawnLocation(), false);
            });
            return null;
        });
    }

    private Location findSpawnLocation(World world) {
        Random random = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < MAX_SPAWN_ATTEMPTS; attempt++) {
            int x = random.nextInt(SPAWN_RADIUS * 2 + 1) - SPAWN_RADIUS;
            int z = random.nextInt(SPAWN_RADIUS * 2 + 1) - SPAWN_RADIUS;

            int y = world.getHighestBlockYAt(x, z);
            Location candidate = new Location(world, x + 0.5, y, z + 0.5);
            Block blockBelow = world.getBlockAt(x, y - 1, z);

            if (isSafeBlock(blockBelow.getType())) {
                return candidate.add(0, 1, 0);
            }
        }

        getLogger().warning("No safe spawn found within attempts; using world spawn location.");
        return null;
    }

    private boolean isSafeBlock(Material material) {
        if (material.isAir()) {
            return false;
        }
        return !UNSAFE_BLOCKS.contains(material);
    }

    private void sendWelcomeExperience(Player player, Location location, boolean randomSpawn) {
        player.sendTitle(ChatColor.GOLD + "Willkommen auf PopcornSMP", ChatColor.YELLOW + "Viel Spaß auf PopcornSMP.de!", 10, 70, 20);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0f, 1.0f);
        player.sendMessage(PREFIX + ChatColor.GRAY + "Willkommen " + ChatColor.GOLD + player.getName() + ChatColor.GRAY + " auf PopcornSMP.de!");
        if (randomSpawn) {
            player.sendMessage(PREFIX + ChatColor.GRAY + "Du wurdest durch einen Random Teleport zur Position "
                    + ChatColor.GOLD + location.getBlockX() + ChatColor.GRAY + ", "
                    + ChatColor.GOLD + location.getBlockY() + ChatColor.GRAY + ", "
                    + ChatColor.GOLD + location.getBlockZ() + ChatColor.GRAY + " in der Welt gespawnt.");
        } else {
            player.sendMessage(PREFIX + ChatColor.GRAY + "Wir konnten dich sicher am Welten-Spawn platzieren ("
                    + ChatColor.GOLD + location.getBlockX() + ChatColor.GRAY + ", "
                    + ChatColor.GOLD + location.getBlockY() + ChatColor.GRAY + ", "
                    + ChatColor.GOLD + location.getBlockZ() + ChatColor.GRAY + ").");
        }

        spawnFirework(player.getLocation());
    }

    private void spawnFirework(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> {
            Firework firework = (Firework) world.spawnEntity(location, EntityType.FIREWORK_ROCKET);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.setPower(1);
            meta.addEffect(FireworkEffect.builder()
                    .withColor(Color.ORANGE)
                    .withFade(Color.YELLOW)
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .trail(true)
                    .build());
            firework.setFireworkMeta(meta);
        });
    }

    private void performRandomTeleport(Player player, Location target) {
        World world = target.getWorld();
        if (world == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "Teleport fehlgeschlagen: Welt nicht gefunden.");
            return;
        }

        world.getChunkAtAsync(target.getBlockX() >> 4, target.getBlockZ() >> 4).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(this, () -> {
                    player.teleport(target);
                    player.sendMessage(PREFIX + ChatColor.GRAY + "Du wurdest durch einen Random Teleport zur Position "
                            + ChatColor.GOLD + target.getBlockX() + ChatColor.GRAY + ", "
                            + ChatColor.GOLD + target.getBlockY() + ChatColor.GRAY + ", "
                            + ChatColor.GOLD + target.getBlockZ() + ChatColor.GRAY + " teleportiert.");
                    player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.MASTER, 1.0f, 1.2f);
                })
        ).exceptionally(throwable -> {
            getLogger().warning("Failed to prepare chunk for /rtp: " + throwable.getMessage());
            Bukkit.getScheduler().runTask(this, () -> player.sendMessage(PREFIX + ChatColor.RED + "Teleport fehlgeschlagen. Bitte versuche es erneut."));
            return null;
        });
    }

    private void cancelPendingTeleport(UUID playerId) {
        BukkitTask task = pendingRandomTeleports.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }
}
