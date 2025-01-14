package net.roguelogix.quartz.internal.gl;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import net.roguelogix.quartz.*;
import net.roguelogix.quartz.internal.Buffer;
import net.roguelogix.quartz.internal.QuartzCore;
import net.roguelogix.quartz.internal.common.*;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3ic;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

import static net.roguelogix.quartz.internal.MagicNumbers.GL.*;
import static net.roguelogix.quartz.internal.MagicNumbers.*;
import static net.roguelogix.quartz.internal.gl.GLCore.*;
import static net.roguelogix.quartz.internal.gl.GLMainProgram.SSBO;
import static org.lwjgl.opengl.ARBBaseInstance.glDrawArraysInstancedBaseInstance;
import static org.lwjgl.opengl.ARBBaseInstance.glDrawElementsInstancedBaseVertexBaseInstance;
import static org.lwjgl.opengl.ARBDrawIndirect.*;
import static org.lwjgl.opengl.ARBInstancedArrays.glVertexAttribDivisorARB;
import static org.lwjgl.opengl.ARBMultiDrawIndirect.glMultiDrawArraysIndirect;
import static org.lwjgl.opengl.ARBMultiDrawIndirect.glMultiDrawElementsIndirect;
import static org.lwjgl.opengl.ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.ARBVertexAttribBinding.*;
import static org.lwjgl.opengl.GL32C.*;

@NonnullDefault
public class GLDrawBatch implements DrawBatch {
    
    private class MeshInstanceManager {
        private class DrawComponent {
            private final GLRenderPass renderPass;
            private final boolean QUAD;
            public final int GL_MODE;
            private int drawIndex;
            
            private final int baseVertex;
            private final int elementCount;
            
            private record IndirectDrawInfo(int elementCount,
                                            int instanceCount,
                                            int baseVertex,
                                            int baseInstance) {
            }
            
            private DrawComponent(RenderType renderType, InternalMesh.Manager.TrackedMesh.Component component) {
                renderPass = GLRenderPass.renderPassForRenderType(renderType);
                QUAD = renderPass.QUAD;
                GL_MODE = renderPass.GL_MODE;
                
                baseVertex = component.vertexOffset();
                int elementCountTemp = component.vertexCount();
                if (QUAD) {
                    elementCountTemp *= 6;
                    elementCountTemp /= 4;
                    GLCore.INSTANCE.ensureElementBufferLength(elementCountTemp / 6);
                }
                elementCount = elementCountTemp;
                var drawComponents = (renderPass.ALPHA_DISCARD ? cutoutDrawComponents : opaqueDrawComponents).computeIfAbsent(renderPass, e -> new ObjectArrayList<>());
                drawIndex = drawComponents.size();
                drawComponents.add(this);
            }
            
