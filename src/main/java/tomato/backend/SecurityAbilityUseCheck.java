package tomato.backend;

import assets.IdToAsset;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.StasisPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.security.SecurityGUI;
import tomato.realmshark.enums.CharacterClass;
import util.Util;

public class SecurityAbilityUseCheck {

    private static int decoyCounter = 0;
    private static final int DECOY_ID = 1813;
    private static final int DECOY_FOOLSPRISM_ID = 5136;
    private static final int DECOY_COIN_ID = 28831;
    private static final int DECOY_BRAIN_ID = 45313;
    private static final int DECOY_BRAIN_PUMPKIN_ID = 25736;
    private static final int[] PRISMS = {
        DECOY_ID,
        DECOY_FOOLSPRISM_ID,
        DECOY_COIN_ID,
        DECOY_BRAIN_ID,
        DECOY_BRAIN_PUMPKIN_ID,
    };

    /**
     * Incoming packets updates after observing an entity becoming stasised.
     *
     * @param p    Stasis packet
     * @param data
     */
    public static void stasis(StasisPacket p, TomatoData data) {
        if (p.unknownByteArray[1] != 22) return;
        float stasisDuration = p.stasisDuration;

        for (Entity player : data.playerListUpdated.values()) {
            if (player.stasisCounter == data.time) continue;
            StatData invSlot1 = player.stat.get(StatType.INVENTORY_1_STAT);
            if (invSlot1 == null) continue;
            if (StasisOrbs.usingOrb(invSlot1.statValue, stasisDuration)) {
                player.stasisCounter = 2;
            }
        }
    }

    /**
     * Checks if the player used mana when stasis is detected.
     *
     * @param entity Players that used stasis orbs.
     * @param stats  Stats of the player to check their mana use.
     */
    public static void checkManaFromStasis(Entity entity, StatData[] stats) {
        if (entity.stasisCounter > 0) {
            entity.stasisCounter--;
            for (StatData sd : stats) {
                if (sd.statType == StatType.MP_STAT) {
                    StatData currentMp = entity.stat.get(StatType.MP_STAT);
                    if (
                        currentMp != null &&
                        currentMp.statValue <= sd.statValue
                    ) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("[").append(Util.getHourTime()).append("] ");
                        sb.append(entity.name()).append(": ");
                        StatData invSlot1 = entity.stat.get(StatType.INVENTORY_1_STAT);
                        sb.append(invSlot1 != null ? IdToAsset.objectName(invSlot1.statValue) : "unknown item");
                        SecurityGUI.updateAbilityUsage(sb.toString());
                    }
                }
            }
        }
    }

    public static void decoy(Entity entity) {
        for (int prism : PRISMS) {
            if (entity.objectType == prism) {
                decoyCounter = 1;
                break;
            }
        }
    }

    public static void checkManaFromDecoyUsed(Entity entity, StatData[] stats) {
        if (CharacterClass.isPlayerCharacter(entity.objectType)
                && !CharacterClass.getName(entity.objectType).equals("Trickster")) return;
        if (decoyCounter == 0) {
            for (StatData sd : stats) {
                if (sd.statType == StatType.MP_STAT) {
                    StatData currentMp = entity.stat.get(StatType.MP_STAT);
                    if (
                        currentMp != null &&
                        currentMp.statValue <= sd.statValue
                    ) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("[").append(Util.getHourTime()).append("] ");
                        sb.append(entity.name()).append(": ");
                        StatData invSlot1 = entity.stat.get(StatType.INVENTORY_1_STAT);
                        sb.append(invSlot1 != null ? IdToAsset.objectName(invSlot1.statValue) : "unknown item");
                        SecurityGUI.updateAbilityUsage(sb.toString());
                    }
                }
            }
        }
    }

    public static void decreaseDecoyCounter() {
        decoyCounter--;
    }
}
