package com.yuyan.inputmethod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.shiyu.handwritten.runtime.HCCRRecognizer
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinCaseType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinOutputFormat
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinToneType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinVCharType
import com.yuyan.imemodule.utils.LogUtil
import com.yuyan.imemodule.utils.thread.ThreadPoolUtils
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.imemodule.callback.IHandWritingCallBack
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 手写识别引擎:基于开源 HCCR(Ismantic/Handwritten, NCNN)替换原搜狗 handWriting 库。
 *
 * 数据流:
 *   1. HandwritingKeyboard 采集笔画,编码为 (x, y) 点对,笔画以 (-1, 0) 结束;
 *   2. 本引擎把笔画还原并渲染为白底黑笔画 Bitmap(硬二值,无 AA);
 *   3. HCCRRecognizer.preprocess 完成 bbox 裁切 + 等比缩放居中 → float[64*64];
 *   4. HCCRRecognizer.predict 返回 Top-K 候选,装配为 CandidateListItem 上报。
 */
object HWEngine {

    private const val TAG = "HWEngine"

    /** 渲染给模型的画布长边目标 px。与 Ismantic/Handwritten demo 一致。 */
    private const val RENDER_TARGET_SIZE = 360

    /** 模型输入口径:笔宽约为画布长边的 4.4%,缩到 64×64 后约 2.5px,与 HWDB 分布对齐。 */
    private const val RENDER_STROKE_RATIO = 0.044f

    /** 每次识别返回的候选数。 */
    private const val CANDIDATE_COUNT = 10

    private var mHanyuPinyinOutputFormat: HanyuPinyinOutputFormat
    private var recognizer: HCCRRecognizer? = null

    init {
        mHanyuPinyinOutputFormat = HanyuPinyinOutputFormat()
        mHanyuPinyinOutputFormat.caseType = HanyuPinyinCaseType.LOWERCASE
        mHanyuPinyinOutputFormat.toneType = HanyuPinyinToneType.WITH_TONE_MARK
        mHanyuPinyinOutputFormat.vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
        initRecognizer()
    }

    private fun initRecognizer() {
        if (recognizer != null) return
        try {
            HCCRRecognizer.loadNativeLibrary("hccr_jni")
            recognizer = HCCRRecognizer(
                Launcher.instance.context.assets,
                "hccr_model.int8.ncnn.param",
                "hccr_model.int8.ncnn.bin",
                "hccr_charset.json"
            )
        } catch (e: Throwable) {
            LogUtil.e(TAG, "initRecognizer", "HCCR 手写模型初始化失败: ${e.message}")
        }
    }

    fun recognitionData(strokes: MutableList<Short?>, recogResult: IHandWritingCallBack) {
        val engine = recognizer
        val data = strokes?.toList()
        if (engine == null || data.isNullOrEmpty()) return
        // 推理放到后台线程执行,避免阻塞 IME 主线程
        ThreadPoolUtils.executeSingleton {
            try {
                val gray = renderStrokesToGray(data) ?: return@executeSingleton
                val input = FloatArray(64 * 64)
                if (!engine.preprocess(gray.pixels, gray.width, gray.height, input)) {
                    return@executeSingleton
                }
                val results = engine.predict(input, CANDIDATE_COUNT)
                if (results.isEmpty()) return@executeSingleton
                val items = ArrayList<CandidateListItem>(results.size)
                for (r in results) {
                    val text = r.text
                    val pinyin =
                        PinyinHelper.toHanYuPinyin(text, mHanyuPinyinOutputFormat, "'").ifEmpty { text }
                    items.add(CandidateListItem(pinyin, text))
                }
                recogResult.onSucess(items.toTypedArray())
            } catch (e: Throwable) {
                LogUtil.e(TAG, "recognitionData", "HCCR 识别失败: ${e.message}")
            }
        }
    }

    private class GrayImage(val pixels: ByteArray, val width: Int, val height: Int)

    /**
     * 把采集的笔画点集还原并渲染为白底黑笔画灰度图。
     *
     * mSBPoint 编码:按 (x, y) 成对排列,笔画以 (-1, 0) 结束
     * (坐标恒 >= 0,不会与真实点冲突)。渲染口径与 Ismantic/Handwritten
     * 保持一致(360 目标长边 + 笔宽 4.4% + 关闭 AA + lineTo 直线段),
     * 使 C 端预处理结果与训练 / Python demo 分布对齐。
     */
    private fun renderStrokesToGray(records: List<Short?>): GrayImage? {
        val strokes = ArrayList<MutableList<PointF>>()
        var stroke: MutableList<PointF>? = null
        var i = 0
        while (i < records.size - 1) {
            val x = records[i]?.toInt() ?: break
            val y = records[i + 1]?.toInt() ?: break
            if (x == -1) { // 笔画结束标记,跳过其后的 0
                stroke = null
                i += 2
                continue
            }
            if (stroke == null) {
                stroke = ArrayList()
                strokes.add(stroke)
            }
            stroke.add(PointF(x.toFloat(), y.toFloat()))
            i += 2
        }
        if (strokes.isEmpty()) return null

        // 计算所有笔画点的包围盒
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        for (s in strokes) {
            for (p in s) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
        }
        val contentW = maxX - minX
        val contentH = maxY - minY
        if (contentW <= 0f || contentH <= 0f) return null

        // 长边等比缩放到目标尺寸,避免在高分辨率画布放大后再大幅缩小损失细节
        val scale = RENDER_TARGET_SIZE / max(contentW, contentH)
        val dstW = max(1, (contentW * scale).roundToInt())
        val dstH = max(1, (contentH * scale).roundToInt())

        val bmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false // ★关 AA★,与 PIL ImageDraw.line(L mode)一致,hard binary
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT // PIL line cap 默认 butt
            strokeJoin = Paint.Join.ROUND // PIL joint='curve' 同 round
            strokeWidth = RENDER_TARGET_SIZE * RENDER_STROKE_RATIO
        }

        val path = Path()
        for (s in strokes) {
            if (s.size < 2) {
                // 单点(点按):画一个圆点
                val p = s[0]
                canvas.drawCircle((p.x - minX) * scale, (p.y - minY) * scale, paint.strokeWidth / 2f, paint)
                continue
            }
            path.reset()
            var first = true
            for (p in s) {
                val px = (p.x - minX) * scale
                val py = (p.y - minY) * scale
                if (first) {
                    path.moveTo(px, py)
                    first = false
                } else {
                    path.lineTo(px, py) // 直线段模拟数位板采样,与训练分布一致
                }
            }
            canvas.drawPath(path, paint)
        }

        // 转灰度(白底=255,笔画=0),交给 C 端 preprocess 做 bbox+缩放+归一化
        val pixels = IntArray(dstW * dstH)
        bmp.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        val gray = ByteArray(dstW * dstH)
        for (idx in pixels.indices) {
            val c = pixels[idx]
            val lum = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
            gray[idx] = lum.roundToInt().toByte()
        }
        return GrayImage(gray, dstW, dstH)
    }
}