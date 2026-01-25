package com.eval.export

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Encodes a GIF file consisting of one or more frames.
 * Based on the Java implementation by Kevin Weiner.
 */
class AnimatedGifEncoder {
    private var width: Int = 0
    private var height: Int = 0
    private var transparent: Int? = null
    private var transIndex: Int = 0
    private var repeat: Int = -1 // -1 = no repeat, 0 = infinite repeat
    private var delay: Int = 0 // frame delay in hundredths of a second
    private var started: Boolean = false
    private var out: OutputStream? = null
    private var image: Bitmap? = null
    private var pixels: ByteArray? = null
    private var indexedPixels: ByteArray? = null
    private var colorDepth: Int = 0
    private var colorTab: ByteArray? = null
    private var usedEntry = BooleanArray(256)
    private var palSize: Int = 7 // color table size (bits - 1)
    private var dispose: Int = -1 // disposal code (-1 = use default)
    private var closeStream: Boolean = false
    private var firstFrame: Boolean = true
    private var sizeSet: Boolean = false
    private var sample: Int = 10 // default sample interval for quantizer

    /**
     * Sets the delay time between each frame, or changes it for subsequent frames.
     * @param ms delay time in milliseconds
     */
    fun setDelay(ms: Int) {
        delay = (ms / 10f).toInt()
    }

    /**
     * Sets the GIF frame disposal code for the last added frame and any subsequent frames.
     * Default is 0 if no transparent color has been set, otherwise 2.
     * @param code disposal code
     */
    fun setDispose(code: Int) {
        if (code >= 0) {
            dispose = code
        }
    }

    /**
     * Sets the number of times the set of GIF frames should be played.
     * Default is 1; 0 means play indefinitely.
     * @param iter number of iterations
     */
    fun setRepeat(iter: Int) {
        if (iter >= 0) {
            repeat = iter
        }
    }

    /**
     * Sets the transparent color for the last added frame and any subsequent frames.
     * Since all colors are subject to modification in the quantization process,
     * the color in the final palette for each frame closest to the given color
     * becomes the transparent color for that frame.
     * @param c Color to be treated as transparent on display
     */
    fun setTransparent(c: Int) {
        transparent = c
    }

    /**
     * Adds next GIF frame. The frame is not written immediately, but is actually
     * deferred until the next frame is received so that timing data can be inserted.
     * @param im Bitmap containing frame to write
     * @return true if successful
     */
    fun addFrame(im: Bitmap?): Boolean {
        if (im == null || !started) {
            return false
        }
        var ok = true
        try {
            if (!sizeSet) {
                // use first frame's size
                setSize(im.width, im.height)
            }
            image = im
            if (!getImagePixels()) return false // convert to correct format if necessary
            if (!analyzePixels()) return false // build color table & map pixels
            if (firstFrame) {
                writeLSD() // logical screen descriptor
                writePalette() // global color table
                if (repeat >= 0) {
                    writeNetscapeExt() // use NS app extension to indicate reps
                }
            }
            writeGraphicCtrlExt() // write graphic control extension
            writeImageDesc() // image descriptor
            if (!firstFrame) {
                writePalette() // local color table
            }
            writePixels() // encode and write pixel data
            firstFrame = false
        } catch (e: Exception) {
            ok = false
        }
        return ok
    }

    /**
     * Initiates GIF file creation on the given stream.
     * @param os OutputStream on which GIF images are written
     * @return false if initial write failed
     */
    fun start(os: OutputStream): Boolean {
        var ok = true
        closeStream = false
        out = os
        try {
            writeString("GIF89a") // header
        } catch (e: Exception) {
            ok = false
        }
        started = ok
        return ok
    }

    /**
     * Flushes any pending data and closes output stream.
     * @return true if successful
     */
    fun finish(): Boolean {
        if (!started) return false
        var ok = true
        started = false
        try {
            out?.write(0x3b) // gif trailer
            out?.flush()
            if (closeStream) {
                out?.close()
            }
        } catch (e: Exception) {
            ok = false
        }
        // reset for subsequent use
        transIndex = 0
        out = null
        image = null
        pixels = null
        indexedPixels = null
        colorTab = null
        closeStream = false
        firstFrame = true
        return ok
    }

