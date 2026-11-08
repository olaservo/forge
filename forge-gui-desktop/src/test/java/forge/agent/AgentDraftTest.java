package forge.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.deck.Deck;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.IBoosterDraft;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.event.DraftPickEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.gui.interfaces.IDraftEventHandler;
import forge.interfaces.IUpdateable;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.CardBlock;
import forge.model.FModel;

/**
 * Hosts a two-seat network booster draft in-process and lets an {@link AgentSession} take the
 * second seat, picking through the bridge's own API until the pool arrives.
 */
public class AgentDraftTest {

    private static final int PORT = 36799;
    private FServerManager server;
    private ServerGameLobby lobby;
    private AgentSession session;
    private final ExecutorService hostPicker = Executors.newSingleThreadExecutor();

    @BeforeClass
    public void initializeModel() {
        GuiBase.setInterface(new AgentGuiBase());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
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
        hostPicker.shutdownNow();
    }

    @Test(timeOut = 180_000)
    public void agentDraftsTheSecondSeat() throws Exception {
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

        // The host seat auto-picks the first card of every pack it sees.
        final AtomicReference<Deck> hostPool = new AtomicReference<>();
        server.setDraftHandler(new IDraftEventHandler() {
            @Override
            public void draftPackArrived(final int seatIndex, final List<PaperCard> pack, final int packNumber,
                    final int pickNumber, final int timerDurationSeconds) {
                hostPicker.execute(() -> lobby.handleDraftPick(new DraftPickEvent(seatIndex, pack.get(0)), -1));
            }

            @Override
            public void draftSeatPicked(final int seatIndex, final int[] seatQueueDepths) {
            }

            @Override
            public void draftAutoPicked(final int seatIndex, final PaperCard card, final int packNumber, final int pickInPack) {
            }

            @Override
            public void receiveEventPool(final String eventId, final Deck pool) {
                hostPool.set(pool);
            }
        });

        session = new AgentSession();
        session.connect("127.0.0.1", PORT, "AgentBot");
        waitFor("agent takes a lobby slot", 20_000, () -> ((Number) session.lobbySnapshot().get("mySlot")).intValue() >= 0);
        assertEquals(lobby.getNumberOfSlots(), 2);

        final CardBlock block = FModel.getBlocks().get("Magic 2011");
        assertNotNull(block, "Magic 2011 block in res/blockdata");
        final BoosterDraft draft = new BoosterDraft(LimitedPoolType.Block, 2) {
            {
                for (int i = 0; i < 3; i++) {
                    product.add(block.getBooster("M11"));
                }
            }
        };
        IBoosterDraft.LAND_SET_CODE[0] = block.getLandSet();
        IBoosterDraft.CUSTOM_RANKINGS_FILE[0] = null;

        lobby.setLimitedMode(true);
        lobby.createEvent(EventFormat.BOOSTER_DRAFT);
        assertTrue(lobby.configureEvent(LimitedPoolType.Block, draft, 0, 0));
        final ServerGameLobby.DraftStartResult start = lobby.startDraftEvent();
        assertNotNull(start);
        assertEquals(start.names().length, 2);

        final long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            final Map<String, Object> d = session.draftSnapshot(false);
            if (Boolean.TRUE.equals(d.get("poolReceived"))) {
                break;
            }
            if (Boolean.TRUE.equals(d.get("pickPending"))) {
                session.pick("0");
            } else {
                session.awaitChange(session.revision(), 500);
            }
        }

        final Map<String, Object> pool = session.poolSnapshot();
        assertEquals(pool.get("poolReceived"), Boolean.TRUE, "agent received its pool: " + session.draftSnapshot(false));
        assertEquals(((Number) pool.get("size")).intValue(), 45, "three 15-card packs");
        @SuppressWarnings("unchecked")
        final List<String> tags = (List<String>) pool.get("tags");
        assertTrue(tags.stream().anyMatch(t -> t.startsWith("eventId")), "event tags: " + tags);
        assertEquals(session.picks.size(), 45);
        waitFor("host receives its pool", 10_000, () -> hostPool.get() != null);
        assertEquals(hostPool.get().getAllCardsInASinglePool().countAll(), 45);
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
