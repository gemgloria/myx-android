/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class EngineState {
    public final Position position;
    public final Set<String> legalMoves;
    public final String result;
    public final String reason;
    public final boolean check;

    public EngineState(String fen, Set<String> moves, String result, String reason, boolean check) {
        position = new Position(fen);
        for (String move : moves) if (!Position.validMove(move))
            throw new IllegalArgumentException("Invalid engine move");
        legalMoves = Collections.unmodifiableSet(new LinkedHashSet<>(moves));
        if (!result.matches("ongoing|red|black|draw")) throw new IllegalArgumentException("Invalid result");
        this.result = result;
        this.reason = reason;
        this.check = check;
    }
    public boolean finished() { return !result.equals("ongoing"); }
    public String resultText() {
        if (result.equals("draw")) return "和棋";
        if (result.equals("red")) return "红方获胜";
        if (result.equals("black")) return "黑方获胜";
        return "对局进行中";
    }
    public String reasonText() {
        if (reason.equals("checkmate")) return "将死";
        if (reason.equals("stalemate")) return "困毙";
        if (reason.equals("rule")) return "按皮卡鱼计算机规则判定";
        return "";
    }
}
