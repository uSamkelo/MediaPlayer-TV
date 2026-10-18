package com.ssdplayer.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        if (uris.isEmpty()) { finish(); return }

        view = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            controllerShowTimeoutMs = 3000
            keepScreenOn = true
        }
        setContentView(view)

        val exo = ExoPlayer.Builder(this).build().also { player = it }
        view.player = exo
        exo.setMediaItems(uris.mapIndexed { i, u ->
            MediaItem.Builder()
                .setUri(u.toUri())
                .setMediaId(u)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.getOrNull(i)).build())
                .build()
        })
        exo.seekTo(start, Progress.position(this, uris[start]))
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) exo.currentMediaItem?.let { Progress.clear(this@PlayerActivity, it.mediaId) }
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // Resume the next item where it was left off, if it was partly watched.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && item != null) {
                    val pos = Progress.position(this@PlayerActivity, item.mediaId)
                    if (pos > 0) exo.seekTo(pos)
                }
            }
        })
        exo.prepare()
        exo.playWhenReady = true
        view.requestFocus()
    }

    // Lets the PlayerView handle D-pad / media keys (seek, play-pause, show controls).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    private fun saveProgress() {
        val p = player ?: return
        p.currentMediaItem?.let { Progress.save(this, it.mediaId, p.currentPosition, p.duration) }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        saveProgress()
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
    }
}
