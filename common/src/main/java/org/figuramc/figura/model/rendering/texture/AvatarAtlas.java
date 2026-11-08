package org.figuramc.figura.model.rendering.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.mixin.render.TextureManagerAccessor;
import org.figuramc.figura.utils.FiguraIdentifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

// all the textures of one avatar packed into one GPU texture
// every texture of an avatar was its own render type: one buffer switch and one draw call per texture and per avatar,
// and crowded avatars carry 10 to 35 textures each. Drawn from the atlas they share one render type, so one draw call.
// The pixels are the same (nearest sampling, no mipmaps, each texture keeps its own size), only the uvs are moved into the atlas;
// parts whose uvs leave the texture (wrapping, scrolling) and render types that sample differently still use the texture itself,
// see ImmediateAvatarRenderer.getTexture. Each region has a 1 pixel border copied from its edge, so sampling right on the edge
// can't pick up the neighbouring texture.
public class AvatarAtlas extends AbstractTexture {

    // turned off with -Dfigura.avatarAtlas=false
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("figura.avatarAtlas"));

    // textures bigger than this stay on their own, they would waste most of the atlas around them
    private static final int MAX_PACKED_SIZE = 2048;
    private static final int PADDING = 1;

    private final ResourceLocation location;
    private final int width, height;
    private final List<FiguraTexture> textures = new ArrayList<>();
    private boolean closed;

    public static final class Region {
        public final AvatarAtlas atlas;
        // the texture's pixels, without the border
        public final int x, y, width, height;
        // uv 0 to 1 of the texture to uv in the atlas: u * scaleU + offsetU
        public final double scaleU, scaleV, offsetU, offsetV;

        Region(AvatarAtlas atlas, int x, int y, int width, int height) {
            this.atlas = atlas;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.scaleU = (double) width / atlas.width;
            this.scaleV = (double) height / atlas.height;
            this.offsetU = (double) x / atlas.width;
            this.offsetV = (double) y / atlas.height;
        }

        // copies the image (already at the uploaded size) into the region and its border, on the render thread
        void write(NativeImage image) {
            if (atlas.closed)
                return;
            int w = Math.min(width, image.getWidth()), h = Math.min(height, image.getHeight());
            if (w <= 0 || h <= 0)
                return;
            atlas.bind();
            // level, x, y, skip pixels, skip rows, width, height, mipmap, close
            image.upload(0, x, y, 0, 0, w, h, false, false);
            // border: edges then corners
            image.upload(0, x - 1, y, 0, 0, 1, h, false, false);
            image.upload(0, x + w, y, w - 1, 0, 1, h, false, false);
            image.upload(0, x, y - 1, 0, 0, w, 1, false, false);
            image.upload(0, x, y + h, 0, h - 1, w, 1, false, false);
            image.upload(0, x - 1, y - 1, 0, 0, 1, 1, false, false);
            image.upload(0, x + w, y - 1, w - 1, 0, 1, 1, false, false);
            image.upload(0, x - 1, y + h, 0, h - 1, 1, 1, false, false);
            image.upload(0, x + w, y + h, w - 1, h - 1, 1, 1, false, false);
        }
    }

    private AvatarAtlas(UUID owner, int width, int height) {
        this.location = new FiguraIdentifier("avatar_atlas/" + owner + "/" + UUID.randomUUID());
        this.width = width;
        this.height = height;
    }

    @Override
    public void load(ResourceManager manager) {}

    public ResourceLocation getLocation() {
        return location;
    }

    // packs the textures of the sets, or returns null when fewer than two of them fit (nothing to gain)
    // must run on the render thread, before the textures are uploaded
    public static AvatarAtlas build(UUID owner, Collection<FiguraTextureSet> sets) {
        LinkedHashSet<FiguraTexture> unique = new LinkedHashSet<>();
        for (FiguraTextureSet set : sets)
            for (FiguraTexture texture : set.textures)
                if (texture != null)
                    unique.add(texture);

        List<FiguraTexture> candidates = new ArrayList<>();
        for (FiguraTexture texture : unique) {
            int w = texture.getUploadWidth(), h = texture.getUploadHeight();
            if (w > 0 && h > 0 && w <= MAX_PACKED_SIZE && h <= MAX_PACKED_SIZE)
                candidates.add(texture);
        }
        if (candidates.size() < 2)
            return null;

        // tallest first, so each shelf wastes little height
        candidates.sort(Comparator.comparingInt(FiguraTexture::getUploadHeight).reversed()
                .thenComparing(Comparator.comparingInt(FiguraTexture::getUploadWidth).reversed()));

        int maxSize = Math.min(RenderSystem.maxSupportedTextureSize(), 8192);
        // the atlas must not take much more GPU memory than the textures did on their own
        while (candidates.size() >= 2) {
            long area = 0;
            int widest = 0;
            for (FiguraTexture t : candidates) {
                area += (long) (t.getUploadWidth() + 2 * PADDING) * (t.getUploadHeight() + 2 * PADDING);
                widest = Math.max(widest, t.getUploadWidth() + 2 * PADDING);
            }

            // the width (any size, GPUs take non power of two textures) giving the smallest atlas
            int bestWidth = -1, bestHeight = 0;
            long bestArea = Long.MAX_VALUE;
            for (int w = widest; w <= maxSize; w += 16) {
                int[] positions = pack(candidates, w);
                if (positions == null)
                    continue;
                int h = positions[positions.length - 1];
                if (h > maxSize)
                    continue;
                long a = (long) w * h;
                if (a < bestArea) {
                    bestArea = a;
                    bestWidth = w;
                    bestHeight = h;
                }
            }

            if (bestWidth > 0 && bestArea <= area + area / 4)
                return create(owner, candidates, bestWidth, bestHeight);

            // too big or too wasteful: leave the largest texture on its own and try again
            candidates.remove(0);
        }
        return null;
    }

    private static AvatarAtlas create(UUID owner, List<FiguraTexture> candidates, int atlasWidth, int atlasHeight) {
        int[] positions = pack(candidates, atlasWidth);
        AvatarAtlas atlas = new AvatarAtlas(owner, atlasWidth, atlasHeight);
        TextureUtil.prepareImage(atlas.getId(), atlasWidth, atlasHeight);
        Minecraft.getInstance().getTextureManager().register(atlas.location, atlas);
        for (int i = 0; i < candidates.size(); i++) {
            FiguraTexture texture = candidates.get(i);
            Region region = new Region(atlas, positions[i * 2] + PADDING, positions[i * 2 + 1] + PADDING,
                    texture.getUploadWidth(), texture.getUploadHeight());
            atlas.textures.add(texture);
            texture.setAtlasRegion(region);
        }
        FiguraMod.debug("Packed " + candidates.size() + " textures of " + owner + " into a " + atlasWidth + "x" + atlasHeight + " atlas");
        return atlas;
    }

    // shelf packing: x, y of each texture (with its border), then the total height as the last element; null if a texture is too wide
    private static int[] pack(List<FiguraTexture> textures, int atlasWidth) {
        int[] out = new int[textures.size() * 2 + 1];
        int x = 0, y = 0, shelfHeight = 0;
        for (int i = 0; i < textures.size(); i++) {
            FiguraTexture t = textures.get(i);
            int w = t.getUploadWidth() + 2 * PADDING, h = t.getUploadHeight() + 2 * PADDING;
            if (w > atlasWidth)
                return null;
            if (x + w > atlasWidth) {
                y += shelfHeight;
                x = 0;
                shelfHeight = 0;
            }
            out[i * 2] = x;
            out[i * 2 + 1] = y;
            x += w;
            shelfHeight = Math.max(shelfHeight, h);
        }
        out[out.length - 1] = y + shelfHeight;
        return out;
    }

    // gives every texture back its own upload and frees the atlas
    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        for (FiguraTexture texture : textures)
            if (texture.atlasRegion != null && texture.atlasRegion.atlas == this)
                texture.setAtlasRegion(null);
        textures.clear();
        this.releaseId();
        ((TextureManagerAccessor) Minecraft.getInstance().getTextureManager()).getByPath().remove(this.location);
    }

    public boolean isClosed() {
        return closed;
    }
}
