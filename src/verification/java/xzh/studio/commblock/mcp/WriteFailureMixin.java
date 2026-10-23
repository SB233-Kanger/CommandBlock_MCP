package xzh.studio.commblock.mcp.verification.mixin;
import xzh.studio.commblock.mcp.WorldTools;
import xzh.studio.commblock.mcp.WorldVerification;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(WorldTools.class)
public class WriteFailureMixin {
    @Inject(method = "configure", at = @At("HEAD"))
    private void injectFailure(CallbackInfo ci) {
        if (WorldVerification.writesUntilFailure > 0 && --WorldVerification.writesUntilFailure == 0)
            throw new IllegalStateException("Injected second-write failure");
    }
}
