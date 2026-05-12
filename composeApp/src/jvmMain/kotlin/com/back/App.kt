package com.back

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import com.lowagie.text.Chunk as PdfChunk
import com.lowagie.text.Document as PdfDocument
import com.lowagie.text.Font as PdfFont
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph as PdfParagraph
import com.lowagie.text.Phrase as PdfPhrase
import com.lowagie.text.pdf.BaseFont
import com.lowagie.text.pdf.PdfWriter
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FileOutputStream
import java.io.FilenameFilter
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.delay

data class Memo(val id: Int, val title: String, val content: TextFieldValue)
data class ThemeColors(
    val background: Color,
    val sidebar: Color,
    val sidebarHover: Color,
    val text: Color,
    val subText: Color,
    val statusBar: Color,
    val accent: Color
)

enum class SaveFormat(val label: String, val ext: String) {
    TXT("Text (.txt)", ".txt"),
    MD("Markdown (.md)", ".md"),
    PDF("PDF (.pdf)", ".pdf")
}

// Fix: use FQN for org.commonmark.node.Text to avoid conflict with Compose Text composable.
// Fix: override visit(customNode: CustomNode) instead of visit(strikethrough: Strikethrough)
//      because Strikethrough is a CustomNode extension — AbstractVisitor has no visit(Strikethrough).
class MarkdownAnnotatedStringVisitor(private val builder: AnnotatedString.Builder) : AbstractVisitor() {

    override fun visit(text: org.commonmark.node.Text) {
        builder.append(text.literal)
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            visitChildren(strongEmphasis)
        }
    }

    override fun visit(emphasis: Emphasis) {
        builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            visitChildren(emphasis)
        }
    }

    override fun visit(customNode: CustomNode) {
        when (customNode) {
            is Strikethrough -> builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                visitChildren(customNode)
            }
            else -> super.visit(customNode)
        }
    }

    override fun visit(code: Code) {
        builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFE8E8E8))) {
            builder.append(code.literal)
        }
    }

    override fun visit(softLineBreak: SoftLineBreak) {
        builder.append(" ")
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        builder.append("\n")
    }

    // Must be public so parseNodeToAnnotatedString can call it from outside this class.
    public override fun visitChildren(node: Node) {
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            child.accept(this)
            child = next
        }
    }
}

fun parseNodeToAnnotatedString(node: Node): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val visitor = MarkdownAnnotatedStringVisitor(builder)
    // accept() dispatches to the right visit() override; AbstractVisitor then calls visitChildren.
    node.accept(visitor)
    return builder.toAnnotatedString()
}

// ── 마크다운 서식 삽입 유틸리티 ──────────────────────────────────────────

fun applyInlineFormat(
    value: TextFieldValue,
    prefix: String,
    suffix: String,
    placeholder: String
): TextFieldValue {
    val text = value.text
    val sel  = value.selection
    return if (sel.collapsed) {
        // 커서만 있을 때: 마커 사이에 placeholder 삽입 후 placeholder 선택
        val newText = text.substring(0, sel.start) + prefix + placeholder + suffix + text.substring(sel.start)
        TextFieldValue(
            text = newText,
            selection = TextRange(sel.start + prefix.length, sel.start + prefix.length + placeholder.length)
        )
    } else {
        // 텍스트 선택 중일 때: 선택 영역을 마커로 감싸고 내부 텍스트 재선택
        val selected = text.substring(sel.start, sel.end)
        val newText  = text.substring(0, sel.start) + prefix + selected + suffix + text.substring(sel.end)
        TextFieldValue(
            text = newText,
            selection = TextRange(sel.start + prefix.length, sel.start + prefix.length + selected.length)
        )
    }
}

