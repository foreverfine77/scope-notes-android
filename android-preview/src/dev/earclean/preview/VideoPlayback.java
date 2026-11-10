package dev.earclean.preview;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import java.io.IOException;

final class VideoPlayback extends FrameLayout implements TextureView.SurfaceTextureListener {
    final MediaPlayer player = new MediaPlayer();
    private final TextureView texture;
    private final Uri uri;
    private final Runnable onLoadError;
    private final boolean circular;
    private int videoWidth;
    private int videoHeight;
    private boolean loading;
    private boolean closed;

    VideoPlayback(Context context, Uri uri, boolean circular, Runnable onLoadError) {
        super(context);
        this.uri = uri;
        this.circular = circular;
        this.onLoadError = onLoadError;
        texture = new TextureView(context);
        if (circular) {
            texture.setOpaque(false);
            texture.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            texture.setClipToOutline(true);
        }
        texture.setSurfaceTextureListener(this);
        addView(texture, new LayoutParams(-1, -1, Gravity.CENTER));
        player.setOnVideoSizeChangedListener((media, width, height) -> {
            videoWidth = width;
            videoHeight = height;
            fitTexture();
        });
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        fitTexture();
    }

    private void fitTexture() {
        if (getWidth() == 0 || getHeight() == 0 || videoWidth == 0 || videoHeight == 0) return;
        float scale = Math.min((float) getWidth() / videoWidth, (float) getHeight() / videoHeight);
        int width = Math.round(videoWidth * scale);
        int height = Math.round(videoHeight * scale);
        if (circular) width = height = Math.min(width, height);
        LayoutParams params = (LayoutParams) texture.getLayoutParams();
        if (params.width == width && params.height == height) return;
        params.width = width;
        params.height = height;
        texture.setLayoutParams(params);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        if (closed) return;
        Surface surface = new Surface(surfaceTexture);
        try {
            player.setSurface(surface);
            if (!loading) {
                player.setDataSource(getContext(), uri);
                player.prepareAsync();
                loading = true;
            }
        } catch (IOException | RuntimeException error) {
            onLoadError.run();
            close();
        } finally {
            surface.release();
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (!closed) player.setSurface(null);
        return true;
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    void close() {
        if (closed) return;
        closed = true;
        player.release();
    }
}
