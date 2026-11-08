package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * Hosts a Limited match in-process with the host seat played by the AI and lets an
 * {@link AgentSession} take the other seat through the bridge's own API. The agent plays the
 * trivial policy from the plan: answer any pending decision with its first option, otherwise
 * press OK, and when OK is disabled select the first card in hand (discard to hand size). The
 * game must reach an end and the agent must be able to quit the match afterwards.
 */
public class AgentGameTest {

    private static final int PORT = 36798;
    private FServerManager server;
    private ServerGameLobby lobby;
    private AgentSession session;

    @BeforeClass
    public void initializeModel() {
        GuiBase.setInterface(new AgentGuiBase());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(FPref.PLAYER_NAME, "HostAI"); // a blank name pops a Swing input dialog at match start
            preferences.setPref(FPref.UI_ENABLE_SOUNDS, false);
            preferences.setPref(FPref.UI_ENABLE_MUSIC, false);
            return null;
        });
        FModel.getNetPreferences().setPref(FNetPref.UPnP, "NEVER");
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (session != null) {
            session.disconnect();
        }
        if (server != null) {
            server.stopServer();
        }
    }

    @Test(timeOut = 600_000)
    public void agentPlaysAMatchAgainstTheHostAi() throws Exception {
        server = FServerManager.getInstance();
        lobby = new ServerGameLobby();
        server.startServer(PORT);
        server.setLobby(lobby);
        lobby.setListener(new IUpdateable() {
            @Override
            public void update(final boolean fullUpdate) {
                server.updateLobbyState();
            }

            @Override
            public void update(final int slot, final LobbySlotType type) {
            }
        });

        final LobbySlot hostSlot = lobby.getSlot(0);
        hostSlot.setType(LobbySlotType.AI);
        hostSlot.setDeck(DeckgenUtil.getRandomColorDeck(true));
        hostSlot.setIsReady(true);
        lobby.setLimitedMode(true);

        session = new AgentSession();
        session.connect("127.0.0.1", PORT, "AgentBot");
        waitFor("agent takes a lobby slot", 20_000, () -> ((Number) session.lobbySnapshot().get("mySlot")).intValue() == 1);

        final Deck agentDeck = DeckgenUtil.getRandomColorDeck(false);
        session.setDeck(agentDeck);
        session.setReady(true);
        waitFor("host sees the agent's deck and ready flag", 20_000,
                () -> lobby.getSlot(1).isReady() && lobby.getSlot(1).getDeck() != null);

        final Runnable start = lobby.startGame();
        assertNotNull(start, "lobby accepted the match setup");
        start.run();

        waitFor("agent gets openView", 30_000, () -> Boolean.TRUE.equals(session.gameSnapshot(false).get("inGame")));

        int actions = 0;
        int decisions = 0;
        long seen = -1;
        final long deadline = System.currentTimeMillis() + 540_000;
        Map<String, Object> g = session.gameSnapshot(false);
        while (!Boolean.TRUE.equals(g.get("gameOver"))) {
            assertTrue(System.currentTimeMillis() < deadline, "game did not end in time; last state " + g);
            final long rev = session.revision();
            if (rev == seen) {
                // Nothing new since we last acted: wait for the host rather than resend.
                session.awaitChange(rev, 3_000);
                g = session.gameSnapshot(false);
                continue;
            }
            seen = rev;
            @SuppressWarnings("unchecked")
            final Map<String, Object> pd = (Map<String, Object>) g.get("pendingDecision");
            @SuppressWarnings("unchecked")
            final Map<String, Object> buttons = (Map<String, Object>) g.get("buttons");
            final String prompt = String.valueOf(g.get("prompt"));
            if (pd != null) {
                decisions++;
                final int id = ((Number) pd.get("id")).intValue();
                final String kind = (String) pd.get("kind");
                @SuppressWarnings("unchecked")
                final List<String> options = (List<String>) pd.get("options");
                if ("text".equals(kind) || "number".equals(kind)) {
                    final String dflt = (String) pd.get("default");
                    assertTrue(session.decide(id, List.of(), dflt == null ? "0" : dflt));
                } else {
                    final int min = ((Number) pd.get("min")).intValue();
                    final List<Integer> first = new ArrayList<>();
                    for (int i = 0; i < Math.max(min, 1) && i < options.size(); i++) {
                        first.add(i);
                    }
                    assertTrue(session.decide(id, first, null));
                }
            } else if (prompt.startsWith("Waiting for")) {
                // The other player is acting; nothing to do.
            } else if (Boolean.TRUE.equals(buttons.get("okEnabled"))) {
                actions++;
                session.act("ok", null, null);
            } else {
                final Integer handCard = firstCardInHand();
                if (handCard != null) {
                    actions++;
                    session.act("selectCard", handCard, null);
                }
            }
            session.awaitChange(rev, 3_000);
            g = session.gameSnapshot(false);
        }

        final Map<String, Object> full = session.gameSnapshot(true);
        assertEquals(full.get("gameOver"), Boolean.TRUE);
        assertNotNull(full.get("winner"), "winner reported: " + full);
        assertTrue(((Number) full.get("turn")).intValue() > 1, "more than one turn: " + full);
        assertTrue(actions > 10, "agent passed priority through the game: " + actions);
        assertTrue(session.gameLog(0).size() > 10, "game log arrived");
        System.out.println("[AgentGameTest] game over on turn " + full.get("turn") + ", winner " + full.get("winner")
                + ", " + actions + " actions, " + decisions + " decisions, " + full.get("incorrectActions") + " incorrect");

        session.act("quit", null, null);
        waitFor("host ends the match", 30_000, () -> !lobby.isMatchActive());
        assertTrue(!lobby.getSlot(1).isReady(), "lobby resets ready flags after the match");
    }

    @SuppressWarnings("unchecked")
    private Integer firstCardInHand() {
        final Map<String, Object> full = session.gameSnapshot(true);
        final List<Map<String, Object>> players = (List<Map<String, Object>>) full.get("players");
        if (players == null) {
            return null;
        }
        for (final Map<String, Object> p : players) {
            if (Boolean.TRUE.equals(p.get("isMe"))) {
                final List<Map<String, Object>> hand = (List<Map<String, Object>>) p.get("hand");
                if (hand != null && !hand.isEmpty()) {
                    return ((Number) hand.get(0).get("id")).intValue();
                }
            }
        }
        return null;
    }

    private static void waitFor(final String what, final long timeoutMillis, final java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(100);
        }
    }
}
