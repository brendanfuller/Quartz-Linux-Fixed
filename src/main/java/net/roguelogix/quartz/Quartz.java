package net.roguelogix.quartz;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.roguelogix.phosphophyllite.registry.Registry;
import org.joml.Vector3ic;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import net.roguelogix.quartz.internal.QuartzCore;

import java.util.function.Consumer;

@Mod(Quartz.modid)
@NonnullDefault
public final class Quartz {
    public static final String modid = "quartz";
    
    public Quartz(){
        new Registry();
    }
    
    public static IEventBus EVENT_BUS = BusBuilder.builder().setTrackPhases(false).build();
    
    public static Mesh createStaticMesh(BlockState blockState) {
        return createStaticMesh(builder -> {
            //noinspection ConstantConditions
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(blockState, builder.matrixStack(), builder.bufferSource(), 0, 0x00000, ModelData.EMPTY, null);
        });
    }
    
    public static Mesh createStaticMesh(Consumer<Mesh.Builder> buildFunc) {
        return QuartzCore.INSTANCE.meshManager.createMesh(buildFunc);
    }
    
    public static DrawBatch getDrawBatchForBlock(BlockPos blockPos) {
        return getDrawBatcherForSection(SectionPos.asLong(blockPos));
    }
    
    public static DrawBatch getDrawBatcherForBlock(Vector3ic blockPos) {
        return getDrawBatcherForSection(SectionPos.asLong(blockPos.x() >> 4, blockPos.y() >> 4, blockPos.z() >> 4));
    }
    
    public static DrawBatch getDrawBatcherForSection(long sectionPos) {
        return QuartzCore.INSTANCE.getWorldEngine().getBatcherForSection(sectionPos);
    }
    
    public static DrawBatch getDrawBatcherForAABB(AABB aabb) {
        return QuartzCore.INSTANCE.getWorldEngine().getBatcherForAABB(aabb);
    }
}
