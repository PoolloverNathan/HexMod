package at.petrak.hexcasting.api.casting.circles;

import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.misc.Result;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.utils.HexUtils;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * See {@link BlockEntityAbstractImpetus}, this is what's stored in it
 */
public class CircleExecutionState {
    public static final String
        TAG_IMPETUS_POS = "impetus_pos",
        TAG_IMPETUS_DIR = "impetus_dir",
        TAG_REACHED_POSITIONS = "reached_positions",
        TAG_CURRENT_POS = "current_pos",
        TAG_ENTERED_FROM = "entered_from",
        TAG_IMAGE = "image",
        TAG_CASTER = "caster",
        TAG_PIGMENT = "pigment",
        TAG_REACHED_NUMBER = "reached_slate",
        TAG_POSITIVE_POS = "positive_pos",
        TAG_NEGATIVE_POS = "negative_pos";

    public final BlockPos impetusPos;
    public final Direction impetusDir;
    // Does contain the starting impetus
    public final HashSet<BlockPos> reachedPositions;
    public BlockPos currentPos;
    public Direction enteredFrom;
    public CastingImage currentImage;
    public @Nullable UUID caster;
    public @Nullable FrozenPigment casterPigment;
    // This controls the speed of the current slate
    public Long reachedSlate;

    // Tracks the highest pos, and lowest pos of the AABB
    public BlockPos positivePos;
    public BlockPos negativePos;

    public final AABB bounds;


    protected CircleExecutionState(BlockPos impetusPos, Direction impetusDir,
       HashSet<BlockPos> reachedPositions, BlockPos currentPos, Direction enteredFrom,
       CastingImage currentImage, @Nullable UUID caster, @Nullable FrozenPigment casterPigment, @Nullable Long reachedSlate,
       BlockPos positivePos, BlockPos negativePos) {
        this.impetusPos = impetusPos;
        this.impetusDir = impetusDir;
        this.reachedPositions = reachedPositions;
        this.currentPos = currentPos;
        this.enteredFrom = enteredFrom;
        this.currentImage = currentImage;
        this.caster = caster;
        this.casterPigment = casterPigment;
        this.reachedSlate = reachedSlate;

        this.positivePos = positivePos;
        this.negativePos = negativePos;

        this.bounds = new AABB(positivePos,negativePos);
    }

    public @Nullable ServerPlayer getCaster(ServerLevel world) {
        if (this.caster == null) {
            return null;
        }
        var entity = world.getEntity(this.caster);
        if (entity instanceof ServerPlayer serverPlayer) {
            return serverPlayer;
        }
        // there's a problem if this branch is reached
        return null;
    }

