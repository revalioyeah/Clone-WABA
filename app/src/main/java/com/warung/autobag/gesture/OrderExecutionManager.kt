package com.warung.autobag.gesture

/**
 * Coordinates one-item-at-a-time order execution.
 *
 * Safety model:
 * - Exactly one hold-and-drag is dispatched at a time.
 * - A successful Android gesture is NOT treated as proof that the game
 *   accepted the product. A fresh OCR quantity snapshot must confirm it.
 * - The next drag is not dispatched until the previous quantity increases
 *   by exactly one.
 * - A failed or ambiguous verification pauses the queue; it never retries
 *   blindly, preventing accidental duplicate products.
 * - Pause takes effect after an in-flight gesture finishes. Stop prevents
 *   all subsequent gestures, although Android may finish a gesture already
 *   dispatched because AccessibilityService has no reliable cancellation
 *   API for an individual dispatchGesture call.
 *
 * The caller supplies calibrated physical screen coordinates and connects
 * [DragDispatcher] to GestureController.executeOneItem().
 */
class OrderExecutionManager(
    private val dragDispatcher: DragDispatcher,
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
        val dispatchedGestures: Int
    )

    fun interface DragDispatcher {
        fun dispatch(
            source: PixelPoint,
            destination: PixelPoint,
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
    private var destination: PixelPoint? = null
    private var taskIndex = 0
    private var gestureInFlight = false
    private var awaitingVerification = false
    private var pauseRequested = false
    private var runToken = 0
    private var dispatchedGestures = 0
    private var message = "Mesin eksekusi belum dimulai."

    var state: State = State.IDLE
        private set

    /**
     * Starts a new queue. All points must already be calibrated in physical
     * screen pixels. No default bag location is assumed.
     */
    fun start(tasks: List<OrderTask>, bagPoint: PixelPoint) {
        if (
            state == State.RUNNING ||
            state == State.WAITING_VERIFICATION ||
            state == State.PAUSED ||
            gestureInFlight
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
        if (!bagPoint.isValid()) {
            fail("Koordinat kantong plastik belum valid.")
            return
        }
        if (invalidTask != null) {
            fail("Data pesanan atau koordinat sumber tidak valid: ${invalidTask.itemName}.")
            return
        }
        if (tasks.map { it.itemName.trim().lowercase() }.distinct().size != tasks.size) {
            fail("Nama barang ganda ditemukan dalam antrean. Periksa hasil OCR sebelum menjalankan gesture.")
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

        destination = bagPoint
        taskIndex = 0
        gestureInFlight = false
        awaitingVerification = false
        pauseRequested = false
        dispatchedGestures = 0

        if (queue.isEmpty()) {
            state = State.COMPLETED
            publish("Semua jumlah pesanan sudah terpenuhi. Tidak ada gesture yang dikirim.")
            return
        }

        state = State.RUNNING
        publish("Antrean dimulai. Barang diproses satu per satu dan setiap drag wajib diverifikasi.")
        dispatchCurrentItem()
    }

    /** Pause after the current drag; never interrupts a finger-down gesture. */
    fun pause() {
        when (state) {
            State.RUNNING -> {
                pauseRequested = true
                if (!gestureInFlight && !awaitingVerification) {
                    state = State.PAUSED
                    publish("Proses dijeda sebelum gesture berikutnya.")
                } else {
                    state = State.PAUSED
                    publish("Pause diminta. Gesture yang sedang berlangsung dibiarkan selesai; tidak ada barang berikutnya yang akan diambil.")
                }
            }
            State.WAITING_VERIFICATION -> {
                state = State.PAUSED
                publish("Proses dijeda. Hasil drag terakhir masih menunggu verifikasi OCR.")
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
            publish("Dilanjutkan ke tahap verifikasi. Baca ulang jumlah pesanan sebelum drag berikutnya.")
            notifyVerificationRequired()
        } else if (gestureInFlight) {
            state = State.RUNNING
            publish("Dilanjutkan. Menunggu gesture yang sedang berlangsung selesai.")
        } else {
            state = State.RUNNING
            publish("Antrean dilanjutkan.")
            dispatchCurrentItem()
        }
    }

    /**
     * Stops the queue and invalidates callbacks from the old run. A gesture
     * already accepted by Android may finish physically, but cannot trigger
     * another drag after this call.
     */
    fun stop() {
        runToken++
        queue.clear()
        taskIndex = 0
        destination = null
        gestureInFlight = false
        awaitingVerification = false
        pauseRequested = false
        state = State.STOPPED
        publish("Antrean dihentikan. Tidak ada gesture lanjutan yang akan dikirim.")
    }

    /**
     * Feed a fresh OCR reading after the previous drag. The count must move
     * from N to exactly N+1 for the same product. Any mismatch pauses safely.
     */
    fun confirmQuantity(
        itemName: String,
        observedCurrentQuantity: Int?,
        observedRequiredQuantity: Int?
    ) {
        if (!awaitingVerification || (state != State.WAITING_VERIFICATION && state != State.PAUSED)) {
            publish("Tidak ada drag yang sedang menunggu verifikasi.")
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
            pauseWithVerification("Jumlah pesanan belum terbaca jelas. Tidak ada drag lanjutan yang dikirim.")
            return
        }

        if (observedRequiredQuantity != task.requiredQuantity) {
            pauseWithVerification("Jumlah total pesanan berubah dari ${task.requiredQuantity} menjadi $observedRequiredQuantity. Perlu pemeriksaan manual.")
            return
        }

        val expected = task.currentQuantity + 1
        if (observedCurrentQuantity != expected) {
            pauseWithVerification("Verifikasi belum cocok. Sebelum drag: ${task.currentQuantity}/${task.requiredQuantity}; terbaca: $observedCurrentQuantity/$observedRequiredQuantity; seharusnya: $expected/$observedRequiredQuantity. Tidak akan mengulang drag otomatis.")
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
        pauseWithVerification("Verifikasi gagal: $reason. Antrean dijeda tanpa mengulang drag.")
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
            dispatchedGestures = dispatchedGestures
        )
    }

    private fun dispatchCurrentItem() {
        if (state != State.RUNNING || pauseRequested) return
        if (gestureInFlight || awaitingVerification) return

        val task = currentTaskOrNull()
        val bag = destination
        if (task == null) {
            state = State.COMPLETED
            publish("Antrean selesai.")
            return
        }
        if (bag == null) {
            fail("Koordinat kantong plastik hilang. Gesture dibatalkan.")
            return
        }

        val token = runToken
        gestureInFlight = true
        publish("Mengambil ${task.itemName}: ${task.currentQuantity}/${task.requiredQuantity} → ${task.currentQuantity + 1}/${task.requiredQuantity}.")

        try {
            dragDispatcher.dispatch(task.source, bag) { success, detail ->
                if (token != runToken || state == State.STOPPED) return@dispatch

                gestureInFlight = false
                if (!success) {
                    state = State.PAUSED
                    pauseRequested = true
                    publish("Gesture gagal: $detail. Antrean dijeda; periksa posisi layar sebelum melanjutkan.")
                    return@dispatch
                }

                dispatchedGestures++
                awaitingVerification = true

                if (pauseRequested || state == State.PAUSED) {
                    state = State.PAUSED
                    publish("Drag ${task.itemName} selesai dikirim. Proses dijeda dan menunggu verifikasi jumlah.")
                } else {
                    state = State.WAITING_VERIFICATION
                    publish("Drag ${task.itemName} selesai dikirim. Menunggu OCR untuk memastikan jumlah bertambah satu.")
                }
                notifyVerificationRequired()
            }
        } catch (exception: Exception) {
            gestureInFlight = false
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
        destination = null
        gestureInFlight = false
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
