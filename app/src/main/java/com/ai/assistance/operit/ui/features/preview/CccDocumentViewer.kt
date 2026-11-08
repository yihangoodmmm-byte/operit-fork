package com.ai.assistance.operit.ui.features.preview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.ui.common.markdown.StreamMarkdownRenderer
import java.io.File
import java.util.zip.ZipFile

/** 预览状态：一条路径对应一次加载结果。 */
private sealed interface PreviewState {
    data object Idle : PreviewState
    data object Loading : PreviewState
    data class Markdown(val text: String) : PreviewState
    data class PlainText(val text: String) : PreviewState
    data class Pdf(val pages: List<Bitmap>) : PreviewState
    data class Failed(val message: String) : PreviewState
}

/**
 * 文档预览：Markdown / 纯文本 / PDF / Office（docx、xlsx、pptx）。
 *
 * 为什么这么做而不是引第三方库：
 * - Markdown 复用工程里已经有的 StreamMarkdownRenderer，视觉和聊天里的渲染完全一致；
 * - PDF 用系统自带的 PdfRenderer，把每页渲染成位图铺进列表，不引 pdfium；
 * - Office 是 OOXML，本质是 zip + XML。这里直接解包按段落抽正文，
 *   不做排版还原——目的是能看到内容，不是还原 Word 版式，界面上会写明这一点。
 */
@Composable
fun CccDocumentViewer(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var pathInput by remember { mutableStateOf("/sdcard/Download/") }
    var openedPath by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<PreviewState>(PreviewState.Idle) }

    LaunchedEffect(openedPath) {
        if (openedPath.isBlank()) {
            state = PreviewState.Idle
            return@LaunchedEffect
        }
        state = PreviewState.Loading
        state = loadDocument(openedPath)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .background(cs.onSurface.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (pathInput.isEmpty()) {
                    Text(
                        text = "文件路径，例如 /sdcard/Download/a.pdf",
                        fontSize = 13.sp,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    singleLine = true,
                    textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier
                        .background(cs.primary.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .clickable { openedPath = pathInput.trim() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "打开", fontSize = 13.sp, color = cs.primary, fontWeight = FontWeight.Medium)
            }
        }

        when (val current = state) {
            PreviewState.Idle ->
                PreviewHint("输入一个文件路径，支持 .md / .txt / .pdf / .docx / .xlsx / .pptx")
            PreviewState.Loading -> PreviewHint("正在加载…")
            is PreviewState.Failed -> PreviewHint(current.message)
            is PreviewState.Markdown ->
                StreamMarkdownRenderer(
                    content = current.text,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                )
            is PreviewState.PlainText ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
                ) {
                    item {
                        Text(
                            text = current.text,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            is PreviewState.Pdf ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(count = current.pages.size) { index ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "第 " + (index + 1) + " 页 / 共 " + current.pages.size + " 页",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Image(
                                bitmap = current.pages[index].asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun PreviewHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

private fun loadDocument(path: String): PreviewState {
    val file = File(path)
    if (!file.exists()) {
        return PreviewState.Failed("文件不存在：" + path)
    }
    if (file.isDirectory) {
        return PreviewState.Failed("这是一个目录，请输入文件路径")
    }
    val name = file.name.lowercase()
    return when {
        name.endsWith(".md") || name.endsWith(".markdown") -> PreviewState.Markdown(file.readText())
        name.endsWith(".txt") ||
            name.endsWith(".json") ||
            name.endsWith(".kt") ||
            name.endsWith(".java") ||
            name.endsWith(".ts") ||
            name.endsWith(".js") ||
            name.endsWith(".xml") ||
            name.endsWith(".yaml") ||
            name.endsWith(".yml") ||
            name.endsWith(".csv") ||
            name.endsWith(".log") -> PreviewState.PlainText(file.readText())
        name.endsWith(".pdf") -> renderPdf(file)
        name.endsWith(".docx") -> extractDocx(file)
        name.endsWith(".xlsx") -> extractXlsx(file)
        name.endsWith(".pptx") -> extractPptx(file)
        else -> PreviewState.Failed("暂不支持这个格式：" + name.substringAfterLast('.', "未知"))
    }
}

private fun renderPdf(file: File): PreviewState =
    try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val pages = ArrayList<Bitmap>(renderer.pageCount)
        for (index in 0 until renderer.pageCount) {
            renderer.openPage(index).use { page ->
                val width = 1240
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pages.add(bitmap)
            }
        }
        renderer.close()
        pfd.close()
        PreviewState.Pdf(pages)
    } catch (e: Exception) {
        PreviewState.Failed("PDF 打开失败：" + (e.message ?: e.javaClass.simpleName))
    }

private fun extractDocx(file: File): PreviewState {
    val xml = readZipEntryText(file, "word/document.xml")
    if (xml == null) {
        return PreviewState.Failed("docx 解析失败：找不到 word/document.xml")
    }
    val text =
        xml.replace("</w:p>", "\n")
            .replace("<w:br/>", "\n")
            .replace("<w:tab/>", "\t")
            .replace(Regex("<[^>]+>"), "")
            .let(::decodeXmlEntities)
            .trim()
    return if (text.isBlank()) {
        PreviewState.Failed("这个 docx 里没有可提取的文字")
    } else {
        PreviewState.PlainText(text)
    }
}

private fun extractXlsx(file: File): PreviewState {
    val xml = readZipEntryText(file, "xl/sharedStrings.xml")
    if (xml == null) {
        return PreviewState.Failed("xlsx 解析失败：找不到 xl/sharedStrings.xml")
    }
    val text =
        xml.split("</si>")
            .map { chunk -> chunk.replace(Regex("<[^>]+>"), "").let(::decodeXmlEntities).trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    return if (text.isBlank()) {
        PreviewState.Failed("这个 xlsx 里没有可提取的文字")
    } else {
        PreviewState.PlainText(text)
    }
}

private fun extractPptx(file: File): PreviewState =
    try {
        ZipFile(file).use { zip ->
            val slides =
                zip.entries()
                    .asSequence()
                    .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                    .sortedBy { it.name }
                    .toList()
            if (slides.isEmpty()) {
                PreviewState.Failed("这个 pptx 里没有幻灯片")
            } else {
                val builder = StringBuilder()
                slides.forEachIndexed { index, entry ->
                    val xml = zip.getInputStream(entry).bufferedReader().readText()
                    val text =
                        xml.replace("</a:p>", "\n")
                            .replace(Regex("<[^>]+>"), "")
                            .let(::decodeXmlEntities)
                            .trim()
                    builder.append("[第 ").append(index + 1).append(" 页]\n").append(text).append("\n\n")
                }
                PreviewState.PlainText(builder.toString().trim())
            }
        }
    } catch (e: Exception) {
        PreviewState.Failed("pptx 解析失败：" + (e.message ?: e.javaClass.simpleName))
    }

private fun readZipEntryText(file: File, entryName: String): String? =
    try {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(entryName)
            if (entry == null) {
                null
            } else {
                zip.getInputStream(entry).bufferedReader().readText()
            }
        }
    } catch (e: Exception) {
        null
    }

private fun decodeXmlEntities(text: String): String =
    text.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
