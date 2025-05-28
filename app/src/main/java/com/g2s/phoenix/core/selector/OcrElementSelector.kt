package com.g2s.phoenix.core.selector

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * OCR控件选择器
 * 实现旧架构的OCR为主的控件选择方式，支持快速开发而不需要手动查找控件标识
 */
class OcrElementSelector {
    
    private val ocrCache = ConcurrentHashMap<String, OcrResult>()
    private val elementCache = ConcurrentHashMap<String, List<ElementInfo>>()
    
    /**
     * 通过OCR文本查找控件
     */
    suspend fun findElementByText(
        text: String,
        rootNode: AccessibilityNodeInfo? = null,
        screenshot: Bitmap? = null,
        options: OcrOptions = OcrOptions()
    ): ElementInfo? = withContext(Dispatchers.Default) {
        
        // 1. 首先尝试无障碍服务查找
        val accessibilityResult = findByAccessibility(text, rootNode, options)
        if (accessibilityResult != null && options.preferAccessibility) {
            return@withContext accessibilityResult
        }
        
        // 2. 使用OCR查找
        val ocrResult = findByOcr(text, screenshot, options)
        if (ocrResult != null) {
            return@withContext ocrResult
        }
        
        // 3. 如果OCR失败，返回无障碍服务结果
        return@withContext accessibilityResult
    }
    
    /**
     * 通过OCR查找多个匹配的控件
     */
    suspend fun findElementsByText(
        text: String,
        rootNode: AccessibilityNodeInfo? = null,
        screenshot: Bitmap? = null,
        options: OcrOptions = OcrOptions()
    ): List<ElementInfo> = withContext(Dispatchers.Default) {
        
        val results = mutableListOf<ElementInfo>()
        
        // 1. 无障碍服务查找
        val accessibilityResults = findAllByAccessibility(text, rootNode, options)
        if (options.preferAccessibility) {
            results.addAll(accessibilityResults)
        }
        
        // 2. OCR查找
        val ocrResults = findAllByOcr(text, screenshot, options)
        results.addAll(ocrResults)
        
        // 3. 如果没有优先使用无障碍服务，现在添加无障碍服务结果
        if (!options.preferAccessibility) {
            results.addAll(accessibilityResults)
        }
        
        // 4. 去重和排序
        return@withContext deduplicateAndSort(results, options)
    }
    
    /**
     * 通过模糊匹配查找控件
     */
    suspend fun findElementByFuzzyText(
        text: String,
        threshold: Float = 0.8f,
        rootNode: AccessibilityNodeInfo? = null,
        screenshot: Bitmap? = null,
        options: OcrOptions = OcrOptions()
    ): ElementInfo? = withContext(Dispatchers.Default) {
        
        val fuzzyOptions = options.copy(fuzzyMatch = true, fuzzyThreshold = threshold)
        return@withContext findElementByText(text, rootNode, screenshot, fuzzyOptions)
    }
    
    /**
     * 通过正则表达式查找控件
     */
    suspend fun findElementByRegex(
        pattern: String,
        rootNode: AccessibilityNodeInfo? = null,
        screenshot: Bitmap? = null,
        options: OcrOptions = OcrOptions()
    ): ElementInfo? = withContext(Dispatchers.Default) {
        
        val regexOptions = options.copy(useRegex = true, regexPattern = pattern)
        return@withContext findElementByText("", rootNode, screenshot, regexOptions)
    }
    
    /**
     * 通过无障碍服务查找控件
     */
    private fun findByAccessibility(
        text: String,
        rootNode: AccessibilityNodeInfo?,
        options: OcrOptions
    ): ElementInfo? {
        if (rootNode == null) return null
        
        return findAccessibilityNode(text, rootNode, options)?.let { node ->
            createElementInfo(node, "accessibility")
        }
    }
    
    /**
     * 通过无障碍服务查找所有匹配的控件
     */
    private fun findAllByAccessibility(
        text: String,
        rootNode: AccessibilityNodeInfo?,
        options: OcrOptions
    ): List<ElementInfo> {
        if (rootNode == null) return emptyList()
        
        val results = mutableListOf<ElementInfo>()
        findAllAccessibilityNodes(text, rootNode, options, results)
        return results
    }
    
    /**
     * 递归查找无障碍节点
     */
    private fun findAccessibilityNode(
        text: String,
        node: AccessibilityNodeInfo,
        options: OcrOptions
    ): AccessibilityNodeInfo? {
        
        // 检查当前节点
        if (matchesText(node, text, options)) {
            return node
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findAccessibilityNode(text, child, options)
            if (result != null) {
                return result
            }
        }
        
        return null
    }
    
