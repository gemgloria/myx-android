/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import java.util.*;

/** UI-thread-owned transcript. Red always moves first; choosing sides never changes the rules. */
public final class GameSession {
    public boolean humanRed = true;
    private final ArrayList<String> moves = new ArrayList<>();
    private final ArrayList<String> notes = new ArrayList<>();
    public List<String> moves() { return new ArrayList<>(moves); }
    public List<String> notes() { return new ArrayList<>(notes); }
    public int size() { return moves.size(); }
    public String lastMove() { return moves.isEmpty() ? null : moves.get(moves.size()-1); }
    public boolean humanTurn() { return ((moves.size() & 1) == 0) == humanRed; }
    public void reset(boolean humanRed) { this.humanRed=humanRed; moves.clear();notes.clear(); }
    public void append(String move, EngineState state) {
        if (state.finished() || !state.legalMoves.contains(move)) throw new IllegalArgumentException("非法着法");
        if (state.position.redTurn != ((moves.size() & 1)==0)) throw new IllegalStateException("局面不同步");
        notes.add(state.position.notation(move));
        moves.add(move);
    }
    public boolean canUndo() { return lastHumanMove() >= 0; }
    private int lastHumanMove() {
        for (int i=moves.size()-1;i>=0;i--) if (((i&1)==0)==humanRed) return i;
        return -1;
    }
    public boolean undo() {
        int index=lastHumanMove();
        if (index<0) return false;
        while (moves.size()>index) { moves.remove(moves.size()-1);notes.remove(notes.size()-1); }
        return true;
    }
    public String serializeMoves() { return String.join(" ",moves); }
    public String serializeNotes() { return String.join("\n",notes); }
    public void restore(boolean humanRed,String transcript,String descriptions) {
        reset(humanRed);
        if (transcript==null || transcript.trim().isEmpty()) return;
        String[] parsed=transcript.trim().split("\\s+");
        if (parsed.length>5000) throw new IllegalArgumentException("棋谱过长");
        for (String m:parsed) if (!Position.validMove(m)) throw new IllegalArgumentException("棋谱损坏");
        String[] d=descriptions==null?new String[0]:descriptions.split("\n");
        for (int i=0;i<parsed.length;i++) { moves.add(parsed[i]);notes.add(i<d.length?d[i]:parsed[i]); }
    }
}
