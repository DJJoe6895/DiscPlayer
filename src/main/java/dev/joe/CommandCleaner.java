package dev.joe;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

public class CommandCleaner implements Listener {

    @EventHandler
    public void onCommandList(PlayerCommandSendEvent event) {
        event.getCommands().remove("discplayer:discplayer");
    }
}