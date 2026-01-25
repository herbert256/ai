package com.eval.stockfish

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.concurrent.atomic.AtomicLong

data class PvLine(
    val score: Float,
    val isMate: Boolean,
    val mateIn: Int,
    val pv: String,
    val multipv: Int
)

data class AnalysisResult(
    val depth: Int,
    val nodes: Long,
    val nps: Long,
    val lines: List<PvLine>
) {
    // Convenience properties for backward compatibility
    val bestLine: PvLine? get() = lines.firstOrNull()
    val score: Float get() = bestLine?.score ?: 0f
    val isMate: Boolean get() = bestLine?.isMate ?: false
    val mateIn: Int get() = bestLine?.mateIn ?: 0
    val bestMove: String get() = bestLine?.pv?.split(" ")?.firstOrNull() ?: ""
    val pv: String get() = bestLine?.pv ?: ""
}

class StockfishEngine(private val context: Context) {
    companion object {
        // Maximum number of moves to display in the principal variation line
        private const val MAX_PV_MOVES_DISPLAY = 8
        // Maximum safe hash table size in MB to prevent crashes on mobile devices
        private const val MAX_SAFE_HASH_MB = 32
        // Maximum safe thread count for mobile devices
        private const val MAX_SAFE_THREADS = 4
    }

    private var process: Process? = null
    private var processWriter: BufferedWriter? = null
    private var processReader: BufferedReader? = null

