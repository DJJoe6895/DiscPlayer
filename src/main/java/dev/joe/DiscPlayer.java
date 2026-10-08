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
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class DiscPlayer extends JavaPlugin {

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final String NO_NAME = "(no name)";
    private NamespacedKey audioKey;

    @Override
    public void onEnable() {
        audioKey = new NamespacedKey(this, "audio_id");
        getServer().getPluginManager().registerEvents(new JukeboxListener(this, audioKey), this);
        getServer().getPluginManager().registerEvents(new CommandCleaner(), this);
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
        if (!command.getName().equalsIgnoreCase("discplayer")) {
            return false;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("upload")) {
            if (!sender.isOp()) {
                sender.sendMessage(Component.text("Only operators can upload songs."));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(Component.text("Usage: /discplayer upload <url> [name]"));
                return true;
            }
            String name = args.length >= 3
                    ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim()
                    : "";
            downloadAudio(sender, args[1], name);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("apply")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can do this."));
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(Component.text("Usage: /discplayer apply <song name>"));
                return true;
            }
            String input = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            applyAudio(player, input);
            return true;
        }
        if (sender.isOp()) {
            sender.sendMessage(Component.text("Usage: /discplayer upload <url> [name] | apply <song name>"));
        } else {
            sender.sendMessage(Component.text("Usage: /discplayer apply <song name>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = sender.isOp() ? List.of("upload", "apply") : List.of("apply");
            return options.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("upload") && sender.isOp()) {
            return List.of("<url>");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("apply")) {
            return allNames().stream()
                    .map(n -> n.replace(' ', '_'))
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("upload") && sender.isOp()) {
            return List.of("[name]");
        }
        return List.of();
    }

    // ---------- saved audio info ----------

    private List<String> audioIds() {
        Path folder = getDataFolder().toPath().resolve("audio");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".mp3") || n.endsWith(".wav"))
                    .map(n -> n.substring(0, n.lastIndexOf('.')))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public String readName(String id) {
        try {
            Path file = getDataFolder().toPath().resolve("audio").resolve(id + ".txt");
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (!lines.isEmpty() && !lines.get(0).isBlank()) {
                    return lines.get(0).trim();
                }
            }
        } catch (IOException ignored) {
        }
        return NO_NAME;
    }

    private List<String> allNames() {
        List<String> names = new ArrayList<>();
        for (String id : audioIds()) {
            names.add(readName(id));
        }
        return names;
    }

    private boolean hasName(List<String> names, String name) {
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private String uniqueName(String base) {
        List<String> taken = allNames();
        String candidate = base;
        int n = 2;
        while (hasName(taken, candidate)) {
            candidate = base + " " + n;
            n++;
        }
        return candidate;
    }

    private String findId(String input) {
        String wanted = input.replace('_', ' ');
        for (String id : audioIds()) {
            if (id.equals(input) || readName(id).equalsIgnoreCase(wanted)) {
                return id;
            }
        }
        return null;
    }

    private String nameFromLink(String link) {
        try {
            String path = URI.create(link).getPath();
            if (path == null) {
                return NO_NAME;
            }
            String last = path.substring(path.lastIndexOf('/') + 1);
            last = URLDecoder.decode(last, StandardCharsets.UTF_8);
            int dot = last.lastIndexOf('.');
            if (dot > 0) {
                last = last.substring(0, dot);
            }
            last = last.replace('_', ' ').replace('-', ' ').trim();
            return last.isEmpty() ? NO_NAME : last;
        } catch (Exception e) {
            return NO_NAME;
        }
    }

    // ---------- commands ----------

    private void applyAudio(Player player, String input) {
        String id = findId(input);
        if (id == null) {
            player.sendMessage(Component.text("There's no song with that name."));
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!item.getType().name().startsWith("MUSIC_DISC_")) {
            player.sendMessage(Component.text("Hold a music disc in your main hand."));
            return;
        }
        String name = readName(id);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(audioKey, PersistentDataType.STRING, id);
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Custom audio", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
        player.sendMessage(Component.text("Applied \"" + name + "\" to your disc!"));
    }

    private void downloadAudio(CommandSender sender, String link, String name) {
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

                String base = name.isEmpty() ? nameFromLink(link) : name;
                if (base.length() > 50) {
                    base = base.substring(0, 50);
                }
                String finalName = uniqueName(base);
                Files.writeString(folder.resolve(id + ".txt"), finalName, StandardCharsets.UTF_8);

                sender.sendMessage(Component.text("Saved \"" + finalName + "\"! Players can use /discplayer apply " + finalName.replace(' ', '_')));
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