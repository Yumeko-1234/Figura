package org.figuramc.figura.model.rendering.texture;

import com.mojang.blaze3d.pipeline.RenderCall;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.lua.LuaNotNil;
import org.figuramc.figura.lua.LuaWhitelist;
import org.figuramc.figura.lua.docs.LuaMethodDoc;
import org.figuramc.figura.lua.docs.LuaMethodOverload;
import org.figuramc.figura.lua.docs.LuaTypeDoc;
import org.figuramc.figura.math.matrix.FiguraMat4;
import org.figuramc.figura.math.vector.FiguraVec2;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.math.vector.FiguraVec4;
import org.figuramc.figura.mixin.render.TextureManagerAccessor;
import org.figuramc.figura.utils.ColorUtils;
import org.figuramc.figura.utils.FiguraIdentifier;
import org.figuramc.figura.utils.LuaUtils;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

@LuaWhitelist
@LuaTypeDoc(
        name = "Texture",
        value = "texture"
)
public class FiguraTexture extends SimpleTexture {

    /**
     * The ID of the texture, used to register to Minecraft.
     */
    private boolean registered = false;
    private boolean dirty = true;
    private boolean modified = false;
    private final String name;
    private final Avatar owner;

    /**
     * Native image holding the texture data for this texture.
     * Other players' textures only need it on the GPU: it is null once uploaded (or while "upload" waits for the first render),
     * and is read again from the source file if a script or a new size limit needs it, see image()
     */
    private NativeImage texture;
    // the image ready for the next upload of a dropped texture, already downscaled, closed once uploaded
    private NativeImage upload;
    // the file the texture was read from, null when it was made by a script or could not be read (then the image is never dropped)
    private final byte[] source;
    private final int width, height;
    // a script used the image, it stays loaded from then on
    private boolean pinned;
    private NativeImage backup;
    private boolean isClosed = false;

    // -- alpha of the uploaded image -- //
    // a texture whose pixels are all fully opaque or fully transparent draws the same with a cutout render type as with a translucent one
    // (both discard alpha under 0.1), and cutout needs no back to front sorting, see ImmediateAvatarRenderer.getTexture
    public static final byte ALPHA_UNKNOWN = 0, ALPHA_BINARY = 1, ALPHA_BLENDED = 2;
    private volatile byte alphaKind = ALPHA_UNKNOWN;

    // -- avatar atlas -- //
    // where this texture sits in its avatar's atlas, null when it is drawn on its own (see AvatarAtlas)
    AvatarAtlas.Region atlasRegion;
    // an atlas texture is only uploaded on its own once a part needs it that way (uvs outside the texture, a render type the atlas can't serve)
    private boolean standaloneWanted;
    private boolean standaloneUploaded;

    public FiguraTexture(Avatar owner, String name, byte[] data) {
        super(new FiguraIdentifier("avatar_tex/" + owner.owner + "/" + UUID.randomUUID()));

        NativeImage image = read(data);
        this.source = image == null ? null : data;
        if (image == null)
            image = new NativeImage(1, 1, true);

        this.texture = image;
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.name = name;
        this.owner = owner;

        // other players' textures: keep only what the first upload needs, the full image is loaded again from the source if ever needed
        if (canDrop()) {
            int factor = getDownscaleFactor();
            if (factor > 1) {
                this.upload = downscale(image, factor);
                image.close();
            } else {
                this.upload = image;
            }
            this.texture = null;
            // the image that will be uploaded is at hand here, usually off the render thread
            this.alphaKind = scanAlpha(this.upload);
        }
    }

