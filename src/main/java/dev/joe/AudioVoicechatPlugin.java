package dev.joe;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

public class AudioVoicechatPlugin implements VoicechatPlugin {

    public static final String CATEGORY_ID = "discplayer";

    public static VoicechatApi api;
    public static VoicechatServerApi serverApi;

    @Override
    public String getPluginId() {
        return "discplayer";
    }

    @Override
    public void initialize(VoicechatApi voicechatApi) {
        api = voicechatApi;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            serverApi = event.getVoicechat();
            VolumeCategory category = serverApi.volumeCategoryBuilder()
                    .setId(CATEGORY_ID)
                    .setName("DiscPlayer")
                    .setDescription("The volume of custom music discs")
                    .setIcon(loadIcon())
                    .build();
            serverApi.registerVolumeCategory(category);
        });
    }

    // Reads the 16x16 picture from src/main/resources/category_icon.png
    private static int[][] loadIcon() {
        try (InputStream in = AudioVoicechatPlugin.class.getResourceAsStream("/category_icon.png")) {
            if (in == null) {
                return null;
            }
            BufferedImage image = ImageIO.read(in);
            if (image == null || image.getWidth() != 16 || image.getHeight() != 16) {
                return null;
            }
            int[][] icon = new int[16][16];
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    icon[x][y] = image.getRGB(x, y);
                }
            }
            return icon;
        } catch (Exception e) {
            return null;
        }
    }
}