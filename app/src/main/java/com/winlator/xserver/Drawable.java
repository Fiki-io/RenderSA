package com.winlator.xserver;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.winlator.core.Callback;
import com.winlator.math.Mathf;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Drawable extends XResource {
    public final short width;
    public final short height;
    public final Visual visual;
    private Texture texture = new Texture(this);
    private ByteBuffer data;
    private boolean useSharedData;
    private Runnable onDrawListener;
    private boolean offscreenStorage = false;
    private Callback<Drawable> onDestroyListener;
    public final Object renderLock = new Object();

    static {
        System.loadLibrary("winlator");
    }

    public Drawable(int id, int width, int height, Visual visual) {
        super(id);
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;
        this.data = null;
    }

    private ByteBuffer ensureDataAllocated() {
        if (this.data == null && width > 0 && height > 0) {
            this.data = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        }
        return this.data;
    }

    public static Drawable fromBitmap(Bitmap bitmap) {
        Drawable drawable = new Drawable(0, bitmap.getWidth(), bitmap.getHeight(), null);
        fromBitmap(bitmap, drawable.ensureDataAllocated());
        return drawable;
    }

    public boolean isOffscreenStorage() {
        return offscreenStorage;
    }

    public void setOffscreenStorage(boolean offscreenStorage) {
        this.offscreenStorage = offscreenStorage;
    }

    public Texture getTexture() {
        return texture;
    }

    public void setTexture(Texture texture) {
        if (texture instanceof GPUImage) data = ((GPUImage)texture).getVirtualData();
        this.texture = texture;
    }

    @Nullable
    public ByteBuffer getData() {
        return ensureDataAllocated();
    }

    public void setData(ByteBuffer data) {
        this.data = data;
    }

    private short getStride() {
        return texture instanceof GPUImage ? ((GPUImage)texture).getStride() : width;
    }

    public Runnable getOnDrawListener() {
        return onDrawListener;
    }

    public void setOnDrawListener(Runnable onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public Callback<Drawable> getOnDestroyListener() {
        return onDestroyListener;
    }

    public void setOnDestroyListener(Callback<Drawable> onDestroyListener) {
        this.onDestroyListener = onDestroyListener;
    }

    public void drawImage(short srcX, short srcY, short dstX, short dstY, short width, short height, byte depth, ByteBuffer data, short totalWidth, short totalHeight) {
        ByteBuffer dstBuffer = ensureDataAllocated();
        if (dstBuffer == null) return;

        if (depth == 1) {
            drawBitmap(width, height, data, dstBuffer);
        } else if (depth == 24 || depth == 32) {
            dstX = (short)Mathf.clamp(dstX, 0, this.width - 1);
            dstY = (short)Mathf.clamp(dstY, 0, this.height - 1);
            if ((dstX + width) > this.width) width = (short)(this.width - dstX);
            if ((dstY + height) > this.height) height = (short)(this.height - dstY);

            copyArea(srcX, srcY, dstX, dstY, width, height, totalWidth, this.getStride(), data, dstBuffer);
        }

        dstBuffer.rewind();
        data.rewind();

        forceUpdate();
    }

    public ByteBuffer getImage(short x, short y, short width, short height) {
        ByteBuffer dstData = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer srcBuffer = ensureDataAllocated();
        if (srcBuffer == null) return dstData;

        x = (short)Mathf.clamp(x, 0, this.width - 1);
        y = (short)Mathf.clamp(y, 0, this.height - 1);
        if ((x + width) > this.width) width = (short)(this.width - x);
        if ((y + height) > this.height) height = (short)(this.height - y);

        copyArea(x, y, (short)0, (short)0, width, height, this.getStride(), width, srcBuffer, dstData);

        srcBuffer.rewind();
        dstData.rewind();
        return dstData;
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable) {
        copyArea(srcX, srcY, dstX, dstY, width, height, drawable, GraphicsContext.Function.COPY);
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable, GraphicsContext.Function gcFunction) {
        ByteBuffer dstBuffer = ensureDataAllocated();
        ByteBuffer srcBuffer = drawable.ensureDataAllocated();
        if (dstBuffer == null || srcBuffer == null) return;

        dstX = (short)Mathf.clamp(dstX, 0, this.width - 1);
        dstY = (short)Mathf.clamp(dstY, 0, this.height - 1);
        if ((dstX + width) > this.width) width = (short)(this.width - dstX);
        if ((dstY + height) > this.height) height = (short)(this.height - dstY);

        if (gcFunction == GraphicsContext.Function.COPY) {
            copyArea(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), srcBuffer, dstBuffer);
        } else {
            copyAreaOp(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), srcBuffer, dstBuffer, gcFunction.ordinal());
        }

        dstBuffer.rewind();
        srcBuffer.rewind();

        forceUpdate();
    }

    public void fillColor(int color) {
        fillRect(0, 0, width, height, color);
    }

    public void fillRect(int x, int y, int width, int height, int color) {
        ByteBuffer dstBuffer = ensureDataAllocated();
        if (dstBuffer == null) return;
        x = (short)Mathf.clamp(x, 0, this.width - 1);
        y = (short)Mathf.clamp(y, 0, this.height - 1);
        if ((x + width) > this.width) width = (short)(this.width - x);
        if ((y + height) > this.height) height = (short)(this.height - y);

        fillRect((short)x, (short)y, (short)width, (short)height, color, this.getStride(), dstBuffer);
        dstBuffer.rewind();
        forceUpdate();
    }

    public void drawLines(int color, int lineWidth, short... points) {
        for (int i = 2; i < points.length; i += 2) {
            drawLine(points[i - 2], points[i - 1], points[i], points[i + 1], color, (short)lineWidth);
        }
    }

    public void drawLine(int x0, int y0, int x1, int y1, int color, int lineWidth) {
        ByteBuffer dstBuffer = ensureDataAllocated();
        if (dstBuffer == null) return;
        x0 = Mathf.clamp(x0, 0, width - lineWidth);
        y0 = Mathf.clamp(y0, 0, height - lineWidth);
        x1 = Mathf.clamp(x1, 0, width - lineWidth);
        y1 = Mathf.clamp(y1, 0, height - lineWidth);

        drawLine((short)x0, (short)y0, (short)x1, (short)y1, color, (short)lineWidth, this.getStride(), dstBuffer);

        dstBuffer.rewind();
        forceUpdate();
    }

    public void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, Drawable srcDrawable, Drawable maskDrawable) {
        ByteBuffer dstBuffer = ensureDataAllocated();
        ByteBuffer srcBuffer = srcDrawable.ensureDataAllocated();
        ByteBuffer maskBuffer = maskDrawable.ensureDataAllocated();
        if (dstBuffer == null || srcBuffer == null || maskBuffer == null) return;
        drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, srcBuffer, maskBuffer, dstBuffer);
        dstBuffer.rewind();

        forceUpdate();
    }

    public void forceUpdate() {
        if (!offscreenStorage || useSharedData) {
            texture.setNeedsUpdate(true);
        }
        if (onDrawListener != null) onDrawListener.run();
    }

    public boolean isUseSharedData() {
        return useSharedData;
    }

    public void setUseSharedData(boolean useSharedData) {
        this.useSharedData = useSharedData;
    }

    private static native void drawBitmap(short width, short height, ByteBuffer srcData, ByteBuffer dstData);

    private static native void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, ByteBuffer srcData, ByteBuffer maskData, ByteBuffer dstData);

    private static native void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, ByteBuffer srcData, ByteBuffer dstData);

    private static native void copyAreaOp(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, ByteBuffer srcData, ByteBuffer dstData, int gcFunction);

    private static native void fillRect(short x, short y, short width, short height, int color, short stride, ByteBuffer data);

    private static native void drawLine(short x0, short y0, short x1, short y1, int color, short lineWidth, short stride, ByteBuffer data);

    private static native void fromBitmap(Bitmap bitmap, ByteBuffer data);
}