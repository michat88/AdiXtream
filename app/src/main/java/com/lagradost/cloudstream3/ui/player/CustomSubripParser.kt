/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
* This is a fork of media3 subrip parses as the developers fear a flexible player, and open classes.
*/
package com.lagradost.cloudstream3.ui.player

import android.text.Spanned
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Format.CueReplacementBehavior
import androidx.media3.common.text.Cue
import androidx.media3.common.text.Cue.AnchorType
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleParser.OutputOptions
import com.google.common.collect.ImmutableList
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern

/** A [SubtitleParser] for SubRip. */
@UnstableApi
class CustomSubripParser : SubtitleParser {
    private val textBuilder: StringBuilder = StringBuilder()
    private val tags: ArrayList<String> = ArrayList()
    private val parsableByteArray: ParsableByteArray = ParsableByteArray()

    override fun getCueReplacementBehavior(): @CueReplacementBehavior Int {
        return CUE_REPLACEMENT_BEHAVIOR
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: OutputOptions,
        output: Consumer<CuesWithTiming>
    ) {
        parsableByteArray.reset(data, offset + length)
        parsableByteArray.setPosition(offset)
        val charset = detectUtfCharset(parsableByteArray)

        val cuesWithTimingBeforeRequestedStartTimeUs: MutableList<CuesWithTiming>? =
            if (outputOptions.startTimeUs != C.TIME_UNSET && outputOptions.outputAllCues)
                ArrayList()
            else
                null

        // KITA UBAH CARA LOOPINGNYA SUPAYA KOTLIN TIDAK BINGUNG
        while (true) {
            // 1. Membaca baris nomor Index
            val indexLine = parsableByteArray.readLine(charset) ?: break

            if (indexLine.isEmpty()) {
                continue
            }

            try {
                indexLine.toInt()
            } catch (_: NumberFormatException) {
                Log.w(TAG, "Skipping invalid index: $indexLine")
                continue
            }

            // 2. Membaca baris Waktu (Timing)
            val timingLine = parsableByteArray.readLine(charset)
            if (timingLine == null) {
                Log.w(TAG, "Unexpected end")
                break
            }

            val startTimeUs: Long
            val endTimeUs: Long
            val matcher = SUBRIP_TIMING_LINE.matcher(timingLine)
            if (matcher.matches()) {
                startTimeUs = parseTimecode(matcher, 1)
                endTimeUs = parseTimecode(matcher, 6)
            } else {
                Log.w(TAG, "Skipping invalid timing: $timingLine")
                continue
            }

            // 3. Membaca isi Teks Subtitle
            textBuilder.setLength(0)
            tags.clear()
            
            while (true) {
                val textLine = parsableByteArray.readLine(charset)
                // Karena menggunakan val, Kotlin langsung tahu bahwa di bawah kondisi ini, 
                // textLine PASTI tidak null (smart-cast bekerja sempurna)
                if (textLine.isNullOrEmpty()) {
                    break
                }
                
                if (textBuilder.isNotEmpty()) {
                    textBuilder.append("<br>")
                }
                // Karena smart-cast, kita bisa melempar variabel tanpa pengaman apapun
                textBuilder.append(processLine(textLine, tags))
            }

            val text = androidx.core.text.HtmlCompat.fromHtml(
                textBuilder.toString(),
                androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
            )

            var alignmentTag: String? = null
            for (i in tags.indices) {
                val tag = tags[i]
                if (tag.matches(SUBRIP_ALIGNMENT_TAG.toRegex())) {
                    alignmentTag = tag
                    break
                }
            }

            val cuesWithTiming = CuesWithTiming(
                ImmutableList.of(buildCue(text, alignmentTag)),
                startTimeUs,
                endTimeUs - startTimeUs
            )

            if (outputOptions.startTimeUs == C.TIME_UNSET || endTimeUs >= outputOptions.startTimeUs) {
                output.accept(cuesWithTiming)
            } else {
                cuesWithTimingBeforeRequestedStartTimeUs?.add(cuesWithTiming)
            }
        }
        
        cuesWithTimingBeforeRequestedStartTimeUs?.forEach {
            output.accept(it)
        }
    }

    private fun detectUtfCharset(data: ParsableByteArray): Charset {
        return data.readUtfCharsetFromBom() ?: StandardCharsets.UTF_8
    }

    private fun processLine(line: String, tags: ArrayList<String>): String {
        val processedLineStr = line.trim { it <= ' ' }
        var removedCharacterCount = 0
        val processedLine = StringBuilder(processedLineStr)
        val matcher = SUBRIP_TAG_PATTERN.matcher(processedLineStr)
        
        while (matcher.find()) {
            val tag = matcher.group()
            tags.add(tag)
            val start = matcher.start() - removedCharacterCount
            val tagLength = tag.length
            processedLine.replace(start, start + tagLength, "")
            removedCharacterCount += tagLength
        }

        return processedLine.toString()
    }