    /**
     * 递归查找所有匹配的无障碍节点
     */
    private fun findAllAccessibilityNodes(
        text: String,
        node: AccessibilityNodeInfo,
        options: OcrOptions,
        results: MutableList<ElementInfo>
    ) {
        
        // 检查当前节点
        if (matchesText(node, text, options)) {
            results.add(createElementInfo(node, "accessibility"))
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllAccessibilityNodes(text, child, options, results)
        }
    }
    
    /**
     * 检查节点文本是否匹配
     */
    private fun matchesText(
        node: AccessibilityNodeInfo,
        text: String,
        options: OcrOptions
    ): Boolean {
        val nodeTexts = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.viewIdResourceName
        )
        
        return nodeTexts.any { nodeText ->
            when {
                options.useRegex -> {
                    val pattern = options.regexPattern ?: text
                    nodeText.matches(Regex(pattern, RegexOption.IGNORE_CASE))
                }
                options.fuzzyMatch -> {
                    calculateSimilarity(nodeText, text) >= options.fuzzyThreshold
                }
                options.exactMatch -> {
                    nodeText.equals(text, ignoreCase = !options.caseSensitive)
                }
                else -> {
                    nodeText.contains(text, ignoreCase = !options.caseSensitive)
                }
            }
        }
    }
    
    /**
     * 通过OCR查找控件
     */
    private suspend fun findByOcr(
        text: String,
        screenshot: Bitmap?,
        options: OcrOptions
    ): ElementInfo? {
        if (screenshot == null) return null
        
        val cacheKey = generateCacheKey(screenshot, text, options)
        
        // 检查缓存
        ocrCache[cacheKey]?.let { cachedResult ->
            if (System.currentTimeMillis() - cachedResult.timestamp < options.cacheTimeout) {
                return cachedResult.elements.firstOrNull()
            }
        }
        
        // 执行OCR
        val ocrResults = performOcr(screenshot, options)
        val matchingElements = filterOcrResults(ocrResults, text, options)
        
        // 缓存结果
        ocrCache[cacheKey] = OcrResult(
            elements = matchingElements,
            timestamp = System.currentTimeMillis()
        )
        
        return matchingElements.firstOrNull()
    }
    
    /**
     * 通过OCR查找所有匹配的控件
     */
    private suspend fun findAllByOcr(
        text: String,
        screenshot: Bitmap?,
        options: OcrOptions
    ): List<ElementInfo> {
        if (screenshot == null) return emptyList()
        
        val cacheKey = generateCacheKey(screenshot, text, options)
        
        // 检查缓存
        ocrCache[cacheKey]?.let { cachedResult ->
            if (System.currentTimeMillis() - cachedResult.timestamp < options.cacheTimeout) {
                return cachedResult.elements
            }
        }
        
        // 执行OCR
        val ocrResults = performOcr(screenshot, options)
        val matchingElements = filterOcrResults(ocrResults, text, options)
        
        // 缓存结果
        ocrCache[cacheKey] = OcrResult(
            elements = matchingElements,
            timestamp = System.currentTimeMillis()
        )
        
        return matchingElements
    }
    
    /**
     * 执行OCR识别
     */
    private suspend fun performOcr(
        screenshot: Bitmap,
        options: OcrOptions
    ): List<OcrTextBlock> = withContext(Dispatchers.IO) {
        
        // 这里应该集成实际的OCR引擎，如Google ML Kit、Tesseract等
        // 目前返回模拟数据
        return@withContext mockOcrResults(screenshot, options)
    }
    
    /**
     * 模拟OCR结果（实际实现中应该替换为真实的OCR引擎）
     */
    private fun mockOcrResults(
        screenshot: Bitmap,
        options: OcrOptions
    ): List<OcrTextBlock> {
        // 这是一个模拟实现，实际应该使用真实的OCR引擎
        return listOf(
            OcrTextBlock(
                text = "示例文本",
                bounds = Rect(100, 100, 200, 150),
                confidence = 0.95f
            )
        )
    }
    
    /**
     * 过滤OCR结果
     */
    private fun filterOcrResults(
        ocrResults: List<OcrTextBlock>,
        targetText: String,
        options: OcrOptions
    ): List<ElementInfo> {
        return ocrResults.filter { block ->
            block.confidence >= options.minConfidence && matchesOcrText(block.text, targetText, options)
        }.map { block ->
            ElementInfo(
                text = block.text,
                bounds = block.bounds,
                confidence = block.confidence,
                source = "ocr",
                clickable = true,
                enabled = true
            )
        }
    }
    
    /**
     * 检查OCR文本是否匹配
     */
    private fun matchesOcrText(
        ocrText: String,
        targetText: String,
        options: OcrOptions
    ): Boolean {
        return when {
            options.useRegex -> {
                val pattern = options.regexPattern ?: targetText
                ocrText.matches(Regex(pattern, RegexOption.IGNORE_CASE))
            }
            options.fuzzyMatch -> {
                calculateSimilarity(ocrText, targetText) >= options.fuzzyThreshold
            }
            options.exactMatch -> {
                ocrText.equals(targetText, ignoreCase = !options.caseSensitive)
            }
            else -> {
                ocrText.contains(targetText, ignoreCase = !options.caseSensitive)
            }
        }
    }
    
    /**
     * 创建元素信息
     */
    private fun createElementInfo(
        node: AccessibilityNodeInfo,
        source: String
    ): ElementInfo {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        return ElementInfo(
            text = node.text?.toString() ?: node.contentDescription?.toString() ?: "",
            bounds = bounds,
            confidence = 1.0f,
            source = source,
            clickable = node.isClickable,
            enabled = node.isEnabled,
            resourceId = node.viewIdResourceName,
            className = node.className?.toString()
        )
    }
    
    /**
     * 去重和排序
     */
    private fun deduplicateAndSort(
        elements: List<ElementInfo>,
        options: OcrOptions
    ): List<ElementInfo> {
        // 按位置去重
        val deduplicated = elements.distinctBy { "${it.bounds.left},${it.bounds.top}" }
        
        // 排序：优先级 > 置信度 > 位置
        return deduplicated.sortedWith(compareBy<ElementInfo> {
            when (it.source) {
                "accessibility" -> if (options.preferAccessibility) 0 else 1
                "ocr" -> if (options.preferAccessibility) 1 else 0
                else -> 2
            }
        }.thenByDescending { it.confidence }
         .thenBy { it.bounds.top }
         .thenBy { it.bounds.left })
    }
    
    /**
     * 计算文本相似度
     */
    private fun calculateSimilarity(text1: String, text2: String): Float {
        if (text1 == text2) return 1.0f
        if (text1.isEmpty() || text2.isEmpty()) return 0.0f
        
        val longer = if (text1.length > text2.length) text1 else text2
        val shorter = if (text1.length > text2.length) text2 else text1
        
        val editDistance = levenshteinDistance(longer, shorter)
        return (longer.length - editDistance).toFloat() / longer.length
    }
    
    /**
     * 计算编辑距离
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        
        return dp[s1.length][s2.length]
    }
    
    /**
     * 生成缓存键
     */
    private fun generateCacheKey(
        screenshot: Bitmap,
        text: String,
        options: OcrOptions
    ): String {
        return "${screenshot.hashCode()}_${text}_${options.hashCode()}"
    }
    
    /**
     * 清理缓存
     */
    fun clearCache() {
        ocrCache.clear()
        elementCache.clear()
    }
    
    /**
     * 清理过期缓存
     */
    fun clearExpiredCache(maxAge: Long = 300000L) { // 5分钟
        val currentTime = System.currentTimeMillis()
        ocrCache.entries.removeIf { (_, result) ->
            currentTime - result.timestamp > maxAge
        }
    }
}

/**
 * OCR选项配置
 */
data class OcrOptions(
    val preferAccessibility: Boolean = true,
    val exactMatch: Boolean = false,
    val fuzzyMatch: Boolean = false,
    val fuzzyThreshold: Float = 0.8f,
    val useRegex: Boolean = false,
    val regexPattern: String? = null,
    val caseSensitive: Boolean = false,
    val minConfidence: Float = 0.7f,
    val cacheTimeout: Long = 60000L // 1分钟
)

/**
 * 元素信息
 */
data class ElementInfo(
    val text: String,
    val bounds: Rect,
    val confidence: Float,
    val source: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val resourceId: String? = null,
    val className: String? = null
)

/**
 * OCR文本块
 */
data class OcrTextBlock(
    val text: String,
    val bounds: Rect,
    val confidence: Float
)

/**
 * OCR结果
 */
data class OcrResult(
    val elements: List<ElementInfo>,
    val timestamp: Long
)