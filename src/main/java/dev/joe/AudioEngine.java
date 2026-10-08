package dev.joe;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.bukkit.World;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

public class AudioEngine {

    private static final int FRAME_SIZE = 960;
    private static final long FRAME_NANOS = 20_000_000L;
    private static final AudioFormat TARGET =
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48000F, 16, 1, 2, 48000F, false);

    // Turns an .mp3 or .wav file into raw 48kHz mono sound
    public static short[] decode(Path file) throws Exception {
        VoicechatApi api = AudioVoicechatPlugin.api;
        AudioInputStream source;
        if (file.toString().toLowerCase().endsWith(".mp3")) {
            source = readMp3(file);
        } else {
            source = AudioSystem.getAudioInputStream(file.toFile());
        }
        try (AudioInputStream in = source) {
            AudioFormat src = in.getFormat();
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16,
                    src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
            AudioInputStream step1 = AudioSystem.getAudioInputStream(pcm, in);
            AudioInputStream step2 = AudioSystem.getAudioInputStream(TARGET, step1);
            return api.getAudioConverter().bytesToShorts(step2.readAllBytes());
        }
    }

    private static AudioInputStream readMp3(Path file) throws Exception {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int sampleRate = 0;
        int channels = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                sampleRate = buffer.getSampleFrequency();
                channels = buffer.getChannelCount();
                short[] samples = buffer.getBuffer();
                for (int i = 0; i < buffer.getBufferLength(); i++) {
                    pcm.write(samples[i] & 0xFF);
                    pcm.write((samples[i] >> 8) & 0xFF);
                }
                bitstream.closeFrame();
            }
            bitstream.close();
        }
        if (sampleRate == 0) {
            throw new IOException("No audio found in this mp3");
        }
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16,
                channels, channels * 2, sampleRate, false);
        byte[] data = pcm.toByteArray();
        return new AudioInputStream(new ByteArrayInputStream(data), format, data.length / format.getFrameSize());
    }

    // Plays the sound from a spot in the world. Returns the thread so it can be stopped later.
    public static Thread play(World world, double x, double y, double z, float distance, short[] pcm) {
        VoicechatApi api = AudioVoicechatPlugin.api;
        VoicechatServerApi server = AudioVoicechatPlugin.serverApi;
        if (api == null || server == null) {
            return null;
        }
        LocationalAudioChannel channel = server.createLocationalAudioChannel(
                UUID.randomUUID(), server.fromServerLevel(world), server.createPosition(x, y, z));
        if (channel == null) {
            return null;
        }
        channel.setDistance(distance);
        channel.setCategory(AudioVoicechatPlugin.CATEGORY_ID);

        Thread thread = new Thread(() -> {
            OpusEncoder encoder = api.createEncoder(OpusEncoderMode.AUDIO);
            int count = (pcm.length + FRAME_SIZE - 1) / FRAME_SIZE;
            byte[][] frames = new byte[count][];
            short[] window = new short[FRAME_SIZE];
            for (int i = 0; i < count; i++) {
                int length = Math.min(FRAME_SIZE, pcm.length - i * FRAME_SIZE);
                System.arraycopy(pcm, i * FRAME_SIZE, window, 0, length);
                if (length < FRAME_SIZE) {
                    Arrays.fill(window, length, FRAME_SIZE, (short) 0);
                }
                frames[i] = encoder.encode(window);
            }
            encoder.close();

            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                channel.send(frames[i]);
                long wait = start + (i + 1) * FRAME_NANOS - System.nanoTime();
                try {
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
            channel.flush();
        }, "DiscPlayer-playback");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}