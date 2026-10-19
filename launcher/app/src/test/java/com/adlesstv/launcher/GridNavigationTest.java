package com.adlesstv.launcher;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.adlesstv.launcher.GridNavigation.Direction.*;

public class GridNavigationTest {
    @Test public void skipsHeadingsAndClampsTheLastIncompleteRow() {
        // Heading, four favorites, heading, five apps. The last row contains only one tile.
        GridNavigation grid = new GridNavigation(new boolean[]{true, false, false, false, false,
                true, false, false, false, false, false}, 4);
        assertEquals(9, grid.move(4, DOWN));
        assertEquals(10, grid.move(9, DOWN));
        assertEquals(6, grid.move(10, UP));
        assertEquals(4, grid.move(9, UP));
        assertEquals(GridNavigation.TOP_BAR, grid.move(3, UP));
        assertEquals(4, grid.move(4, RIGHT));
        assertEquals(6, grid.move(6, LEFT));
        assertEquals(10, grid.move(10, DOWN));
    }

    @Test public void adaptsAcrossColumnsAndDoesNotSkipAnIncompleteFavoritesRow() {
        GridNavigation grid = new GridNavigation(new boolean[]{true, false, false, false, false,
                false, true, false, false, false, false}, 3);
        assertEquals(5, grid.move(3, DOWN));
        assertEquals(8, grid.move(5, DOWN));
        assertEquals(5, grid.move(9, UP));
        assertEquals(7, grid.move(10, UP));
    }
}
