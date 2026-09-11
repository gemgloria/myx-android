/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.*;

public final class BoardView extends View {
    public interface Tap { void square(int square); }
    private final Paint paint = new Paint(3);
    private final RectF rect = new RectF();
    private final Set<Integer> targets = new HashSet<>();
    private EngineState state;
    private boolean humanRed = true;
    private int selected = -1;
    private String lastMove, hint;
    private Tap tap;
    private float cell, left, top;
    private final float density;
    private static final int INK = Color.rgb(100, 76, 48);
    private static final int GREEN = Color.rgb(42, 106, 79);
    private static final int RED = Color.rgb(171, 62, 45);

    public BoardView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    public void setTap(Tap tap) { this.tap = tap; }
    public void show(EngineState state, boolean humanRed, String lastMove) {
        this.state = state;
        this.humanRed = humanRed;
        this.lastMove = lastMove;
        selected = -1;
        targets.clear();
        hint = null;
        setContentDescription(state == null ? "正在准备棋盘" : state.position.accessibleSummary());
        invalidate();
    }
    public void select(int square) {
        selected = square;
        targets.clear();
        if (state != null && square >= 0)
            for (String move : state.legalMoves)
                if (Position.from(move) == square) targets.add(Position.to(move));
        invalidate();
    }
    public int selected() { return selected; }
    public void showHint(String move) { hint = move; invalidate(); }
    public void clearHint() { hint = null; invalidate(); }
    private float sx(int square) {
        int f = square % 9;
        return left + (humanRed ? f : 8 - f) * cell;
    }
    private float sy(int square) {
        int r = square / 9;
        return top + (humanRed ? r : 9 - r) * cell;
    }
    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(left + x1 * cell, top + y1 * cell, left + x2 * cell, top + y2 * cell, paint);
    }
    private void text(Canvas c, String s, float x, float y, float size, int color, Typeface font) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTypeface(font);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = paint.getFontMetrics();
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, paint);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        cell = Math.min(getWidth() / 9.55f, getHeight() / 10.55f);
        left = (getWidth() - cell * 8) / 2;
        top = (getHeight() - cell * 9) / 2;
        paint.setShader(null);
        paint.setShadowLayer(3 * density, 0, 2 * density, 0x1837281A);
        rect.set(left - .67f * cell, top - .67f * cell, left + 8.67f * cell, top + 9.67f * cell);
        paint.setColor(0xFFF0DCB8);
        paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(rect, .25f * cell, .25f * cell, paint);
        paint.clearShadowLayer();
        paint.setColor(0xFFC1A17A);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        c.drawRoundRect(rect, .25f * cell, .25f * cell, paint);
        paint.setColor(INK);
        paint.setStrokeWidth(Math.max(.7f * density, cell * .022f));
        for (int row = 0; row <= 9; row++) line(c, 0, row, 8, row);
        for (int col = 0; col <= 8; col++) {
            if (col == 0 || col == 8) line(c, col, 0, col, 9);
            else { line(c, col, 0, col, 4); line(c, col, 5, col, 9); }
        }
        line(c, 3, 0, 5, 2); line(c, 5, 0, 3, 2);
        line(c, 3, 7, 5, 9); line(c, 5, 7, 3, 9);
        paint.setStrokeWidth(1.5f * density);
        rect.set(left - .085f * cell, top - .085f * cell, left + 8.085f * cell, top + 9.085f * cell);
        c.drawRect(rect, paint);
        paint.setStrokeWidth(.75f * density);
        int[][] marks = {{1,2},{7,2},{0,3},{2,3},{4,3},{6,3},{8,3},{0,6},{2,6},{4,6},{6,6},{8,6},{1,7},{7,7}};
        for (int[] p : marks) for (int dir : new int[]{-1, 1}) {
            if (p[0] == 0 && dir == -1 || p[0] == 8 && dir == 1) continue;
            for (int v : new int[]{-1, 1}) {
                float x = p[0] + dir * .10f, y = p[1] + v * .10f;
                line(c, x, y, x + dir * .14f, y);
                line(c, x, y, x, y + v * .14f);
            }
        }
        Typeface serif = Typeface.create("serif", Typeface.NORMAL);
        text(c, "楚 河", left + cell * 2, top + cell * 4.5f, cell * .42f, INK, serif);
        text(c, "汉 界", left + cell * 6, top + cell * 4.5f, cell * .42f, INK, serif);
        for (int col = 0; col <= 8; col++) {
            String bottom = humanRed ? "九八七六五四三二一".substring(col, col+1) : Integer.toString(9-col);
            String upper = humanRed ? Integer.toString(col+1) : "一二三四五六七八九".substring(col,col+1);
            text(c, bottom, left + col * cell, top + 9.45f * cell, cell * .20f, 0xFF937854, Typeface.DEFAULT);
            text(c, upper, left + col * cell, top - .45f * cell, cell * .20f, 0xFF937854, Typeface.DEFAULT);
        }
        if (state == null) return;
        if (Position.validMove(lastMove)) {
            int a = Position.from(lastMove), b = Position.to(lastMove);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x554E7E48);
            float r = cell * .18f;
            rect.set(sx(a)-r,sy(a)-r,sx(a)+r,sy(a)+r);
            c.drawRoundRect(rect, .06f*cell, .06f*cell, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2*density);
            paint.setColor(0x99528146);
            c.drawCircle(sx(b),sy(b),cell*.445f,paint);
        }
        for (int square = 0; square < 90; square++) {
            char piece = state.position.squares[square];
            if (piece == '.') continue;
            float x = sx(square), y = sy(square), radius = cell * .40f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF8EBCF);
            paint.setShadowLayer(1.6f*density,0,1.6f*density,0x604F361E);
            c.drawCircle(x,y,radius,paint);
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.0f*density);
            paint.setColor(0xFFAD8E62);
            c.drawCircle(x,y,radius,paint);
            int color = Position.isRed(piece) ? RED : 0xFF293D32;
            paint.setColor(color);
            paint.setAlpha(155);
            paint.setStrokeWidth(.7f*density);
            c.drawCircle(x,y,radius*.85f,paint);
            paint.setAlpha(255);
            text(c,Position.pieceName(piece),x,y,cell*.51f,color,Typeface.create("serif",Typeface.BOLD));
            if (state.check && Character.toLowerCase(piece)=='k' && Position.isRed(piece)==state.position.redTurn) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2*density);
                paint.setColor(0xFFC6402F);
                c.drawCircle(x,y,cell*.455f,paint);
            }
        }
        for (int square : targets) {
            paint.setColor(0xAA2C7859);
            if (state.position.squares[square]=='.') {
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(sx(square),sy(square),cell*.115f,paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.5f*density);
                c.drawCircle(sx(square),sy(square),cell*.45f,paint);
            }
        }
        if (selected >= 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f*density);
            paint.setColor(GREEN);
            c.drawCircle(sx(selected),sy(selected),cell*.455f,paint);
        }
        if (Position.validMove(hint)) {
            int a=Position.from(hint), b=Position.to(hint);
            float dx=sx(b)-sx(a),dy=sy(b)-sy(a),len=(float)Math.hypot(dx,dy);
            if (len>0) {
                float ux=dx/len,uy=dy/len;
                float x1=sx(a)+ux*cell*.34f,y1=sy(a)+uy*cell*.34f;
                float x2=sx(b)-ux*cell*.30f,y2=sy(b)-uy*cell*.30f;
                paint.setColor(0xDD257659);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(cell*.085f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(x1,y1,x2,y2,paint);
                c.drawLine(x2,y2,x2-ux*cell*.23f+uy*cell*.17f,y2-uy*cell*.23f-ux*cell*.17f,paint);
                c.drawLine(x2,y2,x2-ux*cell*.23f-uy*cell*.17f,y2-uy*cell*.23f+ux*cell*.17f,paint);
                paint.setStrokeCap(Paint.Cap.BUTT);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction()==MotionEvent.ACTION_DOWN) return true;
        if (event.getAction()==MotionEvent.ACTION_UP && cell>0) {
            performClick();
            int col=Math.round((event.getX()-left)/cell),row=Math.round((event.getY()-top)/cell);
            if (col>=0 && col<9 && row>=0 && row<10 && tap!=null) {
                if (!humanRed) { col=8-col;row=9-row; }
                tap.square(row*9+col);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
