package lockon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A small bounded A* planner using legal walking, single-block jumps, small drops
 * and (when explicitly enabled) ordinary block mining.
 * Costs compare going around/jumping versus waiting for the tool to break a block.
 * This is not a full Baritone replacement and deliberately refuses risky terrain.
 */
final class Navigator {
    static final int MAX_SEARCH = 420;
    static final int MAX_RADIUS = 12;
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};
    private static final int[] DY = {0, 1, -1};

    record Step(BlockPos position, BlockPos digFoot, BlockPos digHead) {
        BlockPos nextBlockToBreak(ClientLevel level) {
            if (digFoot != null && !level.getBlockState(digFoot).getCollisionShape(level, digFoot).isEmpty()) {
                return digFoot;
            }
            if (digHead != null && !level.getBlockState(digHead).getCollisionShape(level, digHead).isEmpty()) {
                return digHead;
            }
            return null;
        }
    }
    private static final class Node {
        final BlockPos pos;
        final Node parent;
        final BlockPos digFoot, digHead;
        final double g, f;
        Node(BlockPos pos, Node parent, BlockPos digFoot, BlockPos digHead, double g, double f) {
            this.pos = pos; this.parent = parent;
            this.digFoot = digFoot; this.digHead = digHead;
            this.g = g; this.f = f;
        }
    }

    private Navigator() {}

    static Step route(ClientLevel level, LocalPlayer player, Player target, boolean mine) {
        BlockPos from = BlockPos.containing(player.getX(), player.getY() + 0.08, player.getZ());
        double goalX = target.getX();
        double goalZ = target.getZ();
        double goalY = target.getY();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<BlockPos, Double> bestCosts = new HashMap<>();
        Node start = new Node(from, null, null, null, 0, estimate(from, goalX, goalY, goalZ));
        open.add(start);
        bestCosts.put(from, 0.0);
        Node best = start;
        double bestScore = start.f;
        int processed = 0;
        while (!open.isEmpty() && processed++ < MAX_SEARCH) {
            Node node = open.poll();
            if (node.g > bestCosts.getOrDefault(node.pos, Double.POSITIVE_INFINITY) + 0.01) continue;
            double distance = estimate(node.pos, goalX, goalY, goalZ);
            double score = distance + node.g * 0.16;
            if (score < bestScore) { bestScore = score; best = node; }
            if (distance < 0.38) { best = node; break; }

            for (int dir = 0; dir < DX.length; dir++) {
                for (int change : DY) {
                    BlockPos next = node.pos.offset(DX[dir], change, DZ[dir]);
                    if (Math.abs(next.getX() - from.getX()) > MAX_RADIUS
                        || Math.abs(next.getZ() - from.getZ()) > MAX_RADIUS
                        || Math.abs(next.getY() - from.getY()) > 2) continue;
                    // Space over the departing node is essential for a one-block jump.
                    if (change > 0 && !passable(level, node.pos.above(2))) continue;
                    if (!supports(level, next.below())) continue;
                    double cost = 1.0 + (change > 0 ? 0.65 : change < 0 ? 0.24 : 0);
                    BlockPos digFoot = null, digHead = null;
                    if (!passable(level, next)) {
                        if (!mine || !mineable(level, player, next)) continue;
                        digFoot = next;
                        cost += diggingCost(level, player, next);
                    }
                    if (!passable(level, next.above())) {
                        if (!mine || !mineable(level, player, next.above())) continue;
                        digHead = next.above();
                        cost += diggingCost(level, player, digHead);
                    }
                    double newCost = node.g + cost;
                    if (newCost >= bestCosts.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    bestCosts.put(next, newCost);
                    double heuristic = estimate(next, goalX, goalY, goalZ);
                    open.add(new Node(next, node, digFoot, digHead, newCost, newCost + heuristic * 1.3));
                }
            }
        }
        if (best == start) return null;
        while (best.parent != null && best.parent != start) best = best.parent;
        return new Step(best.pos, best.digFoot, best.digHead);
    }

    // Probe the immediate direction of movement at feet height.
    // A solid block with clear space one and two blocks above it is
    // a normal single-block obstacle and can be climbed by jumping.
    static boolean oneBlockAhead(ClientLevel level, LocalPlayer player, double dx, double dz) {
        double length = Math.hypot(dx, dz);
        if (length < 0.01) return false;
        dx /= length;
        dz /= length;
        for (double distance : new double[] {0.42, 0.66, 0.92}) {
            BlockPos ahead = BlockPos.containing(
                player.getX() + dx * distance,
                player.getY() + 0.09,
                player.getZ() + dz * distance);
            if (!passable(level, ahead)
                && !level.getBlockState(ahead).getFluidState().isEmpty()) continue;
            if (!passable(level, ahead)
                && passable(level, ahead.above())
                && passable(level, ahead.above(2))) return true;
        }
        return false;
    }

    /**
     * Find the FIRST breakable obstruction in a direct path toward the enemy.
     * Never mines block entities, valuable containers, fluids or unbreakables.
     * The user must enable B for this to be called by chaseTarget().
     */
    static BlockPos directMiningObstacle(ClientLevel level, LocalPlayer player, Player target) {
        Vec3Sample direction = Vec3Sample.between(player, target);
        if (direction.length < 0.01) return null;
        BlockPos previous = null;
        double maxDistance = Math.min(4.0, direction.length);
        for (double distance = 0.60; distance <= maxDistance; distance += 0.18) {
            double x = player.getX() + direction.dx * distance;
            double z = player.getZ() + direction.dz * distance;
            for (double height : new double[] {0.15, 1.10}) {
                BlockPos sample = BlockPos.containing(x, player.getY() + height, z);
                if (sample.equals(previous)) continue;
                previous = sample;
                if (!passable(level, sample)) {
                    return mineable(level, player, sample) ? sample : null;
                }
            }
        }
        return null;
    }

    private record Vec3Sample(double dx, double dz, double length) {
        static Vec3Sample between(LocalPlayer player, Player target) {
            double x = target.getX() - player.getX();
            double z = target.getZ() - player.getZ();
            double length = Math.hypot(x, z);
            return length < 0.01 ? new Vec3Sample(0, 0, 0)
                : new Vec3Sample(x / length, z / length, length);
        }
    }

    /**
     * A small look-ahead prevents the direct chase from walking off a ledge.
     * Navigation jumps and pathfinding handle higher obstacles separately.
     */
    static boolean safeForward(ClientLevel level, LocalPlayer player, double dx, double dz) {
        double length = Math.hypot(dx, dz);
        if (length < 0.01) return false;
        BlockPos ahead = BlockPos.containing(
            player.getX() + (dx / length) * 0.76,
            player.getY() + 0.08,
            player.getZ() + (dz / length) * 0.76);
        return passable(level, ahead) && passable(level, ahead.above())
            && supports(level, ahead.below());
    }

    static boolean safeToJump(ClientLevel level, LocalPlayer player) {
        BlockPos feet = BlockPos.containing(player.getX(), player.getY() + 0.08, player.getZ());
        return supports(level, feet.below()) && passable(level, feet.above(2));
    }

    static boolean passable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && !hazard(state)
            && state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean supports(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getFluidState().isEmpty() && !hazard(s)
            && !s.getCollisionShape(level, pos).isEmpty();
    }

    static boolean mineable(ClientLevel level, LocalPlayer player, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir() || hazard(s) || !s.getFluidState().isEmpty()) return false;
        if (s.hasBlockEntity() || level.getBlockEntity(pos) != null) return false;
        float hardness = s.getDestroySpeed(level, pos);
        if (hardness < 0 || hardness > 3.0F) return false;
        return s.getDestroyProgress(player, level, pos) > 0.002f;
    }

    private static double diggingCost(ClientLevel level, LocalPlayer player, BlockPos pos) {
        float progress = level.getBlockState(pos).getDestroyProgress(player, level, pos);
        return Math.min(35.0, Math.max(1.3, 0.16 / Math.max(progress, 0.0001f)));
    }

    private static boolean hazard(BlockState s) {
        return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)
            || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK)
            || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
            || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.COBWEB);
    }

    private static double estimate(BlockPos at, double x, double y, double z) {
        // Stop near sword range, not at the opponent's exact location.
        double dx = at.getX() + 0.5 - x;
        double dz = at.getZ() + 0.5 - z;
        // Pathfinding must still make progress around obstructions even when
        // the opponent is horizontally within attack range but unreachable.
        double horizontal = Math.max(0, Math.sqrt(dx * dx + dz * dz) - 0.95);
        return horizontal + Math.abs((at.getY()) - y) * 2.0;
    }
}
