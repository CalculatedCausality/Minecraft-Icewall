package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Atmospheric lore-whisper system.
 *
 * As players approach the glacier wall they receive brief, eerie messages and
 * ambient sounds at three distance tiers:
 *
 *   Tier 0 — Far approach (600–300 blocks): rare whispers on the actionbar.
 *   Tier 1 — Near approach (300–100 blocks): more frequent actionbar messages.
 *   Tier 2 — Critical zone (<100 blocks): dramatic subtitles.
 *
 * Each player has an independent per-tier cooldown so multiple players do not
 * receive messages in lockstep.
 */
public final class GlacialWhispers {

    // -----------------------------------------------------------------------
    // Lore message pools
    // -----------------------------------------------------------------------

    private static final String[] WHISPERS_FAR = {
        "A cold wind rises from the north...",
        "Something vast stirs in the distance.",
        "The birds have gone silent.",
        "Your breath fogs in the frozen air.",
        "Ice crystals form on the tips of your fingers.",
        "The ground is colder than it should be.",
        "The stars seem dimmer tonight.",
        "You hear groaning in the distance. Ice, settling.",
    };

    private static final String[] WHISPERS_NEAR = {
        "The cold has purpose.",
        "Run. There is no stopping it.",
        "Even stone bends to the glacier in the end.",
        "Ancient ice remembers this place.",
        "It has swallowed whole mountains before you.",
        "The temperature is still dropping.",
        "Every step forward is borrowed time.",
        "It does not hate you. It does not know you.",
    };

    private static final String[] WHISPERS_CRITICAL = {
        "The glacier remembers everything.",
        "Your warmth will be preserved. Forever.",
        "All things freeze in time.",
        "It was here before you. It will be here after.",
        "This is how the world ends. Quietly.",
        "You cannot outrun a kilometre of ice.",
        "Even fire freezes here.",
        "The wall does not stop.",
    };

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final Map<UUID, Long> nextWhisperTick = new HashMap<>();
    private final Random rng = new Random();

    // -----------------------------------------------------------------------
    // Tick
    // -----------------------------------------------------------------------

    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        long gt = world.getGameTime();
        int wallZ = state.getWallFrontZ();

        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) continue;
            int dist = player.blockPosition().getZ() - wallZ;
            if (dist <= 0 || dist > IceWallConfig.SNOW_ONSET_DISTANCE) continue;

            long nextTick = nextWhisperTick.getOrDefault(player.getUUID(), 0L);
            if (gt < nextTick) continue;

            int tier;
            long interval;
            if (dist < IceWallConfig.WHISPER_DISTANCE_CRITICAL) {
                tier = 2;
                interval = IceWallConfig.WHISPER_INTERVAL_CRITICAL + rng.nextInt(60);
            } else if (dist < IceWallConfig.WHISPER_DISTANCE_NEAR) {
                tier = 1;
                interval = IceWallConfig.WHISPER_INTERVAL_NEAR + rng.nextInt(100);
            } else {
                tier = 0;
                interval = IceWallConfig.WHISPER_INTERVAL_FAR + rng.nextInt(200);
            }

            sendWhisper(player, tier);
            playAmbientSound(world, player.blockPosition(), tier);
            nextWhisperTick.put(player.getUUID(), gt + interval);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void sendWhisper(ServerPlayer player, int tier) {
        String msg;
        switch (tier) {
            case 2 -> {
                msg = WHISPERS_CRITICAL[rng.nextInt(WHISPERS_CRITICAL.length)];
                // Dramatic subtitle (blank title so only subtitle shows)
                player.connection.send(new ClientboundSetTitlesAnimationPacket(15, 60, 20));
                player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
                player.connection.send(new ClientboundSetSubtitleTextPacket(
                        Component.literal("\u2744 " + msg)
                                .withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC)));
            }
            case 1 -> {
                msg = WHISPERS_NEAR[rng.nextInt(WHISPERS_NEAR.length)];
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("\u2744 " + msg)
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC)));
            }
            default -> {
                msg = WHISPERS_FAR[rng.nextInt(WHISPERS_FAR.length)];
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("\u2744 " + msg)
                                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
            }
        }
    }

    private void playAmbientSound(ServerLevel world, BlockPos pos, int tier) {
        switch (tier) {
            // Tier 0: distant — very low, eerie heartbeat
            case 0 -> world.playSound(null, pos, SoundEvents.WARDEN_HEARTBEAT,
                    SoundSource.AMBIENT, 0.2f, 0.35f + rng.nextFloat() * 0.15f);
            // Tier 1: near — audible heartbeat
            case 1 -> world.playSound(null, pos, SoundEvents.WARDEN_HEARTBEAT,
                    SoundSource.HOSTILE, 0.4f, 0.4f + rng.nextFloat() * 0.1f);
            // Tier 2: critical — loud, slow, ominous
            case 2 -> world.playSound(null, pos, SoundEvents.WARDEN_HEARTBEAT,
                    SoundSource.HOSTILE, 0.8f, 0.25f + rng.nextFloat() * 0.08f);
        }
    }
}
