package dev.yurie.display.ui

import dev.yurie.display.Rgba
import dev.yurie.display.compose.ComposeFrameAdapter

internal object BitmapLabels {
    private val glyphs = mapOf(
        'A' to "010/101/111/101/101", 'B' to "110/101/110/101/110",
        'C' to "011/100/100/100/011", 'D' to "110/101/101/101/110",
        'E' to "111/100/110/100/111", 'F' to "111/100/110/100/100",
        'G' to "011/100/101/101/011", 'H' to "101/101/111/101/101",
        'I' to "111/010/010/010/111", 'J' to "001/001/001/101/010",
        'K' to "101/101/110/101/101", 'L' to "100/100/100/100/111",
        'M' to "101/111/111/101/101", 'N' to "101/111/111/111/101",
        'O' to "010/101/101/101/010", 'P' to "110/101/110/100/100",
        'Q' to "010/101/101/111/011", 'R' to "110/101/110/101/101",
        'S' to "011/100/010/001/110", 'T' to "111/010/010/010/010",
        'U' to "101/101/101/101/111", 'V' to "101/101/101/101/010",
        'W' to "101/101/111/111/101", 'X' to "101/101/010/101/101",
        'Y' to "101/101/010/010/010", 'Z' to "111/001/010/100/111",
        '0' to "111/101/101/101/111", '1' to "010/110/010/010/111",
        '2' to "110/001/010/100/111", '3' to "110/001/010/001/110",
        '4' to "101/101/111/001/001", '5' to "111/100/110/001/110",
        '6' to "011/100/110/101/010", '7' to "111/001/010/010/010",
        '8' to "010/101/010/101/010", '9' to "010/101/011/001/110",
        '!' to "010/010/010/000/010", '?' to "110/001/010/000/010",
        '.' to "000/000/000/000/010", ':' to "000/010/000/010/000",
        '-' to "000/000/111/000/000", '/' to "001/001/010/100/100",
        '+' to "000/010/111/010/000", ' ' to "000/000/000/000/000",
    ).mapValues { (_, rows) -> rows.split('/') }

    fun width(text: String, scale: Float): Float =
        if (text.isEmpty()) 0f else (text.length * 4 - 1) * scale

    fun draw(adapter: ComposeFrameAdapter, text: String, x: Float, y: Float, scale: Float, color: Rgba) {
        require(scale.isFinite() && scale > 0f) { "Text scale must be finite and positive" }
        require(text.all { it.uppercaseChar() in glyphs }) { "Only basic ASCII bitmap glyphs are supported" }
        text.uppercase().forEachIndexed { character, symbol ->
            val rows = glyphs.getValue(symbol)
            rows.forEachIndexed { row, bits ->
                var column = 0
                while (column < 3) {
                    if (bits[column] == '0') {
                        column++
                        continue
                    }
                    val start = column
                    while (column < 3 && bits[column] == '1') column++
                    adapter.drawRect(x + (character * 4 + start) * scale, y + row * scale,
                        (column - start) * scale, scale, color)
                }
            }
        }
    }
}
