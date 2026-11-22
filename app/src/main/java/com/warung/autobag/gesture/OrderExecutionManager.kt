package com.warung.autobag.gesture

/**
 * Coordinates a fast tap sequence across the products in one customer order.
 *
 * Each execution group contains up to MAX_ITEM_TYPES_PER_SEQUENCE product
 * types. The taps are sent sequentially without an OCR round-trip between
 * product types, then the whole group is verified from one fresh order-panel
 * OCR. A failed or ambiguous verification pauses the queue; it never retries
 * blindly.
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

    data class TapCommand(
        val source: PixelPoint,
        val tapCount: Int
    )

    data class VerificationTarget(
        val itemName: String,
        val previousQuantity: Int,
        val expectedQuantity: Int,
        val requiredQuantity: Int
    )

    data class ObservedQuantity(
        val itemName: String,
        val currentQuantity: Int?,
        val requiredQuantity: Int?
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
            commands: List<TapCommand>,
            onComplete: (Boolean, String, List<Int>) -> Unit
        )
    }

    interface Listener {
        fun onStateChanged(snapshot: Snapshot)
        fun onBatchVerificationRequired(targets: List<VerificationTarget>)
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
    private var pendingTargets = emptyList<VerificationTarget>()
    private var pauseRequested = false
    private var runToken = 0
    private var dispatchedTaps = 0
    private var message = "Mesin eksekusi belum dimulai."

    var state: State = State.IDLE
        private set

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
                it.requiredQuantity <= 0 ||
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
        if (tasks.map { normalizeName(it.itemName) }.distinct().size != tasks.size) {
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
        pendingTargets = emptyList()
        pauseRequested = false
        dispatchedTaps = 0

        if (queue.isEmpty()) {
            state = State.COMPLETED
            publish("Semua jumlah pesanan sudah terpenuhi. Tidak ada tap yang dikirim.")
            return
        }

        state = State.RUNNING
        publish("Antrean dimulai. Hingga ${TargetGameConfig.MAX_ITEM_TYPES_PER_SEQUENCE} jenis barang diproses berurutan sebelum satu kali verifikasi OCR.")
        dispatchCurrentGroup()
    }

    fun pause() {
        when (state) {
            State.RUNNING -> {
                pauseRequested = true
                state = State.PAUSED
                publish("Pause diminta. Tap yang sedang berlangsung dibiarkan selesai; tap berikutnya dalam urutan dibatalkan.")
            }
            State.WAITING_VERIFICATION -> {
                state = State.PAUSED
                publish("Proses dijeda. Kelompok tap terakhir masih menunggu verifikasi OCR.")
            }
            else -> Unit
        }
    }

    fun resume() {
        if (state != State.PAUSED) return
        pauseRequested = false
        if (awaitingVerification) {
            state = State.WAITING_VERIFICATION
            publish("Dilanjutkan ke tahap verifikasi kelompok. Tidak ada tap ulang sebelum jumlah diperiksa.")
            notifyVerificationRequired()
        } else if (tapInFlight) {
            state = State.RUNNING
            publish("Dilanjutkan. Menunggu urutan tap yang sedang diproses selesai.")
        } else {
            state = State.RUNNING
            publish("Antrean dilanjutkan.")
            dispatchCurrentGroup()
        }
    }

    fun stop() {
        runToken++
        queue.clear()
        taskIndex = 0
        tapInFlight = false
        awaitingVerification = false
        pendingTargets = emptyList()
        pauseRequested = false
        state = State.STOPPED
        publish("Antrean dihentikan. Tidak ada tap lanjutan yang akan dikirim.")
    }

    /**
     * Confirms all products in the last small execution group from one fresh
     * OCR read of the order panel. A partial but positive count is accepted;
     * the remaining quantity is recalculated before another group is sent.
     */
    fun confirmBatchQuantities(observed: List<ObservedQuantity>) {
        if (!awaitingVerification || (state != State.WAITING_VERIFICATION && state != State.PAUSED)) {
            publish("Tidak ada kelompok tap yang sedang menunggu verifikasi.")
            return
        }
        if (pendingTargets.isEmpty()) {
            pauseWithVerification("Checkpoint verifikasi kelompok kosong.")
            return
        }

        val updates = mutableListOf<Pair<MutableTask, Int>>()
        for (target in pendingTargets) {
            val result = observed.firstOrNull {
                normalizeName(it.itemName) == normalizeName(target.itemName)
            }
            if (result == null || result.currentQuantity == null || result.requiredQuantity == null) {
                pauseWithVerification("Jumlah ${target.itemName} belum terbaca pada verifikasi kelompok. Tidak ada tap lanjutan yang dikirim.")
                return
            }
            if (result.requiredQuantity != target.requiredQuantity) {
                pauseWithVerification("Jumlah total ${target.itemName} berubah dari ${target.requiredQuantity} menjadi ${result.requiredQuantity}. Perlu pemeriksaan manual.")
                return
            }
            val minimumExpected = target.previousQuantity + 1
            if (result.currentQuantity !in minimumExpected..target.expectedQuantity) {
                pauseWithVerification(
                    "Verifikasi ${target.itemName} belum cocok. Sebelum tap ${target.previousQuantity}/${target.requiredQuantity}; " +
                        "terbaca ${result.currentQuantity}/${result.requiredQuantity}; " +
                        "yang diharapkan $minimumExpected-${target.expectedQuantity}. Tidak ada tap ulang otomatis."
                )
                return
            }

            val task = queue.firstOrNull {
                normalizeName(it.itemName) == normalizeName(target.itemName)
            }
            if (task == null) {
                pauseWithVerification("Item ${target.itemName} tidak ditemukan lagi di antrean internal.")
                return
            }
            updates += task to result.currentQuantity
        }

        updates.forEach { (task, current) -> task.currentQuantity = current }
        awaitingVerification = false
        pendingTargets = emptyList()
        advanceTaskIndex()

        publish(
            "Verifikasi kelompok berhasil: ${updates.joinToString { (task, current) -> "${task.itemName} $current/${task.requiredQuantity}" }}."
        )

        if (state == State.PAUSED || pauseRequested) {
            state = State.PAUSED
            publish("Progres kelompok tersimpan. Antrean dijeda pada ${currentTaskOrNull()?.itemName ?: "akhir antrean"}.")
            return
        }

        if (queue.all { it.currentQuantity >= it.requiredQuantity }) {
            state = State.COMPLETED
            publish("Semua pesanan berhasil diverifikasi. Antrean selesai.")
            return
        }

        state = State.RUNNING
        dispatchCurrentGroup()
    }

    fun verificationFailed(reason: String) {
        if (!awaitingVerification) return
        pauseWithVerification("Verifikasi kelompok gagal: $reason. Antrean dijeda tanpa mengulang tap.")
    }

    fun snapshot(): Snapshot {
        val task = currentTaskOrNull()
        return Snapshot(
            state = state,
            message = message,
            currentItem = task?.itemName,
            currentQuantity = task?.currentQuantity,
            requiredQuantity = task?.requiredQuantity,
            completedOrderTypes = queue.count { it.currentQuantity >= it.requiredQuantity },
            totalOrderTypes = queue.size,
            dispatchedTaps = dispatchedTaps
        )
    }

    private fun dispatchCurrentGroup() {
        if (state != State.RUNNING || pauseRequested || tapInFlight || awaitingVerification) return

        advanceTaskIndex()
        if (queue.all { it.currentQuantity >= it.requiredQuantity }) {
            state = State.COMPLETED
            publish("Antrean selesai. Semua jumlah pesanan telah diverifikasi.")
            return
        }

        val selectedTasks = queue
            .filter { it.currentQuantity < it.requiredQuantity }
            .take(TargetGameConfig.MAX_ITEM_TYPES_PER_SEQUENCE)

        if (selectedTasks.isEmpty()) {
            state = State.COMPLETED
            publish("Antrean selesai.")
            return
        }

        val commands = selectedTasks.map { task ->
            val remaining = (task.requiredQuantity - task.currentQuantity).coerceAtLeast(0)
            TapCommand(
                source = task.source,
                tapCount = minOf(remaining, TargetGameConfig.MAX_TAPS_PER_BATCH).coerceAtLeast(1)
            )
        }

        val token = runToken
        tapInFlight = true
        pendingTargets = emptyList()
        publish("Memproses ${selectedTasks.size} jenis barang dalam satu urutan tap, lalu memverifikasi semuanya sekaligus.")

        try {
            tapDispatcher.dispatch(commands) { success, detail, completedCounts ->
                if (token != runToken || state == State.STOPPED) return@dispatch
                tapInFlight = false

                val targets = mutableListOf<VerificationTarget>()
                selectedTasks.forEachIndexed { index, task ->
                    val sent = completedCounts.getOrNull(index)?.coerceIn(0, commands[index].tapCount) ?: 0
                    if (sent > 0) {
                        dispatchedTaps += sent
                        targets += VerificationTarget(
                            itemName = task.itemName,
                            previousQuantity = task.currentQuantity,
                            expectedQuantity = task.currentQuantity + sent,
                            requiredQuantity = task.requiredQuantity
                        )
                    }
                }

                if (targets.isEmpty()) {
                    state = State.PAUSED
                    pauseRequested = true
                    publish("Tidak ada tap yang berhasil dikirim: $detail. Antrean dijeda.")
                    return@dispatch
                }

                pendingTargets = targets
                awaitingVerification = true
                state = if (pauseRequested || state == State.PAUSED) State.PAUSED else State.WAITING_VERIFICATION
                val prefix = if (success) "Urutan tap selesai" else "Urutan tap terhenti setelah sebagian proses"
                publish("$prefix. ${targets.size} jenis barang menunggu satu verifikasi OCR.")
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
        if (pendingTargets.isNotEmpty()) {
            listener.onBatchVerificationRequired(pendingTargets.toList())
        }
    }

    private fun advanceTaskIndex() {
        taskIndex = queue.indexOfFirst { it.currentQuantity < it.requiredQuantity }
            .let { if (it < 0) queue.size else it }
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
        pendingTargets = emptyList()
        pauseRequested = false
        publish(reason)
    }

    private fun publish(newMessage: String) {
        message = newMessage
        listener.onStateChanged(snapshot())
    }

    private fun normalizeName(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun PixelPoint.isValid(): Boolean =
        x.isFinite() && y.isFinite() && x > 0f && y > 0f
}
