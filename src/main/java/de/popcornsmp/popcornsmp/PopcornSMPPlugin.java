package de.popcornsmp.popcornsmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

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

    private static final int SPAWN_RADIUS = 25_000;
    private static final int MAX_SPAWN_ATTEMPTS = 40;
    private static final int RTP_COUNTDOWN_SECONDS = 3;
    private static final int TPA_REQUEST_TIMEOUT_SECONDS = 60;
    private static final String PREFIX = ChatColor.GOLD + "" + ChatColor.BOLD + "PopcornSMP" + ChatColor.RESET
            + ChatColor.DARK_GRAY + " » " + ChatColor.RESET;
    private static final double MOVEMENT_TOLERANCE = 0.5;
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final Sound TELEPORT_SOUND = Sound.ENTITY_ENDERMAN_TELEPORT;
    private static final float TELEPORT_SOUND_VOLUME = 1.0f;
    private static final float TELEPORT_SOUND_PITCH = 1.2f;
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

    private final Map<UUID, BukkitTask> pendingTeleportTasks = new HashMap<>();
    private final Map<UUID, Location> frozenAnchors = new ConcurrentHashMap<>();
    private final Map<UUID, TeleportRequest> incomingTeleportRequests = new ConcurrentHashMap<>();
    private final Map<UUID, TeleportRequest> outgoingTeleportRequests = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("rtp"), "Command /rtp not defined in plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("tpa"), "Command /tpa not defined in plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("tpaccept"), "Command /tpaccept not defined in plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("tpdeny"), "Command /tpdeny not defined in plugin.yml").setExecutor(this);
        getLogger().info("PopcornSMP plugin enabled.");
    }

    @Override
    public void onDisable() {
        pendingTeleportTasks.values().forEach(BukkitTask::cancel);
        pendingTeleportTasks.clear();
        frozenAnchors.clear();
        incomingTeleportRequests.clear();
        outgoingTeleportRequests.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.joinMessage(buildJoinQuitMessage(player, ChatColor.GREEN, "BETRETEN"));

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
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(buildJoinQuitMessage(event.getPlayer(), ChatColor.RED, "VERLASSEN"));
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        Location anchor = frozenAnchors.get(playerId);

        if (anchor == null) {
            return;
        }

        Location to = event.getTo();

        if (to == null) {
            return;
        }

        double distanceSquared = anchor.distanceSquared(to);

        if (distanceSquared <= MOVEMENT_TOLERANCE * MOVEMENT_TOLERANCE) {
            return;
        }

        cancelPendingTeleport(playerId);
        frozenAnchors.remove(playerId);
        player.resetTitle();
        player.sendMessage(PREFIX + ChatColor.RED + "Teleport abgebrochen, weil du dich bewegt hast.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        return switch (name) {
            case "rtp" -> handleRandomTeleportCommand(sender);
            case "tpa" -> handleTeleportRequestCommand(sender, args);
            case "tpaccept" -> handleTeleportAcceptCommand(sender);
            case "tpdeny" -> handleTeleportDenyCommand(sender);
            default -> false;
        };
    }

    private boolean handleRandomTeleportCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Nur Spieler können diesen Befehl verwenden.");
            return true;
        }

        if (pendingTeleportTasks.containsKey(player.getUniqueId())) {
            player.sendMessage(PREFIX + ChatColor.RED + "Ein Teleport läuft bereits – bitte warte einen Moment.");
            return true;
        }

        startTeleportCountdown(player, () -> prepareRandomTeleport(player));
        return true;
    }

    private boolean handleTeleportRequestCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Nur Spieler können diesen Befehl verwenden.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(PREFIX + ChatColor.RED + "Verwendung: /tpa <Spieler>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);

        if (target == null || !target.isOnline()) {
            player.sendMessage(PREFIX + ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }

        if (target.equals(player)) {
            player.sendMessage(PREFIX + ChatColor.RED + "Du kannst dich nicht zu dir selbst teleportieren.");
            return true;
        }

        TeleportRequest request = new TeleportRequest(player.getUniqueId(), target.getUniqueId(),
                System.currentTimeMillis() + TPA_REQUEST_TIMEOUT_SECONDS * 1000L);

        TeleportRequest previousIncoming = incomingTeleportRequests.put(target.getUniqueId(), request);
        if (previousIncoming != null && !previousIncoming.requesterId.equals(player.getUniqueId())) {
            Player previousRequester = Bukkit.getPlayer(previousIncoming.requesterId);
            if (previousRequester != null) {
                previousRequester.sendMessage(PREFIX + ChatColor.RED + "Deine Teleportanfrage an "
                        + ChatColor.GOLD + target.getName() + ChatColor.RED + " wurde durch eine neue Anfrage ersetzt.");
            }
            outgoingTeleportRequests.remove(previousIncoming.requesterId);
        }

        TeleportRequest previousOutgoing = outgoingTeleportRequests.put(player.getUniqueId(), request);
        if (previousOutgoing != null && !previousOutgoing.targetId.equals(target.getUniqueId())) {
            incomingTeleportRequests.remove(previousOutgoing.targetId, previousOutgoing);
            Player previousTarget = Bukkit.getPlayer(previousOutgoing.targetId);
            if (previousTarget != null) {
                previousTarget.sendMessage(PREFIX + ChatColor.RED + "Die Teleportanfrage von "
                        + ChatColor.GOLD + player.getName() + ChatColor.RED + " wurde zurückgezogen.");
            }
        }

        player.sendMessage(PREFIX + ChatColor.GRAY + "Teleportanfrage an " + ChatColor.GOLD + target.getName()
                + ChatColor.GRAY + " gesendet. Sie läuft in " + ChatColor.GOLD + TPA_REQUEST_TIMEOUT_SECONDS
                + ChatColor.GRAY + " Sekunden ab.");
        target.sendMessage(buildTeleportRequestMessage(player));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.MASTER, 1.0f, 1.0f);

        new BukkitRunnable() {
            @Override
            public void run() {
                TeleportRequest current = incomingTeleportRequests.get(target.getUniqueId());
                if (current != request) {
                    return;
                }

                if (!request.isExpired()) {
                    return;
                }

                incomingTeleportRequests.remove(target.getUniqueId(), request);
                outgoingTeleportRequests.remove(player.getUniqueId(), request);

                if (player.isOnline()) {
                    player.sendMessage(PREFIX + ChatColor.RED + "Deine Teleportanfrage an "
                            + ChatColor.GOLD + target.getName() + ChatColor.RED + " ist abgelaufen.");
                }

                if (target.isOnline()) {
                    target.sendMessage(PREFIX + ChatColor.RED + "Die Teleportanfrage von "
                            + ChatColor.GOLD + player.getName() + ChatColor.RED + " ist abgelaufen.");
                }
            }
        }.runTaskLater(this, TPA_REQUEST_TIMEOUT_SECONDS * 20L);

        return true;
    }

    private boolean handleTeleportAcceptCommand(CommandSender sender) {
        if (!(sender instanceof Player target)) {
            sender.sendMessage(ChatColor.RED + "Nur Spieler können diesen Befehl verwenden.");
            return true;
        }

        TeleportRequest request = incomingTeleportRequests.get(target.getUniqueId());

        if (request == null) {
            target.sendMessage(PREFIX + ChatColor.RED + "Du hast keine ausstehenden Teleportanfragen.");
            return true;
        }

        if (request.isExpired()) {
            cleanupTeleportRequest(request);
            target.sendMessage(PREFIX + ChatColor.RED + "Die Teleportanfrage ist bereits abgelaufen.");
            return true;
        }

        Player requester = Bukkit.getPlayer(request.requesterId);

        if (requester == null || !requester.isOnline()) {
            cleanupTeleportRequest(request);
            target.sendMessage(PREFIX + ChatColor.RED + "Der anfragende Spieler ist nicht mehr online.");
            return true;
        }

        if (pendingTeleportTasks.containsKey(requester.getUniqueId())) {
            target.sendMessage(PREFIX + ChatColor.RED + "Dieser Spieler führt bereits einen Teleport aus.");
            return true;
        }

        cleanupTeleportRequest(request);

        requester.sendMessage(PREFIX + ChatColor.GRAY + "Deine Anfrage wurde von " + ChatColor.GOLD + target.getName()
                + ChatColor.GRAY + " akzeptiert.");
        target.sendMessage(PREFIX + ChatColor.GRAY + "Du hast die Teleportanfrage von " + ChatColor.GOLD + requester.getName()
                + ChatColor.GRAY + " akzeptiert.");

        startTeleportCountdown(requester, () -> teleportToPlayer(requester, target));
        return true;
    }

    private boolean handleTeleportDenyCommand(CommandSender sender) {
        if (!(sender instanceof Player target)) {
            sender.sendMessage(ChatColor.RED + "Nur Spieler können diesen Befehl verwenden.");
            return true;
        }

        TeleportRequest request = incomingTeleportRequests.get(target.getUniqueId());

        if (request == null) {
            target.sendMessage(PREFIX + ChatColor.RED + "Du hast keine ausstehenden Teleportanfragen.");
            return true;
        }

        cleanupTeleportRequest(request);

        Player requester = Bukkit.getPlayer(request.requesterId);
        if (requester != null && requester.isOnline()) {
            requester.sendMessage(PREFIX + ChatColor.RED + "Deine Teleportanfrage an " + ChatColor.GOLD + target.getName()
                    + ChatColor.RED + " wurde abgelehnt.");
        }

        target.sendMessage(PREFIX + ChatColor.GRAY + "Du hast die Teleportanfrage abgelehnt.");
        return true;
    }

    private void startTeleportCountdown(Player player, Runnable onSuccess) {
        UUID playerId = player.getUniqueId();

        player.sendMessage(PREFIX + ChatColor.GRAY + "Du wirst in " + ChatColor.GOLD + RTP_COUNTDOWN_SECONDS
                + ChatColor.GRAY + " Sekunden teleportiert. Bewege dich nicht!");
        frozenAnchors.put(playerId, player.getLocation().clone());

        BukkitTask task = new BukkitRunnable() {
            private int secondsLeft = RTP_COUNTDOWN_SECONDS;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelAndCleanup();
                    return;
                }

                if (!frozenAnchors.containsKey(playerId)) {
                    cancelAndCleanup();
                    return;
                }

                if (secondsLeft <= 0) {
                    cancel();
                    pendingTeleportTasks.remove(playerId);
                    frozenAnchors.remove(playerId);
                    player.resetTitle();
                    onSuccess.run();
                    return;
                }

                player.sendTitle(ChatColor.GOLD + "" + secondsLeft + ChatColor.GRAY + " Sekunden bis zum Teleport",
                        "", 0, 20, 0);
                float pitch = 1.0f + (RTP_COUNTDOWN_SECONDS - secondsLeft) * 0.15f;
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, pitch);
                secondsLeft--;
            }

            private void cancelAndCleanup() {
                cancel();
                pendingTeleportTasks.remove(playerId);
                frozenAnchors.remove(playerId);
                if (player.isOnline()) {
                    player.resetTitle();
                }
            }
        }.runTaskTimer(PopcornSMPPlugin.this, 0L, 20L);

        pendingTeleportTasks.put(playerId, task);
    }

    private Component buildJoinQuitMessage(Player player, ChatColor actionColor, String actionText) {
        String legacyMessage = ChatColor.GOLD + player.getName() + ChatColor.GRAY + " hat den Server "
                + actionColor + ChatColor.BOLD + actionText + ChatColor.RESET;
        return LEGACY_SERIALIZER.deserialize(legacyMessage);
    }

    private Component buildTeleportRequestMessage(Player requester) {
        Component prefixComponent = LEGACY_SERIALIZER.deserialize(PREFIX);
        Component separator = LEGACY_SERIALIZER.deserialize(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH
                + "------------------------------");
        Component acceptButton = Component.text("[ANNEHMEN]", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true)
                .clickEvent(ClickEvent.runCommand("/tpaccept"))
                .hoverEvent(HoverEvent.showText(Component.text("Teleport annehmen", NamedTextColor.GREEN)));
        Component denyButton = Component.text("[ABLEHNEN]", NamedTextColor.RED)
                .decoration(TextDecoration.BOLD, true)
                .clickEvent(ClickEvent.runCommand("/tpdeny"))
                .hoverEvent(HoverEvent.showText(Component.text("Teleport ablehnen", NamedTextColor.RED)));

        return Component.text()
                .append(separator)
                .append(Component.newline())
                .append(prefixComponent)
                .append(Component.text(requester.getName(), NamedTextColor.GOLD))
                .append(Component.text(" möchte sich zu dir teleportieren.", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.newline())
                .append(LEGACY_SERIALIZER.deserialize(PREFIX))
                .append(acceptButton)
                .append(Component.text(" "))
                .append(denyButton)
                .append(Component.newline())
                .append(Component.newline())
                .append(LEGACY_SERIALIZER.deserialize(PREFIX))
                .append(Component.text("Nutze ", NamedTextColor.GRAY))
                .append(Component.text("/tpaccept", NamedTextColor.GOLD))
                .append(Component.text(" oder ", NamedTextColor.GRAY))
                .append(Component.text("/tpdeny", NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(separator)
                .build();
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
                    player.playSound(player.getLocation(), TELEPORT_SOUND, SoundCategory.MASTER,
                            TELEPORT_SOUND_VOLUME, TELEPORT_SOUND_PITCH);
                })
        ).exceptionally(throwable -> {
            getLogger().warning("Failed to prepare chunk for /rtp: " + throwable.getMessage());
            Bukkit.getScheduler().runTask(this, () -> player.sendMessage(PREFIX + ChatColor.RED + "Teleport fehlgeschlagen. Bitte versuche es erneut."));
            return null;
        });
    }

    private void teleportToPlayer(Player requester, Player target) {
        if (!requester.isOnline()) {
            return;
        }

        if (!target.isOnline()) {
            requester.sendMessage(PREFIX + ChatColor.RED + "Teleport fehlgeschlagen: Der Zielspieler ist nicht mehr online.");
            return;
        }

        Location targetLocation = target.getLocation();
        World world = targetLocation.getWorld();

        if (world == null) {
            requester.sendMessage(PREFIX + ChatColor.RED + "Teleport fehlgeschlagen: Welt nicht gefunden.");
            return;
        }

        world.getChunkAtAsync(targetLocation.getBlockX() >> 4, targetLocation.getBlockZ() >> 4).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(this, () -> {
                    requester.teleport(targetLocation);
                    requester.sendMessage(PREFIX + ChatColor.GRAY + "Du wurdest zu " + ChatColor.GOLD + target.getName()
                            + ChatColor.GRAY + " teleportiert.");
                    requester.playSound(requester.getLocation(), TELEPORT_SOUND, SoundCategory.MASTER,
                            TELEPORT_SOUND_VOLUME, TELEPORT_SOUND_PITCH);
                })
        ).exceptionally(throwable -> {
            getLogger().warning("Failed to prepare chunk for /tpa: " + throwable.getMessage());
            Bukkit.getScheduler().runTask(this, () -> requester.sendMessage(PREFIX + ChatColor.RED
                    + "Teleport fehlgeschlagen. Bitte versuche es erneut."));
            return null;
        });
    }

    private void cleanupTeleportRequest(TeleportRequest request) {
        incomingTeleportRequests.remove(request.targetId, request);
        outgoingTeleportRequests.remove(request.requesterId, request);
    }

    private void cancelPendingTeleport(UUID playerId) {
        BukkitTask task = pendingTeleportTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
        frozenAnchors.remove(playerId);
    }

    private static final class TeleportRequest {
        private final UUID requesterId;
        private final UUID targetId;
        private final long expiresAt;

        private TeleportRequest(UUID requesterId, UUID targetId, long expiresAt) {
            this.requesterId = requesterId;
            this.targetId = targetId;
            this.expiresAt = expiresAt;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