    /**
     * Sets frame rate in frames per second.
     * @param fps frame rate
     */
    fun setFrameRate(fps: Float) {
        if (fps > 0f) {
            delay = (100f / fps).toInt()
        }
    }

    /**
     * Sets quality of color quantization (conversion of images to the maximum 256
     * colors allowed by the GIF specification). Lower values (minimum = 1) produce
     * better colors, but slow processing significantly. 10 is the default, and
     * produces good color mapping at reasonable speeds. Values greater than 20 do
     * not yield significant improvements in speed.
     * @param quality quality value greater than 0
     */
    fun setQuality(quality: Int) {
        if (quality < 1) sample = 1 else sample = quality
    }

    /**
     * Sets the GIF frame size.
     * @param w frame width
     * @param h frame height
     */
    fun setSize(w: Int, h: Int) {
        if (started && !firstFrame) return
        width = w
        height = h
        if (width < 1) width = 320
        if (height < 1) height = 240
        sizeSet = true
    }

    /**
     * Extracts image pixels into byte array "pixels"
     * @return true if successful, false if image is null
     */
    private fun getImagePixels(): Boolean {
        val img = image ?: return false
        val w = img.width
        val h = img.height
        val scaledImg = if (w != width || h != height) {
            // create new image with right size/format
            Bitmap.createScaledBitmap(img, width, height, true).also { image = it }
        } else {
            img
        }
        val pixelsInt = IntArray(width * height)
        scaledImg.getPixels(pixelsInt, 0, width, 0, 0, width, height)

        // convert to RGB bytes
        val pixelBytes = ByteArray(width * height * 3)
        var idx = 0
        for (pixel in pixelsInt) {
            pixelBytes[idx++] = ((pixel shr 16) and 0xff).toByte() // R
            pixelBytes[idx++] = ((pixel shr 8) and 0xff).toByte()  // G
            pixelBytes[idx++] = (pixel and 0xff).toByte()          // B
        }
        pixels = pixelBytes
        return true
    }

    /**
     * Analyzes image colors and creates color map.
     * @return true if successful, false if pixels is null
     */
    private fun analyzePixels(): Boolean {
        val pix = pixels ?: return false
        val len = pix.size
        val nPix = len / 3
        val indexed = ByteArray(nPix)

        // Use NeuQuant algorithm to quantize colors
        val nq = NeuQuant(pix, len, sample)
        colorTab = nq.process() // create reduced palette

        // Map image pixels to new palette
        var k = 0
        for (i in 0 until nPix) {
            val index = nq.map(
                pix[k++].toInt() and 0xff,
                pix[k++].toInt() and 0xff,
                pix[k++].toInt() and 0xff
            )
            usedEntry[index] = true
            indexed[i] = index.toByte()
        }
        indexedPixels = indexed
        pixels = null
        colorDepth = 8
        palSize = 7

        // Get closest match to transparent color if specified
        val trans = transparent
        if (trans != null) {
            transIndex = findClosest(trans)
        }
        return true
    }

    /**
     * Returns index of palette color closest to c
     */
    private fun findClosest(c: Int): Int {
        val colors = colorTab ?: return -1
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        var minpos = 0
        var dmin = 256 * 256 * 256
        val len = colors.size
        var i = 0
        while (i < len) {
            val dr = r - (colors[i++].toInt() and 0xff)
            val dg = g - (colors[i++].toInt() and 0xff)
            val db = b - (colors[i].toInt() and 0xff)
            val d = dr * dr + dg * dg + db * db
            val index = i / 3
            if (usedEntry[index] && d < dmin) {
                dmin = d
                minpos = index
            }
            i++
        }
        return minpos
    }

