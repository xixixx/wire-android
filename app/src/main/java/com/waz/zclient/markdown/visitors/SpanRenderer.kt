/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.markdown.visitors

import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.LeadingMarginSpan
import android.text.style.TabStopSpan
import com.waz.zclient.markdown.StyleSheet
import com.waz.zclient.markdown.spans.custom.ListPrefixSpan
import com.waz.zclient.markdown.utils.*
import org.commonmark.internal.renderer.text.BulletListHolder
import org.commonmark.internal.renderer.text.ListHolder
import org.commonmark.node.*
import org.commonmark.renderer.NodeRenderer

/**
 * A SpanRenderer instance traverses the syntax tree of a markdown document and constructs
 * a spannable string with the appropriate style spans for each markdown unit. The style
 * spans for each node in the tree are provided by a configured StyleSheet instance.
 */
class SpanRenderer(private val styleSheet: StyleSheet) : AbstractVisitor(), NodeRenderer {

    // TODO: make this an option
    val softBreaksAsHardBreaks = true
    val spannableString: SpannableString get() = writer.spannableString.trim() as SpannableString

    private val writer = TextWriter()
    private var listHolder: ListHolder? = null
    private var listRanges = mutableListOf<IntRange>()

    //region NodeRenderer
    override fun getNodeTypes(): MutableSet<Class<out Node>> {
        return mutableSetOf(
            Document::class.java,
            Heading::class.java,
            Paragraph::class.java,
            BlockQuote::class.java,
            OrderedList::class.java,
            BulletList::class.java,
            ListItem::class.java,
            FencedCodeBlock::class.java,
            IndentedCodeBlock::class.java,
            HtmlBlock::class.java,
            Link::class.java,
            Image::class.java,
            Emphasis::class.java,
            StrongEmphasis::class.java,
            Code::class.java,
            HtmlInline::class.java,
            Text::class.java,
            SoftLineBreak::class.java,
            HardLineBreak::class.java,
            ThematicBreak::class.java
        )
    }

    override fun render(node: Node?) { node?.accept(this) }
    //endregion

    override fun visit(document: Document?) {
        if (document == null) return
        visitChildren(document)
    }

    override fun visit(heading: Heading?) {
        if (heading == null) return
        writer.saveCursor()
        visitChildren(heading)
        writeLineIfNeeded(heading)
        writer.set(styleSheet.spanFor(heading), writer.retrieveCursor())
    }

    override fun visit(paragraph: Paragraph?) {
        if (paragraph == null) return
        writer.saveCursor()
        visitChildren(paragraph)
        writeLineIfNeeded(paragraph)
        writer.set(styleSheet.spanFor(paragraph), writer.retrieveCursor())
    }

    override fun visit(blockQuote: BlockQuote?) {
        if (blockQuote == null) return
        if (blockQuote.isNested) writer.line()
        writer.saveCursor()
        visitChildren(blockQuote)
        writeLineIfNeeded(blockQuote)

        val start = writer.retrieveCursor()
        if (blockQuote.isOuterMost)
            writer.set(styleSheet.spanFor(blockQuote), start)
    }

    override fun visit(orderedList: OrderedList?) {
        if (orderedList == null) return
        writer.saveCursor()

        // we're already inside a list
        if (listHolder != null) writer.line()

        // new holder for this list
        listHolder = SmartOrderedListHolder(listHolder, orderedList)
        visitChildren(orderedList)
        writeLineIfNeeded(orderedList)

        val start = writer.retrieveCursor()
        val span = styleSheet.spanFor(orderedList)

        writer.set(span, start)

        // we're done with the current holder
        listHolder = (listHolder as ListHolder).parent
        listRanges.add(start..writer.cursor)
    }

    override fun visit(bulletList: BulletList?) {
        if (bulletList == null) return
        writer.saveCursor()

        // we're already inside a list
        if (listHolder != null) writer.line()

        // new holder for this list
        listHolder = BulletListHolder(listHolder, bulletList)
        visitChildren(bulletList)
        writeLineIfNeeded(bulletList)

        val start = writer.retrieveCursor()
        val span = styleSheet.spanFor(bulletList)

        writer.set(span, start)

        listHolder = (listHolder as ListHolder).parent
        listRanges.add(start..writer.cursor)
    }