fun applyCodeBlock(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val sel  = value.selection
    return if (sel.collapsed) {
        // 빈 코드 블록 삽입, 커서를 내부 빈 줄에 위치
        val inserted = "```\n\n```"
        val newText  = text.substring(0, sel.start) + inserted + text.substring(sel.start)
        TextFieldValue(text = newText, selection = TextRange(sel.start + 4))
    } else {
        // 선택 영역을 코드 블록으로 감싸기
        val selected = text.substring(sel.start, sel.end)
        val prefix   = "```\n"
        val suffix   = "\n```"
        val newText  = text.substring(0, sel.start) + prefix + selected + suffix + text.substring(sel.end)
        TextFieldValue(
            text = newText,
            selection = TextRange(sel.start + prefix.length, sel.start + prefix.length + selected.length)
        )
    }
}

fun applyLink(value: TextFieldValue): TextFieldValue {
    val text      = value.text
    val sel       = value.selection
    val linkText  = "링크 텍스트"
    return if (sel.collapsed) {
        // 삽입 후 링크 텍스트 부분 선택
        val inserted = "[$linkText](URL)"
        val newText  = text.substring(0, sel.start) + inserted + text.substring(sel.start)
        TextFieldValue(
            text = newText,
            selection = TextRange(sel.start + 1, sel.start + 1 + linkText.length)
        )
    } else {
        // 선택 텍스트를 링크 텍스트로, URL 부분 선택
        val selected = text.substring(sel.start, sel.end)
        val inserted = "[$selected](URL)"
        val newText  = text.substring(0, sel.start) + inserted + text.substring(sel.end)
        val urlStart = sel.start + 1 + selected.length + 2
        TextFieldValue(
            text = newText,
            selection = TextRange(urlStart, urlStart + 3)
        )
    }
}

fun buildPdf(memo: Memo, targetFile: File) {
    val fontPath = listOf(
        "C:/Windows/Fonts/malgun.ttf",
        "C:/Windows/Fonts/NanumGothic.ttf",
        "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
        "/Library/Fonts/AppleGothic.ttf"
    ).firstOrNull { File(it).exists() }

    val doc = PdfDocument(PageSize.A4, 60f, 60f, 72f, 60f)
    PdfWriter.getInstance(doc, FileOutputStream(targetFile))
    doc.open()

    val bf = if (fontPath != null)
        BaseFont.createFont(fontPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED)
    else
        BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)

    fun font(size: Float, bold: Boolean = false, italic: Boolean = false): PdfFont {
        val style = when {
            bold && italic -> PdfFont.BOLDITALIC
            bold           -> PdfFont.BOLD
            italic         -> PdfFont.ITALIC
            else           -> PdfFont.NORMAL
        }
        return PdfFont(bf, size, style)
    }

    fun inlinePhrase(node: Node, size: Float, bold: Boolean = false, italic: Boolean = false): PdfPhrase {
        val phrase = PdfPhrase()
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is org.commonmark.node.Text -> phrase.add(PdfChunk(child.literal, font(size, bold, italic)))
                is StrongEmphasis -> inlinePhrase(child, size, true, italic).forEach { phrase.add(it) }
                is Emphasis       -> inlinePhrase(child, size, bold, true).forEach { phrase.add(it) }
                is Code           -> phrase.add(PdfChunk(child.literal, font(size, bold, italic)))
                is SoftLineBreak  -> phrase.add(PdfChunk(" ", font(size)))
                is HardLineBreak  -> phrase.add(PdfChunk("\n", font(size)))
                else              -> inlinePhrase(child, size, bold, italic).forEach { phrase.add(it) }
            }
            child = child.next
        }
        return phrase
    }

    val titlePara = PdfParagraph(memo.title, font(20f, bold = true))
    titlePara.spacingAfter = 18f
    doc.add(titlePara)

    val mdParser = Parser.builder()
        .extensions(listOf(StrikethroughExtension.create()))
        .build()
    val mdDoc = mdParser.parse(memo.content.text)

    var node = mdDoc.firstChild
    while (node != null) {
        when (node) {
            is Heading -> {
                val sz = when (node.level) { 1 -> 18f; 2 -> 15f; else -> 13f }
                val para = PdfParagraph(inlinePhrase(node, sz, bold = true))
                para.spacingBefore = if (node.level == 1) 16f else 10f
                para.spacingAfter = 6f
                doc.add(para)
            }
            is Paragraph -> {
                val para = PdfParagraph(inlinePhrase(node, 11f))
                para.leading = 16f; para.spacingAfter = 8f; doc.add(para)
            }
            is BulletList -> {
                var item = node.firstChild
                while (item is ListItem) {
                    val ph = PdfPhrase(); ph.add(PdfChunk("• ", font(11f)))
                    var cn = item.firstChild
                    while (cn != null) { inlinePhrase(cn, 11f).forEach { ph.add(it) }; cn = cn.next }
                    val para = PdfParagraph(ph); para.indentationLeft = 12f; para.spacingAfter = 4f
                    doc.add(para); item = item.next
                }
            }
            is OrderedList -> {
                var item = node.firstChild; var idx = node.markerStartNumber
                while (item is ListItem) {
                    val ph = PdfPhrase(); ph.add(PdfChunk("${idx++}. ", font(11f)))
                    var cn = item.firstChild
                    while (cn != null) { inlinePhrase(cn, 11f).forEach { ph.add(it) }; cn = cn.next }
                    val para = PdfParagraph(ph); para.indentationLeft = 12f; para.spacingAfter = 4f
                    doc.add(para); item = item.next
                }
            }
            is FencedCodeBlock -> {
                val para = PdfParagraph(node.literal.trimEnd(), font(10f))
                para.spacingBefore = 6f; para.spacingAfter = 8f; doc.add(para)
            }
            is IndentedCodeBlock -> {
                val para = PdfParagraph(node.literal.trimEnd(), font(10f))
                para.spacingAfter = 8f; doc.add(para)
            }
            is BlockQuote -> {
                var cn = node.firstChild
                while (cn != null) {
                    val para = PdfParagraph(inlinePhrase(cn, 11f, italic = true))
                    para.indentationLeft = 20f; para.leading = 16f; para.spacingAfter = 8f
                    doc.add(para); cn = cn.next
                }
            }
            is ThematicBreak -> {
                val para = PdfParagraph("─".repeat(45), font(9f))
                para.spacingBefore = 8f; para.spacingAfter = 8f; doc.add(para)
            }
        }
        node = node.next
    }

    doc.close()
}

