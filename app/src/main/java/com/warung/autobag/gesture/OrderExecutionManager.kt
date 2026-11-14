package com.warung.autobag.tap

/**
 * Coordinates one-item-at-a-time tap-to-bag order execution.
 *
 * Safety model:
 * - Exactly one tap is dispatched at a time.
 * - A successful Android tap is NOT treated as proof that the game
 *   accepted the product. A fresh OCR quantity snapshot must confirm it.
 * - The next tap is not dispatched until the previous quantity increases
 *   by exactly one.
 * - A failed or ambiguous verification pauses the queue; it never retries
 *   blindly, preventing accidental duplicate products.
 * - Pause takes effect after an in-flight tap finishes. Stop prevents
 *   all subsequent taps, although Android may finish a tap already
 *   dispatched because AccessibilityService has no reliable cancellation
 *   API for an individual dispatchGesture call.
 *
 * The caller supplies calibrated physical screen coordinates and connects
 * [TapDispatcher] to GestureController.executeTapFromImage().
 */
class OrderExecutionManager(
    private val tapDispatcher: TapDispatcher,
    private val listener: Listener
) {

    data class PixelPoint(val x: Float, val y: Float)

    data class OrderTask(
        val itemName: String,
        val currentQuantity: Int,
        val requiredQuantity: Int,
        val source: PixelPoint
    )

    enum class State {
        IDLE,
        RUNNING,
        WAITING_VERIFICATION,
        PAUSED,
        COMPLETED,
        STOPPED,
        FAILED
    }

    data class Snapshot(
        val state: State,
        val message: String,
        val currentItem: String?,
        val currentQuantity: Int?,
        val requiredQuantity: Int?,
        val completedOrderTypes: Int,
        val totalOrderTypes: Int,
        val dispatchedTaps: Int
    )

    fun interface TapDispatcher {
        fun dispatch(
            source: PixelPoint,
            onComplete: (Boolean, String) -> Unit
        )
    }

    interface Listener {
        fun onStateChanged(snapshot: Snapshot)
        fun onVerificationRequired(
            itemName: String,
            previousQuantity: Int,
            expectedQuantity: Int,
            requiredQuantity: Int
        )
    }

    private data class MutableTask(
        val itemName: String,
        var currentQuantity: Int,
        val requiredQuantity: Int,
        val source: PixelPoint
    )

    private val queue = mutableListOf<MutableTask>()
    private var taskIndex = 0
    private var tapInFlight = false
    private var awaitingVerification = false
    private var pauseRequested = false
    private var runToken = 0
    private var dispatchedTaps = 0
    private var message = "Mesin eksekusi belum dimulai."

    var state: State = State.IDLE
        private set

    /**
     * Starts a new queue. Product points are screenshot coordinates and are
     * mapped to physical display coordinates by GestureController.
     * The game itself moves a tapped product into the bag.
     */
    fun start(tasks: List<OrderTask>) {
        if (
            state == State.RUNNING ||
            state == State.WAITING_VERIFICATION ||
            state == State.PAUSED ||
            tapInFlight
        ) {
            publish("Antrean masih aktif atau memiliki checkpoint yang belum selesai. Lanjutkan atau tekan STOP sebelum memulai antrean baru.")
            return
        }

        val invalidTask = tasks.firstOrNull {
            it.itemName.isBlank() ||
                it.currentQuantity < 0 ||
                it.requiredQuantity < 0 ||
                it.currentQuantity > it.requiredQuantity ||
                !it.source.isValid()
        }

        if (tasks.isEmpty()) {
            fail("Daftar pesanan kosong.")
            return
        }
        if (invalidTask != null) {
            fail("Data pesanan atau koordinat sumber tidak valid: ${invalidTask.itemName}.")
            return
        }
        if (tasks.map { it.itemName.trim().lowercase() }.distinct().size != tasks.size) {
            fail("Nama barang ganda ditemukan dalam antrean. Periksa hasil OCR sebelum menjalankan tap.")
            return
        }

        runToken++
        queue.clear()
        queue += tasks
            .filter { it.currentQuantity < it.requiredQuantity }
            .map {
                MutableTask(
                    itemName = it.itemName,
                    currentQuantity = it.currentQuantity,
                    requiredQuantity = it.requiredQuantity,
                    source = it.source
                )
            }

        taskIndex = 0
        tapInFlight = false
        awaitingVerification = false
        pauseRequested = false
        dispatchedTaps = 0

        if (queue.isEmpty()) {
            state = State.COMPLETED
            publish("Semua jumlah pesanan sudah terpenuhi. Tidak ada tap yang dikirim.")
            return
        }

        state = State.RUNNING
        publish("Antrean dimulai. Barang diproses satu per satu dan setiap tap wajib diverifikasi.")
        dispatchCurrentItem()
    }

    /** Pause after the current tap; never interrupts a finger-down tap. */
    fun pause() {
        when (state) {
            State.RUNNING -> {
                pauseRequested = true
                if (!tapInFlight && !awaitingVerification) {
                    state = State.PAUSED
                    publish("Proses dijeda sebelum tap berikutnya.")
                } else {
                    state = State.PAUSED
                    publish("Pause diminta. Tap yang sedang berlangsung dibiarkan selesai; tidak ada barang berikutnya yang akan diambil.")
                }
            }
            State.WAITING_VERIFICATION -> {
                state = State.PAUSED
                publish("Proses dijeda. Hasil tap terakhir masih menunggu verifikasi OCR.")
            }
            else -> Unit
        }
    }

    /** Resume without discarding the current item or verification checkpoint. */
    fun resume() {
        if (state != State.PAUSED) return
        pauseRequested = false

        if (awaitingVerification) {
            state = State.WAITING_VERIFICATION
            publish("Dilanjutkan ke tahap verifikasi. Baca ulang jumlah pesanan sebelum tap berikutnya.")
            notifyVerificationRequired()
        } else if (tapInFlight) {
            state = State.RUNNING
            publish("Dilanjutkan. Menunggu tap yang sedang berlangsung selesai.")
        } else {
            state = State.RUNNING
            publish("Antrean dilanjutkan.")
            dispatchCurrentItem()
        }
    }

    /**
     * Stops the queue and invalidates callbacks from the old run. A tap
     * already accepted by Android may finish physically, but cannot trigger
     * another tap after this call.
     */
    fun stop() {
        runToken++
        queue.clear()
        taskIndex = 0
        tapInFlight = false
        awaitingVerification = false
        pauseRequested = false
        state = State.STOPPED
        publish("Antrean dihentikan. Tidak ada tap lanjutan yang akan dikirim.")
    }

    /**
     * Feed a fresh OCR reading after the previous tap. The count must move
     * from N to exactly N+1 for the same product. Any mismatch pauses safely.
     */
    fun confirmQuantity(
        itemName: String,
        observedCurrentQuantity: Int?,
        observedRequiredQuantity: Int?
    ) {
        if (!awaitingVerification || (state != State.WAITING_VERIFICATION && state != State.PAUSED)) {
            publish("Tidak ada tap yang sedang menunggu verifikasi.")
            return
        }

        val task = currentTaskOrNull()
        if (task == null) {
            fail("Checkpoint verifikasi tidak memiliki item aktif.")
            return
        }

        if (!itemName.equals(task.itemName, ignoreCase = true)) {
            pauseWithVerification("OCR membaca item '$itemName', sedangkan item yang menunggu verifikasi adalah '${task.itemName}'.")
            return
        }

        if (observedCurrentQuantity == null || observedRequiredQuantity == null) {
            pauseWithVerification("Jumlah pesanan belum terbaca jelas. Tidak ada tap lanjutan yang dikirim.")
            return
        }

        if (observedRequiredQuantity != task.requiredQuantity) {
            pauseWithVerification("Jumlah total pesanan berubah dari ${task.requiredQuantity} menjadi $observedRequiredQuantity. Perlu pemeriksaan manual.")
            return
        }

        val expected = task.currentQuantity + 1
        if (observedCurrentQuantity != expected) {
            pauseWithVerification("Verifikasi belum cocok. Sebelum tap: ${task.currentQuantity}/${task.requiredQuantity}; terbaca: $observedCurrentQuantity/$observedRequiredQuantity; seharusnya: $expected/$observedRequiredQuantity. Tidak akan mengulang tap otomatis.")
            return
        }

        task.currentQuantity = observedCurrentQuantity
        awaitingVerification = false
        publish("Terverifikasi: ${task.itemName} ${task.currentQuantity}/${task.requiredQuantity}.")

        if (task.currentQuantity >= task.requiredQuantity) {
            taskIndex++
        }

        if (state == State.PAUSED || pauseRequested) {
            state = State.PAUSED
            publish("Progres tersimpan. Dijeda pada ${currentTaskOrNull()?.itemName ?: "akhir antrean"}.")
            return
        }

        if (taskIndex >= queue.size) {
            state = State.COMPLETED
            publish("Semua pesanan berhasil diverifikasi. Antrean selesai.")
            return
        }

        state = State.RUNNING
        dispatchCurrentItem()
    }

    /** Keep the queue paused when OCR fails or the screenshot is stale. */
    fun verificationFailed(reason: String) {
        if (!awaitingVerification) return
        pauseWithVerification("Verifikasi gagal: $reason. Antrean dijeda tanpa mengulang tap.")
    }

    fun snapshot(): Snapshot {
        val task = currentTaskOrNull()
        return Snapshot(
            state = state,
            message = message,
            currentItem = task?.itemName,
            currentQuantity = task?.currentQuantity,
            requiredQuantity = task?.requiredQuantity,
            completedOrderTypes = taskIndex.coerceAtMost(queue.size),
            totalOrderTypes = queue.size,
            dispatchedTaps = dispatchedTaps
        )
    }

    private fun dispatchCurrentItem() {
        if (state != State.RUNNING || pauseRequested) return
        if (tapInFlight || awaitingVerification) return

        val task = currentTaskOrNull()
        if (task == null) {
            state = State.COMPLETED
            publish("Antrean selesai.")
            return
        }

        val token = runToken
        tapInFlight = true
        publish("Mengetuk ${task.itemName}: ${task.currentQuantity}/${task.requiredQuantity} → ${task.currentQuantity + 1}/${task.requiredQuantity}.")

        try {
            tapDispatcher.dispatch(task.source) { success, detail ->
                if (token != runToken || state == State.STOPPED) return@dispatch

                tapInFlight = false
                if (!success) {
                    state = State.PAUSED
                    pauseRequested = true
                    publish("Tap gagal: $detail. Antrean dijeda; periksa posisi layar sebelum melanjutkan.")
                    return@dispatch
                }

                dispatchedTaps++
                awaitingVerification = true

                if (pauseRequested || state == State.PAUSED) {
                    state = State.PAUSED
                    publish("Tap ${task.itemName} selesai dikirim. Proses dijeda dan menunggu verifikasi jumlah.")
                } else {
                    state = State.WAITING_VERIFICATION
                    publish("Tap ${task.itemName} selesai dikirim. Menunggu OCR untuk memastikan jumlah bertambah satu.")
                }
                notifyVerificationRequired()
            }
        } catch (exception: Exception) {
            tapInFlight = false
            state = State.PAUSED
            pauseRequested = true
            publish("Dispatcher mengalami kesalahan: ${exception.message ?: "tidak diketahui"}. Antrean dijeda.")
        }
    }

    private fun notifyVerificationRequired() {
        val task = currentTaskOrNull() ?: return
        listener.onVerificationRequired(
            itemName = task.itemName,
            previousQuantity = task.currentQuantity,
            expectedQuantity = task.currentQuantity + 1,
            requiredQuantity = task.requiredQuantity
        )
    }

    private fun currentTaskOrNull(): MutableTask? = queue.getOrNull(taskIndex)

    private fun pauseWithVerification(reason: String) {
        state = State.PAUSED
        pauseRequested = true
        publish(reason)
    }

    private fun fail(reason: String) {
        state = State.FAILED
        queue.clear()
        taskIndex = 0
        tapInFlight = false
        awaitingVerification = false
        pauseRequested = false
        publish(reason)
    }

    private fun publish(newMessage: String) {
        message = newMessage
        listener.onStateChanged(snapshot())
    }

    private fun PixelPoint.isValid(): Boolean =
        x.isFinite() && y.isFinite() && x > 0f && y > 0f
}
