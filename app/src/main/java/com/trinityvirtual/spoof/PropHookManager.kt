package com.trinityvirtual.spoof

import android.util.Log

/**
 * PropHookManager — Kotlin wrapper untuk prop_hook.cpp (trinity_spoof native lib).
 *
 * Dipanggil PERTAMA di TrinityApplication.onCreate(), sebelum VirtualCore dan
 * RootEngine, sehingga semua hook property + maps filter sudah aktif sebelum
 * proses guest (mis. Free Fire) di-spawn.
 *
 * Flow init():
 *  1. Load library "trinity_spoof" (jika belum dimuat)
 *  2. Panggil nativeApplyIntegrityBypass():
 *       → Muat Samsung S23 Ultra prop profile ke tabel fakeroot_preload
 *       → Register semua suspicious patterns (qemu/goldfish/magisk/frida/…)
 *  3. Log hasilnya
 *
 * API tambahan:
 *  setPropOverride(key, value)   — Override satu properti spesifik
 *  addHiddenPattern(pattern)     — Tambah pattern ke filter runtime
 *  checkIntegrity(): Boolean     — Self-test: verifikasi hook merespons benar
 *  sanitize(str): String         — Strip suspicious strings dari output apapun
 *  reset()                       — Reset prop table ke default (Samsung profile)
 */
object PropHookManager {

    private const val TAG = "PropHookManager"

    @Volatile private var initialized = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Inisialisasi integrity bypass hooks.
     * Harus dipanggil SEBELUM VirtualCore.init() dan RootEngine.init().
     * Thread-safe — idempotent (aman dipanggil lebih dari sekali).
     */
    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val ok = nativeApplyIntegrityBypass()
                initialized = true
                if (ok) {
                    Log.i(TAG, "Integrity bypass active — Samsung S23 Ultra profile loaded")
                } else {
                    Log.w(TAG, "nativeApplyIntegrityBypass() returned false — partial init")
                }
            } catch (e: Exception) {
                Log.e(TAG, "init() failed: ${e.message}")
            }
        }
    }

    // ── Runtime configuration ─────────────────────────────────────────────────

    /**
     * Override satu property spesifik.
     * Contoh: setPropOverride("ro.product.model", "SM-S928B")
     */
    fun setPropOverride(key: String, value: String) {
        try {
            nativeSetPropOverride(key, value)
            Log.d(TAG, "Prop override: [$key] = [$value]")
        } catch (e: Exception) {
            Log.e(TAG, "setPropOverride failed: ${e.message}")
        }
    }

    /**
     * Tambah kata kunci ke daftar pattern yang difilter dari semua prop reads
     * dan /proc/self/maps. Contoh: addHiddenPattern("gameguardian")
     */
    fun addHiddenPattern(pattern: String) {
        try {
            nativeAddHiddenPattern(pattern)
            Log.d(TAG, "Hidden pattern added: '$pattern'")
        } catch (e: Exception) {
            Log.e(TAG, "addHiddenPattern failed: ${e.message}")
        }
    }

    /**
     * Verifikasi bahwa hooks merespons dengan nilai yang benar.
     * Membaca ro.product.brand, ro.build.fingerprint, ro.hardware via
     * __system_property_get dan memastikan tidak ada suspicious string.
     *
     * @return true jika semua prop checks lulus
     */
    fun checkIntegrity(): Boolean = try {
        val ok = nativeCheckPropIntegrity()
        Log.i(TAG, "Integrity check: ${if (ok) "PASS ✓" else "FAIL ✗"}")
        ok
    } catch (e: Exception) {
        Log.e(TAG, "checkIntegrity exception: ${e.message}")
        false
    }

    /**
     * Sanitize string — hapus semua suspicious substring (qemu, magisk, dll).
     * Berguna untuk membersihkan output shell/proc sebelum diteruskan ke guest.
     */
    fun sanitize(input: String): String = try {
        nativeSanitizeString(input)
    } catch (e: Exception) {
        Log.e(TAG, "sanitize failed: ${e.message}")
        input
    }

    /**
     * Reset tabel prop ke default Samsung profile dan reload semua patterns.
     * Gunakan setelah perubahan config agar state konsisten.
     */
    fun reset() {
        initialized = false
        init()
        Log.i(TAG, "PropHookManager reset and re-initialized")
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    /** Load Samsung S23 Ultra profile + register all suspicious patterns. */
    private external fun nativeApplyIntegrityBypass(): Boolean

    /** Override satu key-value di prop table. */
    private external fun nativeSetPropOverride(key: String, value: String)

    /** Tambah satu pattern ke hidden pattern list. */
    private external fun nativeAddHiddenPattern(pattern: String)

    /** Self-test: return true jika semua critical props return spoofed values. */
    private external fun nativeCheckPropIntegrity(): Boolean

    /** Strip suspicious substrings dari arbitrary string. */
    private external fun nativeSanitizeString(input: String): String

    init {
        try {
            System.loadLibrary("trinity_spoof")
            Log.d(TAG, "trinity_spoof library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "trinity_spoof not available: ${e.message}")
        }
    }
}
