package pearlthrow;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Middle mouse: throws an ender pearl where you aim, predicts its whole flight,
 * then throws a wind charge a few ticks later aimed at the exact point where
 * charge and pearl will be in the same place at the same time.
 */
public class PearlThrowClient implements ClientModInitializer {

    // ---- vanilla projectile values (from memory, adjust if a build disagrees) ----
    private static final double SPEED = 1.5;            // launch speed of pearl and wind charge
    private static final double PEARL_GRAVITY = 0.03;
    private static final double PEARL_DRAG = 0.99;
    private static final double PEARL_SPAWN_DROP = 0.1;  // pearl spawns at eyeY - 0.1
    private static final double CHARGE_SPAWN_DROP = 0.0; // wind charge spawns at eyeY

    // ---- tuning ----
    private static final int MAX_PEARL_TICKS = 80;       // how far ahead to simulate
    private static final int MAX_DELAY = 4;              // longest gap between pearl and charge
    private static final int MIN_FLIGHT_TICKS = 2;       // charge must fly at least this long
    private static final double MIN_SAFE_DISTANCE = 4.0; // keep the explosion this far from you
    private static final double HIT_TOLERANCE = 0.35;    // max predicted miss distance in blocks

    private record Aim(float yaw, float pitch, int hitTick, double error) {}

    private static KeyMapping boostKey;