    /**
     * Writes Graphic Control Extension
     */
    private fun writeGraphicCtrlExt() {
        out?.write(0x21) // extension introducer
        out?.write(0xf9) // GCE label
        out?.write(4) // data block size
        val transp: Int
        var disp: Int
        if (transparent == null) {
            transp = 0
            disp = 0 // dispose = no action
        } else {
            transp = 1
            disp = 2 // force clear if using transparent color
        }
        if (dispose >= 0) {
            disp = dispose and 7 // user override
        }
        disp = disp shl 2

        // packed fields
        out?.write(
            0 or // 1:3 reserved
            disp or // 4:6 disposal
            0 or // 7 user input - 0 = none
            transp // 8 transparency flag
        )

        writeShort(delay) // delay x 1/100 sec
        out?.write(transIndex) // transparent color index
        out?.write(0) // block terminator
    }

    /**
     * Writes Image Descriptor
     */
    private fun writeImageDesc() {
        out?.write(0x2c) // image separator
        writeShort(0) // image position x,y = 0,0
        writeShort(0)
        writeShort(width) // image size
        writeShort(height)
        // packed fields
        if (firstFrame) {
            // no LCT - GCT is used for first (or only) frame
            out?.write(0)
        } else {
            // specify normal LCT
            out?.write(
                0x80 or // 1 local color table 1=yes
                0 or // 2 interlace - 0=no
                0 or // 3 sorted - 0=no
                0 or // 4-5 reserved
                palSize // 6-8 size of color table
            )
        }
    }

    /**
     * Writes Logical Screen Descriptor
     */
    private fun writeLSD() {
        // logical screen size
        writeShort(width)
        writeShort(height)
        // packed fields
        out?.write(
            0x80 or // 1 : global color table flag = 1 (gct used)
            0x70 or // 2-4 : color resolution = 7
            0x00 or // 5 : gct sort flag = 0
            palSize // 6-8 : gct size
        )
        out?.write(0) // background color index
        out?.write(0) // pixel aspect ratio - assume 1:1
    }

    /**
     * Writes Netscape application extension to define repeat count.
     */
    private fun writeNetscapeExt() {
        out?.write(0x21) // extension introducer
        out?.write(0xff) // app extension label
        out?.write(11) // block size
        writeString("NETSCAPE" + "2.0") // app id + auth code
        out?.write(3) // sub-block size
        out?.write(1) // loop sub-block id
        writeShort(repeat) // loop count (extra iterations, 0=repeat forever)
        out?.write(0) // block terminator
    }

    /**
     * Writes color table
     */
    private fun writePalette() {
        val colors = colorTab ?: return
        out?.write(colors, 0, colors.size)
        val n = (3 * 256) - colors.size
        for (i in 0 until n) {
            out?.write(0)
        }
    }

    /**
     * Encodes and writes pixel data
     */
    private fun writePixels() {
        val indexed = indexedPixels ?: return
        val output = out ?: return
        val encoder = LZWEncoder(width, height, indexed, colorDepth)
        encoder.encode(output)
    }

    /**
     * Write 16-bit value to output stream, LSB first
     */
    private fun writeShort(value: Int) {
        out?.write(value and 0xff)
        out?.write((value shr 8) and 0xff)
    }

    /**
     * Writes string to output stream
     */
    private fun writeString(s: String) {
        for (c in s) {
            out?.write(c.code)
        }
    }
}

/**
 * NeuQuant Neural-Net Quantization Algorithm
 * Based on Anthony Dekker's algorithm
 */
private class NeuQuant(private val thePicture: ByteArray, private val lengthCount: Int, private val sampleFac: Int) {
    companion object {
        private const val netSize = 256 // number of colors used
        private const val prime1 = 499
        private const val prime2 = 491
        private const val prime3 = 487
        private const val prime4 = 503
        private const val minPictureBytes = 3 * prime4

        private const val maxNetPos = netSize - 1
        private const val netBiasShift = 4
        private const val nCycles = 100

        private const val intBiasShift = 16
        private const val intBias = 1 shl intBiasShift
        private const val gammaShift = 10
        private const val betaShift = 10
        private const val beta = intBias shr betaShift
        private const val betaGamma = intBias shl (gammaShift - betaShift)

        private const val initRad = netSize shr 3
        private const val radiusBiasShift = 6
        private const val radiusBias = 1 shl radiusBiasShift
        private const val initRadius = initRad * radiusBias
        private const val radiusDec = 30

        private const val alphaBiasShift = 10
        private const val initAlpha = 1 shl alphaBiasShift

        private const val radBiasShift = 8
        private const val radBias = 1 shl radBiasShift
        private const val alphaRadBiasShift = alphaBiasShift + radBiasShift
        private const val alphaRadBias = 1 shl alphaRadBiasShift
    }

