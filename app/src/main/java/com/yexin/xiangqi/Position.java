/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import java.util.Arrays;

/** Display and notation only. All legal moves and results come from Pikafish. */
public final class Position {
    public final char[] squares = new char[90];
    public final boolean redTurn;
    public final String fen;

    public Position(String fen) {
        this.fen = fen;
        Arrays.fill(squares, '.');
        String[] fields = fen.trim().split("\\s+");
        if (fields.length < 2) throw new IllegalArgumentException("Invalid FEN");
        String[] ranks = fields[0].split("/");
        if (ranks.length != 10) throw new IllegalArgumentException("Invalid ranks");
        for (int row = 0; row < 10; row++) {
            int file = 0;
            for (char c : ranks[row].toCharArray()) {
                if (c >= '1' && c <= '9') file += c - '0';
                else {
                    if (file >= 9 || "rnbakcpRNBAKCPheHE".indexOf(c) < 0)
                        throw new IllegalArgumentException("Invalid piece");
                    squares[row * 9 + file++] = c;
                }
            }
            if (file != 9) throw new IllegalArgumentException("Invalid files");
        }
        if (!fields[1].equals("w") && !fields[1].equals("b"))
            throw new IllegalArgumentException("Invalid turn");
        redTurn = fields[1].equals("w");
    }

    public static boolean validMove(String move) {
        return move != null && move.matches("[a-i][0-9][a-i][0-9]")
            && !move.substring(0, 2).equals(move.substring(2, 4));
    }

    public static int from(String move) { return square(move.charAt(0), move.charAt(1)); }
    public static int to(String move) { return square(move.charAt(2), move.charAt(3)); }
    public static int square(char file, char rank) { return (9 - (rank - '0')) * 9 + file - 'a'; }
    public static String coordinate(int square) {
        return "" + (char) ('a' + square % 9) + (char) ('0' + 9 - square / 9);
    }
    public static boolean isRed(char c) { return c != '.' && Character.isUpperCase(c); }

    public static String pieceName(char c) {
        switch (c) {
            case 'K': return "帅"; case 'k': return "将";
            case 'A': return "仕"; case 'a': return "士";
            case 'B': case 'E': return "相"; case 'b': case 'e': return "象";
            case 'N': case 'H': case 'n': case 'h': return "马";
            case 'R': case 'r': return "车";
            case 'C': case 'c': return "炮";
            case 'P': return "兵"; case 'p': return "卒";
            default: return "";
        }
    }
    private static String number(int n, boolean red) {
        return red ? "〇一二三四五六七八九".substring(n, n + 1) : Integer.toString(n);
    }
    public String notation(String move) {
        if (!validMove(move)) return move == null ? "" : move;
        int a = from(move), b = to(move);
        char piece = squares[a];
        boolean red = isRed(piece);
        int ax = a % 9, ay = a / 9, bx = b % 9, by = b / 9;
        int file = red ? 9 - ax : ax + 1;
        String prefix = pieceName(piece) + number(file, red);
        int sameFile = 0, ahead = 0;
        for (int r = 0; r < 10; r++) if (squares[r * 9 + ax] == piece) {
            sameFile++;
            if (red ? r < ay : r > ay) ahead++;
        }
        if (sameFile == 2) prefix = (ahead == 0 ? "前" : "后") + pieceName(piece);
        else if (sameFile == 3) prefix = (ahead == 0 ? "前" : ahead == 1 ? "中" : "后") + pieceName(piece);
        else if (sameFile > 3) prefix = number(ahead + 1, red) + pieceName(piece);
        if (ay == by) return prefix + "平" + number(red ? 9 - bx : bx + 1, red);
        boolean forward = red ? by < ay : by > ay;
        char type = Character.toLowerCase(piece);
        int target = "nbhae".indexOf(type) >= 0 ? (red ? 9 - bx : bx + 1) : Math.abs(by - ay);
        return prefix + (forward ? "进" : "退") + number(target, red);
    }

    public String accessibleSummary() {
        StringBuilder b = new StringBuilder(redTurn ? "红方走棋。" : "黑方走棋。");
        for (int i = 0; i < 90; i++) if (squares[i] != '.')
            b.append(isRed(squares[i]) ? "红" : "黑").append(pieceName(squares[i]))
                .append(coordinate(i)).append('，');
        return b.toString();
    }
}
