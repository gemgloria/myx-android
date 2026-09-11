/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import java.util.Arrays;

/** A bounded input plan; all preparatory taps stay on occupied friendly squares. */
public final class MoveTapPlan {
    private MoveTapPlan(){}
    public static int[] create(EngineState state,BoardRecognizer.Observation observed,String move) {
        if(state==null||observed==null||state.finished()||!state.legalMoves.contains(move)
            ||!Arrays.equals(state.position.squares,observed.squares)||state.position.redTurn!=observed.redBottom)
            throw new IllegalArgumentException("局面或轮次不匹配，不能落子");
        int from=Position.from(move),to=Position.to(move),selected=observed.selectedSquare;
        if(!friendly(observed,from))throw new IllegalArgumentException("不能操作对方棋子");
        if(!observed.hasSelectionEvidence())return new int[]{from,to};
        if(selected>=0&&friendly(observed,selected)&&verifiedHints(state,observed,selected)) {
            // Tapping an already selected source may toggle selection OFF in this game.
            if(selected==from&&observed.moveHints[to])return new int[]{to};
            if(selected!=from)return new int[]{from,to};
        }
        // Unknown selection: selecting a different friendly piece cannot be a move or
        // capture. Whether that tap selects or deselects it, the next tap selects from.
        for(int s=0;s<90;s++)if(s!=from&&friendly(observed,s))return new int[]{s,from,to};
        throw new IllegalArgumentException("无法确认选中状态，请先取消选中再开始");
    }
    private static boolean friendly(BoardRecognizer.Observation observed,int s) {
        char c=observed.squares[s];return c!='.'&&Character.isUpperCase(c)==observed.redBottom;
    }
    private static boolean verifiedHints(EngineState state,BoardRecognizer.Observation observed,int selected) {
        boolean[] legal=new boolean[90];boolean any=false;
        for(String m:state.legalMoves)if(Position.from(m)==selected)legal[Position.to(m)]=true;
        for(int s=0;s<90;s++)if(observed.moveHints[s]){any=true;if(!legal[s])return false;}
        return any;
    }
}