    override fun visit(listItem: ListItem?) {
        if (listItem == null) return
        val prefixStart = writer.saveCursor()

        var digits = 3
        var digitWidth = styleSheet.maxDigitWidth.toInt()

        val standardPrefixWidth = styleSheet.listItemContentMargin
        var tabLocation = standardPrefixWidth
        val indentation = (listHolder?.depth ?: 0) * standardPrefixWidth

        when (listHolder) {
            is SmartOrderedListHolder -> {
                val smartListHolder = listHolder as SmartOrderedListHolder

                // standard prefix width is 2 digits + "."
                digits = Math.max(2, smartListHolder.largestPrefix.numberOfDigits) + 1
                tabLocation = digits * digitWidth + styleSheet.listPrefixGapWidth

                writer.write("${smartListHolder.counter}.")
                smartListHolder.increaseCounter()
            }

            is BulletListHolder -> {
                // a bullet is just one digit, but we make the width equal to 3
                // so that it aligns with number prefixes
                digits = 1
                digitWidth = 3 * styleSheet.maxDigitWidth.toInt()

                writer.write("\u2022")
            }
        }

        // span the prefix
        val prefixSpan = ListPrefixSpan(digits, digitWidth, styleSheet.listPrefixColor)
        writer.set(AbsoluteSizeSpan(styleSheet.baseFontSize, false), prefixStart, writer.cursor)
        writer.set(prefixSpan, prefixStart, writer.cursor)

        // write the content
        writer.tabIfNeeded()
        visitChildren(listItem)
        writeLineIfNeeded(listItem)

        val start = writer.retrieveCursor()

        // TODO: General clean up here

        // if the item contains a break, then the first line of each paragraph is not
        // correctly indented, so we must adjust it to match the rest of the lines.
        val breakIndex = writer.toString().indexOf('\n', start)

        // there is a break & it's not the last char
        if (-1 < breakIndex && breakIndex < writer.cursor - 1) {
            // boundary is start of nested list if it exists, else end of this item.
            // TODO: be more explicit about how to get the next occuring list. Here we just rely on the fact that list ranges are added in reverse order.
            val boundary = listRanges.lastOrNull {
                it.start > start && it.endInclusive <= writer.cursor
            }?.start ?: writer.cursor

            // spans for first paragraph (up to abd including the break)
            writer.set(LeadingMarginSpan.Standard(indentation, indentation + standardPrefixWidth), start, breakIndex + 1)
            writer.set(TabStopSpan.Standard(tabLocation), start, breakIndex + 1)

            // TODO: is boundary a newline? It needs to be for paragraph spans
            if (breakIndex != boundary) {
                // spans for rest paragraphs
                writer.set(LeadingMarginSpan.Standard(indentation + standardPrefixWidth), breakIndex, boundary)
            }
        }
        else {
            // there is no break, no nested list, just apply the span to the whole item
            writer.set(LeadingMarginSpan.Standard(indentation, indentation + standardPrefixWidth), start, writer.cursor)
            writer.set(TabStopSpan.Standard(tabLocation), start, writer.cursor)
        }

        writer.set(styleSheet.spanFor(listItem), start)
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock?) {
        if (fencedCodeBlock == null) return
        writer.saveCursor()
        writer.write(fencedCodeBlock.literal)
        writer.set(styleSheet.spanFor(fencedCodeBlock), writer.retrieveCursor())
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock?) {
        if (indentedCodeBlock == null) return
        writer.saveCursor()
        writer.write(indentedCodeBlock.literal)
        writer.set(styleSheet.spanFor(indentedCodeBlock), writer.retrieveCursor())
    }

    override fun visit(htmlBlock: HtmlBlock?) {
        if (htmlBlock == null) return
        writer.saveCursor()
        writer.write(htmlBlock.literal)
        writer.set(styleSheet.spanFor(htmlBlock), writer.retrieveCursor())
    }

    override fun visit(link: Link?) {
        if (link == null) return
        writer.saveCursor()
        visitChildren(link)
        writer.set(styleSheet.spanFor(link), writer.retrieveCursor())
    }

    override fun visit(image: Image?) {
        if (image == null) return
        // TODO: write raw input b/c this is unsupported
        writer.saveCursor()
        visitChildren(image)
        writer.set(styleSheet.spanFor(image), writer.retrieveCursor())
    }

    override fun visit(emphasis: Emphasis?) {
        if (emphasis == null) return
        writer.saveCursor()
        visitChildren(emphasis)
        writer.set(styleSheet.spanFor(emphasis), writer.retrieveCursor())
    }

    override fun visit(strongEmphasis: StrongEmphasis?) {
        if (strongEmphasis == null) return
        writer.saveCursor()
        visitChildren(strongEmphasis)
        writer.set(styleSheet.spanFor(strongEmphasis), writer.retrieveCursor())
    }

    override fun visit(code: Code?) {
        if (code == null) return
        writer.saveCursor()
        writer.write(code.literal)
        writer.set(styleSheet.spanFor(code), writer.retrieveCursor())
    }

    override fun visit(htmlInline: HtmlInline?) {
        if (htmlInline == null) return
        writer.saveCursor()
        writer.write(htmlInline.literal)
        writer.set(styleSheet.spanFor(htmlInline), writer.retrieveCursor())
    }

    override fun visit(text: Text?) {
        if (text == null) return
        writer.saveCursor()
        writer.write(text.literal)
        writer.set(styleSheet.spanFor(text), writer.retrieveCursor())
    }

    override fun visit(softLineBreak: SoftLineBreak?) {
        if (softLineBreak == null) return
        writer.saveCursor()
        if (softBreaksAsHardBreaks) writer.line() else writer.space()
        writer.set(styleSheet.spanFor(softLineBreak), writer.retrieveCursor())
    }

    override fun visit(hardLineBreak: HardLineBreak?) {
        if (hardLineBreak == null) return
        writer.saveCursor()
        writer.line()
        writer.set(styleSheet.spanFor(hardLineBreak), writer.retrieveCursor())
    }

    override fun visit(thematicBreak: ThematicBreak?) {
        if (thematicBreak == null) return
        writer.saveCursor()
        writer.write("---\n")
        writer.set(styleSheet.spanFor(thematicBreak), writer.retrieveCursor())
    }

    private fun writeLineIfNeeded(node: Node) {
        when (node) {
        // newlines only for non nested paragraphs
            is Paragraph -> if (!node.isOuterMost) return
            else -> { }
        }

        // TODO: we might want to prohibit adding newline if it is the last node
        writer.lineIfNeeded()
    }
}