@Composable
fun MarkdownContent(markdown: String, colors: ThemeColors) {
    val parser = remember {
        Parser.builder()
            .extensions(listOf(StrikethroughExtension.create()))
            .build()
    }
    val document = parser.parse(markdown)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 28.dp)
    ) {
        renderNodes(document, colors)
    }
}

@Composable
fun renderNodes(node: Node, colors: ThemeColors, level: Int = 0) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is Heading -> {
                val (size, weight) = when (child.level) {
                    1 -> 32.sp to FontWeight.Bold
                    2 -> 26.sp to FontWeight.Bold
                    else -> 20.sp to FontWeight.SemiBold
                }
                Column(
                    modifier = Modifier.padding(
                        top = if (child.level == 1) 24.dp else 16.dp,
                        bottom = 8.dp
                    )
                ) {
                    Text(
                        text = parseNodeToAnnotatedString(child),
                        fontSize = size,
                        fontWeight = weight,
                        color = colors.text,
                        lineHeight = (size.value * 1.35f).sp
                    )
                    if (child.level <= 2) {
                        HorizontalDivider(
                            color = colors.subText.copy(alpha = if (child.level == 1) 0.4f else 0.2f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            is Paragraph -> {
                Text(
                    text = parseNodeToAnnotatedString(child),
                    color = colors.text,
                    lineHeight = 26.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            is BlockQuote -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(vertical = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(colors.accent, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                colors.accent.copy(alpha = 0.08f),
                                RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        renderNodes(child, colors, level + 1)
                    }
                }
            }
            is BulletList -> {
                Column(
                    modifier = Modifier.padding(
                        start = (level * 16).dp,
                        top = 4.dp,
                        bottom = 4.dp
                    )
                ) {
                    var item = child.firstChild
                    while (item is ListItem) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "• ",
                                color = colors.text,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                text = parseNodeToAnnotatedString(item),
                                color = colors.text,
                                lineHeight = 24.sp
                            )
                        }
                        item = item.next
                    }
                }
            }
            is OrderedList -> {
                Column(
                    modifier = Modifier.padding(
                        start = (level * 16).dp,
                        top = 4.dp,
                        bottom = 4.dp
                    )
                ) {
                    var item = child.firstChild
                    var index = child.markerStartNumber
                    while (item is ListItem) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "${index++}. ",
                                color = colors.text,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                text = parseNodeToAnnotatedString(item),
                                color = colors.text,
                                lineHeight = 24.sp
                            )
                        }
                        item = item.next
                    }
                }
            }
            is FencedCodeBlock -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                        .padding(20.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = child.literal.trimEnd(),
                        color = Color(0xFFD4D4D4),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
            }
            is IndentedCodeBlock -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = child.literal.trimEnd(),
                        color = Color(0xFFD4D4D4),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
            }
            is ThematicBreak -> {
                HorizontalDivider(
                    color = colors.subText.copy(alpha = 0.4f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        child = child.next
    }
}

// ── 편집 툴바 ─────────────────────────────────────────────────────────────

@Composable
private fun FmtButton(
    label: String,
    colors: ThemeColors,
    fontWeight: FontWeight = FontWeight.Normal,
    fontStyle: FontStyle = FontStyle.Normal,
    textDecoration: TextDecoration? = null,
    fontFamily: FontFamily = FontFamily.Default,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 30.dp, minHeight = 28.dp)
            .background(Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = colors.text,
            fontSize = 13.sp,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            textDecoration = textDecoration,
            fontFamily = fontFamily
        )
    }
}

