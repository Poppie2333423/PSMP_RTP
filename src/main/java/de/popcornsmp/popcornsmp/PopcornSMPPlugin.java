package de.popcornsmp.popcornsmp;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class PopcornSMPPlugin extends JavaPlugin implements Listener {

    private static final int SPAWN_RADIUS = 10_000;
    private static final int MAX_SPAWN_ATTEMPTS = 40;
    private static final String PREFIX = ChatColor.GOLD + "" + ChatColor.BOLD + "PopcornSMP" + ChatColor.RESET + ChatColor.DARK_GRAY + " » " + ChatColor.RESET;
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

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("PopcornSMP plugin enabled.");
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

            teleportPlayer(player, spawn, randomSpawn);
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        event.setFormat(PREFIX + ChatColor.GRAY + "%1$s" + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE + "%2$s");
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
            player.sendMessage(PREFIX + ChatColor.GRAY + "Du wurdest an einer zufälligen Position bei "
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
}
