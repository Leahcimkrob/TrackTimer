package de.ethria.trackTimer.race;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.TrafficLightSounds;
import de.ethria.trackTimer.database.DatabaseManager.TrafficLightStartPoint;
import de.ethria.trackTimer.database.DatabaseManager.StartPoint;
import de.ethria.trackTimer.tools.TrafficLightTriggerTool;
import de.ethria.trackTimer.tools.TrafficLightTriggerTool.SignalHead;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/** Runs the traffic-light countdown and rejects drivers who cross before the go signal. */
public final class TrafficLightRaceListener implements Listener {
    private static final String DARK_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjRhYWM4ZTcxMzczNjcyOWM2ODA3MWIyNDlkMzNmYmQ3MWEwYTNjZWQwMzIxZTkyOGY5NGJjOTNjMGU5NDZmNyJ9fX0=";
    private static final String RED_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWE0NGRhMGFmOTBhY2I2MDdlYWIyOGYyODc5ODUwNGE3MzE4OTM3YTE1N2ZiM2EwM2UxNDdhZTcwZTM1MzFjZSJ9fX0=";

    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final de.ethria.trackTimer.language.LanguageManager language;
    private final TrafficLightTriggerTool lightTool;
    private final RaceStartListener races;
    private final Map<Long, StartSequence> sequences = new HashMap<>();

    public TrafficLightRaceListener(JavaPlugin plugin, DatabaseManager database,
                                    de.ethria.trackTimer.language.LanguageManager language,
                                    TrafficLightTriggerTool lightTool, RaceStartListener races) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
        this.lightTool = lightTool;
        this.races = races;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        if (event.getOldCurrent() > 0 || event.getNewCurrent() <= 0) return;
        Block block = event.getBlock();
        try {
            for (TrafficLightStartPoint trigger : database.findTrafficLightStartTriggers(
                    database.getServerName(), block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ())) {
                startSequence(trigger, block);
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not start a traffic-light countdown.", exception);
        }
    }

    /** Called by the ordinary start detector; false means the line crossing is suppressed. */
    public boolean allowStart(Player player, StartPoint point) {
        StartSequence sequence = sequences.get(point.eventId());
        if (sequence == null || sequence.aborted) return false;
        if (sequence.released) return true;
        abort(sequence);
        return false;
    }

    private void startSequence(TrafficLightStartPoint trigger, Block redstoneBlock) throws SQLException {
        StartSequence previous = sequences.get(trigger.eventId());
        if (previous != null && previous.hasStarted) return;
        List<List<SignalHead>> rows = lightTool.signalHeadRows(trigger.eventId(),
                database.getServerName(), redstoneBlock.getWorld().getName());
        if (rows.isEmpty()) {
            plugin.getLogger().warning("Traffic-light start for event " + trigger.eventName()
                    + " was ignored because no traffic-light heads are saved in this world.");
            return;
        }
        previous = sequences.remove(trigger.eventId());
        if (previous != null) {
            if (previous.task != null) previous.task.cancel();
            if (previous.joinTimeout != null) previous.joinTimeout.cancel();
            setAllDark(previous.rows);
        }
        TrafficLightSounds sounds = database.getTrafficLightSounds(trigger.eventId());
        StartSequence sequence = new StartSequence(trigger.eventId(), trigger.eventName(),
                redstoneBlock.getLocation().add(.5, .5, .5), rows, sounds);
        races.resetLiveStandings(trigger.eventId());
        sequences.put(sequence.eventId, sequence);
        sequence.task = Bukkit.getScheduler().runTask(plugin, () -> turnOnNextLight(sequence));
    }

    private void turnOnNextLight(StartSequence sequence) {
        if (sequences.get(sequence.eventId) != sequence || sequence.aborted) return;
        if (sequence.nextLight >= 5) {
            long delay = ThreadLocalRandom.current().nextLong(20, 61);
            sequence.task = Bukkit.getScheduler().runTaskLater(plugin, () -> releaseStart(sequence), delay);
            return;
        }

        int lightIndex = sequence.nextLight++;
        for (List<SignalHead> row : sequence.rows) {
            SignalHead head = row.get(lightIndex);
            TrafficLightTriggerTool.setHeadTexture(head.block(), RED_TEXTURE, head.rotation());
        }
        playNote(sequence.rows.getFirst().get(lightIndex).block().getLocation().add(.5, .5, .5),
                sequence.sounds.sound(), sequence.sounds.redPitch(lightIndex));
        sequence.task = Bukkit.getScheduler().runTaskLater(plugin,
                () -> turnOnNextLight(sequence), 20L);
    }

