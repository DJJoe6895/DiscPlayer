package dev.joe;

import org.bukkit.plugin.java.JavaPlugin;

public class DiscPlayer extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("DiscPlayer enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("DiscPlayer disabled!");
    }
}