    private val network = Array(netSize) { IntArray(4) }
    private val netIndex = IntArray(256)
    private val bias = IntArray(netSize)
    private val freq = IntArray(netSize)
    private val radPower = IntArray(initRad)

    init {
        for (i in 0 until netSize) {
            val p = network[i]
            val v = (i shl (netBiasShift + 8)) / netSize
            p[0] = v
            p[1] = v
            p[2] = v
            freq[i] = intBias / netSize
            bias[i] = 0
        }
    }

    fun process(): ByteArray {
        learn()
        unbiasNet()
        inxBuild()
        return colorMap()
    }

    private fun colorMap(): ByteArray {
        val map = ByteArray(3 * netSize)
        val index = IntArray(netSize)
        for (i in 0 until netSize) {
            index[network[i][3]] = i
        }
        var k = 0
        for (i in 0 until netSize) {
            val j = index[i]
            map[k++] = network[j][0].toByte()
            map[k++] = network[j][1].toByte()
            map[k++] = network[j][2].toByte()
        }
        return map
    }

    private fun inxBuild() {
        var previousCol = 0
        var startPos = 0
        for (i in 0 until netSize) {
            val p = network[i]
            var smallPos = i
            var smallVal = p[1]
            for (j in i + 1 until netSize) {
                val q = network[j]
                if (q[1] < smallVal) {
                    smallPos = j
                    smallVal = q[1]
                }
            }
            val q = network[smallPos]
            if (i != smallPos) {
                var j = q[0]; q[0] = p[0]; p[0] = j
                j = q[1]; q[1] = p[1]; p[1] = j
                j = q[2]; q[2] = p[2]; p[2] = j
                j = q[3]; q[3] = p[3]; p[3] = j
            }
            if (smallVal != previousCol) {
                netIndex[previousCol] = (startPos + i) shr 1
                for (j in previousCol + 1 until smallVal) {
                    netIndex[j] = i
                }
                previousCol = smallVal
                startPos = i
            }
        }
        netIndex[previousCol] = (startPos + maxNetPos) shr 1
        for (j in previousCol + 1..255) {
            netIndex[j] = maxNetPos
        }
    }

    private fun learn() {
        if (lengthCount < minPictureBytes) {
            sampleFac
        }
        val alphadec = 30 + ((sampleFac - 1) / 3)
        val pix = thePicture
        val lengthCount = lengthCount
        val samplepixels = lengthCount / (3 * sampleFac)
        var delta = samplepixels / nCycles
        var alpha = initAlpha
        var radius = initRadius

        var rad = radius shr radiusBiasShift
        if (rad <= 1) rad = 0
        for (i in 0 until rad) {
            radPower[i] = alpha * (((rad * rad - i * i) * radBias) / (rad * rad))
        }

        val step = when {
            lengthCount < minPictureBytes -> 3
            lengthCount % prime1 != 0 -> 3 * prime1
            lengthCount % prime2 != 0 -> 3 * prime2
            lengthCount % prime3 != 0 -> 3 * prime3
            else -> 3 * prime4
        }

        var i = 0
        var p = 0
        while (i < samplepixels) {
            val b = (pix[p].toInt() and 0xff) shl netBiasShift
            val g = (pix[p + 1].toInt() and 0xff) shl netBiasShift
            val r = (pix[p + 2].toInt() and 0xff) shl netBiasShift
            var j = contest(b, g, r)
            alterSingle(alpha, j, b, g, r)
            if (rad != 0) alterNeigh(rad, j, b, g, r)
            p += step
            if (p >= lengthCount) p -= lengthCount
            i++
            if (delta == 0) delta = 1
            if (i % delta == 0) {
                alpha -= alpha / alphadec
                radius -= radius / radiusDec
                rad = radius shr radiusBiasShift
                if (rad <= 1) rad = 0
                for (k in 0 until rad) {
                    radPower[k] = alpha * (((rad * rad - k * k) * radBias) / (rad * rad))
                }
            }
        }
    }

