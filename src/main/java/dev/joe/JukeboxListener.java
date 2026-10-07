package dev.joe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class JukeboxListener implements Listener {

    private final DiscPlayer plugin;
    private final NamespacedKey audioKey;
    private final Map<Location, Thread> playing = new HashMap<>();

    public JukeboxListener(DiscPlayer plugin, NamespacedKey audioKey) {
        this.plugin = plugin;
        this.audioKey = audioKey;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) {
            return;
        }
        if (event.getPlayer().isSneaking()
                && event.getPlayer().getInventory().getItemInMainHand().getType() != Material.AIR) {
            return;
        }
        Jukebox jukebox = (Jukebox) block.getState();
        if (jukebox.hasRecord()) {
            // a disc is being taken out
            stop(block.getLocation());
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.getType().name().startsWith("MUSIC_DISC_") || !item.hasItemMeta()) {
            return;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(audioKey, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        // wait one tick so the disc is really inside the jukebox first
        Bukkit.getScheduler().runTask(plugin, () -> start(block, id));
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.JUKEBOX) {
            stop(event.getBlock().getLocation());
        }
    }

    private void start(Block block, String id) {
        if (!(block.getState() instanceof Jukebox jukebox) || !jukebox.hasRecord()) {
            return;
        }
        jukebox.stopPlaying(); // silence the normal disc sound
        if (AudioVoicechatPlugin.serverApi == null) {
            return;
        }
        Path folder = plugin.getDataFolder().toPath().resolve("audio");
        Path file = Files.exists(folder.resolve(id + ".mp3")) ? folder.resolve(id + ".mp3") : folder.resolve(id + ".wav");
        if (!Files.exists(file)) {
            plugin.getLogger().warning("Audio file for ID " + id + " is missing");
            return;
        }
        Location key = block.getLocation();
        stop(key);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                short[] pcm = AudioEngine.decode(file);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!(block.getState() instanceof Jukebox j) || !j.hasRecord()) {
                        return;
                    }
                    Thread thread = AudioEngine.play(block.getWorld(),
                            block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5, 64F, pcm);
                    if (thread != null) {
                        playing.put(key, thread);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Could not play audio " + id + ": " + e.getMessage());
            }
        });
    }

    private void stop(Location key) {
        Thread thread = playing.remove(key);
        if (thread != null) {
            thread.interrupt();
        }
    }
}