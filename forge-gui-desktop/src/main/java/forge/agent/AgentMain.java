package forge.agent;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * {@code forge agent [--port N]}: a headless Forge network client behind a local HTTP JSON API, for
 * the MCP server in {@code _mtg/forge-mcp-server}. Runs until killed.
 */
public final class AgentMain {

    private AgentMain() {
    }

    public static void run(final String[] args) {
        int port = 36744;
        for (int i = 1; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }

        GuiBase.setInterface(new AgentGuiBase());
        System.err.println("[agent] loading card database");
        final long t0 = System.currentTimeMillis();
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        System.err.println("[agent] card database loaded in " + (System.currentTimeMillis() - t0) + " ms");

        final AgentBridge bridge = new AgentBridge();
        try {
            bridge.start(port);
        } catch (final Exception e) {
            System.err.println("[agent] could not bind 127.0.0.1:" + port + ": " + e);
            System.exit(2);
        }
        System.out.println("AGENT_BRIDGE_READY port=" + bridge.port());
        System.out.flush();
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop, "Agent-Shutdown"));
        try {
            Thread.currentThread().join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
