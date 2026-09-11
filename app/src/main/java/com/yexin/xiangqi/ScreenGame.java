/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import java.util.*;

/** Main-thread transcript. A tap is committed only after its board result is observed. */
public final class ScreenGame {
    public enum Change { SAME, MOVED, PENDING_CONFIRMED, PENDING_AND_REPLY, UNKNOWN, WAIT_ENGINE }
    public boolean ownRed;
    public String initialFen;
    public EngineState state;
    private final ArrayList<String> moves=new ArrayList<>();
    private String pending;
    private EngineState pendingState;

    public void reset(BoardRecognizer.Observation observation,Boolean redToMove) {
        ownRed=observation.redBottom;
        if(observation.opening())initialFen=null;
        else {
            if(redToMove==null)throw new IllegalArgumentException("中途接管需要确认当前轮次");
            initialFen=observation.placement+(redToMove?" w":" b")+" - - 0 1";
        }
        moves.clear();state=null;pending=null;pendingState=null;
    }
    public List<String> moves(){return new ArrayList<>(moves);}
    public boolean ourTurn(){return state!=null&&!state.finished()&&state.position.redTurn==ownRed;}
    public boolean hasPending(){return pending!=null;}
    public String pendingMove(){return pending;}
    public EngineState pendingState(){return pendingState;}
    public void clearPending(){pending=null;pendingState=null;}
    public void expect(String move,EngineState after) {
        if(!ourTurn()||pending!=null||!state.legalMoves.contains(move))throw new IllegalStateException("当前不可落子");
        if(!Arrays.equals(apply(state.position.squares,move),after.position.squares))throw new IllegalArgumentException("预期局面错误");
        pending=move;pendingState=after;
    }
    public Change observe(BoardRecognizer.Observation observation) {
        if(state==null)return Change.WAIT_ENGINE;
        if(observation.redBottom!=ownRed)return Change.UNKNOWN;
        char[] actual=observation.squares;
        if(Arrays.equals(actual,state.position.squares))return Change.SAME;
        if(pending!=null) {
            if(Arrays.equals(actual,pendingState.position.squares)) {
                moves.add(pending);state=pendingState;clearPending();return Change.PENDING_CONFIRMED;
            }
            String reply=matchingMove(pendingState,actual);
            if(reply!=null) {
                moves.add(pending);moves.add(reply);state=null;clearPending();return Change.PENDING_AND_REPLY;
            }
            return Change.UNKNOWN;
        }
        String move=matchingMove(state,actual);
        if(move==null)return Change.UNKNOWN;
        moves.add(move);state=null;return Change.MOVED;
    }
    public static char[] apply(char[] before,String move) {
        char[] after=before.clone();int from=Position.from(move),to=Position.to(move);
        after[to]=after[from];after[from]='.';return after;
    }
    public static String matchingMove(EngineState before,char[] actual) {
        if(before==null||before.finished())return null;
        String answer=null;
        for(String move:before.legalMoves)if(Arrays.equals(apply(before.position.squares,move),actual)) {
            if(answer!=null)return null;answer=move;
        }
        return answer;
    }
}