            private void draw() {
                if (!BASE_INSTANCE) {
                    if (ATTRIB_BINDING) {
                        glBindVertexBuffer(1, instanceDataBuffer.handle(), instanceDataOffset, INSTANCE_DATA_BYTE_SIZE);
                    } else {
                        int offset = instanceDataOffset;
                        glVertexAttribIPointer(WORLD_POSITION_LOCATION, 3, GL_INT, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += IVEC4_BYTE_SIZE;
                        glVertexAttribIPointer(DYNAMIC_MATRIX_ID_LOCATION, 1, GL_INT, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += INT_BYTE_SIZE;
                        glVertexAttribIPointer(DYNAMIC_LIGHT_ID_LOCATION, 1, GL_INT, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += INT_BYTE_SIZE;
                        glVertexAttribPointer(STATIC_MATRIX_LOCATION, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += VEC4_BYTE_SIZE;
                        glVertexAttribPointer(STATIC_MATRIX_LOCATION + 1, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += VEC4_BYTE_SIZE;
                        glVertexAttribPointer(STATIC_MATRIX_LOCATION + 2, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += VEC4_BYTE_SIZE;
                        glVertexAttribPointer(STATIC_MATRIX_LOCATION + 3, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += VEC4_BYTE_SIZE;
                        glVertexAttribPointer(STATIC_NORMAL_MATRIX_LOCATION, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += VEC4_BYTE_SIZE;
                        glVertexAttribPointer(STATIC_NORMAL_MATRIX_LOCATION + 1, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += VEC4_BYTE_SIZE;
                        glVertexAttribPointer(STATIC_NORMAL_MATRIX_LOCATION + 2, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += VEC4_BYTE_SIZE;
                        glVertexAttribPointer(STATIC_NORMAL_MATRIX_LOCATION + 3, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                        offset += VEC4_BYTE_SIZE;
                    }
                    if (QUAD) {
                        glDrawElementsInstancedBaseVertex(GL_TRIANGLES, elementCount, GL_UNSIGNED_INT, 0, instanceCount, baseVertex);
                    } else {
                        glDrawArraysInstanced(GL_MODE, baseVertex, elementCount, instanceCount);
                    }
                } else {
                    if (QUAD) {
                        glDrawElementsInstancedBaseVertexBaseInstance(GL_TRIANGLES, elementCount, GL_UNSIGNED_INT, 0, instanceCount, baseVertex, instanceDataOffset / INSTANCE_DATA_BYTE_SIZE);
                    } else {
                        glDrawArraysInstancedBaseInstance(GL_MODE, baseVertex, elementCount, instanceCount, instanceDataOffset / INSTANCE_DATA_BYTE_SIZE);
                    }
                }
            }
            
            private IndirectDrawInfo indirectInfo() {
                return new IndirectDrawInfo(elementCount, instanceCount, baseVertex, instanceDataOffset / INSTANCE_DATA_BYTE_SIZE);
            }
        }
        
        private final boolean autoDelete;
        @Nullable
        private InternalMesh staticMesh;
        @Nullable
        private InternalMesh.Manager.TrackedMesh trackedMesh;
        private final Consumer<InternalMesh.Manager.TrackedMesh> meshBuildCallback;
        private final ObjectArrayList<DrawComponent> components = new ObjectArrayList<>();
        private Buffer.Allocation instanceDataAlloc;
        private int instanceDataOffset;
        
        private final ObjectArrayList<Instance.Location> liveInstances = new ObjectArrayList<>();
        private int instanceCount = 0;
        
        private MeshInstanceManager(InternalMesh mesh, boolean autoDelete) {
            this.autoDelete = autoDelete;
            final var ref = new WeakReference<>(this);
            meshBuildCallback = ignored -> {
                final var manager = ref.get();
                if (manager != null) {
                    manager.onRebuild();
                }
            };
            instanceDataAlloc = instanceDataBuffer.alloc(INSTANCE_DATA_BYTE_SIZE);
            instanceDataAlloc.addReallocCallback(alloc -> {
                final var manager = ref.get();
                if (manager != null) {
                    manager.instanceDataOffset = alloc.offset();
                }
            });
            updateMesh(mesh);
        }
        
        public void updateMesh(Mesh quartzMesh) {
            if (!(quartzMesh instanceof InternalMesh mesh)) {
                return;
            }
            if (trackedMesh != null) {
                trackedMesh.removeBuildCallback(meshBuildCallback);
            }
            
            staticMesh = mesh;
            trackedMesh = QuartzCore.INSTANCE.meshManager.getMeshInfo(mesh);
            if (trackedMesh == null) {
                throw new IllegalArgumentException("Unable to find mesh in mesh registry");
            }
            
            onRebuild();
            trackedMesh.addBuildCallback(meshBuildCallback);
        }
        
        private void onRebuild() {
            for (int i = 0; i < components.size(); i++) {
                var component = components.get(i);
                var renderPass = component.renderPass;
                final var componentMap = renderPass.ALPHA_DISCARD ? cutoutDrawComponents : opaqueDrawComponents;
                final var drawComponents = componentMap.get(renderPass);
                if (drawComponents == null) {
                    component.drawIndex = -1;
                    continue;
                }
                if (component.drawIndex != -1) {
                    var removed = drawComponents.pop();
                    if (component.drawIndex < drawComponents.size()) {
                        removed.drawIndex = component.drawIndex;
                        drawComponents.set(component.drawIndex, removed);
                    }
                    component.drawIndex = -1;
                }
                if (drawComponents.isEmpty()) {
                    componentMap.remove(renderPass);
                }
            }
            components.clear();
            for (RenderType renderType : trackedMesh.usedRenderTypes()) {
                var component = trackedMesh.renderTypeComponent(renderType);
                if (component == null) {
                    continue;
                }
                var drawComponent = new DrawComponent(renderType, component);
                components.add(drawComponent);
            }
            indirectDrawInfoDirty = rebuildIndirectBlocks = true;
        }
        
        @Nullable
        public DrawBatch.Instance createInstance(Vector3ic position, @Nullable DynamicMatrix quartzDynamicMatrix, @Nullable Matrix4fc staticMatrix, @Nullable DynamicLight quartzLight, @Nullable DynamicLight.Type lightType) {
            if (quartzDynamicMatrix == null) {
                quartzDynamicMatrix = IDENTITY_DYNAMIC_MATRIX;
            }
            if (!(quartzDynamicMatrix instanceof DynamicMatrixManager.Matrix dynamicMatrix) || !dynamicMatrixManager.owns(dynamicMatrix)) {
                return null;
            }
            if (quartzLight == null) {
                if (lightType == null) {
                    lightType = DynamicLight.Type.SMOOTH;
                }
                quartzLight = QuartzCore.INSTANCE.lightEngine.createLightForPos(position, lightManager, lightType);
            }
            if (!(quartzLight instanceof DynamicLightManager.Light light) || !lightManager.owns(light)) {
                return null;
            }
            if (staticMatrix == null) {
                staticMatrix = IDENTITY_MATRIX;
            }
            return createInstance(position, dynamicMatrix, staticMatrix, light);
        }
        
        Instance createInstance(Vector3ic worldPosition, DynamicMatrixManager.Matrix dynamicMatrix, Matrix4fc staticMatrix, DynamicLightManager.Light dynamicLight) {
            if (instanceDataAlloc.size() < (instanceCount + 1) * INSTANCE_DATA_BYTE_SIZE) {
                instanceDataAlloc = instanceDataBuffer.realloc(instanceDataAlloc, instanceDataAlloc.size() * 2, INSTANCE_DATA_BYTE_SIZE);
            }
            
            
            final var byteBuf = instanceDataAlloc.buffer();
            int baseOffset = instanceCount * INSTANCE_DATA_BYTE_SIZE;
            worldPosition.get(baseOffset + WORLD_POSITION_OFFSET, byteBuf);
            byteBuf.putInt(baseOffset + DYNAMIC_MATRIX_ID_OFFSET, dynamicMatrix.id());
            byteBuf.putInt(baseOffset + DYNAMIC_LIGHT_ID_OFFSET, dynamicLight.id());
            staticMatrix.get(baseOffset + STATIC_MATRIX_OFFSET, byteBuf);
            staticMatrix.normal(SCRATCH_NORMAL_MATRIX).get(baseOffset + STATIC_NORMAL_MATRIX_OFFSET, byteBuf);
//            instanceDataAlloc.dirtyRange(baseOffset, INSTANCE_DATA_BYTE_SIZE);
            instanceDataBuffer.dirtyAll();
            
            var instance = new Instance(instanceCount++, dynamicMatrix, dynamicLight);
            liveInstances.add(instance.location);
            indirectDrawInfoDirty = true;
            return instance;
        }
        
        void removeInstance(Instance.Location instance) {
            if (instance.location == -1) {
                return;
            }
            instanceCount--;
            var endInstance = liveInstances.pop();
            if (instance != endInstance) {
                // swapping time!
                instanceDataAlloc.copy(endInstance.location * INSTANCE_DATA_BYTE_SIZE, instance.location * INSTANCE_DATA_BYTE_SIZE, INSTANCE_DATA_BYTE_SIZE);
//                instanceDataAlloc.dirtyRange(instance.location * INSTANCE_DATA_BYTE_SIZE, INSTANCE_DATA_BYTE_SIZE);
//                instanceDataBuffer.dirtyAll();
                
                liveInstances.set(instance.location, endInstance);
                endInstance.location = instance.location;
            }
            instance.location = -1;
            if (instanceCount == 0 && autoDelete) {
                delete();
            }
            indirectDrawInfoDirty = true;
        }
        
        public void delete() {
            while (!liveInstances.isEmpty()) {
                removeInstance(liveInstances.peek(0));
            }
            for (int i = 0; i < components.size(); i++) {
                var component = components.get(i);
                var renderPass = component.renderPass;
                final var componentMap = renderPass.ALPHA_DISCARD ? cutoutDrawComponents : opaqueDrawComponents;
                final var drawComponents = componentMap.get(renderPass);
                if (drawComponents == null) {
                    component.drawIndex = -1;
                    continue;
                }
                if (component.drawIndex != -1) {
                    var removed = drawComponents.pop();
                    if (component.drawIndex < drawComponents.size()) {
                        removed.drawIndex = component.drawIndex;
                        drawComponents.set(component.drawIndex, removed);
                    }
                    component.drawIndex = -1;
                }
                if (drawComponents.isEmpty()) {
                    componentMap.remove(renderPass);
                }
            }
            components.clear();
            instanceManagers.remove(staticMesh, this);
            instanceBatches.remove(this);
            indirectDrawInfoDirty = rebuildIndirectBlocks = true;
            trackedMesh.removeBuildCallback(meshBuildCallback);
        }
        
        private class Instance implements DrawBatch.Instance {
            
            private static class Location {
                private int location;
                
                private Location(int location) {
                    this.location = location;
                }
            }
            
            private final Location location;
            @Nullable
            private MeshInstanceManager.InstanceBatch batch;
            private DynamicMatrixManager.Matrix dynamicMatrix;
            private DynamicLightManager.Light dynamicLight;
            
            private Instance(int initialLocation, DynamicMatrixManager.Matrix dynamicMatrix, DynamicLightManager.Light dynamicLight) {
                final var manager = MeshInstanceManager.this;
                final var location = new Location(initialLocation);
                QuartzCore.CLEANER.register(this, () -> QuartzCore.deletionQueue.enqueue(() -> manager.removeInstance(location)));
                this.location = location;
                this.dynamicMatrix = dynamicMatrix;
                this.dynamicLight = dynamicLight;
            }
            
            @Override
            public void updateDynamicMatrix(@Nullable DynamicMatrix newDynamicMatrix) {
                if (dynamicMatrix == newDynamicMatrix) {
                    return;
                }
                if (newDynamicMatrix == null) {
                    newDynamicMatrix = IDENTITY_DYNAMIC_MATRIX;
                }
                if (newDynamicMatrix instanceof DynamicMatrixManager.Matrix dynamicMatrix && dynamicMatrixManager.owns(dynamicMatrix)) {
                    this.dynamicMatrix = dynamicMatrix;
                    final var offset = location.location * INSTANCE_DATA_BYTE_SIZE + DYNAMIC_MATRIX_ID_OFFSET;
                    instanceDataAlloc.buffer().putInt(offset, dynamicMatrix.id());
                    instanceDataAlloc.dirtyRange(offset, INT_BYTE_SIZE);
                }
            }
            
            @Override
            public void updateStaticMatrix(@Nullable Matrix4fc newStaticMatrix) {
                if (newStaticMatrix == null) {
                    newStaticMatrix = IDENTITY_MATRIX;
                }
                final var transformOffset = location.location * INSTANCE_DATA_BYTE_SIZE + STATIC_MATRIX_OFFSET;
                final var normalOffset = location.location * INSTANCE_DATA_BYTE_SIZE + STATIC_NORMAL_MATRIX_OFFSET;
                newStaticMatrix.get(transformOffset, instanceDataAlloc.buffer());
                newStaticMatrix.normal(SCRATCH_NORMAL_MATRIX).get(normalOffset, instanceDataAlloc.buffer());
                instanceDataAlloc.dirtyRange(transformOffset, MATRIX_4F_BYTE_SIZE_2);
            }
            
            @Override
            public void updateDynamicLight(@Nullable DynamicLight newDynamicLight) {
                if (dynamicLight == newDynamicLight) {
                    return;
                }
                if (newDynamicLight == null) {
                    newDynamicLight = ZERO_LEVEL_LIGHT;
                }
                if (newDynamicLight instanceof DynamicLightManager.Light dynamicLight && lightManager.owns(dynamicLight)) {
                    this.dynamicLight = dynamicLight;
                    int newLightID = dynamicLight.id();
                    final var offset = location.location * INSTANCE_DATA_BYTE_SIZE + DYNAMIC_LIGHT_ID_OFFSET;
                    instanceDataAlloc.buffer().putInt(offset, newLightID);
                    instanceDataAlloc.dirtyRange(offset, INT_BYTE_SIZE);
                }
            }
            
            @Override
            public void delete() {
                removeInstance(location);
            }
        }
        
        private static class InstanceBatch implements DrawBatch.InstanceBatch {
            private final MeshInstanceManager instanceManager;
            
            public InstanceBatch(MeshInstanceManager instanceManager) {
                this.instanceManager = instanceManager;
                QuartzCore.CLEANER.register(this, instanceManager::delete);
            }
            
            @Override
            public void updateMesh(Mesh mesh) {
                instanceManager.updateMesh(mesh);
            }
            
            @Nullable
            @Override
            public DrawBatch.Instance createInstance(Vector3ic position, @Nullable DynamicMatrix dynamicMatrix, @Nullable Matrix4fc staticMatrix, @Nullable DynamicLight light, @Nullable DynamicLight.Type lightType) {
                final var instance = instanceManager.createInstance(position, dynamicMatrix, staticMatrix, light, lightType);
                if (!(instance instanceof MeshInstanceManager.Instance instance1)) {
                    return null;
                }
                instance1.batch = this;
                return instance;
            }
        }
    }
    
    private record IndirectDrawBlock(int glMode, boolean QUAD, GLBuffer.Allocation drawInfoAlloc, int count,
                                     ObjectArrayList<MeshInstanceManager.DrawComponent> drawComponents,
                                     boolean multidraw) {
        
        public IndirectDrawBlock(ObjectArrayList<MeshInstanceManager.DrawComponent> drawComponents, GLBuffer indirectBuffer, boolean multidraw) {
            this(drawComponents.get(0).GL_MODE, drawComponents.get(0).QUAD, indirectBuffer.alloc(drawComponents.size() * 5 * INT_BYTE_SIZE), drawComponents.size(), drawComponents, multidraw);
            updateDrawInfo();
        }
        
        public void draw() {
            if (multidraw) {
                if (QUAD) {
                    glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, drawInfoAlloc.offset(), count, 0);
                } else {
                    glMultiDrawArraysIndirect(glMode, drawInfoAlloc.offset(), count, 0);
                }
            } else {
                if (QUAD) {
                    for (int i = 0; i < count; i++) {
                        glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, drawInfoAlloc.offset() + i * 20L);
                    }
                } else {
                    for (int i = 0; i < count; i++) {
                        glDrawArraysIndirect(glMode, drawInfoAlloc.offset() + i * 20L);
                    }
                }
            }
        }
        
        public void updateDrawInfo() {
            var drawInfo = drawInfoAlloc.buffer().asIntBuffer();
            for (int i = 0; i < drawComponents.size(); i++) {
                var indirectInfo = drawComponents.get(i).indirectInfo();
                drawInfo.put(i * 5, indirectInfo.elementCount);
                drawInfo.put(i * 5 + 1, indirectInfo.instanceCount);
                drawInfo.put(i * 5 + 2, 0);
                drawInfo.put(i * 5 + 3, indirectInfo.baseVertex);
                drawInfo.put(i * 5 + 4, indirectInfo.baseInstance);
            }
        }
    }
    
    private static final Matrix4fc IDENTITY_MATRIX = new Matrix4f();
    private static final Matrix4f SCRATCH_NORMAL_MATRIX = new Matrix4f();
    
    private final GLBuffer instanceDataBuffer = new GLBuffer(false);
    
    private final GLBuffer dynamicMatrixBuffer = new GLBuffer(false);
    private final DynamicMatrixManager dynamicMatrixManager = new DynamicMatrixManager(dynamicMatrixBuffer);
    private final DynamicMatrix IDENTITY_DYNAMIC_MATRIX = dynamicMatrixManager.createMatrix((matrix, nanoSinceLastFrame, partialTicks, playerBlock, playerPartialBlock) -> matrix.write(IDENTITY_MATRIX), null);
    private final GLBuffer dynamicLightBuffer = new GLBuffer(false);
    private final DynamicLightManager lightManager = new DynamicLightManager(dynamicLightBuffer);
    private final DynamicLightManager.Light ZERO_LEVEL_LIGHT = lightManager.createLight((light, blockAndTintGetter) -> light.write((byte) 0, (byte) 0, (byte) 0));
    
    private final int VAO;
    private final int dynamicMatrixTexture;
    private final int dynamicLightTexture;
    
    private final Object2ObjectMap<InternalMesh, MeshInstanceManager> instanceManagers = new Object2ObjectOpenHashMap<>();
    private final ObjectOpenHashSet<MeshInstanceManager> instanceBatches = new ObjectOpenHashSet<>();
    private final Object2ObjectMap<GLRenderPass, ObjectArrayList<MeshInstanceManager.DrawComponent>> opaqueDrawComponents = new Object2ObjectArrayMap<>();
    private final Object2ObjectMap<GLRenderPass, ObjectArrayList<MeshInstanceManager.DrawComponent>> cutoutDrawComponents = new Object2ObjectArrayMap<>();
    
    private final GLBuffer indirectDrawBuffer = DRAW_INDIRECT ? new GLBuffer(false) : null;
    private final Object2ObjectMap<GLRenderPass, IndirectDrawBlock> opaqueIndirectInfo = new Object2ObjectArrayMap<>();
    private final Object2ObjectMap<GLRenderPass, IndirectDrawBlock> cutoutIndirectInfo = new Object2ObjectArrayMap<>();
    private boolean indirectDrawInfoDirty = false;
    private boolean rebuildIndirectBlocks = false;
    
    @Nullable
    private AABB cullAABB = null;
    private final Vector4f cullVector = new Vector4f();
    private final Vector4f cullVectorMin = new Vector4f();
    private final Vector4f cullVectorMax = new Vector4f();
    private boolean enabled = true;
    private boolean culled = false;
    
    public GLDrawBatch() {
        final int VAO = glGenVertexArrays();
        B3DStateHelper.bindVertexArray(VAO);
        
        B3DStateHelper.bindElementBuffer(GLCore.INSTANCE.elementBuffer.handle());
        
        glEnableVertexAttribArray(POSITION_LOCATION);
        glEnableVertexAttribArray(COLOR_LOCATION);
        glEnableVertexAttribArray(TEX_COORD_LOCATION);
        glEnableVertexAttribArray(LIGHTINFO_LOCATION);
        
        glEnableVertexAttribArray(WORLD_POSITION_LOCATION);
        glEnableVertexAttribArray(DYNAMIC_MATRIX_ID_LOCATION);
        glEnableVertexAttribArray(DYNAMIC_LIGHT_ID_LOCATION);
        glEnableVertexAttribArray(STATIC_MATRIX_LOCATION);
        glEnableVertexAttribArray(STATIC_MATRIX_LOCATION + 1);
        glEnableVertexAttribArray(STATIC_MATRIX_LOCATION + 2);
        glEnableVertexAttribArray(STATIC_MATRIX_LOCATION + 3);
        glEnableVertexAttribArray(STATIC_NORMAL_MATRIX_LOCATION);
        glEnableVertexAttribArray(STATIC_NORMAL_MATRIX_LOCATION + 1);
        glEnableVertexAttribArray(STATIC_NORMAL_MATRIX_LOCATION + 2);
        glEnableVertexAttribArray(STATIC_NORMAL_MATRIX_LOCATION + 3);
        
        if (ATTRIB_BINDING) {
            glBindVertexBuffer(0, QuartzCore.INSTANCE.meshManager.vertexBuffer.as(GLBuffer.class).handle(), 0, VERTEX_BYTE_SIZE);
            
            glVertexAttribBinding(POSITION_LOCATION, 0);
            glVertexAttribBinding(COLOR_LOCATION, 0);
            glVertexAttribBinding(TEX_COORD_LOCATION, 0);
            glVertexAttribBinding(LIGHTINFO_LOCATION, 0);
            
            glVertexAttribFormat(POSITION_LOCATION, 3, GL_FLOAT, false, 0);
            glVertexAttribIFormat(COLOR_LOCATION, 1, GL_INT, 12);
            glVertexAttribFormat(TEX_COORD_LOCATION, 2, GL_FLOAT, false, 16);
            glVertexAttribIFormat(LIGHTINFO_LOCATION, 2, GL_INT, 24);
            
            // when base instance is unavailable, this must be setup per draw
            if (BASE_INSTANCE || DRAW_INDIRECT) {
                glBindVertexBuffer(1, instanceDataBuffer.handle(), 0, INSTANCE_DATA_BYTE_SIZE);
            }
            glVertexBindingDivisor(1, 1);
            
            glVertexAttribBinding(WORLD_POSITION_LOCATION, 1);
            glVertexAttribBinding(DYNAMIC_MATRIX_ID_LOCATION, 1);
            glVertexAttribBinding(DYNAMIC_LIGHT_ID_LOCATION, 1);
            glVertexAttribBinding(STATIC_MATRIX_LOCATION, 1);
            glVertexAttribBinding(STATIC_MATRIX_LOCATION + 1, 1);
            glVertexAttribBinding(STATIC_MATRIX_LOCATION + 2, 1);
            glVertexAttribBinding(STATIC_MATRIX_LOCATION + 3, 1);
            glVertexAttribBinding(STATIC_NORMAL_MATRIX_LOCATION, 1);
            glVertexAttribBinding(STATIC_NORMAL_MATRIX_LOCATION + 1, 1);
            glVertexAttribBinding(STATIC_NORMAL_MATRIX_LOCATION + 2, 1);
            glVertexAttribBinding(STATIC_NORMAL_MATRIX_LOCATION + 3, 1);
            
            int offset = 0;
            glVertexAttribIFormat(WORLD_POSITION_LOCATION, 3, GL_INT, offset);
            offset += IVEC4_BYTE_SIZE;
            glVertexAttribIFormat(DYNAMIC_MATRIX_ID_LOCATION, 1, GL_INT, offset);
            offset += INT_BYTE_SIZE;
            glVertexAttribIFormat(DYNAMIC_LIGHT_ID_LOCATION, 1, GL_INT, offset);
            offset += INT_BYTE_SIZE;
            glVertexAttribFormat(STATIC_MATRIX_LOCATION, 4, GL_FLOAT, false, offset);
            offset += VEC4_BYTE_SIZE;
            glVertexAttribFormat(STATIC_MATRIX_LOCATION + 1, 4, GL_FLOAT, false, offset);
            offset += VEC4_BYTE_SIZE;
            glVertexAttribFormat(STATIC_MATRIX_LOCATION + 2, 4, GL_FLOAT, false, offset);
            offset += VEC4_BYTE_SIZE;
            glVertexAttribFormat(STATIC_MATRIX_LOCATION + 3, 4, GL_FLOAT, false, offset);
            offset += VEC4_BYTE_SIZE;
            glVertexAttribFormat(STATIC_NORMAL_MATRIX_LOCATION, 4, GL_FLOAT, false, offset);
            offset += VEC4_BYTE_SIZE;
            glVertexAttribFormat(STATIC_NORMAL_MATRIX_LOCATION + 1, 4, GL_FLOAT, false, offset);
            offset += VEC4_BYTE_SIZE;
            glVertexAttribFormat(STATIC_NORMAL_MATRIX_LOCATION + 2, 4, GL_FLOAT, false, offset);
            offset += VEC4_BYTE_SIZE;
            glVertexAttribFormat(STATIC_NORMAL_MATRIX_LOCATION + 3, 4, GL_FLOAT, false, offset);
            offset += VEC4_BYTE_SIZE;
            
            
        } else {
            B3DStateHelper.bindArrayBuffer(QuartzCore.INSTANCE.meshManager.vertexBuffer.as(GLBuffer.class).handle());
            glVertexAttribPointer(POSITION_LOCATION, 3, GL_FLOAT, false, VERTEX_BYTE_SIZE, 0);
            glVertexAttribIPointer(COLOR_LOCATION, 1, GL_INT, VERTEX_BYTE_SIZE, 12);
            glVertexAttribPointer(TEX_COORD_LOCATION, 2, GL_FLOAT, false, VERTEX_BYTE_SIZE, 16);
            glVertexAttribIPointer(LIGHTINFO_LOCATION, 2, GL_INT, VERTEX_BYTE_SIZE, 24);
            
            glVertexAttribDivisorARB(WORLD_POSITION_LOCATION, 1);
            glVertexAttribDivisorARB(DYNAMIC_MATRIX_ID_LOCATION, 1);
            glVertexAttribDivisorARB(DYNAMIC_LIGHT_ID_LOCATION, 1);
            glVertexAttribDivisorARB(STATIC_MATRIX_LOCATION, 1);
            glVertexAttribDivisorARB(STATIC_MATRIX_LOCATION + 1, 1);
            glVertexAttribDivisorARB(STATIC_MATRIX_LOCATION + 2, 1);
            glVertexAttribDivisorARB(STATIC_MATRIX_LOCATION + 3, 1);
            glVertexAttribDivisorARB(STATIC_NORMAL_MATRIX_LOCATION, 1);
            glVertexAttribDivisorARB(STATIC_NORMAL_MATRIX_LOCATION + 1, 1);
            glVertexAttribDivisorARB(STATIC_NORMAL_MATRIX_LOCATION + 2, 1);
            glVertexAttribDivisorARB(STATIC_NORMAL_MATRIX_LOCATION + 3, 1);
            
            // when base instance is unavailable, this must be setup per draw
            if (BASE_INSTANCE || DRAW_INDIRECT) {
                B3DStateHelper.bindArrayBuffer(instanceDataBuffer.handle());
                int offset = 0;
                glVertexAttribIPointer(WORLD_POSITION_LOCATION, 3, GL_INT, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += IVEC4_BYTE_SIZE;
                glVertexAttribIPointer(DYNAMIC_MATRIX_ID_LOCATION, 1, GL_INT, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += INT_BYTE_SIZE;
                glVertexAttribIPointer(DYNAMIC_LIGHT_ID_LOCATION, 1, GL_INT, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += INT_BYTE_SIZE;
                glVertexAttribPointer(STATIC_MATRIX_LOCATION, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += VEC4_BYTE_SIZE;
                glVertexAttribPointer(STATIC_MATRIX_LOCATION + 1, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += VEC4_BYTE_SIZE;
                glVertexAttribPointer(STATIC_MATRIX_LOCATION + 2, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += VEC4_BYTE_SIZE;
                glVertexAttribPointer(STATIC_MATRIX_LOCATION + 3, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += VEC4_BYTE_SIZE;
                glVertexAttribPointer(STATIC_NORMAL_MATRIX_LOCATION, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += VEC4_BYTE_SIZE;
                glVertexAttribPointer(STATIC_NORMAL_MATRIX_LOCATION + 1, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += VEC4_BYTE_SIZE;
                glVertexAttribPointer(STATIC_NORMAL_MATRIX_LOCATION + 2, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += VEC4_BYTE_SIZE;
                glVertexAttribPointer(STATIC_NORMAL_MATRIX_LOCATION + 3, 4, GL_FLOAT, false, INSTANCE_DATA_BYTE_SIZE, offset);
                offset += VEC4_BYTE_SIZE;
            }
        }
        glBindVertexArray(0);
        
        final int dynamicMatrixTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, dynamicMatrixTexture);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, dynamicMatrixBuffer.handle());
        
        final int dynamicLightTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_BUFFER, dynamicLightTexture);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RG8UI, dynamicLightBuffer.handle());
        
        glBindTexture(GL_TEXTURE_BUFFER, 0);
        
        QuartzCore.CLEANER.register(this, () -> QuartzCore.deletionQueue.enqueue(() -> {
            glDeleteTextures(dynamicLightTexture);
            glDeleteTextures(dynamicMatrixTexture);
            glDeleteVertexArrays(VAO);
        }));
        
        this.VAO = VAO;
        this.dynamicMatrixTexture = dynamicMatrixTexture;
        this.dynamicLightTexture = dynamicLightTexture;
    }
    
    @Override
    @Nullable
    public InstanceBatch createInstanceBatch(Mesh quartzMesh) {
        if (!(quartzMesh instanceof InternalMesh mesh)) {
            return null;
        }
        var instanceManager = new MeshInstanceManager(mesh, false);
        var instanceBatch = new MeshInstanceManager.InstanceBatch(instanceManager);
        instanceBatches.add(instanceManager);
        return instanceBatch;
    }
    
    @Nullable
    @Override
    public Instance createInstance(Vector3ic position, Mesh quartzMesh, @Nullable DynamicMatrix quartzDynamicMatrix, @Nullable Matrix4fc staticMatrix, @Nullable DynamicLight quartzLight, @Nullable DynamicLight.Type lightType) {
        if (!(quartzMesh instanceof InternalMesh mesh)) {
            return null;
        }
        if (quartzDynamicMatrix == null) {
            quartzDynamicMatrix = IDENTITY_DYNAMIC_MATRIX;
        }
        if (!(quartzDynamicMatrix instanceof DynamicMatrixManager.Matrix dynamicMatrix) || !dynamicMatrixManager.owns(dynamicMatrix)) {
            return null;
        }
        if (quartzLight == null) {
            if (lightType == null) {
                lightType = DynamicLight.Type.SMOOTH;
            }
            quartzLight = QuartzCore.INSTANCE.lightEngine.createLightForPos(position, lightManager, lightType);
        }
        if (!(quartzLight instanceof DynamicLightManager.Light light) || !lightManager.owns(light)) {
            return null;
        }
        var instanceManager = instanceManagers.computeIfAbsent(mesh, (InternalMesh m) -> new MeshInstanceManager(m, true));
        if (staticMatrix == null) {
            staticMatrix = IDENTITY_MATRIX;
        }
        return instanceManager.createInstance(position, dynamicMatrix, staticMatrix, light);
    }
    
    @Override
    public DynamicMatrix createDynamicMatrix(@Nullable DynamicMatrix parentTransform, @Nullable DynamicMatrix.UpdateFunc updateFunc) {
        return dynamicMatrixManager.createMatrix(updateFunc, parentTransform);
    }
    
    @Override
    public DynamicLight createLight(Vector3ic lightPosition, DynamicLight.Type lightType) {
        return QuartzCore.INSTANCE.lightEngine.createLightForPos(lightPosition, lightManager, lightType);
    }
    
    @Override
    public void setCullAABB(AABB aabb) {
        this.cullAABB = aabb;
    }
    
    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    @Override
    public boolean isEmpty() {
        return instanceManagers.isEmpty() && instanceBatches.isEmpty();
    }
    
    void updateAndCull(@SuppressWarnings("SameParameterValue") DrawInfo drawInfo) {
        // matrices need to be updated anyway
        dynamicMatrixManager.updateAll(drawInfo.deltaNano, drawInfo.partialTicks, drawInfo.playerPosition, drawInfo.playerSubBlock);
        
        if (cullAABB != null) {
            cullVectorMin.set(2);
            cullVectorMax.set(-2);
            for (int i = 0; i < 8; i++) {
                cullVector.set(((i & 1) == 0 ? cullAABB.maxX() : cullAABB.minX()) - drawInfo.playerPosition.x, ((i & 2) == 0 ? cullAABB.maxY() : cullAABB.minY()) - drawInfo.playerPosition.y, ((i & 4) == 0 ? cullAABB.maxZ() : cullAABB.minZ()) - drawInfo.playerPosition.z, 1);
                cullVector.sub(drawInfo.playerSubBlock.x, drawInfo.playerSubBlock.y, drawInfo.playerSubBlock.z, 0);
                cullVector.mul(drawInfo.projectionMatrix);
                cullVector.div(cullVector.w);
                cullVectorMin.min(cullVector);
                cullVectorMax.max(cullVector);
            }
            culled = cullVectorMin.x > 1 || cullVectorMax.x < -1 || cullVectorMin.y > 1 || cullVectorMax.y < -1 || cullVectorMin.z > 1 || cullVectorMax.z < -1;
        } else {
            culled = false;
        }
        
        if (culled) {
            return;
        }
        
        assert Minecraft.getInstance().level != null;
        lightManager.updateAll(Minecraft.getInstance().level);
        dynamicMatrixBuffer.flush();
        dynamicLightBuffer.flush();
        instanceDataBuffer.flush();
        updateIndirectInfo();
    }
    
    private void updateIndirectInfo() {
        if (!DRAW_INDIRECT || !indirectDrawInfoDirty) {
            return;
        }
        indirectDrawInfoDirty = false;
        if (rebuildIndirectBlocks) {
            rebuildIndirectBlocks = false;
            opaqueIndirectInfo.forEach((glRenderPass, drawBlock) -> indirectDrawBuffer.free(drawBlock.drawInfoAlloc));
            cutoutIndirectInfo.forEach((glRenderPass, drawBlock) -> indirectDrawBuffer.free(drawBlock.drawInfoAlloc));
            opaqueIndirectInfo.clear();
            cutoutIndirectInfo.clear();
            opaqueDrawComponents.forEach((glRenderPass, drawComponents) -> opaqueIndirectInfo.put(glRenderPass, new IndirectDrawBlock(drawComponents, indirectDrawBuffer, MULTIDRAW_INDIRECT)));
            cutoutDrawComponents.forEach((glRenderPass, drawComponents) -> cutoutIndirectInfo.put(glRenderPass, new IndirectDrawBlock(drawComponents, indirectDrawBuffer, MULTIDRAW_INDIRECT)));
        } else {
            opaqueIndirectInfo.values().forEach(IndirectDrawBlock::updateDrawInfo);
            cutoutIndirectInfo.values().forEach(IndirectDrawBlock::updateDrawInfo);
        }
        indirectDrawBuffer.dirtyAll();
        indirectDrawBuffer.flush();
    }
    
    void drawOpaque() {
        if (!enabled || culled || opaqueDrawComponents.isEmpty()) {
            return;
        }
        
        if (!SSBO) {
            glActiveTexture(DYNAMIC_MATRIX_TEXTURE_UNIT_GL);
            glBindTexture(GL_TEXTURE_BUFFER, dynamicMatrixTexture);
            glActiveTexture(DYNAMIC_LIGHT_TEXTURE_UNIT_GL);
            glBindTexture(GL_TEXTURE_BUFFER, dynamicLightTexture);
        } else {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, dynamicMatrixBuffer.handle());
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, dynamicLightBuffer.handle());
        }
        
        if (!BASE_INSTANCE && !DRAW_INDIRECT) {
            B3DStateHelper.bindArrayBuffer(instanceDataBuffer.handle());
        }
        
        final var program = GLCore.INSTANCE.mainProgram;
        
        glActiveTexture(ATLAS_TEXTURE_UNIT_GL);
        
        glBindVertexArray(VAO);
        if (DRAW_INDIRECT) {
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBuffer.handle());
            opaqueIndirectInfo.forEach((renderPass, drawBlock) -> {
                program.setupRenderPass(renderPass);
                drawBlock.draw();
            });
        } else {
            for (var entry : opaqueDrawComponents.entrySet()) {
                program.setupRenderPass(entry.getKey());
                var drawComponents = entry.getValue();
                for (int i = 0; i < drawComponents.size(); i++) {
                    drawComponents.get(i).draw();
                }
            }
        }
    }
    
    void drawCutout() {
        if (!enabled || culled || cutoutDrawComponents.isEmpty()) {
            return;
        }
        
        if (!SSBO) {
            glActiveTexture(DYNAMIC_MATRIX_TEXTURE_UNIT_GL);
            glBindTexture(GL_TEXTURE_BUFFER, dynamicMatrixTexture);
            glActiveTexture(DYNAMIC_LIGHT_TEXTURE_UNIT_GL);
            glBindTexture(GL_TEXTURE_BUFFER, dynamicLightTexture);
        } else {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, dynamicMatrixBuffer.handle());
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, dynamicLightBuffer.handle());
        }
        
        if (!BASE_INSTANCE && !DRAW_INDIRECT) {
            B3DStateHelper.bindArrayBuffer(instanceDataBuffer.handle());
        }
        
        final var program = GLCore.INSTANCE.mainProgram;
        
        glActiveTexture(ATLAS_TEXTURE_UNIT_GL);
        
        glBindVertexArray(VAO);
        if (DRAW_INDIRECT) {
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBuffer.handle());
            cutoutIndirectInfo.forEach((renderPass, drawBlock) -> {
                program.setupRenderPass(renderPass);
                drawBlock.draw();
            });
        } else {
            for (var entry : cutoutDrawComponents.entrySet()) {
                program.setupRenderPass(entry.getKey());
                var drawComponents = entry.getValue();
                for (int i = 0; i < drawComponents.size(); i++) {
                    drawComponents.get(i).draw();
                }
            }
        }
    }
}