    private val _analysisResult = MutableStateFlow<AnalysisResult?>(null)
    val analysisResult: StateFlow<AnalysisResult?> = _analysisResult

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private var analysisJob: Job? = null
    // Scope is created lazily and can be recreated after shutdown
    private var _scope: CoroutineScope? = null
    private val scope: CoroutineScope
        get() = _scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob()).also { _scope = it }
    // Mutex to ensure only one analysis runs at a time
    private val analysisMutex = kotlinx.coroutines.sync.Mutex()
    // Lock for thread-safe access to pvLines
    private val pvLinesLock = Any()

    private var stockfishPath: String? = null
    private var currentMultiPv = 1
    private val pvLines = mutableMapOf<Int, PvLine>()
    private var currentNodes: Long = 0
    private var currentNps: Long = 0

    /**
     * Check if Stockfish is installed on the system (com.stockfish141 package).
     * This is a synchronous check that can be called before initialize().
     */
    fun isStockfishInstalled(): Boolean {
        return try {
            val packageManager = context.packageManager
            packageManager.getApplicationInfo("com.stockfish141", 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Use system-installed Stockfish (com.stockfish141 package)
            val systemStockfishPath = findSystemStockfish()
            if (systemStockfishPath != null) {
                android.util.Log.i("StockfishEngine", "Using system Stockfish: $systemStockfishPath")
                stockfishPath = systemStockfishPath
            } else {
                android.util.Log.e("StockfishEngine", "Stockfish 17.1 Chess Engine app not installed")
                return@withContext false
            }

            // Start the process
            startProcess()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Attempts to find Stockfish binary from the system-installed com.stockfish141 package.
     * Returns the path to the binary if found, null otherwise.
     */
    private fun findSystemStockfish(): String? {
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo("com.stockfish141", 0)
            val nativeLibDir = appInfo.nativeLibraryDir

            // Look for the Stockfish binary (lib_sf171.so for Stockfish 17.1)
            val libDir = File(nativeLibDir)
            if (libDir.exists() && libDir.isDirectory) {
                // Find any stockfish-related .so file
                val stockfishFile = libDir.listFiles()?.find {
                    it.name.contains("sf") || it.name.contains("stockfish", ignoreCase = true)
                }
                if (stockfishFile != null && stockfishFile.exists() && stockfishFile.canExecute()) {
                    return stockfishFile.absolutePath
                }
            }
            null
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            android.util.Log.d("StockfishEngine", "System Stockfish package not installed")
            null
        } catch (e: Exception) {
            android.util.Log.e("StockfishEngine", "Error finding system Stockfish: ${e.message}")
            null
        }
    }

    private suspend fun startProcess() {
        val path = stockfishPath ?: return

        try {
            process = ProcessBuilder(path)
                .redirectErrorStream(true)
                .start()

            val p = process ?: throw IllegalStateException("Stockfish process creation failed")
            processWriter = BufferedWriter(OutputStreamWriter(p.outputStream))
            processReader = BufferedReader(InputStreamReader(p.inputStream))

            // Initialize UCI
            sendCommand("uci")

            // Read until uciok
            var line = processReader?.readLine()
            while (line != null && line != "uciok") {
                line = processReader?.readLine()
            }

            // Send isready and wait for readyok
            sendCommand("isready")
            line = processReader?.readLine()
            while (line != null && line != "readyok") {
                line = processReader?.readLine()
            }

            _isReady.value = true

        } catch (e: Exception) {
            e.printStackTrace()
            _isReady.value = false
        }
    }

    private fun sendCommand(command: String) {
        try {
            processWriter?.write(command)
            processWriter?.newLine()
            processWriter?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Start a new game (clears hash table and resets engine state).
     * Should be called before analyzing a new game or between analysis rounds.
     */
    fun newGame() {
        if (!_isReady.value) {
            android.util.Log.e("StockfishEngine", "newGame called but engine not ready!")
            return
        }
        sendCommand("stop")
        sendCommand("ucinewgame")
        android.util.Log.d("StockfishEngine", "New game started (hash cleared)")
    }

    fun configure(threads: Int, hashMb: Int, multiPv: Int, useNnue: Boolean = true) {
        if (!_isReady.value) {
            android.util.Log.e("StockfishEngine", "configure called but engine not ready!")
            return
        }

        // Stop any ongoing analysis first
        sendCommand("stop")

        // Cap hash size to prevent memory-related crashes
        // On mobile devices, large hash tables can cause the process to die
        val safeHashMb = hashMb.coerceAtMost(MAX_SAFE_HASH_MB)
        val safeThreads = threads.coerceAtMost(MAX_SAFE_THREADS)
        if (safeHashMb != hashMb || safeThreads != threads) {
            android.util.Log.w("StockfishEngine", "Settings capped for stability: Hash ${hashMb}→${safeHashMb}MB, Threads ${threads}→${safeThreads}")
        }

        currentMultiPv = multiPv
        sendCommand("setoption name Threads value $safeThreads")
        sendCommand("setoption name Hash value $safeHashMb")
        sendCommand("setoption name MultiPV value $multiPv")
        sendCommand("setoption name Use NNUE value $useNnue")

        android.util.Log.d("StockfishEngine", "Configured: Threads=$safeThreads, Hash=$safeHashMb, MultiPV=$multiPv, NNUE=$useNnue")
        // Note: The analysis job will send "isready" and wait for "readyok" before starting
    }

    fun analyze(fen: String, depth: Int = 16) {
        if (!_isReady.value) {
            android.util.Log.e("StockfishEngine", "analyze: engine not ready, skipping")
            return
        }

        analysisJob?.cancel()
        analysisJob = scope.launch {
            analysisMutex.withLock {
                try {
                    // Stop any ongoing analysis
                    sendCommand("stop")

                    // Clear previous lines and reset result (synchronized for thread safety)
                    synchronized(pvLinesLock) {
                        pvLines.clear()
                    }
                    currentNodes = 0
                    _analysisResult.value = null

                    // Ensure engine is ready (waits for any pending commands to complete)
                    sendCommand("isready")

                    // Read until we get readyok to ensure engine is ready
                    var line = processReader?.readLine()
                    while (line != null && line != "readyok" && isActive) {
                        line = processReader?.readLine()
                    }

                    if (!isActive) return@withLock

                    // Check if process is still alive
                    if (line == null) {
                        android.util.Log.e("StockfishEngine", "analyze: processReader returned null (engine crashed?)")
                        _isReady.value = false
                        return@withLock
                    }

                    // Set position and start depth-based analysis (runs until depth reached)
                    sendCommand("position fen $fen")
                    sendCommand("go depth $depth")

                    // Read analysis output
                    line = processReader?.readLine()
                    while (line != null && isActive) {
                        when {
                            line.startsWith("info depth") && line.contains("score") -> {
                                parseInfoLine(line)
                            }
                            line.startsWith("bestmove") -> {
                                break
                            }
                        }
                        line = processReader?.readLine()
                    }

                    // Check if we exited due to process death
                    if (line == null && isActive) {
                        android.util.Log.e("StockfishEngine", "analyze: engine died during analysis")
                        _isReady.value = false
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        android.util.Log.e("StockfishEngine", "analyze: exception: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun analyzeWithTime(fen: String, timeMs: Int) {
        if (!_isReady.value) {
            android.util.Log.e("StockfishEngine", "analyzeWithTime: engine not ready, skipping analysis")
            return
        }

        android.util.Log.d("StockfishEngine", "analyzeWithTime: starting analysis for ${timeMs}ms")

        // Cancel previous job
        analysisJob?.cancel()
        analysisJob = scope.launch {
            analysisMutex.withLock {
                try {
                    // Stop any ongoing analysis in the engine
                    sendCommand("stop")

                    // Clear previous lines and reset result (synchronized for thread safety)
                    synchronized(pvLinesLock) {
                        pvLines.clear()
                    }
                    currentNodes = 0
                    _analysisResult.value = null

                    // Ensure engine is ready (waits for any pending commands to complete)
                    sendCommand("isready")

                    // Read until we get readyok to ensure engine is ready
                    var line = processReader?.readLine()
                    var readyAttempts = 0
                    val maxReadyAttempts = 50  // Limit attempts to avoid infinite loop
                    while (line != null && line != "readyok" && isActive && readyAttempts < maxReadyAttempts) {
                        // Skip info lines from previous analysis that might still be in buffer
                        if (line.startsWith("info ") || line.startsWith("bestmove")) {
                            // Discard leftover output
                        }
                        line = processReader?.readLine()
                        readyAttempts++
                    }

                    if (!isActive) {
                        android.util.Log.d("StockfishEngine", "analyzeWithTime: cancelled before analysis")
                        return@withLock
                    }
                    if (line == null) {
                        // Check if process is still alive
                        val isAlive = try { process?.isAlive == true } catch (e: Exception) { false }
                        android.util.Log.e("StockfishEngine", "analyzeWithTime: processReader returned null (EOF?), process.isAlive=$isAlive")
                        _isReady.value = false
                        return@withLock
                    }
                    if (readyAttempts >= maxReadyAttempts) {
                        android.util.Log.e("StockfishEngine", "analyzeWithTime: timeout waiting for readyok after $readyAttempts attempts")
                        return@withLock
                    }

                    // Set position and start analysis with time limit
                    sendCommand("position fen $fen")
                    sendCommand("go movetime $timeMs")

                    // Read analysis output
                    line = processReader?.readLine()
                    var linesRead = 0
                    while (line != null && isActive) {
                        linesRead++
                        when {
                            line.startsWith("info depth") && line.contains("score") -> {
                                parseInfoLine(line)
                            }
                            line.startsWith("bestmove") -> {
                                break
                            }
                        }
                        line = processReader?.readLine()
                    }
                    if (line == null && linesRead < 3) {
                        android.util.Log.e("StockfishEngine", "analyzeWithTime: analysis ended early, only $linesRead lines read")
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        android.util.Log.e("StockfishEngine", "analyzeWithTime: exception: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun parseInfoLine(line: String) {
        try {
            // Extract depth
            val depthMatch = Regex("depth (\\d+)").find(line)
            val depth = depthMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            // Extract nodes
            val nodesMatch = Regex("nodes (\\d+)").find(line)
            val nodes = nodesMatch?.groupValues?.get(1)?.toLongOrNull() ?: currentNodes
            currentNodes = nodes

            // Extract nps (nodes per second)
            val npsMatch = Regex("nps (\\d+)").find(line)
            val nps = npsMatch?.groupValues?.get(1)?.toLongOrNull() ?: currentNps
            currentNps = nps

            // Extract multipv (defaults to 1)
            val multipvMatch = Regex("multipv (\\d+)").find(line)
            val multipv = multipvMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

            // Extract score
            var score = 0f
            var isMate = false
            var mateIn = 0

            val mateMatch = Regex("score mate (-?\\d+)").find(line)
            val cpMatch = Regex("score cp (-?\\d+)").find(line)

            if (mateMatch != null) {
                isMate = true
                mateIn = mateMatch.groupValues[1].toIntOrNull() ?: 0
                score = if (mateIn > 0) 100f else -100f
            } else if (cpMatch != null) {
                score = (cpMatch.groupValues[1].toIntOrNull() ?: 0) / 100f
            }

            // Extract PV
            val pvMatch = Regex(" pv (.+)$").find(line)
            val pv = pvMatch?.groupValues?.get(1) ?: ""

            // Store this PV line
            val pvLine = PvLine(
                score = score,
                isMate = isMate,
                mateIn = mateIn,
                pv = pv.split(" ").take(MAX_PV_MOVES_DISPLAY).joinToString(" "),
                multipv = multipv
            )

            // Update pvLines and emit result (synchronized for thread safety)
            val sortedLines = synchronized(pvLinesLock) {
                pvLines[multipv] = pvLine
                pvLines.values.sortedBy { it.multipv }
            }
            _analysisResult.value = AnalysisResult(
                depth = depth,
                nodes = currentNodes,
                nps = currentNps,
                lines = sortedLines
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        analysisJob?.cancel()
        sendCommand("stop")
    }

    /**
     * Wait for the current analysis job to complete.
     * Returns true if completed, false if timed out or no job running.
     */
    suspend fun waitForCompletion(timeoutMs: Long = 5000): Boolean {
        val job = analysisJob ?: return true  // No job means "complete"
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                job.join()
                true
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.w("StockfishEngine", "waitForCompletion: timeout after ${timeoutMs}ms")
            false
        }
    }

    /**
     * Restart the Stockfish engine. Kills the current process and starts a new one.
     * Returns true if the restart was successful.
     */
    suspend fun restart(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Stop any ongoing analysis
            analysisJob?.cancel()
            _isReady.value = false
            _analysisResult.value = null

            // Kill the current process
            try {
                sendCommand("quit")
                processWriter?.close()
                processReader?.close()
                val terminated = process?.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: true
                if (!terminated) {
                    process?.destroyForcibly()
                    process?.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } catch (e: Exception) {
                // Ignore errors during cleanup
            }

            // Clear state
            process = null
            processWriter = null
            processReader = null
            synchronized(pvLinesLock) {
                pvLines.clear()
            }
            currentNodes = 0

            // Delay to ensure process is fully terminated
            kotlinx.coroutines.delay(300)

            // Start new process
            startProcess()

            _isReady.value
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun shutdown() {
        analysisJob?.cancel()
        _scope?.cancel()
        _scope = null  // Allow scope to be recreated if engine is restarted
        try {
            sendCommand("quit")
            processWriter?.close()
            processReader?.close()
            process?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