@Composable
private fun FmtDivider(colors: ThemeColors) {
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(16.dp)
            .background(colors.subText.copy(alpha = 0.3f))
    )
}

@Composable
fun FormattingToolbar(
    contentValue: TextFieldValue,
    onContentChange: (TextFieldValue) -> Unit,
    colors: ThemeColors
) {
    val isDark    = colors.background.red < 0.5f
    val toolbarBg = if (isDark) Color(0xFF282828) else Color(0xFFFAFAFA)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(toolbarBg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── 그룹 1: 텍스트 서식 ────────────────────────────────────────
        FmtButton("B", colors, fontWeight = FontWeight.Bold) {
            onContentChange(applyInlineFormat(contentValue, "**", "**", "굵은 텍스트"))
        }
        FmtButton("I", colors, fontStyle = FontStyle.Italic) {
            onContentChange(applyInlineFormat(contentValue, "*", "*", "기울임 텍스트"))
        }
        FmtButton("S", colors, textDecoration = TextDecoration.LineThrough) {
            onContentChange(applyInlineFormat(contentValue, "~~", "~~", "취소선 텍스트"))
        }

        FmtDivider(colors)

        // ── 그룹 2: 코드 ────────────────────────────────────────────────
        FmtButton("</>", colors, fontFamily = FontFamily.Monospace) {
            onContentChange(applyInlineFormat(contentValue, "`", "`", "코드"))
        }
        FmtButton("```", colors, fontFamily = FontFamily.Monospace) {
            onContentChange(applyCodeBlock(contentValue))
        }

        FmtDivider(colors)

        // ── 그룹 3: 링크 ────────────────────────────────────────────────
        FmtButton("⊞ 링크", colors) {
            onContentChange(applyLink(contentValue))
        }
    }
}