    private void releaseStart(StartSequence sequence) {
        if (sequences.get(sequence.eventId) != sequence || sequence.aborted) return;
        sequence.task = null;
        try {
            sequence.session = database.activateRedstoneSession(sequence.eventId, System.currentTimeMillis());
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not activate the traffic-light start session.", exception);
            abort(sequence);
            return;
        }
        playLongNote(sequence.center, sequence.sounds.sound(), sequence.sounds.go());
        sendTitle(sequence.center, language.chatFragment("race.start-title"));
        setAllDark(sequence.rows);
        sequence.released = true;
        long timeoutSeconds = Math.max(0,
                plugin.getConfig().getLong("race.redstone-start-join-timeout-seconds", 30L));
        if (timeoutSeconds > 0) {
            long delayTicks = timeoutSeconds > Long.MAX_VALUE / 20 ? Long.MAX_VALUE : timeoutSeconds * 20;
            sequence.joinTimeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                sequence.joinTimeout = null;
                if (sequences.get(sequence.eventId) == sequence && !sequence.hasStarted
                        && !races.hasActiveRaces(sequence.eventId)) {
                    endSequence(sequence);
                }
            }, delayTicks);
        }
    }

    /** Called after a driver successfully crosses the released traffic-light start. */
    public void participantStarted(long eventId) {
        StartSequence sequence = sequences.get(eventId);
        if (sequence == null || !sequence.released) return;
        sequence.hasStarted = true;
        if (sequence.joinTimeout != null) sequence.joinTimeout.cancel();
        sequence.joinTimeout = null;
    }

    /** Returns the shared GO-time session for a released traffic-light start. */
    public DatabaseManager.RedstoneSession session(long eventId) {
        StartSequence sequence = sequences.get(eventId);
        return sequence == null || !sequence.released ? null : sequence.session;
    }

    /** Ends the start session once its last driver finishes or leaves. */
    public void participantsChanged(long eventId) {
        StartSequence sequence = sequences.get(eventId);
        if (sequence != null && sequence.hasStarted && !races.hasActiveRaces(eventId)) endSequence(sequence);
    }

    private void endSequence(StartSequence sequence) {
        if (!sequences.remove(sequence.eventId, sequence)) return;
        if (sequence.task != null) sequence.task.cancel();
        if (sequence.joinTimeout != null) sequence.joinTimeout.cancel();
        sequence.task = null;
        sequence.joinTimeout = null;
        if (sequence.session != null && !sequence.hasStarted) expireSession(sequence);
        setAllDark(sequence.rows);
    }

    private void expireSession(StartSequence sequence) {
        try {
            database.expireUnjoinedRedstoneSession(sequence.session.id(), sequence.session.startTime());
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not remove an unused traffic-light start session.", exception);
        }
    }

    private void abort(StartSequence sequence) {
        if (sequence.task != null) sequence.task.cancel();
        if (sequence.joinTimeout != null) sequence.joinTimeout.cancel();
        sequence.task = null;
        sequence.joinTimeout = null;
        sequence.aborted = true;
        sequences.remove(sequence.eventId, sequence);
        if (sequence.session != null && !sequence.hasStarted) expireSession(sequence);
        setAllDark(sequence.rows);
        sendTitle(sequence.center, language.chatFragment("race.traffic-light-abort-title"));
    }

    private void setAllDark(List<List<SignalHead>> rows) {
        for (List<SignalHead> row : rows) {
            for (SignalHead head : row) {
                TrafficLightTriggerTool.setHeadTexture(head.block(), DARK_TEXTURE, head.rotation());
            }
        }
    }

    private void playNote(Location location, String soundId, int note) {
        float pitch = notePitch(note);
        Sound sound;
        try { sound = Sound.valueOf(soundId); }
        catch (IllegalArgumentException exception) { sound = Sound.BLOCK_NOTE_BLOCK_BIT; }
        location.getWorld().playSound(location, sound, 1.0f, pitch);
    }

    private void playLongNote(Location location, String soundId, int note) {
        playNote(location, soundId, note);
        // Note-block sounds have a fixed sample length. Replay the same note once
        // after its pitch-adjusted sample duration to make the GO tone about twice as long.
        long sampleTicks = Math.max(1, Math.round(10.0 / notePitch(note)));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (location.getWorld() != null) playNote(location, soundId, note);
        }, sampleTicks);
    }

    private float notePitch(int note) {
        return (float) Math.pow(2.0, (Math.max(0, Math.min(24, note)) - 12) / 12.0);
    }

    private void sendTitle(Location center, Component title) {
        Title message = Title.title(title, Component.empty());
        double radius = Math.max(0, plugin.getConfig().getDouble("race.redstone-start-title-radius-blocks", 50));
        double radiusSquared = radius * radius;
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= radiusSquared) player.showTitle(message);
        }
    }

    public void shutdown() {
        for (StartSequence sequence : new ArrayList<>(sequences.values())) {
            if (sequence.task != null) sequence.task.cancel();
            if (sequence.joinTimeout != null) sequence.joinTimeout.cancel();
            setAllDark(sequence.rows);
        }
        sequences.clear();
    }

    private static final class StartSequence {
        private final long eventId;
        @SuppressWarnings("unused")
        private final String eventName;
        private final Location center;
        private final List<List<SignalHead>> rows;
        private final TrafficLightSounds sounds;
        private int nextLight;
        private boolean released;
        private boolean aborted;
        private boolean hasStarted;
        private DatabaseManager.RedstoneSession session;
        private BukkitTask task;
        private BukkitTask joinTimeout;

        private StartSequence(long eventId, String eventName, Location center,
                              List<List<SignalHead>> rows, TrafficLightSounds sounds) {
            this.eventId = eventId;
            this.eventName = eventName;
            this.center = center;
            this.rows = rows;
            this.sounds = sounds;
        }
    }
}