    fun map(b: Int, g: Int, r: Int): Int {
        var bestd = 1000
        var best = -1
        var i = netIndex[g]
        var j = i - 1

        while (i < netSize || j >= 0) {
            if (i < netSize) {
                val p = network[i]
                var dist = p[1] - g
                if (dist >= bestd) {
                    i = netSize
                } else {
                    i++
                    if (dist < 0) dist = -dist
                    var a = p[0] - b
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
            if (j >= 0) {
                val p = network[j]
                var dist = g - p[1]
                if (dist >= bestd) {
                    j = -1
                } else {
                    j--
                    if (dist < 0) dist = -dist
                    var a = p[0] - b
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
        }
        return best
    }

    private fun unbiasNet() {
        for (i in 0 until netSize) {
            network[i][0] = network[i][0] shr netBiasShift
            network[i][1] = network[i][1] shr netBiasShift
            network[i][2] = network[i][2] shr netBiasShift
            network[i][3] = i
        }
    }

    private fun alterNeigh(rad: Int, i: Int, b: Int, g: Int, r: Int) {
        var lo = i - rad
        if (lo < -1) lo = -1
        var hi = i + rad
        if (hi > netSize) hi = netSize

        var j = i + 1
        var k = i - 1
        var m = 1
        while (j < hi || k > lo) {
            val a = radPower[m++]
            if (j < hi) {
                val p = network[j++]
                p[0] -= (a * (p[0] - b)) / alphaRadBias
                p[1] -= (a * (p[1] - g)) / alphaRadBias
                p[2] -= (a * (p[2] - r)) / alphaRadBias
            }
            if (k > lo) {
                val p = network[k--]
                p[0] -= (a * (p[0] - b)) / alphaRadBias
                p[1] -= (a * (p[1] - g)) / alphaRadBias
                p[2] -= (a * (p[2] - r)) / alphaRadBias
            }
        }
    }

    private fun alterSingle(alpha: Int, i: Int, b: Int, g: Int, r: Int) {
        val n = network[i]
        n[0] -= (alpha * (n[0] - b)) / initAlpha
        n[1] -= (alpha * (n[1] - g)) / initAlpha
        n[2] -= (alpha * (n[2] - r)) / initAlpha
    }

    private fun contest(b: Int, g: Int, r: Int): Int {
        var bestd = Integer.MAX_VALUE.inv()
        var bestBiasd = bestd
        var bestPos = -1
        var bestBiasPos = bestPos

        for (i in 0 until netSize) {
            val n = network[i]
            var dist = n[0] - b
            if (dist < 0) dist = -dist
            var a = n[1] - g
            if (a < 0) a = -a
            dist += a
            a = n[2] - r
            if (a < 0) a = -a
            dist += a
            if (dist < bestd) {
                bestd = dist
                bestPos = i
            }
            val biasDist = dist - (bias[i] shr (intBiasShift - netBiasShift))
            if (biasDist < bestBiasd) {
                bestBiasd = biasDist
                bestBiasPos = i
            }
            val betaFreq = freq[i] shr betaShift
            freq[i] -= betaFreq
            bias[i] += betaFreq shl gammaShift
        }
        freq[bestPos] += beta
        bias[bestPos] -= betaGamma
        return bestBiasPos
    }
}

/**
 * LZW Encoder for GIF format
 */
private class LZWEncoder(private val imgW: Int, private val imgH: Int, private val pixAry: ByteArray, private val initCodeSize: Int) {
    companion object {
        private const val EOF = -1
        private const val BITS = 12
        private const val HSIZE = 5003
    }

    private var nBits: Int = 0
    private var maxBits = BITS
    private var maxCode: Int = 0
    private var maxMaxCode = 1 shl BITS
    private val htab = IntArray(HSIZE)
    private val codeTab = IntArray(HSIZE)
    private val masks = intArrayOf(
        0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F,
        0x00FF, 0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    )
    private var freeEnt = 0
    private var clearFlg = false
    private var gInitBits: Int = 0
    private var clearCode: Int = 0
    private var eofCode: Int = 0
    private var curAccum = 0
    private var curBits = 0
    private var aCount: Int = 0
    private val accum = ByteArray(256)
    private var remaining: Int = 0
    private var curPixel: Int = 0

    fun encode(os: OutputStream) {
        os.write(initCodeSize)
        remaining = imgW * imgH
        curPixel = 0
        compress(initCodeSize + 1, os)
        os.write(0)
    }

    private fun compress(initBits: Int, outs: OutputStream) {
        gInitBits = initBits
        clearFlg = false
        nBits = gInitBits
        maxCode = maxCode(nBits)
        clearCode = 1 shl (initBits - 1)
        eofCode = clearCode + 1
        freeEnt = clearCode + 2
        aCount = 0

        var ent = nextPixel()

        var hShift = 0
        var fCode = HSIZE
        while (fCode < 65536) {
            hShift++
            fCode *= 2
        }
        hShift = 8 - hShift

        val hSizeReg = HSIZE
        clHash(hSizeReg)
        output(clearCode, outs)

        var c: Int
        outer@ while ((nextPixel().also { c = it }) != EOF) {
            fCode = (c shl maxBits) + ent
            var i = (c shl hShift) xor ent

            if (htab[i] == fCode) {
                ent = codeTab[i]
                continue
            } else if (htab[i] >= 0) {
                var disp = hSizeReg - i
                if (i == 0) disp = 1
                do {
                    i -= disp
                    if (i < 0) i += hSizeReg
                    if (htab[i] == fCode) {
                        ent = codeTab[i]
                        continue@outer
                    }
                } while (htab[i] >= 0)
            }
            output(ent, outs)
            ent = c
            if (freeEnt < maxMaxCode) {
                codeTab[i] = freeEnt++
                htab[i] = fCode
            } else {
                clBlock(outs)
            }
        }
        output(ent, outs)
        output(eofCode, outs)
    }

    private fun clBlock(outs: OutputStream) {
        clHash(HSIZE)
        freeEnt = clearCode + 2
        clearFlg = true
        output(clearCode, outs)
    }

    private fun clHash(hSize: Int) {
        for (i in 0 until hSize) {
            htab[i] = -1
        }
    }

    private fun maxCode(nBits: Int): Int {
        return (1 shl nBits) - 1
    }

    private fun nextPixel(): Int {
        if (remaining == 0) return EOF
        remaining--
        val pix = pixAry[curPixel++]
        return pix.toInt() and 0xff
    }

    private fun output(code: Int, outs: OutputStream) {
        curAccum = curAccum and masks[curBits]

        curAccum = if (curBits > 0) {
            curAccum or (code shl curBits)
        } else {
            code
        }

        curBits += nBits

        while (curBits >= 8) {
            charOut((curAccum and 0xff).toByte(), outs)
            curAccum = curAccum shr 8
            curBits -= 8
        }

        if (freeEnt > maxCode || clearFlg) {
            if (clearFlg) {
                maxCode = maxCode(gInitBits.also { nBits = it })
                clearFlg = false
            } else {
                nBits++
                maxCode = if (nBits == maxBits) {
                    maxMaxCode
                } else {
                    maxCode(nBits)
                }
            }
        }

        if (code == eofCode) {
            while (curBits > 0) {
                charOut((curAccum and 0xff).toByte(), outs)
                curAccum = curAccum shr 8
                curBits -= 8
            }
            flushChar(outs)
        }
    }

    private fun charOut(c: Byte, outs: OutputStream) {
        accum[aCount++] = c
        if (aCount >= 254) {
            flushChar(outs)
        }
    }

    private fun flushChar(outs: OutputStream) {
        if (aCount > 0) {
            outs.write(aCount)
            outs.write(accum, 0, aCount)
            aCount = 0
        }
    }
}