    // Return OK if it succeeded; returns Err if it didn't close and the location
    public static Result<CircleExecutionState, @Nullable BlockPos> createNew(BlockEntityAbstractImpetus impetus,
         @Nullable ServerPlayer caster) {
        var level = (ServerLevel) impetus.getLevel();

        if (level == null)
            return new Result.Err<>(null);

        // Flood fill! Just like VCC all over again.
        // this contains tentative positions and directions entered from
        var todo = new Stack<Pair<Direction, BlockPos>>();
        todo.add(Pair.of(impetus.getStartDirection(), impetus.getBlockPos().relative(impetus.getStartDirection())));
        var seenGoodPosSet = new HashSet<BlockPos>();
        var positiveBlock = new BlockPos.MutableBlockPos();
        var negativeBlock = new BlockPos.MutableBlockPos();
        var lastBlockPos = new BlockPos.MutableBlockPos();
        BlockPos firstBlock = null;

        while (!todo.isEmpty()) {
            /*
            Sophia/Stickia here!
            This block of code works well enough, it gets all the ICircleComponent that it can. However,
            this tries to do all the calculating in a single tick, so it can get *really* laggy for larger circles.
            So, this `while` would likely need to be moved into the #tick method, so it can be spread out over time during start up.
            Why are these comments here? Likely so Sophia can remember this for after she gets sleep, or if anyone
            else wants to take up the challenge. As for her system will work, but not be clean lmao

            As brought up by Chloe, the slates *could* change during start up.
            Meaning someone could just place/remove slate at the same rate of discovery, and have World Ambit for almost no slate
            cost and generally cheating the system. What would be best would be (somehow) getting the block slate
            without loading the chunk its self, meaning servers wont have to handle 1000s of chunks being loaded at once
            and players not cheating the system.
            But this is easier said than done.
            */

            var pair = todo.pop();
            var enterDir = pair.getFirst();
            var herePos = pair.getSecond();

            var hereBs = level.getBlockState(herePos);
            if (!(hereBs.getBlock() instanceof ICircleComponent cmp)) {
                continue;
            }
            if (!cmp.canEnterFromDirection(enterDir, herePos, hereBs, level)) {
                continue;
            }

            if (seenGoodPosSet.add(herePos)) {
                if (firstBlock == null) {
                    firstBlock = herePos;
                    negativeBlock.set(firstBlock);
                    positiveBlock.set(firstBlock);
                }
                lastBlockPos.set(herePos);
                // Checks to see if it should update the most positive/negative block pos
                if (herePos.getX() > positiveBlock.getX()) positiveBlock.setX(herePos.getX());
                if (herePos.getX() < negativeBlock.getX()) negativeBlock.setX(herePos.getX());

                if (herePos.getY() > positiveBlock.getY()) positiveBlock.setY(herePos.getY());
                if (herePos.getY() < negativeBlock.getY()) negativeBlock.setY(herePos.getY());

                if (herePos.getZ() > positiveBlock.getZ()) positiveBlock.setZ(herePos.getZ());
                if (herePos.getZ() < negativeBlock.getZ()) negativeBlock.setZ(herePos.getZ());

                // it's new
                var outs = cmp.possibleExitDirections(herePos, hereBs, level);
                for (var out : outs) {
                    todo.add(Pair.of(out, herePos.relative(out)));
                }
            }
        }

        if (firstBlock == null) {
            return new Result.Err<>(null);
        } else if (!seenGoodPosSet.contains(impetus.getBlockPos())) {
            // we can't enter from the side the directrix exits from, so this means we couldn't loop back.
            // the last item we tried to examine will always be a terminal slate (b/c if it wasn't,
            // then the *next* slate would be last qed)
            return new Result.Err<>(lastBlockPos);
        }

        var reachedPositions = new HashSet<BlockPos>();
        reachedPositions.add(impetus.getBlockPos());

        FrozenPigment colorizer = null;
        UUID casterUUID;
        if (caster == null) {
            casterUUID = null;
        } else {
            colorizer = HexAPI.instance().getColorizer(caster);
            casterUUID = caster.getUUID();
        }
        return new Result.Ok<>(
            new CircleExecutionState(impetus.getBlockPos(), impetus.getStartDirection(),
                reachedPositions, firstBlock, impetus.getStartDirection(), new CastingImage(), casterUUID, colorizer,
    0L, positiveBlock.move(1,1,1), negativeBlock));
    }

    public CompoundTag save() {
        var out = new CompoundTag();

        out.put(TAG_IMPETUS_POS, NbtUtils.writeBlockPos(this.impetusPos));
        out.putByte(TAG_IMPETUS_DIR, (byte) this.impetusDir.ordinal());

        var reachedTag = new ListTag();
        for (var bp : this.reachedPositions) {
            reachedTag.add(NbtUtils.writeBlockPos(bp));
        }
        out.put(TAG_REACHED_POSITIONS, reachedTag);

        out.put(TAG_CURRENT_POS, NbtUtils.writeBlockPos(this.currentPos));
        out.putByte(TAG_ENTERED_FROM, (byte) this.enteredFrom.ordinal());
        out.put(TAG_IMAGE, this.currentImage.serializeToNbt());

        if (this.caster != null)
            out.putUUID(TAG_CASTER, this.caster);

        if (this.casterPigment != null)
            out.put(TAG_PIGMENT, this.casterPigment.serializeToNBT());

        out.putLong(TAG_REACHED_NUMBER, this.reachedSlate);
        return out;
    }

    public static CircleExecutionState load(CompoundTag nbt, ServerLevel world) {
        var startPos = NbtUtils.readBlockPos(nbt.getCompound(TAG_IMPETUS_POS));
        var startDir = Direction.values()[nbt.getByte(TAG_IMPETUS_DIR)];

        var reachedPositions = new HashSet<BlockPos>();
        var reachedTag = nbt.getList(TAG_REACHED_POSITIONS, Tag.TAG_COMPOUND);
        for (var tag : reachedTag) {
            reachedPositions.add(NbtUtils.readBlockPos(HexUtils.downcast(tag, CompoundTag.TYPE)));
        }

        var currentPos = NbtUtils.readBlockPos(nbt.getCompound(TAG_CURRENT_POS));
        var enteredFrom = Direction.values()[nbt.getByte(TAG_ENTERED_FROM)];
        var image = CastingImage.loadFromNbt(nbt.getCompound(TAG_IMAGE), world);

        UUID caster = null;
        if (nbt.hasUUID(TAG_CASTER))
            caster = nbt.getUUID(TAG_CASTER);

        FrozenPigment pigment = null;
        if (nbt.contains(TAG_PIGMENT, Tag.TAG_COMPOUND))
            pigment = FrozenPigment.fromNBT(nbt.getCompound(TAG_PIGMENT));

        long reachedNumber = 0;
        if (nbt.contains(TAG_REACHED_NUMBER, Tag.TAG_LONG))
            reachedNumber = nbt.getLong(TAG_REACHED_NUMBER);

        BlockPos.MutableBlockPos positivePos = new BlockPos.MutableBlockPos();
        if (nbt.contains(TAG_POSITIVE_POS))
            positivePos.set(NbtUtils.readBlockPos(nbt.getCompound(TAG_POSITIVE_POS)));

        BlockPos.MutableBlockPos negativePos = new BlockPos.MutableBlockPos();
        if (nbt.contains(TAG_NEGATIVE_POS))
            negativePos.set(NbtUtils.readBlockPos(nbt.getCompound(TAG_NEGATIVE_POS)));

        return new CircleExecutionState(startPos, startDir, reachedPositions, currentPos,
            enteredFrom, image, caster, pigment, reachedNumber,positivePos,negativePos);
    }

