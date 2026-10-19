package com.adlesstv.launcher;

/** Row geometry shared by layout and D-pad navigation, including full-width headings. */
final class GridNavigation {
    enum Direction { UP, DOWN, LEFT, RIGHT }
    static final int TOP_BAR = -1;
    private final int[] rows;
    private final int[] columns;

    GridNavigation(boolean[] headings, int spanCount) {
        if (spanCount < 1) throw new IllegalArgumentException("spanCount");
        rows = new int[headings.length];
        columns = new int[headings.length];
        int row = 0, column = 0;
        for (int i = 0; i < headings.length; i++) {
            if (headings[i]) {
                if (column != 0) { row++; column = 0; }
                rows[i] = -1;
                row++;
            } else {
                rows[i] = row;
                columns[i] = column++;
                if (column == spanCount) { row++; column = 0; }
            }
        }
    }

    int move(int position, Direction direction) {
        if (position < 0 || position >= rows.length || rows[position] < 0) return TOP_BAR;
        int row = rows[position], column = columns[position];
        if (direction == Direction.LEFT || direction == Direction.RIGHT) {
            int next = position + (direction == Direction.LEFT ? -1 : 1);
            return next >= 0 && next < rows.length && rows[next] == row ? next : position;
        }
        int wanted = direction == Direction.UP ? -1 : Integer.MAX_VALUE;
        for (int candidate : rows) {
            if (direction == Direction.UP && candidate >= 0 && candidate < row) wanted = Math.max(wanted, candidate);
            if (direction == Direction.DOWN && candidate > row) wanted = Math.min(wanted, candidate);
        }
        if (wanted == -1) return TOP_BAR;
        if (wanted == Integer.MAX_VALUE) return position;
        int best = position, distance = Integer.MAX_VALUE;
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == wanted && Math.abs(columns[i] - column) < distance) {
                best = i;
                distance = Math.abs(columns[i] - column);
            }
        }
        return best;
    }
}
