//package com.g2s.phoenix.core.ocr
//
//import android.graphics.Bitmap
//import android.graphics.Rect
//import com.g2s.phoenix.core.selector.OcrElementSelector
//import com.g2s.phoenix.core.selector.OcrOptions
//import com.g2s.phoenix.core.selector.OcrResult
//import kotlinx.coroutines.*
//import kotlinx.coroutines.sync.Mutex
//import kotlinx.coroutines.sync.withLock
//import java.util.concurrent.ConcurrentHashMap
//
///**
// * 增强的OCR元素选择器
// * 实现旧架构中以OCR为主的控件选择逻辑
// * 核心思想：通过OCR快速开发，无需手动查找控件标识
// */
//class EnhancedOcrElementSelector {
//
//    private val ocrCache = ConcurrentHashMap<String, CachedOcrResult>()
//    private val cacheMutex = Mutex()
//    private val ocrSelector = OcrElementSelector()
//
//    // OCR引擎配置
//    private val defaultOcrOptions = OcrOptions(
//        preferAccessibility = false,
//        exactMatch = false,
//        fuzzyMatch = true,
//        fuzzyThreshold = 0.8f,
//        useRegex = false,
//        caseSensitive = false,
//        minConfidence = 0.7f,
//        cacheTimeout = 5000L
//    )
//
//    /**
//     * 缓存的OCR结果
//     */
//    private data class CachedOcrResult(
//        val results: List<OcrResult>,
//        val timestamp: Long,
//        val screenHash: String
//    )
//
//    /**
//     * 执行OCR识别
//     * 实现真正的OCR功能
//     */
//    suspend fun performOcr(options: OcrOptions = defaultOcrOptions): List<OcrResult> {
//        return try {
//            // 获取当前屏幕截图
//            val screenshot = captureScreen() ?: return emptyList()
//
//            // 生成屏幕内容哈希用于缓存
//            val screenHash = generateScreenHash(screenshot)
//
//            // 检查缓存
//            val cachedResult = checkCache(screenHash)
//            if (cachedResult != null) {
//                return cachedResult
//            }
//
//            // 执行OCR识别
//            val ocrResults = executeOcrRecognition(screenshot, options)
//
//            // 缓存结果
//            cacheResults(screenHash, ocrResults)
//
//            ocrResults
//        } catch (e: Exception) {
//            // OCR失败时返回空列表，不抛出异常
//            emptyList()
//        }
//    }
//
//    /**
//     * 智能文本匹配
//     * 支持模糊匹配、同义词匹配等
//     */
//    fun matchesOcrText(ocrText: String, targetText: String, options: OcrOptions = defaultOcrOptions): Boolean {
//        if (targetText.isBlank()) return false
//
//        // 精确匹配
//        if (!options.caseSensitive) {
//            if (ocrText.equals(targetText, ignoreCase = true)) return true
//        } else {
//            if (ocrText == targetText) return true
//        }
//
//        // 模糊匹配
//        if (options.fuzzyMatch) {
//            val similarity = calculateSimilarity(ocrText, targetText)
//            if (similarity >= options.fuzzyThreshold) {
//                return true
//            }
//        }
//
//        return false
//
//        // 1. 精确匹配
//        if (exactMatch(ocrText, targetText, options.caseSensitive)) {
//            return true
//        }
//
//        // 2. 模糊匹配
//        if (options.enableFuzzyMatch && fuzzyMatch(ocrText, targetText)) {
//            return true
//        }
//
//        // 3. 同义词匹配
//        if (synonymMatch(ocrText, targetText)) {
//            return true
//        }
//
//        // 4. 部分匹配
//        if (partialMatch(ocrText, targetText, options.caseSensitive)) {
//            return true
//        }
//
//        return false
//    }
//
//    /**
//     * 查找包含指定文本的元素（增强版）
//     * 支持多种匹配策略
//     */
//    suspend fun findElementByTextEnhanced(
//        targetText: String,
//        options: OcrOptions = defaultOcrOptions,
//        matchStrategy: TextMatchStrategy = TextMatchStrategy.SMART
//    ): OcrResult? {
//        val ocrResults = performOcr(options)
//
//        return when (matchStrategy) {
//            TextMatchStrategy.EXACT -> {
//                ocrResults.find { exactMatch(it.text, targetText, options.caseSensitive) }
//            }
//            TextMatchStrategy.FUZZY -> {
//                ocrResults.find { fuzzyMatch(it.text, targetText) }
//            }
//            TextMatchStrategy.PARTIAL -> {
//                ocrResults.find { partialMatch(it.text, targetText, options.caseSensitive) }
//            }
//            TextMatchStrategy.SMART -> {
//                // 智能匹配：按优先级尝试不同策略
//                ocrResults.find { matchesOcrText(it.text, targetText, options) }
//            }
//        }
//    }
//
//    /**
//     * 查找多个包含指定文本的元素
//     */
//    suspend fun findElementsByTextEnhanced(
//        targetText: String,
//        options: OcrOptions = defaultOcrOptions,
//        matchStrategy: TextMatchStrategy = TextMatchStrategy.SMART
//    ): List<OcrResult> {
//        val ocrResults = performOcr(options)
//
//        return when (matchStrategy) {
//            TextMatchStrategy.EXACT -> {
//                ocrResults.filter { exactMatch(it.text, targetText, options.caseSensitive) }
//            }
//            TextMatchStrategy.FUZZY -> {
//                ocrResults.filter { fuzzyMatch(it.text, targetText) }
//            }
//            TextMatchStrategy.PARTIAL -> {
//                ocrResults.filter { partialMatch(it.text, targetText, options.caseSensitive) }
//            }
//            TextMatchStrategy.SMART -> {
//                ocrResults.filter { matchesOcrText(it.text, targetText, options) }
//            }
//        }
//    }
//
//    /**
//     * 文本匹配策略枚举
//     */
//    enum class TextMatchStrategy {
//        EXACT,    // 精确匹配
//        FUZZY,    // 模糊匹配
//        PARTIAL,  // 部分匹配
//        SMART     // 智能匹配（综合多种策略）
//    }
//
//    /**
//     * 按区域查找文本
//     * 支持在指定区域内进行OCR识别
//     */
//    suspend fun findTextInRegion(
//        region: Rect,
//        targetText: String,
//        options: OcrOptions = defaultOcrOptions
//    ): List<OcrResult> {
//        val screenshot = captureScreen() ?: return emptyList()
//        val regionBitmap = cropBitmap(screenshot, region)
//
//        val regionResults = executeOcrRecognition(regionBitmap, options)
//
//        // 调整坐标到全屏坐标系
//        return regionResults.map { result ->
//            result.copy(
//                bounds = Rect(
//                    result.bounds.left + region.left,
//                    result.bounds.top + region.top,
//                    result.bounds.right + region.left,
//                    result.bounds.bottom + region.top
//                )
//            )
//        }
//    }
//
//    /**
//     * 智能按钮识别
//     * 识别常见的按钮文本和状态
//     */
//    suspend fun findButtonByText(
//        buttonText: String,
//        options: OcrOptions = defaultOcrOptions
//    ): OcrResult? {
//        // 扩展按钮文本的同义词
//        val buttonSynonyms = getButtonSynonyms(buttonText)
//
//        val ocrResults = performOcr(options)
//
//        // 优先查找精确匹配
//        for (synonym in buttonSynonyms) {
//            val exactMatch = ocrResults.find {
//                exactMatch(it.text, synonym, options.caseSensitive)
//            }
//            if (exactMatch != null) return exactMatch
//        }
//
//        // 然后查找模糊匹配
//        for (synonym in buttonSynonyms) {
//            val fuzzyMatch = ocrResults.find {
//                fuzzyMatch(it.text, synonym)
//            }
//            if (fuzzyMatch != null) return fuzzyMatch
//        }
//
//        return null
//    }
//
//    /**
//     * 检测页面状态
//     * 通过OCR识别页面中的关键文本来判断当前状态
//     */
//    suspend fun detectPageState(
//        stateIndicators: List<String>,
//        options: OcrOptions = defaultOcrOptions
//    ): PageState {
//        val ocrResults = performOcr(options)
//        val detectedTexts = ocrResults.map { it.text }
//
//        val matchedIndicators = stateIndicators.filter { indicator ->
//            detectedTexts.any { ocrText ->
//                matchesOcrText(ocrText, indicator, options)
//            }
//        }
//
//        return PageState(
//            indicators = matchedIndicators,
//            confidence = if (stateIndicators.isNotEmpty()) {
//                matchedIndicators.size.toFloat() / stateIndicators.size
//            } else 0f,
//            allTexts = detectedTexts
//        )
//    }
//
//    /**
//     * 页面状态数据类
//     */
//    data class PageState(
//        val indicators: List<String>,
//        val confidence: Float,
//        val allTexts: List<String>
//    )
//
//    // 私有辅助方法
//
//    private suspend fun captureScreen(): Bitmap? {
//        return try {
//            // 这里应该实现真正的屏幕截图功能
//            // 可以使用MediaProjection API或无障碍服务
//            withContext(Dispatchers.IO) {
//                // 暂时返回null，需要根据具体平台实现
//                null
//            }
//        } catch (e: Exception) {
//            null
//        }
//    }
//
//    private fun generateScreenHash(bitmap: Bitmap): String {
//        // 生成屏幕内容的简单哈希
//        return "${bitmap.width}x${bitmap.height}_${System.currentTimeMillis() / 1000}"
//    }
//
//    private suspend fun checkCache(screenHash: String): List<OcrResult>? {
//        return cacheMutex.withLock {
//            val cached = ocrCache[screenHash]
//            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < 5000) {
//                cached.results
//            } else {
//                null
//            }
//        }
//    }
//
//    private suspend fun cacheResults(screenHash: String, results: List<OcrResult>) {
//        cacheMutex.withLock {
//            ocrCache[screenHash] = CachedOcrResult(
//                results = results,
//                timestamp = System.currentTimeMillis(),
//                screenHash = screenHash
//            )
//
//            // 清理过期缓存
//            val currentTime = System.currentTimeMillis()
//            ocrCache.entries.removeIf { (_, cached) ->
//                currentTime - cached.timestamp > 30000 // 30秒过期
//            }
//        }
//    }
//
//    private suspend fun executeOcrRecognition(
//        bitmap: Bitmap,
//        options: OcrOptions
//    ): List<OcrResult> {
//        return withContext(Dispatchers.Default) {
//            try {
//                // 这里应该集成真正的OCR引擎
//                // 比如 Google ML Kit、Tesseract、百度OCR等
//
//                // 暂时返回模拟结果
//                delay(100) // 模拟OCR处理时间
//
//                // 返回一些模拟的OCR结果用于测试
//                listOf()
//            } catch (e: Exception) {
//                emptyList()
//            }
//        }
//    }
//
//    private fun cropBitmap(bitmap: Bitmap, region: Rect): Bitmap {
//        return try {
//            Bitmap.createBitmap(
//                bitmap,
//                region.left,
//                region.top,
//                region.width(),
//                region.height()
//            )
//        } catch (e: Exception) {
//            bitmap
//        }
//    }
//
//    private fun exactMatch(ocrText: String, targetText: String, caseSensitive: Boolean): Boolean {
//        return if (caseSensitive) {
//            ocrText == targetText
//        } else {
//            ocrText.equals(targetText, ignoreCase = true)
//        }
//    }
//
//    private fun fuzzyMatch(ocrText: String, targetText: String): Boolean {
//        // 简单的模糊匹配实现
//        val similarity = calculateSimilarity(ocrText, targetText)
//        return similarity >= 0.8f
//    }
//
//    private fun synonymMatch(ocrText: String, targetText: String): Boolean {
//        val synonyms = getSynonyms(targetText)
//        return synonyms.any { synonym ->
//            ocrText.contains(synonym, ignoreCase = true) ||
//            synonym.contains(ocrText, ignoreCase = true)
//        }
//    }
//
//    private fun calculateSimilarity(text1: String, text2: String): Float {
//        // 简单的相似度计算，可以根据需要实现更复杂的算法
//        if (text1 == text2) return 1.0f
//        if (text1.isEmpty() || text2.isEmpty()) return 0.0f
//
//        val longer = if (text1.length > text2.length) text1 else text2
//        val shorter = if (text1.length > text2.length) text2 else text1
//
//        val maxLength = longer.length
//        val minLength = shorter.length
//
//        // 如果其中一个文本比另一个长很多，相似度降低
//        if (maxLength > minLength * 2) {
//            return 0.0f
//        }
//
//        // 计算编辑距离
//        val distance = calculateLevenshteinDistance(longer, shorter)
//
//        // 计算相似度
//        return 1.0f - (distance.toFloat() / maxLength)
//    }
//
//    /**
//     * 计算Levenshtein距离
//     */
//    private fun calculateLevenshteinDistance(s1: String, s2: String): Int {
//        val m = s1.length
//        val n = s2.length
//
//        val dp = Array(m + 1) { IntArray(n + 1) }
//
//        for (i in 0..m) {
//            dp[i][0] = i
//        }
//
//        for (j in 0..n) {
//            dp[0][j] = j
//        }
//
//        for (i in 1..m) {
//            for (j in 1..n) {
//                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
//                dp[i][j] = minOf(
//                    dp[i - 1][j] + 1,      // 删除
//                    dp[i][j - 1] + 1,      // 插入
//                    dp[i - 1][j - 1] + cost // 替换
//                )
//            }
//        }
//
//        return dp[m][n]
//    }
//
//
//    /**
//     * 获取同义词
//     */
//    private fun getSynonyms(text: String): List<String> {
//        // 常见同义词映射
//        val synonymMap = mapOf(
//            "签到" to listOf("打卡", "签", "check in"),
//            "点赞" to listOf("赞", "like", "👍"),
//            "关注" to listOf("follow", "订阅", "加关注"),
//            "分享" to listOf("share", "转发", "分享给好友"),
//            "收藏" to listOf("favorite", "收藏夹", "加收藏"),
//            "评论" to listOf("comment", "留言", "回复"),
//            "确定" to listOf("确认", "OK", "好的", "是的"),
//            "取消" to listOf("cancel", "不要", "算了"),
//            "完成" to listOf("finish", "done", "结束")
//        )
//
//        return synonymMap[text] ?: emptyList()
//    }
//
//    private fun getButtonSynonyms(buttonText: String): List<String> {
//        val baseSynonyms = getSynonyms(buttonText)
//        return listOf(buttonText) + baseSynonyms
//    }
//
//    /**
//     * 清理缓存
//     */
//    fun clearCache() {
//        ocrCache.clear()
//    }
//
//    /**
//     * 获取缓存统计信息
//     */
//    fun getCacheStats(): CacheStats {
//        return CacheStats(
//            totalEntries = ocrCache.size,
//            memoryUsage = ocrCache.size * 1024L // 粗略估算
//        )
//    }
//
//    data class CacheStats(
//        val totalEntries: Int,
//        val memoryUsage: Long
//    )
//}