    /**
     * Update this, also mutates the impetus.
     * <p>
     * Returns whether to continue.
     */
    public boolean tick(BlockEntityAbstractImpetus impetus) {
        var world = (ServerLevel) impetus.getLevel();

        if (world == null)
            return true; // if the world is null, try again next tick.

        var env = new CircleCastEnv(world, this);

        var executorBlockState = world.getBlockState(this.currentPos);
        if (!(executorBlockState.getBlock() instanceof ICircleComponent executor)) {
            // TODO: notification of the error?
            ICircleComponent.sfx(this.currentPos, executorBlockState, world,
                Objects.requireNonNull(env.getImpetus()), false);
            return false;
        }

        executorBlockState = executor.startEnergized(this.currentPos, executorBlockState, world);
        this.reachedPositions.add(this.currentPos);
        this.reachedSlate = this.reachedSlate +1;

        // Do the execution!
        boolean halt = false;
        var ctrl = executor.acceptControlFlow(this.currentImage, env, this.enteredFrom, this.currentPos,
            executorBlockState, world);

        if (env.getImpetus() == null)
            return false; //the impetus got removed during the cast and no longer exists in the world. stop casting

        if (ctrl instanceof ICircleComponent.ControlFlow.Stop) {
            // acceptControlFlow should have already posted the error
            halt = true;
        } else if (ctrl instanceof ICircleComponent.ControlFlow.Continue cont) {
            Pair<BlockPos, Direction> found = null;

            for (var exit : cont.exits) {
                var there = world.getBlockState(exit.getFirst());
                if (there.getBlock() instanceof ICircleComponent cc
                    && cc.canEnterFromDirection(exit.getSecond(), exit.getFirst(), there, world)) {
                    if (found != null) {
                        // oh no!
                        impetus.postDisplay(
                            Component.translatable("hexcasting.tooltip.circle.many_exits",
                                Component.literal(this.currentPos.toShortString()).withStyle(ChatFormatting.RED)),
                            new ItemStack(Items.COMPASS));
                        ICircleComponent.sfx(this.currentPos, executorBlockState, world,
                                Objects.requireNonNull(env.getImpetus()), false);
                        halt = true;
                        break;
                    } else {
                        found = exit;
                    }
                }
            }

            if (found == null) {
                // will never enter here if there were too many because found will have been set
                ICircleComponent.sfx(this.currentPos, executorBlockState, world,
                    Objects.requireNonNull(env.getImpetus()), false);
                impetus.postNoExits(this.currentPos);
                halt = true;
            } else {
                // A single valid exit position has been found.
                ICircleComponent.sfx(this.currentPos, executorBlockState, world,
                    Objects.requireNonNull(env.getImpetus()), true);
                currentPos = found.getFirst();
                enteredFrom = found.getSecond();
                currentImage = cont.update.withOverriddenUsedOps(0); // reset ops used after each slate finishes executing
            }
        }

        return !halt;
    }

    /**
     * How many ticks should pass between activations, given the number of blocks encountered so far.
     */
    protected int getTickSpeed() {
        return Math.toIntExact(Math.max(2, 10 - (this.reachedSlate - 1) / 3));
    }

    public void endExecution(BlockEntityAbstractImpetus impetus) {
        var world = (ServerLevel) impetus.getLevel();

        if (world == null)
            return; // TODO: error here?

        for (var pos : this.reachedPositions) {
            var there = world.getBlockState(pos);
            if (there.getBlock() instanceof ICircleComponent cc) {
                cc.endEnergized(pos, there, world);
            }
        }
    }
}