    private fun buildCue(text: Spanned, alignmentTag: String?): Cue {
        val cue = Cue.Builder().setText(text)
        if (alignmentTag == null) {
            return cue.build()
        }

        when (alignmentTag) {
            ALIGN_BOTTOM_LEFT, ALIGN_MID_LEFT, ALIGN_TOP_LEFT -> cue.setPositionAnchor(Cue.ANCHOR_TYPE_START)
            ALIGN_BOTTOM_RIGHT, ALIGN_MID_RIGHT, ALIGN_TOP_RIGHT -> cue.setPositionAnchor(Cue.ANCHOR_TYPE_END)
            ALIGN_BOTTOM_MID, ALIGN_MID_MID, ALIGN_TOP_MID -> cue.setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            else -> cue.setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        }

        when (alignmentTag) {
            ALIGN_BOTTOM_LEFT, ALIGN_BOTTOM_MID, ALIGN_BOTTOM_RIGHT -> cue.setLineAnchor(Cue.ANCHOR_TYPE_END)
            ALIGN_TOP_LEFT, ALIGN_TOP_MID, ALIGN_TOP_RIGHT -> cue.setLineAnchor(Cue.ANCHOR_TYPE_START)
            ALIGN_MID_LEFT, ALIGN_MID_MID, ALIGN_MID_RIGHT -> cue.setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            else -> cue.setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        }

        return cue.setPosition(getFractionalPositionForAnchorType(cue.getPositionAnchor()))
            .setLine(
                getFractionalPositionForAnchorType(cue.getLineAnchor()),
                Cue.LINE_TYPE_FRACTION
            )
            .build()
    }

    companion object {
        const val CUE_REPLACEMENT_BEHAVIOR: @CueReplacementBehavior Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_MERGE

        private const val START_FRACTION = 0.08f
        private const val END_FRACTION = 1 - START_FRACTION
        private const val MID_FRACTION = 0.5f

        private const val TAG = "SubripParser"

        private const val SUBRIP_TIMECODE = "(?:(\\d+):)?(\\d+):(\\d+)(?:[,.](\\d+))?"
        private val SUBRIP_TIMING_LINE: Pattern =
            Pattern.compile("\\s*($SUBRIP_TIMECODE)\\s*-->\\s*($SUBRIP_TIMECODE)\\s*")

        private val SUBRIP_TAG_PATTERN: Pattern = Pattern.compile("\\{\\\\.*?\\}")
        private const val SUBRIP_ALIGNMENT_TAG = "\\{\\\\an[1-9]\\}"

        private const val ALIGN_BOTTOM_LEFT = "{\\an1}"
        private const val ALIGN_BOTTOM_MID = "{\\an2}"
        private const val ALIGN_BOTTOM_RIGHT = "{\\an3}"
        private const val ALIGN_MID_LEFT = "{\\an4}"
        private const val ALIGN_MID_MID = "{\\an5}"
        private const val ALIGN_MID_RIGHT = "{\\an6}"
        private const val ALIGN_TOP_LEFT = "{\\an7}"
        private const val ALIGN_TOP_MID = "{\\an8}"
        private const val ALIGN_TOP_RIGHT = "{\\an9}"

        private fun parseTimecode(matcher: Matcher, groupOffset: Int): Long {
            val hours = matcher.group(groupOffset + 1)
            var timestampMs = if (hours != null) hours.toLong() * 60 * 60 * 1000 else 0
            
            timestampMs += requireNotNull(matcher.group(groupOffset + 2)).toLong() * 60 * 1000
            timestampMs += requireNotNull(matcher.group(groupOffset + 3)).toLong() * 1000
                
            val millis = matcher.group(groupOffset + 4)

            timestampMs += when (millis?.length) {
                null -> 0L
                1 -> millis.toLong() * 100L
                2 -> millis.toLong() * 10L
                3 -> millis.toLong() * 1L
                else -> millis.substring(0, 3).toLong()
            }

            return timestampMs * 1000
        }

        @VisibleForTesting(otherwise = VisibleForTesting.Companion.PRIVATE)
        fun getFractionalPositionForAnchorType(anchorType: @AnchorType Int): Float {
            return when (anchorType) {
                Cue.ANCHOR_TYPE_START -> START_FRACTION
                Cue.ANCHOR_TYPE_MIDDLE -> MID_FRACTION
                Cue.ANCHOR_TYPE_END -> END_FRACTION
                Cue.TYPE_UNSET -> throw IllegalArgumentException()
                else -> throw IllegalArgumentException()
            }
        }
    }
}
