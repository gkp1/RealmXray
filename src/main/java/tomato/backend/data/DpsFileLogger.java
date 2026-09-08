package tomato.backend.data;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import tomato.gui.dps.shared.DeathParser;
import tomato.realmshark.enums.CharacterClass;

/**
 * Appends one json line per finished dungeon to logs/dps/current.jsonl. The log is truncated on
 * start so each Tomato run replaces the previous run's, and can be copied into logs/dps/saved to
 * survive that. Writes happen once per dungeon, never on the packet path.
 */
public class DpsFileLogger {

    public static final DpsFileLogger INSTANCE = new DpsFileLogger();

    private static final File LOG_DIR = new File("logs/dps");
    private static final File CURRENT_LOG = new File(LOG_DIR, "current.jsonl");
    private static final File SAVED_DIR = new File(LOG_DIR, "saved");

    private final Gson gson = new Gson();
    private PrintWriter out;

    /**
     * Opens a fresh log file, replacing the one from the previous run.
     */
    public synchronized void start() {
        close();
        try {
            LOG_DIR.mkdirs();
            out = new PrintWriter(new FileWriter(CURRENT_LOG, false));
        } catch (IOException e) {
            System.err.println(
                "Failed to open dps log file: " + e.getMessage()
            );
        }
    }

    public synchronized void logFinishedDungeon(DpsData data) {
        if (out == null) return;
        out.println(gson.toJson(new DungeonLog(data)));
        out.flush();
    }

    /**
     * Copies the running log into logs/dps/saved so the next start doesn't replace it.
     *
     * @return The saved file, or null if nothing has been logged yet.
     */
    public synchronized File saveCurrentLog() throws IOException {
        if (out != null) out.flush();
        if (!CURRENT_LOG.exists()) return null;
        SAVED_DIR.mkdirs();
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss").format(
            new Date()
        );
        File saved = new File(SAVED_DIR, "dps-log " + stamp + ".jsonl");
        Files.copy(
            CURRENT_LOG.toPath(),
            saved.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        );
        return saved;
    }

    public synchronized void close() {
        if (out != null) {
            out.close();
            out = null;
        }
    }

    /**
     * Flat snapshot of a dungeon run. DpsData itself can't be serialized to json: mob and player
     * entities reference each other through Damage.owner, which sends gson into infinite recursion.
     */
    private static class DungeonLog {

        final String dungeon;
        final String realm;
        final long startTime;
        final long durationMs;
        final Map<String, Integer> deaths;
        final List<MobLog> mobs = new ArrayList<>();

        DungeonLog(DpsData data) {
            dungeon = data.map != null ? data.map.name : null;
            realm = data.map != null ? data.map.realmName : null;
            startTime = data.dungeonStartTime;
            durationMs = data.totalDungeonPcTime;
            deaths = DeathParser.parseDeathsToMap(data.deathNotifications);

            for (Entity e : data.hitList.values()) {
                if (e.maxHp() <= 0) continue;
                if (CharacterClass.isPlayerCharacter(e.objectType)) continue;
                mobs.add(new MobLog(e));
            }
        }
    }

    private static class MobLog {

        final String name;
        final int objectType;
        final int maxHp;
        final long fightDurationMs;
        final List<PlayerLog> players = new ArrayList<>();

        MobLog(Entity mob) {
            name = mob.name();
            objectType = mob.objectType;
            maxHp = mob.maxHp();
            fightDurationMs = mob.getFightDuration();

            for (Damage d : mob.getPlayerDamageList()) {
                players.add(new PlayerLog(d, maxHp));
            }
        }
    }

    private static class PlayerLog {

        final String name;
        final int damage;
        final float percent;

        PlayerLog(Damage d, int mobMaxHp) {
            name = d.owner != null ? d.owner.name() : null;
            damage = d.damage;
            percent = mobMaxHp > 0 ? ((float) d.damage * 100) / mobMaxHp : 0;
        }
    }
}
