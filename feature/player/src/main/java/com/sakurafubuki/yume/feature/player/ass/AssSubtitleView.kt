package com.sakurafubuki.yume.feature.player.ass

import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun AssSubtitleView(
    modifier: Modifier = Modifier,
    state: AssSubtitleState,
) {
    if (!state.isLoaded) return

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setZOrderMediaOverlay(true)

                holder.addCallback(object : SurfaceHolder.Callback {

                    override fun surfaceCreated(holder: SurfaceHolder) {
                        AssRenderer.nativeSetSurface(state.handle, holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        AssRenderer.nativeSetSurface(state.handle, holder.surface)
                        AssRenderer.nativeSetFrameSize(state.handle, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        AssRenderer.nativeSetSurface(state.handle, null)
                    }
                })
            }
        },
    )
}