@Composable
fun TopNavBar(
    title: String,
    onTitleChange: (String) -> Unit,
    onNewMemo: () -> Unit,
    onOpenFile: () -> Unit,
    onSaveFile: () -> Unit,
    isPreviewMode: Boolean,
    onTogglePreview: () -> Unit,
    selectedFormat: SaveFormat,
    onFormatChange: (SaveFormat) -> Unit,
    colors: ThemeColors
) {
    val isDark = colors.background.red < 0.5f
    val navBg = if (isDark) Color(0xFF252525) else Color(0xFFF5F5F5)
    val shortcutColor = colors.subText.copy(alpha = 0.7f)

    var fileExpanded by remember { mutableStateOf(false) }
    var editExpanded by remember { mutableStateOf(false) }
    var formatExpanded by remember { mutableStateOf(false) }

    val titleInteraction = remember { MutableInteractionSource() }
    val isTitleFocused by titleInteraction.collectIsFocusedAsState()

    val menuItemPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(navBg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── 좌측 1/3: 메뉴 버튼들 ──────────────────────────────────
        Row(
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 파일
            Box {
                TextButton(onClick = { fileExpanded = true }) {
                    Text("파일", color = colors.text, fontSize = 13.sp)
                }
                DropdownMenu(
                    expanded = fileExpanded,
                    onDismissRequest = { fileExpanded = false },
                    modifier = Modifier.widthIn(min = 240.dp)
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text("새 메모", fontSize = 13.sp)
                                Text("Ctrl+N", color = shortcutColor, fontSize = 11.sp)
                            }
                        },
                        onClick = { onNewMemo(); fileExpanded = false },
                        contentPadding = menuItemPadding
                    )
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text("열기", fontSize = 13.sp)
                                Text("Ctrl+O", color = shortcutColor, fontSize = 11.sp)
                            }
                        },
                        onClick = { onOpenFile(); fileExpanded = false },
                        contentPadding = menuItemPadding
                    )
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text("다른 이름으로 저장", fontSize = 13.sp)
                                Text("Ctrl+S", color = shortcutColor, fontSize = 11.sp)
                            }
                        },
                        onClick = { onSaveFile(); fileExpanded = false },
                        contentPadding = menuItemPadding
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text("닫기", fontSize = 13.sp) },
                        onClick = { fileExpanded = false },
                        contentPadding = menuItemPadding
                    )
                }
            }

            // 편집
            Box {
                TextButton(onClick = { editExpanded = true }) {
                    Text("편집", color = colors.text, fontSize = 13.sp)
                }
                DropdownMenu(
                    expanded = editExpanded,
                    onDismissRequest = { editExpanded = false },
                    modifier = Modifier.widthIn(min = 210.dp)
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text("실행 취소", fontSize = 13.sp)
                                Text("Ctrl+Z", color = shortcutColor, fontSize = 11.sp)
                            }
                        },
                        onClick = { editExpanded = false },
                        contentPadding = menuItemPadding
                    )
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text("다시 실행", fontSize = 13.sp)
                                Text("Ctrl+Y", color = shortcutColor, fontSize = 11.sp)
                            }
                        },
                        onClick = { editExpanded = false },
                        contentPadding = menuItemPadding
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text("복사", fontSize = 13.sp)
                                Text("Ctrl+C", color = shortcutColor, fontSize = 11.sp)
                            }
                        },
                        onClick = { editExpanded = false },
                        contentPadding = menuItemPadding
                    )
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text("붙여넣기", fontSize = 13.sp)
                                Text("Ctrl+V", color = shortcutColor, fontSize = 11.sp)
                            }
                        },
                        onClick = { editExpanded = false },
                        contentPadding = menuItemPadding
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text("모두 선택", fontSize = 13.sp)
                                Text("Ctrl+A", color = shortcutColor, fontSize = 11.sp)
                            }
                        },
                        onClick = { editExpanded = false },
                        contentPadding = menuItemPadding
                    )
                }
            }

            // 저장 형식
            Box {
                TextButton(onClick = { formatExpanded = true }) {
                    Text("저장 형식: ${selectedFormat.label}", color = colors.text, fontSize = 13.sp)
                }
                DropdownMenu(
                    expanded = formatExpanded,
                    onDismissRequest = { formatExpanded = false },
                    modifier = Modifier.widthIn(min = 200.dp)
                ) {
                    SaveFormat.entries.forEach { fmt ->
                        DropdownMenuItem(
                            text = {
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text(fmt.label, fontSize = 13.sp)
                                    if (fmt == selectedFormat) {
                                        Text("✓", color = colors.accent, fontSize = 13.sp)
                                    }
                                }
                            },
                            onClick = {
                                onFormatChange(fmt)
                                formatExpanded = false
                            },
                            contentPadding = menuItemPadding
                        )
                    }
                }
            }
        }

        // ── 중앙 1/3: 제목 편집 (BasicTextField) ──────────────────
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                interactionSource = titleInteraction,
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .widthIn(min = 80.dp, max = 280.dp)
                    .border(
                        width = 1.dp,
                        color = if (isTitleFocused) colors.subText.copy(alpha = 0.4f) else Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // ── 우측 1/3: 미리보기 토글 칩 (끝 정렬) ──────────────────
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .background(
                        color = if (isPreviewMode) colors.sidebarHover else Color.Transparent,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable(onClick = onTogglePreview)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPreviewMode) "◉  미리보기" else "○  미리보기",
                    color = if (isPreviewMode) colors.text else colors.subText,
                    fontSize = 13.sp,
                    fontWeight = if (isPreviewMode) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
@Preview
fun App() {
    val isDark = isSystemInDarkTheme()
    val colors = if (isDark)
        ThemeColors(Color(0xFF202020), Color(0xFF2D2D2D), Color(0xFF3D3D3D), Color(0xFFE0E0E0), Color(0xFFB0B0B0), Color(0xFF2D2D2D), Color(0xFF505050))
    else
        ThemeColors(Color(0xFFF9F9F9), Color(0xFFEBEBEB), Color(0xFFD1D1D1), Color(0xFF333333), Color(0xFF888888), Color(0xFFF0F0F0), Color(0xFFDCDCDC))

    val storageFile = File(System.getProperty("user.home"), ".memoapp_data.txt")
    fun saveMemos(memos: List<Memo>) {
        storageFile.writeText(memos.joinToString("|||") { "${it.id}:::${it.title}:::${it.content.text}" })
    }
    fun loadMemos(): List<Memo> {
        if (!storageFile.exists()) return listOf(Memo(1, "첫 번째 메모", TextFieldValue("내용입니다.")))
        return try {
            storageFile.readText().split("|||").map { val p = it.split(":::"); Memo(p[0].toInt(), p[1], TextFieldValue(p[2])) }
        } catch (_: Exception) {
            listOf(Memo(1, "첫 번째 메모", TextFieldValue("내용입니다.")))
        }
    }

    MaterialTheme {
        val memos = remember { mutableStateListOf<Memo>().apply { addAll(loadMemos()) } }
        LaunchedEffect(Unit) { snapshotFlow { memos.toList() }.collect { saveMemos(it) } }

        var selectedMemoId by remember { mutableStateOf<Int?>(memos.firstOrNull()?.id) }
        var nextId by remember { mutableStateOf((memos.maxOfOrNull { it.id } ?: 0) + 1) }
        var isPreviewMode by remember { mutableStateOf(false) }
        var selectedFormat by remember { mutableStateOf(SaveFormat.TXT) }
        var notifyMessage by remember { mutableStateOf("") }
        var notifyKey by remember { mutableStateOf(0) }
        var showNotify by remember { mutableStateOf(false) }
        LaunchedEffect(notifyKey) {
            if (notifyKey > 0) {
                showNotify = true
                delay(3000L)
                showNotify = false
            }
        }
        val selectedMemo = memos.find { it.id == selectedMemoId }

        fun saveFile() {
            val currentMemo = memos.find { it.id == selectedMemoId } ?: return
            if (selectedFormat == SaveFormat.PDF) {
                val dialog = FileDialog(null as Frame?, "PDF로 저장", FileDialog.SAVE)
                dialog.file = "${currentMemo.title}.pdf"
                dialog.filenameFilter = FilenameFilter { _, name -> name.endsWith(".pdf", ignoreCase = true) }
                dialog.isVisible = true
                val dir = dialog.directory ?: return
                val raw = dialog.file ?: return
                val filename = if (raw.endsWith(".pdf", ignoreCase = true)) raw else "$raw.pdf"
                try {
                    buildPdf(currentMemo, File(dir, filename))
                    notifyMessage = "파일이 성공적으로 저장되었습니다"
                    notifyKey++
                } catch (e: Exception) {
                    println("[memoApp] PDF 저장 오류 ($filename): ${e.message}")
                    e.printStackTrace()
                }
            } else {
                val dialog = FileDialog(null as Frame?, "다른 이름으로 저장", FileDialog.SAVE)
                dialog.file = currentMemo.title
                dialog.filenameFilter = FilenameFilter { _, name ->
                    name.endsWith(".txt", ignoreCase = true) || name.endsWith(".md", ignoreCase = true)
                }
                dialog.isVisible = true
                val dir = dialog.directory ?: return
                val raw = dialog.file ?: return
                val filename = when {
                    raw.endsWith(".md", ignoreCase = true)  -> raw
                    raw.endsWith(".txt", ignoreCase = true) -> raw
                    else -> "$raw${selectedFormat.ext}"
                }
                try {
                    Files.write(Paths.get(dir, filename), currentMemo.content.text.toByteArray(Charsets.UTF_8))
                    notifyMessage = "파일이 성공적으로 저장되었습니다"
                    notifyKey++
                } catch (e: Exception) {
                    println("[memoApp] 파일 저장 오류 ($filename): ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        fun openFile() {
            val dialog = FileDialog(null as Frame?, "파일 열기", FileDialog.LOAD)
            dialog.filenameFilter = FilenameFilter { _, name ->
                name.endsWith(".txt", ignoreCase = true) || name.endsWith(".md", ignoreCase = true)
            }
            dialog.isVisible = true
            val dir = dialog.directory ?: return
            val filename = dialog.file ?: return
            try {
                val content = File(dir, filename).readText(Charsets.UTF_8)
                val title = File(filename).nameWithoutExtension
                val currentId = selectedMemoId
                if (currentId != null) {
                    val i = memos.indexOfFirst { it.id == currentId }
                    if (i != -1) memos[i] = memos[i].copy(title = title, content = TextFieldValue(content))
                } else {
                    val newMemo = Memo(nextId++, title, TextFieldValue(content))
                    memos.add(newMemo); selectedMemoId = newMemo.id
                }
            } catch (e: Exception) {
                println("[memoApp] 파일 열기 오류 ($filename): ${e.message}")
                e.printStackTrace()
            }
        }

        Row(modifier = Modifier.fillMaxSize().background(colors.background).onPreviewKeyEvent {
            when {
                it.isCtrlPressed && it.type == KeyEventType.KeyDown && it.key == Key.N -> {
                    val n = Memo(nextId++, "새 메모 ${nextId - 1}", TextFieldValue(""))
                    memos.add(n); selectedMemoId = n.id; true
                }
                it.isCtrlPressed && it.type == KeyEventType.KeyDown && it.key == Key.O -> {
                    openFile(); true
                }
                it.isCtrlPressed && it.type == KeyEventType.KeyDown && it.key == Key.S -> {
                    saveFile(); true
                }
                else -> false
            }
        }) {
            Column(modifier = Modifier.width(260.dp).fillMaxHeight().background(colors.sidebar)) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(memos) { memo ->
                        MemoItem(
                            memo, selectedMemoId == memo.id, colors,
                            { selectedMemoId = memo.id },
                            { memos.remove(memo); if (selectedMemoId == memo.id) selectedMemoId = memos.firstOrNull()?.id }
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.accent)
                        .clickable {
                            val n = Memo(nextId++, "새 메모 ${nextId - 1}", TextFieldValue(""))
                            memos.add(n); selectedMemoId = n.id
                        }
                        .padding(16.dp)
                ) { Text("+ 새 메모 추가", color = colors.subText) }
            }

            // ── 우측 편집 영역 (패딩 없이 전체 차지) ──────────────────
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (selectedMemo != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 1. 상단 네비게이션 바
                        TopNavBar(
                            title = selectedMemo.title,
                            onTitleChange = { v ->
                                val i = memos.indexOfFirst { m -> m.id == selectedMemo.id }
                                if (i != -1) memos[i] = selectedMemo.copy(title = v)
                            },
                            onNewMemo = {
                                val n = Memo(nextId++, "새 메모 ${nextId - 1}", TextFieldValue(""))
                                memos.add(n); selectedMemoId = n.id
                            },
                            onOpenFile = ::openFile,
                            onSaveFile = ::saveFile,
                            isPreviewMode = isPreviewMode,
                            onTogglePreview = { isPreviewMode = !isPreviewMode },
                            selectedFormat = selectedFormat,
                            onFormatChange = { fmt ->
                                selectedFormat = fmt
                                notifyMessage = "저장 형식이 ${fmt.label}으로 변경되었습니다"
                                notifyKey++
                            },
                            colors = colors
                        )
                        // 2. 네비바 하단 구분선
                        HorizontalDivider(
                            color = colors.subText.copy(alpha = 0.15f),
                            thickness = 1.dp
                        )
                        // 3. 편집 툴바 (편집 모드일 때만 표시)
                        if (!isPreviewMode) {
                            FormattingToolbar(
                                contentValue = selectedMemo.content,
                                onContentChange = { v ->
                                    val i = memos.indexOfFirst { m -> m.id == selectedMemo.id }
                                    if (i != -1) memos[i] = selectedMemo.copy(content = v)
                                },
                                colors = colors
                            )
                            HorizontalDivider(
                                color = colors.subText.copy(alpha = 0.1f),
                                thickness = 1.dp
                            )
                        }
                        // 4. 콘텐츠 영역 (메모 전환 시 페이드 애니메이션)
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            AnimatedContent(
                                targetState = selectedMemoId,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(200)) togetherWith
                                        fadeOut(animationSpec = tween(150))
                                },
                                modifier = Modifier.fillMaxSize()
                            ) { memoId ->
                                val animMemo = memos.find { it.id == memoId }
                                if (animMemo != null) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(start = 32.dp, end = 32.dp, top = 16.dp)
                                    ) {
                                        if (isPreviewMode) {
                                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                                MarkdownContent(animMemo.content.text, colors)
                                            }
                                        } else {
                                            TextField(
                                                value = animMemo.content,
                                                onValueChange = { v ->
                                                    val i = memos.indexOfFirst { m -> m.id == animMemo.id }
                                                    if (i != -1) memos[i] = animMemo.copy(content = v)
                                                },
                                                textStyle = TextStyle(color = colors.text),
                                                modifier = Modifier.fillMaxWidth().weight(1f),
                                                colors = TextFieldDefaults.colors(
                                                    focusedContainerColor = Color.Transparent,
                                                    unfocusedContainerColor = Color.Transparent,
                                                    focusedIndicatorColor = Color.Transparent,
                                                    unfocusedIndicatorColor = Color.Transparent
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // 4. 상태 바 (전체 너비 — 패딩 없이 하단 고정)
                        val text = selectedMemo.content.text
                        val sel = selectedMemo.content.selection
                        val line = text.substring(0, sel.end).count { it == '\n' } + 1
                        val col = sel.end - text.substring(0, sel.end).lastIndexOf('\n')
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.statusBar)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("줄: $line, 열: $col", color = colors.subText, fontSize = 12.sp)
                            Text("  |  ", color = colors.subText, fontSize = 12.sp)
                            Text("글자 수: ${text.length}", color = colors.subText, fontSize = 12.sp)
                            if (showNotify) {
                                Spacer(Modifier.weight(1f))
                                Text(
                                    notifyMessage,
                                    color = colors.accent.copy(alpha = 0.9f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("메모를 선택하거나 새로 만들어 주세요.", color = colors.subText)
                    }
                }
            }
        }
    }
}

@Composable
fun MemoItem(memo: Memo, isSelected: Boolean, colors: ThemeColors, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) colors.sidebarHover else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = memo.title,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Text(
            text = "✕",
            modifier = Modifier.clickable(onClick = onDelete).padding(start = 8.dp),
            color = colors.subText
        )
    }
}
