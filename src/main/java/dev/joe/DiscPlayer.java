package dev.joe;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class DiscPlayer extends JavaPlugin {

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private NamespacedKey audioKey;

    @Override
    public void onEnable() {
        audioKey = new NamespacedKey(this, "audio_id");
        getServer().getPluginManager().registerEvents(new JukeboxListener(this, audioKey), this);
        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service != null) {
            service.registerPlugin(new AudioVoicechatPlugin());
            getLogger().info("Connected to Simple Voice Chat!");
        } else {
            getLogger().warning("Simple Voice Chat not found!");
        }
        getLogger().info("DiscPlayer enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("DiscPlayer disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("audioplayer")) {
            return false;
        }
        if (!sender.isOp()) {
            sender.sendMessage(Component.text("Only operators can use this."));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("url")) {
            downloadAudio(sender, args[1]);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("apply")) {
            if (sender instanceof Player player) {
                applyAudio(player, args[1]);
            } else {
                sender.sendMessage(Component.text("Only players can do this."));
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
            if (sender instanceof Player player) {
                testPlay(player, args[1]);
            } else {
                sender.sendMessage(Component.text("Only players can do this."));
            }
            return true;
        }
        sender.sendMessage(Component.text("Usage: /audioplayer url <link> or /audioplayer apply <id> or /audioplayer play <id>"));
        return true;
    }

    private void testPlay(Player player, String id) {
        if (!id.matches("[a-f0-9]{8}")) {
            player.sendMessage(Component.text("That doesn't look like an audio ID."));
            return;
        }
        Path folder = getDataFolder().toPath().resolve("audio");
        Path file = Files.exists(folder.resolve(id + ".mp3")) ? folder.resolve(id + ".mp3") : folder.resolve(id + ".wav");
        if (!Files.exists(file)) {
            player.sendMessage(Component.text("No audio found with that ID."));
            return;
        }
        if (AudioVoicechatPlugin.serverApi == null) {
            player.sendMessage(Component.text("Voice chat isn't ready yet."));
            return;
        }
        var location = player.getLocation();
        player.sendMessage(Component.text("Loading..."));
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                short[] pcm = AudioEngine.decode(file);
                AudioEngine.play(location.getWorld(), location.getX(), location.getY(), location.getZ(), 48F, pcm);
                player.sendMessage(Component.text("Playing!"));
            } catch (Exception e) {
                player.sendMessage(Component.text("Could not play: " + e.getMessage()));
            }
        });
    }

    private void applyAudio(Player player, String id) {
        if (!id.matches("[a-f0-9]{8}")) {
            player.sendMessage(Component.text("That doesn't look like an audio ID."));
            return;
        }
        Path folder = getDataFolder().toPath().resolve("audio");
        if (!Files.exists(folder.resolve(id + ".mp3")) && !Files.exists(folder.resolve(id + ".wav"))) {
            player.sendMessage(Component.text("No audio found with that ID."));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!item.getType().name().startsWith("MUSIC_DISC_")) {
            player.sendMessage(Component.text("Hold a music disc in your main hand."));
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(audioKey, PersistentDataType.STRING, id);
        meta.lore(List.of(
                Component.text("Custom audio: " + id, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
        player.sendMessage(Component.text("Audio applied to your disc!"));
    }

    private void downloadAudio(CommandSender sender, String link) {
        String lower = link.toLowerCase();
        String path = lower.split("\\?")[0];
        String ext;
        if (path.endsWith(".mp3")) {
            ext = "mp3";
        } else if (path.endsWith(".wav")) {
            ext = "wav";
        } else {
            sender.sendMessage(Component.text("The link must end in .mp3 or .wav"));
            return;
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            sender.sendMessage(Component.text("The link must start with http:// or https://"));
            return;
        }

        sender.sendMessage(Component.text("Downloading..."));

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            Path file = null;
            try {
                Path folder = getDataFolder().toPath().resolve("audio");
                Files.createDirectories(folder);
                String id = UUID.randomUUID().toString().substring(0, 8);
                file = folder.resolve(id + "." + ext);

                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(link)).build();
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    throw new IOException("Server answered with code " + response.statusCode());
                }

                long total = 0;
                try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_BYTES) {
                            throw new IOException("File is too big (max 20 MB)");
                        }
                        out.write(buffer, 0, read);
                    }
                }

                sender.sendMessage(Component.text("Saved! Audio ID: " + id));
            } catch (Exception e) {
                if (file != null) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                    }
                }
                sender.sendMessage(Component.text("Download failed: " + e.getMessage()));
            }
        });
    }
}