    // if every pixel is either fully opaque or fully transparent, stops at the first one that is neither
    private static byte scanAlpha(NativeImage image) {
        if (image == null || image.format() != NativeImage.Format.RGBA)
            return ALPHA_BLENDED;
        int w = image.getWidth(), h = image.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = image.getPixelRGBA(x, y) >>> 24;
                if (alpha != 0 && alpha != 255)
                    return ALPHA_BLENDED;
            }
        }
        return ALPHA_BINARY;
    }

    // ALPHA_BINARY when the image on the GPU only has fully opaque and fully transparent pixels
    public byte getAlphaKind() {
        return alphaKind;
    }

    // the size of the image on the GPU (other players' textures over the size limit are downscaled)
    public int getUploadWidth() {
        int factor = getDownscaleFactor();
        return factor > 1 ? Math.max((width + factor - 1) / factor, 1) : width;
    }

    public int getUploadHeight() {
        int factor = getDownscaleFactor();
        return factor > 1 ? Math.max((height + factor - 1) / factor, 1) : height;
    }

    public AvatarAtlas.Region getAtlasRegion() {
        return atlasRegion;
    }

    // the texture is now also drawn on its own: uploaded standalone from the next upload on
    public void requestStandalone() {
        if (standaloneWanted)
            return;
        standaloneWanted = true;
        if (!standaloneUploaded)
            dirty = true;
        uploadIfDirty();
    }

    // put in (or taken out of, with null) an atlas: the next upload writes the image there
    void setAtlasRegion(AvatarAtlas.Region region) {
        this.atlasRegion = region;
        this.standaloneWanted = false;
        // a standalone copy uploaded before is freed, it is uploaded again if a part asks for it
        if (region != null && standaloneUploaded) {
            standaloneUploaded = false;
            this.releaseId();
        }
        this.dirty = true;
    }

    public FiguraTexture(Avatar owner, String name, int width, int height) {
        this(owner, name, new NativeImage(width, height, true), "");
    }

    public FiguraTexture(Avatar owner, String name, NativeImage image) {
        this(owner, name, image, "custom/");
    }

    private FiguraTexture(Avatar owner, String name, NativeImage image, String prefix) {
        super(new FiguraIdentifier("avatar_tex/" + owner.owner + "/" + prefix + UUID.randomUUID()));
        this.texture = image;
        this.source = null;
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.name = name;
        this.owner = owner;
    }

    private static NativeImage read(byte[] data) {
        try {
            ByteBuffer wrapper = BufferUtils.createByteBuffer(data.length);
            wrapper.put(data);
            wrapper.rewind();
            return NativeImage.read(wrapper);
        } catch (IOException e) {
            FiguraMod.LOGGER.error("", e);
            return null;
        }
    }

    // the full image, read again from the source when it was dropped
    private NativeImage image() {
        if (texture == null) {
            if (source == null)
                throw new IllegalStateException("Texture " + name + " has no image");
            // the pending upload is the full image when it did not need downscaling
            if (upload != null && upload.getWidth() == width && upload.getHeight() == height) {
                texture = upload;
            } else {
                if (upload != null)
                    upload.close();
                texture = read(source);
                if (texture == null)
                    texture = new NativeImage(width, height, true);
            }
            upload = null;
        }
        return texture;
    }

    // the image for a script: from now on it stays loaded, scripts expect their changes and reads to be consistent
    private NativeImage scriptImage() {
        pinned = true;
        return image();
    }

    // if the full image can be freed between uploads
    private boolean canDrop() {
        return source != null && !pinned && !owner.isHost;
    }

    @Override
    public void load(ResourceManager manager) throws IOException {}

    @Override
    public void close() {
        // Make sure it doesn't close twice (minecraft tries to close the texture when reloading textures
        if (isClosed) return;

        isClosed = true;

        // Close native images
        if (texture != null)
            texture.close();
        if (upload != null && upload != texture)
            upload.close();
        if (backup != null)
            backup.close();
        texture = upload = backup = null;

        this.releaseId();
        ((TextureManagerAccessor) Minecraft.getInstance().getTextureManager()).getByPath().remove(this.location);
    }

    public void uploadIfDirty() {
        if (!registered) {
            Minecraft.getInstance().getTextureManager().register(this.location, this);
            registered = true;
        }

        if (dirty && !isClosed) {
            dirty = false;

            RenderCall runnable = () -> {
                if (isClosed)
                    return;

                // Upload texture to GPU.
                // other players' textures larger than the limit only get a smaller copy on the GPU
                // uvs (which are 0 to 1) and scripts see the full size, see image()
                NativeImage ready = upload;
                upload = null;
                if (ready == null) {
                    NativeImage full = image();
                    int factor = getDownscaleFactor();
                    ready = factor > 1 ? downscale(full, factor) : full;
                    // scripts may have changed the pixels since the last scan
                    alphaKind = scanAlpha(ready);
                }

                // the host's image and the ones scripts use stay here, the others are freed once on the GPU
                boolean keep = ready == texture && !canDrop();

                AvatarAtlas.Region region = atlasRegion;
                if (region != null)
                    region.write(ready);

                if (region == null || standaloneWanted) {
                    TextureUtil.prepareImage(this.getId(), ready.getWidth(), ready.getHeight());
                    ready.upload(0, 0, 0, !keep);
                    standaloneUploaded = true;
                } else if (!keep) {
                    ready.close();
                }
                if (ready == texture && !keep)
                    texture = null;
            };

            if (RenderSystem.isOnRenderThreadOrInit()) {
                runnable.execute();
            } else {
                RenderSystem.recordRenderCall(runnable);
            }
        }
    }

    // forces the texture to be uploaded again, eg. when the size limit changes
    public void markDirty() {
        this.dirty = true;
    }

    private int getDownscaleFactor() {
        int max = Configs.AVATAR_TEXTURE_SIZE.value;
        if (max <= 0 || owner.isHost)
            return 1;
        int size = Math.max(width, height);
        return (size + max - 1) / max;
    }

    // box filter, weighted by alpha so transparent pixels don't darken the edges
    private static NativeImage downscale(NativeImage src, int factor) {
        int srcW = src.getWidth(), srcH = src.getHeight();
        int w = Math.max((srcW + factor - 1) / factor, 1), h = Math.max((srcH + factor - 1) / factor, 1);
        NativeImage dst = new NativeImage(w, h, false);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long r = 0, g = 0, b = 0, a = 0;
                int count = 0;
                int maxY = Math.min((y + 1) * factor, srcH), maxX = Math.min((x + 1) * factor, srcW);
                for (int sy = y * factor; sy < maxY; sy++) {
                    for (int sx = x * factor; sx < maxX; sx++) {
                        int c = src.getPixelRGBA(sx, sy); // ABGR
                        int alpha = c >>> 24;
                        r += (long) (c & 0xFF) * alpha;
                        g += (long) ((c >> 8) & 0xFF) * alpha;
                        b += (long) ((c >> 16) & 0xFF) * alpha;
                        a += alpha;
                        count++;
                    }
                }

                int out = 0;
                if (a > 0) {
                    int ra = (int) (r / a), ga = (int) (g / a), ba = (int) (b / a);
                    int aa = (int) (a / count);
                    out = (aa << 24) | (ba << 16) | (ga << 8) | ra;
                }
                dst.setPixelRGBA(x, y, out);
            }
        }

        return dst;
    }

    public void writeTexture(Path dest) throws IOException {
        image().writeToFile(dest);
    }

    private void backupImage() {
        this.modified = true;
        if (this.backup == null)
            backup = copy();
    }

    public NativeImage copy() {
        NativeImage texture = scriptImage();
        NativeImage image = new NativeImage(texture.format(), texture.getWidth(), texture.getHeight(), true);
        image.copyFrom(texture);
        return image;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    // the texture on its own: anything drawing it by location (sprites, the action wheel, scripts using the id) gets it uploaded standalone
    // even when the model draws it from the avatar atlas
    public ResourceLocation getLocation() {
        if (atlasRegion != null && !standaloneWanted)
            requestStandalone();
        return this.location;
    }

    // the location without asking for a standalone upload, for the model renderer, which decides between it and the atlas itself
    public ResourceLocation getRawLocation() {
        return this.location;
    }


    // -- lua stuff -- // 


    private FiguraVec4 parseColor(String method, Object r, Double g, Double b, Double a) {
        return LuaUtils.parseVec4(method, r, g, b, a, 0, 0, 0, 1);
    }

    @LuaWhitelist
    @LuaMethodDoc("texture.get_name")
    public String getName() {
        return name;
    }

    @LuaWhitelist
    @LuaMethodDoc("texture.get_path")
    public String getPath() {
        return getLocation().toString();
    }

    @LuaWhitelist
    @LuaMethodDoc("texture.get_dimensions")
    public FiguraVec2 getDimensions() {
        return FiguraVec2.of(getWidth(), getHeight());
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = @LuaMethodOverload(
                    argumentTypes = {Integer.class, Integer.class},
                    argumentNames = {"x", "y"}
            ),
            value = "texture.get_pixel")
    public FiguraVec4 getPixel(int x, int y) {
        try {
            return ColorUtils.abgrToRGBA(scriptImage().getPixelRGBA(x, y));
        } catch (Exception e) {
            throw new LuaError(e.getMessage());
        }
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = {
                    @LuaMethodOverload(
                            argumentTypes = {Integer.class, Integer.class, FiguraVec3.class},
                            argumentNames = {"x", "y", "rgb"}
                    ),
                    @LuaMethodOverload(
                            argumentTypes = {Integer.class, Integer.class, FiguraVec4.class},
                            argumentNames = {"x", "y", "rgba"}
                    ),
                    @LuaMethodOverload(
                            argumentTypes = {Integer.class, Integer.class, Double.class, Double.class, Double.class, Double.class},
                            argumentNames = {"x", "y", "r", "g", "b", "a"}
                    )
            },
            aliases = "pixel",
            value = "texture.set_pixel")
    public FiguraTexture setPixel(int x, int y, Object r, Double g, Double b, Double a) {
        try {
            backupImage();
            scriptImage().setPixelRGBA(x, y, ColorUtils.rgbaToIntABGR(parseColor("setPixel", r, g, b, a)));
            return this;
        } catch (Exception e) {
            throw new LuaError(e.getMessage());
        }
    }

    @LuaWhitelist
    public FiguraTexture pixel(int x, int y, Object r, Double g, Double b, Double a) {
        return setPixel(x, y, r, g, b, a);
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = {
                    @LuaMethodOverload(
                            argumentTypes = {Integer.class, Integer.class, Integer.class, Integer.class, FiguraVec3.class},
                            argumentNames = {"x", "y", "width", "height", "rgb"}
                    ),
                    @LuaMethodOverload(
                            argumentTypes = {Integer.class, Integer.class, Integer.class, Integer.class, FiguraVec4.class},
                            argumentNames = {"x", "y", "width", "height", "rgba"}
                    ),
                    @LuaMethodOverload(
                            argumentTypes = {Integer.class, Integer.class, Integer.class, Integer.class, Double.class, Double.class, Double.class, Double.class},
                            argumentNames = {"x", "y", "width", "height", "r", "g", "b", "a"}
                    )
            },
            value = "texture.fill")
    public FiguraTexture fill(int x, int y, int width, int height, Object r, Double g, Double b, Double a) {
        try {
            backupImage();
            scriptImage().fillRect(x, y, width, height, ColorUtils.rgbaToIntABGR(parseColor("fill", r, g, b, a)));
            return this;
        } catch (Exception e) {
            throw new LuaError(e.getMessage());
        }
    }

    @LuaWhitelist
    @LuaMethodDoc("texture.update")
    public FiguraTexture update() {
        this.dirty = true;
        return this;
    }

    @LuaWhitelist
    @LuaMethodDoc("texture.restore")
    public FiguraTexture restore() {
        if (modified) {
            scriptImage().copyFrom(backup);
            this.modified = false;
        }
        return this;
    }

    @LuaWhitelist
    @LuaMethodDoc("texture.save")
    public String save() {
        try {
            return Base64.getEncoder().encodeToString(scriptImage().asByteArray());
        } catch (Exception e) {
            throw new LuaError(e.getMessage());
        }
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = @LuaMethodOverload(
                    argumentTypes = {Integer.class, Integer.class, Integer.class, Integer.class, LuaFunction.class},
                    argumentNames = {"x", "y", "width", "height", "func"}
            ),
            value = "texture.apply_func"
    )
    public FiguraTexture applyFunc(int x, int y, int width, int height, @LuaNotNil LuaFunction function) {
        for (int i = y; i < y + height; i++) {
            for (int j = x; j < x + width; j++) {
                FiguraVec4 color = getPixel(j, i);
                LuaValue result = function.call(owner.luaRuntime.typeManager.javaToLua(color).arg1(), LuaValue.valueOf(j), LuaValue.valueOf(i));
                if (!result.isnil() && result.isuserdata(FiguraVec4.class))
                    setPixel(j, i, result.checkuserdata(FiguraVec4.class), null, null, null);
            }
        }
        return this;
    }

    @LuaWhitelist
    @LuaMethodDoc(
            overloads = @LuaMethodOverload(
                    argumentTypes = {Integer.class, Integer.class, Integer.class, Integer.class, FiguraMat4.class, Boolean.class},
                    argumentNames = {"x", "y", "width", "height", "matrix", "clip"}
            ),
            value = "texture.apply_matrix"
    )
    public FiguraTexture applyMatrix(int x, int y, int width, int height, @LuaNotNil FiguraMat4 matrix, boolean clip) {
        for (int i = y; i < y + height; i++) {
            for (int j = x; j < x + width; j++) {
                FiguraVec4 color = getPixel(j, i);
                color.transform(matrix);

                if (clip) {
                    color.x = Math.max(0, Math.min(color.x, 1));
                    color.y = Math.max(0, Math.min(color.y, 1));
                    color.z = Math.max(0, Math.min(color.z, 1));
                    color.w = Math.max(0, Math.min(color.w, 1));
                }

                setPixel(j, i, color, null, null, null);
            }
        }
        return this;
    }

    @LuaWhitelist
    public Object __index(String arg) {
        return "name".equals(arg) ? name : null;
    }

    @Override
    public String toString() {
        return name + " (" + getWidth() + "x" + getHeight() + ") (Texture)";
    }
}