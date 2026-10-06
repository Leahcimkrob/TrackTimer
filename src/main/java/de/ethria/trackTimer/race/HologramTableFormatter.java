package de.ethria.trackTimer.race;

import org.bukkit.map.MapFont;
import org.bukkit.map.MinecraftFont;

import java.util.ArrayList;
import java.util.List;

/** Aligns legacy-colored table cells using the default Minecraft font's pixel widths. */
final class HologramTableFormatter {
    private HologramTableFormatter() { }

    static List<String> format(List<List<String>> rows) {
        return format(rows, rows);
    }

    static List<String> format(List<List<String>> rows, List<List<String>> sizingRows) {
        int columns = rows.getFirst().size();
        int[] widths = new int[columns];
        for (List<String> row : sizingRows) {
            for (int column = 0; column < columns; column++) {
                widths[column] = Math.max(widths[column], width(row.get(column)));
            }
        }
        List<String> lines = new ArrayList<>();
        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < columns; column++) {
                String cell = row.get(column);
                line.append(cell).append("&r").append(padding(widths[column] - width(cell) + 12));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    // Normal spaces occupy four pixels; bold spaces occupy five. A minimum of
    // twelve pixels lets us represent every required gap exactly using both.
    private static String padding(int pixels) {
        int boldSpaces = pixels % 4;
        int spaces = (pixels - boldSpaces * 5) / 4;
        return " ".repeat(spaces) + (boldSpaces == 0 ? "" : "&l" + " ".repeat(boldSpaces) + "&r");
    }

    static int width(String text) {
        int width = 0;
        boolean bold = false;
        for (int offset = 0; offset < text.length();) {
            if (text.startsWith("<head:", offset)) {
                int end = text.indexOf('>', offset);
                if (end >= 0) {
                    width += 8;
                    offset = end + 1;
                    continue;
                }
            }
            char character = text.charAt(offset);
            if ((character == '&' || character == '\u00a7') && offset + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(offset + 1));
                if ("0123456789abcdefklmnorx".indexOf(code) >= 0) {
                    if (code == 'l') bold = true;
                    else if (code == 'r' || "0123456789abcdefx".indexOf(code) >= 0) bold = false;
                    offset += 2;
                    continue;
                }
            }
            int codePoint = text.codePointAt(offset);
            MapFont.CharacterSprite glyph = codePoint <= Character.MAX_VALUE
                    ? MinecraftFont.Font.getChar((char) codePoint) : null;
            width += (glyph == null ? 5 : glyph.getWidth()) + 1 + (bold ? 1 : 0);
            offset += Character.charCount(codePoint);
        }
        return width;
    }
}
