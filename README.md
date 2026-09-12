# RealmXray

### A public fork of RealmShark + Tomato, focused on reliability, observability, and connection insight.

This repo tracks [X-com/RealmShark](https://github.com/X-com/RealmShark) upstream (`main` ← `realmshark`, `tomato` ← `tomato`) and adds the features below on top. See [Original RealmShark README](#original-realmshark-readme) below for the base project's own docs, install guide, and credits — unchanged.

## 🎯 Goal

Upstream RealmShark/Tomato is a solid sniffer + DPS meter, but a few things
made it hard to trust during real play sessions: the DPS panel could go
silently stale mid-dungeon with no way to tell why, and there was no way to
see which packets were actually crossing the wire or which server you were
even connected to. This fork's additions all serve one goal: **make failures
visible instead of silent, and make the connection itself inspectable.**

## 🐛 Reliability fixes

Several bugs were causing the DPS panel to silently stop updating — sometimes
for the rest of a session — with no error shown anywhere. All were found by
building the diagnostic logging below and reading real crash data from it,
not by guessing.

- Fixed the DPS panel needing a Freeze→Unfreeze click to "unstick" after
  going stale (a Swing thread-safety bug — updates weren't happening on the
  EDT).
- Fixed the DPS panel freezing **permanently** after a dungeon finished, with
  no button able to recover it (a cross-thread visibility race in the new
  finished-dungeon preview feature below).
- Fixed a class-poisoning bug where a missing/stale game asset file
  (`mods2.xml`) could permanently break dungeon-loot tracking — and
  therefore DPS — for the rest of the session, immune to restarting the
  sniffer (only a full app restart cleared it, since the poisoned class
  state lives in the JVM, not the sniffer).
- Fixed several recurring `NullPointerException` crash loops in
  security/equipment/mana-tracking code that were silently aborting packet
  dispatch on every tick once triggered.
- Fixed an off-by-one bug in DPS history navigation (prev/next could land on
  the wrong dungeon).
- Disabled promiscuous mode on the packet capture (was on by default on every
  network interface, unnecessary overhead that scaled with however many
  interfaces — VPNs, virtual adapters — a machine reports).

## 📋 Full Packet Log tab *(new)*

*📸 screenshot: `docs/screenshots/packet-log.png`*

A real packet-by-packet log, not just aggregate stats — every frame that
crosses the wire, known or unknown packet type, with:
- Sortable columns, type-colored rows, stable scroll position during live
  updates (previously reset to the top on every refresh).
- An exclude filter (hide packet types by substring match) plus a
  right-click "Hide `<type>`" shortcut on any row.
- A detail view per packet (lazily-computed JSON, so viewing it costs
  nothing for the 99.9% of packets nobody looks at).
- Save-to-file (JSONL), so a session can be captured and inspected later.
- Server-IP-change events now show up here too — previously detected
  internally but silently discarded, never reaching the log or GUI at all.

## 📶 Server Info panel *(new)*

*📸 screenshot: `docs/screenshots/server-info.png`*

A small window (Info → Server Info) answering "what am I actually connected
to right now?" — tracks both your **current connection** and your **last
Nexus connection** separately (since entering a dungeon reconnects you to a
different server), each showing:
- IP address.
- Region (e.g. `EUEast`, `USWest4`) — resolved from a built-in table of known
  RotMG server IPs that already existed in the codebase but was never
  surfaced anywhere.
- Geolocation (country/region) via an online lookup, cached per IP.
- Live ping — measured as TCP connect-time to the game port itself, more
  representative of actual connection quality than ICMP, refreshed every
  10s.

## 📈 DPS logger improvements

*📸 screenshot: `docs/screenshots/dps-finished-preview.png`*

- **Live finished-dungeon preview**: when a dungeon ends, the DPS panel
  stays in live mode but shows that dungeon's final numbers for 10 seconds
  (with a countdown progress bar at the top) before automatically resuming
  live updates — read the result without losing your place.
- **Save Image**: a button (+ an "Always" checkbox) to export a PNG snapshot
  of the DPS card for any dungeon, rendered off-screen.
- **Session DPS file log**: every finished dungeon's stats are appended to a
  JSONL log file for the session automatically.

## ⚡ Performance & tooling

- Packet-log JSON serialization is now lazy — previously ran on every single
  captured packet (including high-frequency spam like movement) regardless
  of whether anything ever looked at it.
- `build-tomato.sh` — builds the RealmShark library, feeds the jar into the
  Tomato worktree, and builds Tomato, in one command. Works on Linux natively
  and on Windows 11 via Git Bash.
- `update-from-upstream.sh` — pulls the latest changes from
  [X-com/RealmShark](https://github.com/X-com/RealmShark) into whichever
  branch/worktree you run it from (`main` ← `upstream/realmshark`, `tomato` ←
  `upstream/tomato`).
- Fixed the Gradle wrapper so `./gradlew` works standalone without an IDE.

---

## Original RealmShark README

# RealmShark  
### A library/GUI packet sniffer for Realm of the Mad God built with Java.

Discord link: https://discord.gg/uDK2EhJUtv

RealmShark is a Java library/program created to read network packets at the kernel level without the the ability to modify, block or send packets. The library is EULA/copyright compliant because it does not use any game code or assets.  

Given RealmShark reads packets directly from the network adapter it can even be used to listen on a PC that is not running the game. It is an independent program from the game and is completely extendable/customizable.  

Multiple instances of Realm of the Mad God are not supported using this sniffer.  
As of now the sniffer cannot filter packets from multiple instances of the game running at the same time.   
The sniffer crashes if it cannot distinguish the packets from different instances at the network layer.  
In the future, OS specific functionality will be added to support multiple instances of clients.

#### Credits:

- Most backed code was written by [Cortex](https://github.com/MCRcortex). Huge thanks to him.
- Inspired by work done by [abrn](https://github.com/abrn/realmlib) and [thomas-crane](https://github.com/thomas-crane/realmlib-net).
- [ardikars](https://github.com/ardikars/pcap) library for packet processing.
- [libpcap or winpcap](https://npcap.com/) is used by the ardikars library to make the packet sniffing possible.

## Install guide

MAC support is not available right now. It will be added in a future version.

For Windows:

1. Java and Npcap is required for running the program. Java can be downloaded from [here](https://www.java.com/en/download/) and Npcap from [here](https://npcap.com/#download). Open the files one at a time and follow the install instructions for both.

2. Download the latest `Tomato-v*.jar` file from [Releases](https://github.com/X-com/RealmShark/releases). Only need the *.jar file.

- Java download [image](https://user-images.githubusercontent.com/5974568/183230180-f9a66d31-2ed4-4073-8af2-cda12f271d01.png).
- Npcap download [image](https://user-images.githubusercontent.com/5974568/183230181-b8eacef2-71f3-47f5-8d46-959eb1bb82bf.png).
- Jar download [image](https://user-images.githubusercontent.com/5974568/183230231-b47f588a-08be-42f1-942f-8f0facf41aa0.png).  

3. Run the program by simply opening the downloaded Tomato-v*.jar file.  

4. The RealmShark GUI should open. Start it by clicking File -> Start Sniffer. All chat in the game should appear in the Chat tab.

If there are errors running the program described above, please look under Trouble shooting guide or [open an issue here,](https://github.com/X-com/RealmShark/issues) so it can be resolved.

## Troubleshooting guide

Join discord for live tech support: https://discord.gg/uDK2EhJUtv

Windows troubleshooting guide:

Some Windows 11 users have issues with Npcap 1.70. Try and uninstall 1.70 and install the 1.60 version. Link found here, [Npcap 1.60](https://www.mediafire.com/file/xkjmfz1v1b47e0a/npcap-1.60.exe/file).

If the Tomato-v*.jar file does nothing double clicking it after following the installation guide above. Open powershell or CMD in the folder where the Tomato-v*.jar file is located.

1. Open File Explorer and navigate to the folder where the *.jar is located.
2. Right click in the empty space inside the folder, while holding shift. Then select Powershell or CMD. [Example image](https://user-images.githubusercontent.com/5974568/183230822-a35e2c52-8235-4efa-8543-9219b4611adc.png)
3. If PowerShell is opened. type "cmd" and press Enter. If CMD is opened, skip to step 4.
4. Type "java -jar ". Make sure to add space after "-jar ".
5. Press tab several times until the Tomato-v*.jar name appears. Then press Enter. [Example image](https://user-images.githubusercontent.com/5974568/183231024-a1e006b7-7dd0-43f3-8a99-4fdee3827f94.png)

If the program starts without problems it means you have issues with your register keys. To fix your register to not need command prompt to start the program a simple jarfix is needed. If the program still doesn't start, report the bug in the [issues here](https://github.com/X-com/RealmShark/issues). Try to include as much information as possible in the report.

1. Download the jarfix from [here](https://johann.loefflmann.net/en/software/jarfix/index.html). Image of file [here](https://user-images.githubusercontent.com/5974568/183231327-ac0a33c7-edb4-41bb-897f-bb86fa9ab939.png).
2. Run it as Administrator. Example of running the jarfix [here](https://user-images.githubusercontent.com/5974568/183231330-9d53b0b9-8288-4cab-a726-4095f3e3f479.png).
3. Start the program by double clicking Tomato-v*.jar.

If you can start the program, but you can not see any chat messages from ingame chat after starting the sniffer.

1. Open the program in console prompt described above.
2. Start the sniffer and check console for error messages.
3. If you get an error stating "The pcap_t has not been activated" similar to this [image](https://user-images.githubusercontent.com/5974568/183231488-c79f0189-4513-4b06-85d7-17deb610a340.png) your network interface is faulty or you are missing a Loopback Adapter. 
4. Follow this guide, link [here](https://tencomputer.com/npcap-loopback-adapter-no-internet/), for repairing your network interface (recommending to do the steps in inverted order starting with step 5). Do one step at a time and check if it fixes the problem before trying the next.
5. If it still doesn't fix the problem follow a youtube guide to install a Loopback Adapter [here](https://www.youtube.com/watch?v=N3Ido5VEkNE).

If any other problem shows up. Please report them in the issues tracker found [here](https://github.com/X-com/RealmShark/issues) to have it resolved. Make sure to include any console outputs, version of java installed (type "java -v" in console to get the version), windows version and other reproduction steps.

## Building from source!

This is written for use with IntelliJ IDEA only. Other IDEs can also  be used in a similar manner.

You will need an OpenJDK 8 SDK install from [here.](https://jdk.java.net/18/) If you are using IntelliJ IDEA, IntelliJ can install the JDK automatically.

IntelliJ IDEA can be found [here](https://www.jetbrains.com/idea/download/#section=windows). Download the free community edition.

Download the .zip or clone the repo via CLI:
`git clone https://github.com/X-com/RealmShark && cd RealmShark`

Open IntelliJ and click **File > New > Project from Existing Sources...**
Navigate to the realm shark source folder and open build.gradle. Alternatively drag and drop the build.gradle into the IntelliJ window and then double click on it. IntelliJ will automatically install gradle and setup the project.

The application can now be built, ran and debugged from IntelliJ.

To build a runnable jar. Use the gradle tool named shadowJar. The [shadowJar](https://user-images.githubusercontent.com/5974568/185830689-3031bb23-7d6c-416f-984d-4d460f15140c.png) will build a runnable jar and place it in the build/libs folder. The shadowJar build tool builds a fat jar including all the resources. Note! This includes any files that can't be red from within the jar, i.e. dll files.
 - If resources have to be extracted out of the jar during runtime. An example class called LibExtractor.java is found in the project to help extract resources out of the jar during runtime in case it is needed.
 - Both application name and the version can be modified in the build.gradle folder. Simply modify the applicationName to change the release name or project.version to change the release version.

Setting up Tomato or Potato requires RealmShark-vXX.jar to be built as explained above and placed in the /libs folder.
1. Swap to the "realmshark" branch on github.
2. Follow the guide above to build the RealmShark-vXX.jar build using shadow jar builder.
3. Move the RealmShark-vXX.jar file from "./build/libs" into to "./libs".
4. Swap branch to "tomato" or "potato".
5. Run either app with the corresponding class "Tomato.java" or "Potato.java"
6. When ready to build the edited tomato/potato, follow the guide above using the shadow jar again.
