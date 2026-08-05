package ru.example.itemhotkeys.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ru.example.itemhotkeys.VisualSwapState;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    /*
     * Подменяется только ItemStack, который рисуется
     * от первого лица.
     *
     * Реальный инвентарь и сетевые пакеты не меняются.
     */
    @ModifyVariable(
            method = "renderFirstPersonItem",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private ItemStack itemhotkeys$replaceRenderedStack(
            ItemStack original,
            AbstractClientPlayerEntity player,
            float tickDelta,
            float pitch,
            Hand hand,
            float swingProgress,
            ItemStack stack,
            float equipProgress,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light
    ) {
        if (!VisualSwapState.isActive()) {
            return original;
        }

        if (hand == Hand.MAIN_HAND) {
            return VisualSwapState
                    .getSavedMainHand()
                    .copy();
        }

        return VisualSwapState
                .getSavedOffHand()
                .copy();
    }
}