    private static int pendingTicks = -1; // -1 = idle
    private static int delay;
    private static List<Vec3> pearlPath;
    private static Aim fallbackAim;
    private static int restoreSlot = -1;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pearlthrow", "main"));
        boostKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.pearlthrow.boost", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.gameMode == null || mc.level == null) {
                pendingTicks = -1;
                return;
            }

            if (pendingTicks > 0) {
                pendingTicks--;
                if (pendingTicks == 0) fireCharge(mc);
            }

            while (boostKey.consumeClick()) {
                if (pendingTicks < 0) start(mc);
            }
        });
    }

    // ------------------------------------------------------------------ flow

    private static void start(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!hasItem(p, Items.ENDER_PEARL)) { msg(p, "No ender pearl"); return; }
        if (!hasItem(p, Items.WIND_CHARGE)) { msg(p, "No wind charge"); return; }

        float yaw = p.getYRot();
        float pitch = p.getXRot();
        List<Vec3> path = simulatePearl(mc, p, yaw, pitch);

        // Pick the shortest delay that gives a solid intercept; otherwise the best one found.
        Aim chosen = null;
        int chosenDelay = 1;
        Vec3 move = p.getKnownMovement();
        for (int d = 1; d <= MAX_DELAY; d++) {
            Vec3 predictedStart = new Vec3(
                    p.getX() + move.x * d,
                    p.getEyeY() - CHARGE_SPAWN_DROP + move.y * d,
                    p.getZ() + move.z * d);
            Aim a = solveCharge(mc, p, path, d, predictedStart, inheritedVelocity(p));
            if (a == null) continue;
            if (chosen == null || a.error() < chosen.error()) {
                chosen = a;
                chosenDelay = d;
            }
            if (a.error() <= HIT_TOLERANCE) break;
        }

        if (chosen == null || chosen.error() > HIT_TOLERANCE * 2) {
            msg(p, "No clean intercept for this throw (aim somewhere with open space)");
            return;
        }

        restoreSlot = p.getInventory().getSelectedSlot();
        if (!throwItem(mc, Items.ENDER_PEARL, yaw, pitch)) return;

        pearlPath = path;
        delay = chosenDelay;
        fallbackAim = chosen;
        pendingTicks = chosenDelay;
        msg(p, String.format("Intercept in %d ticks, predicted miss %.2f", chosen.hitTick(), chosen.error()));
    }

    private static void fireCharge(Minecraft mc) {
        LocalPlayer p = mc.player;
        Vec3 start = new Vec3(p.getX(), p.getEyeY() - CHARGE_SPAWN_DROP, p.getZ());

        // Re-solve with where you actually are now (matters a lot on elytra)
        Aim aim = solveCharge(mc, p, pearlPath, delay, start, inheritedVelocity(p));
        if (aim == null || aim.error() > HIT_TOLERANCE * 2) aim = fallbackAim;

        throwItem(mc, Items.WIND_CHARGE, aim.yaw(), aim.pitch());
        if (restoreSlot >= 0) p.getInventory().setSelectedSlot(restoreSlot);
        pendingTicks = -1;
    }

    // ------------------------------------------------------------------ maths

    /** Pearl positions after 0,1,2,... ticks. Ends at the block it would hit. */
    private static List<Vec3> simulatePearl(Minecraft mc, LocalPlayer p, float yaw, float pitch) {
        Vec3 pos = new Vec3(p.getX(), p.getEyeY() - PEARL_SPAWN_DROP, p.getZ());
        Vec3 vel = direction(yaw, pitch).scale(SPEED).add(inheritedVelocity(p));

        List<Vec3> path = new ArrayList<>();
        path.add(pos);
        for (int t = 1; t <= MAX_PEARL_TICKS; t++) {
            Vec3 next = pos.add(vel);
            BlockHitResult hit = mc.level.clip(
                    new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() != HitResult.Type.MISS) {
                path.add(hit.getLocation());
                break;
            }
            path.add(next);
            pos = next;
            vel = vel.scale(PEARL_DRAG).add(0, -PEARL_GRAVITY, 0);
        }
        return path;
    }

    /**
     * Finds the aim that puts the wind charge exactly on the pearl.
     * The pearl was thrown 'd' ticks before the charge, so at pearl-tick t the charge has flown k = t - d ticks.
     * The charge moves in a straight line at constant speed (no gravity, no drag), so we need
     *   chargeStart + k * (1.5 * aimDir + inherited) == pearlPos(t)
     * Only the length of (1.5 * aimDir) is fixed, so for each t we check how far the length is off 1.5.
     * That mismatch times k is the predicted miss distance. The earliest good t wins.
     */
    private static Aim solveCharge(Minecraft mc, LocalPlayer p, List<Vec3> pearl, int d,
                                   Vec3 chargeStart, Vec3 inherited) {
        Aim best = null;
        int last = pearl.size() - 2; // must meet it before it lands

        for (int t = d + MIN_FLIGHT_TICKS; t <= last; t++) {
            int k = t - d;
            Vec3 target = pearl.get(t);
            if (target.distanceTo(chargeStart) < MIN_SAFE_DISTANCE) continue;

            Vec3 need = target.subtract(chargeStart).scale(1.0 / k).subtract(inherited);
            double len = need.length();
            if (len < 1.0E-6) continue;

            double error = Math.abs(len - SPEED) * k;

            BlockHitResult block = mc.level.clip(
                    new ClipContext(chargeStart, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (block.getType() != HitResult.Type.MISS) continue;

            float yaw = (float) Math.toDegrees(Math.atan2(-need.x, need.z));
            float pitch = (float) Math.toDegrees(-Math.asin(need.y / len));
            Aim aim = new Aim(yaw, Mth.clamp(pitch, -90f, 90f), t, error);

            if (error <= HIT_TOLERANCE) return aim; // earliest good intercept
            if (best == null || error < best.error()) best = aim;
        }
        return best;
    }

    private static Vec3 direction(float yaw, float pitch) {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        return new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
    }

    /** Projectiles inherit your velocity (vertical part only when airborne). */
    private static Vec3 inheritedVelocity(LocalPlayer p) {
        Vec3 m = p.getKnownMovement();
        return new Vec3(m.x, p.onGround() ? 0 : m.y, m.z);
    }

    // ------------------------------------------------------------------ item use

    /** Switches to the item (hotbar or offhand), uses it along the given rotation, keeps your view unchanged. */
    private static boolean throwItem(Minecraft mc, Item item, float yaw, float pitch) {
        LocalPlayer p = mc.player;
        InteractionHand hand;

        if (p.getMainHandItem().is(item)) {
            hand = InteractionHand.MAIN_HAND;
        } else if (p.getOffhandItem().is(item)) {
            hand = InteractionHand.OFF_HAND;
        } else {
            int slot = findHotbarSlot(p, item);
            if (slot < 0) return false;
            p.getInventory().setSelectedSlot(slot);
            hand = InteractionHand.MAIN_HAND;
        }

        float oldYaw = p.getYRot();
        float oldPitch = p.getXRot();
        p.setYRot(yaw);
        p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));

        mc.gameMode.useItem(p, hand);
        p.swing(hand);

        p.setYRot(oldYaw);
        p.setXRot(oldPitch);
        return true;
    }

    private static boolean hasItem(LocalPlayer p, Item item) {
        return p.getOffhandItem().is(item) || findHotbarSlot(p, item) >= 0;
    }

    private static int findHotbarSlot(LocalPlayer p, Item item) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private static void msg(LocalPlayer p, String text) {
        p.displayClientMessage(Component.literal(text), true);
    }
}
