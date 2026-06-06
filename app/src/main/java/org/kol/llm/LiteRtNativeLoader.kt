package org.kol.llm

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Preloads LiteRT's shared runtime before LiteRT-LM tries to load GPU sampler
 * libraries.
 *
 * Some upstream LiteRT-LM sampler prebuilts rely on `LiteRtCreateEnvironment`
 * from `libLiteRt.so` but do not declare that dependency explicitly in their
 * ELF `DT_NEEDED` entries. Loading the runtime first keeps Android's linker
 * namespace able to resolve those symbols when the sampler libraries are
 * opened later.
 */
object LiteRtNativeLoader {

    private const val TAG = "LiteRtNativeLoader"
    private val loaded = AtomicBoolean(false)

    /**
     * Handles ensure loaded.
     */
    fun ensureLoaded() {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            try {
                System.loadLibrary("LiteRt")
                loaded.set(true)
            } catch (t: Throwable) {
                // Best-effort pre-load: libLiteRt.so may already be resident in the
                // linker namespace (loaded by the AAR's own static initializer on some
                // Android versions). In that case System.loadLibrary throws but the
                // symbol IS resolvable. Mark as loaded and continue rather than
                // crashing the whole engine init.
                loaded.set(true)
                Log.w(TAG, "libLiteRt.so pre-load threw (may already be loaded) — continuing", t)
            }
        }
    }
}
