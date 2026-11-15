package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StorageESP extends Module {

    public static final String MODE_2D = "2D";
    public static final String MODE_3D = "3D";

    private static final double NEAR_CAMERA_MARGIN = 0.2D;
    private static final int FILL_ALPHA = 100;
    /** Normal chests, including merged double chests. */
    private static final int CHEST_COLOR = 0xFFFF8C00;
    private static final int ENDER_CHEST_COLOR = 0xFF9B30FF;

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_2D, MODE_3D}, 1);
    private final BooleanSetting cornersSetting = new BooleanSetting("Corners", false);
    private final BooleanSetting throughWallsSetting = new BooleanSetting("Through Walls", true);

    private final Minecraft mc = Minecraft.getMinecraft();

    private final float[] mv = new float[16];
    private final float[] pr = new float[16];
    private final double[] cornerScratch = new double[2];
    private IntBuffer viewport;
    private FloatBuffer mvBuf;
    private FloatBuffer prBuf;
    private int vw;
    private int vh;
    private double scale = 1.0D;
    private int lastDisplayWidth = -1;
    private int lastDisplayHeight = -1;

    private final RectBatch chestBatch = new RectBatch();
    private final RectBatch enderBatch = new RectBatch();

    private final Map<BlockPos, TileEntity> storages = new HashMap<BlockPos, TileEntity>();
    private final Set<BlockPos> visited = new HashSet<BlockPos>();
    private final Deque<BlockPos> queue = new ArrayDeque<BlockPos>();
    private final List<StorageBox> boxes = new ArrayList<StorageBox>();

    public StorageESP() {
        super("StorageESP", Category.RENDER);
        settings.add(modeSetting);
        settings.add(cornersSetting);
        settings.add(throughWallsSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == cornersSetting) return MODE_2D.equals(mode());
        if (setting == throughWallsSetting) return MODE_3D.equals(mode());
        return true;
    }

    private String mode() {
        return modeSetting.getValue();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        chestBatch.reset();
        enderBatch.reset();
        if (modeSetting.isUnset()) return;
        if (!captureMatrices()) return;

        collectBoxes();
        if (boxes.isEmpty()) return;

        if (MODE_3D.equals(mode())) {
            render3D();
        } else {
            collect2D();
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (!isEnabled()) return;
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (MODE_2D.equals(mode())) {
            chestBatch.draw(CHEST_COLOR);
            enderBatch.draw(ENDER_CHEST_COLOR);
        }
    }

    /**
     * Groups loaded storage tile entities into visual boxes. Normal chests that touch are merged
     * into one double-chest box, so a double chest renders as a single 2x1 outline instead of two
     * overlapping 1x1 boxes. Ender chests never merge.
     */
    private void collectBoxes() {
        storages.clear();
        visited.clear();
        boxes.clear();

        for (TileEntity entity : mc.theWorld.loadedTileEntityList) {
            if (entity instanceof TileEntityChest || entity instanceof TileEntityEnderChest) {
                storages.put(entity.getPos(), entity);
            }
        }

        for (TileEntity entity : storages.values()) {
            BlockPos pos = entity.getPos();
            if (visited.contains(pos)) continue;

            if (entity instanceof TileEntityEnderChest) {
                visited.add(pos);
                boxes.add(new StorageBox(blockBox(pos), true));
                continue;
            }

            Block block = mc.theWorld.getBlockState(pos).getBlock();
            if (!(block instanceof BlockChest)) continue;
            AxisAlignedBB box = blockBox(pos);

            visited.add(pos);
            queue.clear();
            queue.add(pos);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                BlockPos[] neighbours = {
                        current.west(), current.east(), current.north(), current.south()
                };
                for (BlockPos neighbour : neighbours) {
                    if (visited.contains(neighbour)) continue;
                    TileEntity adjacent = storages.get(neighbour);
                    if (!(adjacent instanceof TileEntityChest)) continue;
                    if (mc.theWorld.getBlockState(neighbour).getBlock() != block) continue;
                    visited.add(neighbour);
                    box = box.union(blockBox(neighbour));
                    queue.add(neighbour);
                }
            }

            boxes.add(new StorageBox(box, false));
        }
    }

    private static AxisAlignedBB blockBox(BlockPos pos) {
        return new AxisAlignedBB(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    private void render3D() {
        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;


        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        if (throughWallsSetting.getValue()) GlStateManager.disableDepth();

        Tessellator tessellator = Tessellator.getInstance();
        for (int i = 0; i < boxes.size(); i++) {
            StorageBox storage = boxes.get(i);
            AxisAlignedBB box = storage.box;
            if (RenderUtil.pointNearBox(camX, camY, camZ, box, NEAR_CAMERA_MARGIN)) continue;

            int color = storage.color();
            RenderUtil.drawFilledBox(tessellator,
                    box.minX - camX, box.minY - camY, box.minZ - camZ,
                    box.maxX - camX, box.maxY - camY, box.maxZ - camZ,
                    (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, FILL_ALPHA);
        }

        if (throughWallsSetting.getValue()) GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }

    private void collect2D() {
        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;

        for (int i = 0; i < boxes.size(); i++) {
            StorageBox storage = boxes.get(i);
            AxisAlignedBB box = storage.box;
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            int hits = 0;

            for (int corner = 0; corner < 8; corner++) {
                double x = (corner & 1) == 0 ? box.minX : box.maxX;
                double y = (corner & 2) == 0 ? box.minY : box.maxY;
                double z = (corner & 4) == 0 ? box.minZ : box.maxZ;
                if (!projectCorner(x - camX, y - camY, z - camZ, cornerScratch)) continue;
                hits++;
                if (cornerScratch[0] < minX) minX = cornerScratch[0];
                if (cornerScratch[0] > maxX) maxX = cornerScratch[0];
                if (cornerScratch[1] < minY) minY = cornerScratch[1];
                if (cornerScratch[1] > maxY) maxY = cornerScratch[1];
            }

            if (hits < 2) continue;
            double topDownTop = vh - maxY;
            double topDownBottom = vh - minY;
            float x = (float) (minX / scale);
            float y = (float) (topDownTop / scale);
            float w = (float) ((maxX - minX) / scale);
            float h = (float) ((topDownBottom - topDownTop) / scale);
            if (w < 1.5f || h < 1.5f) continue;

            addRect(storage.ender ? enderBatch : chestBatch, x, y, w, h);
        }
    }

    private boolean captureMatrices() {
        if (mvBuf == null) {
            viewport = BufferUtils.createIntBuffer(16);
            mvBuf = BufferUtils.createFloatBuffer(16);
            prBuf = BufferUtils.createFloatBuffer(16);
        }
        mvBuf.clear();
        prBuf.clear();
        viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mvBuf);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, prBuf);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        mvBuf.get(mv);
        prBuf.get(pr);
        vw = viewport.get(2);
        vh = viewport.get(3);
        if (vw <= 0 || vh <= 0) return false;

        if (mc.displayWidth != lastDisplayWidth || mc.displayHeight != lastDisplayHeight) {
            lastDisplayWidth = mc.displayWidth;
            lastDisplayHeight = mc.displayHeight;
            scale = vh / (double) new ScaledResolution(mc).getScaledHeight();
        }
        return scale > 0;
    }

    /** Projection for box corners: only needs to be in front of the camera. */
    private boolean projectCorner(double x, double y, double z, double[] out) {
        double inX = mv[0] * x + mv[4] * y + mv[8] * z + mv[12];
        double inY = mv[1] * x + mv[5] * y + mv[9] * z + mv[13];
        double inZ = mv[2] * x + mv[6] * y + mv[10] * z + mv[14];
        double inW = mv[3] * x + mv[7] * y + mv[11] * z + mv[15];

        double clipX = pr[0] * inX + pr[4] * inY + pr[8] * inZ + pr[12] * inW;
        double clipY = pr[1] * inX + pr[5] * inY + pr[9] * inZ + pr[13] * inW;
        double clipW = pr[3] * inX + pr[7] * inY + pr[11] * inZ + pr[15] * inW;

        if (clipW <= 0.0D) return false;
        double invW = 1.0D / clipW;
        double ndcX = clipX * invW;
        double ndcY = clipY * invW;
        if (ndcX < -20.0D || ndcX > 20.0D) return false;
        if (ndcY < -20.0D || ndcY > 20.0D) return false;

        out[0] = (ndcX + 1.0D) * 0.5D * vw;
        out[1] = (ndcY + 1.0D) * 0.5D * vh;
        return true;
    }

    private void addRect(RectBatch batch, float x, float y, float w, float h) {
        if (cornersSetting.getValue()) {
            if (!batch.ensureRoom(8)) return;
            float len = Math.max(4f, Math.min(h / 6f, 10f));
            batch.add(x, y, len, 1f);
            batch.add(x, y, 1f, len);
            batch.add(x + w - len, y, len, 1f);
            batch.add(x + w - 1f, y, 1f, len);
            batch.add(x, y + h - 1f, len, 1f);
            batch.add(x, y + h - len, 1f, len);
            batch.add(x + w - len, y + h - 1f, len, 1f);
            batch.add(x + w - 1f, y + h - len, 1f, len);
        } else {
            if (!batch.ensureRoom(4)) return;
            batch.add(x, y, w, 1f);
            batch.add(x, y + h - 1f, w, 1f);
            batch.add(x, y, 1f, h);
            batch.add(x + w - 1f, y, 1f, h);
        }
    }

    /** A storage box plus the color its type uses. */
    private static final class StorageBox {
        final AxisAlignedBB box;
        final boolean ender;

        StorageBox(AxisAlignedBB box, boolean ender) {
            this.box = box;
            this.ender = ender;
        }

        int color() {
            return ender ? ENDER_CHEST_COLOR : CHEST_COLOR;
        }
    }

    /**
     * One color's worth of 2D rectangles, kept in a growable float array so a frame's overlay can
     * be drawn with a single batched call. Each rect is four floats: x, y, width, height.
     */
    private static final class RectBatch {
        private static final int MAX_QUADS = 16384;

        private float[] quads = new float[512];
        private int count;

        void reset() {
            count = 0;
        }

        boolean ensureRoom(int quadsToAdd) {
            if ((count + quadsToAdd) * 4 > quads.length) {
                int needed = (count + quadsToAdd) * 4;
                if (needed > MAX_QUADS * 4) return false;
                quads = Arrays.copyOf(quads, Math.max(needed, quads.length * 2));
            }
            return true;
        }

        void add(float x, float y, float w, float h) {
            int offset = count << 2;
            quads[offset] = x;
            quads[offset + 1] = y;
            quads[offset + 2] = w;
            quads[offset + 3] = h;
            count++;
        }

        void draw(int color) {
            if (count > 0) RenderUtil.drawBatchedRects(quads, count, color);
        }
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        chestBatch.reset();
        enderBatch.reset();
        boxes.clear();
    }

    @Override
    public String getTag() {
        return modeSetting.getDisplayValue();
    }
}
