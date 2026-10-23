package xzh.studio.commblock.mcp;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
public class ClientVerification implements ClientModInitializer {
    public void onInitializeClient() {
        if (!Boolean.getBoolean("cbmcp.verification")) return;
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop()));
        boolean[] opened = {false};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!opened[0] && client.isGameLoadFinished()) {
                opened[0] = true;
                client.createWorldOpenFlows().openWorld("cbmcp-test", client::stop);
            }
        });
    }
}
