/*
 *  Copyright (C) 2011-2017 Cojen.org
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.views;

import java.io.IOException;

import org.cojen.tupl.Combiner;
import org.cojen.tupl.Cursor;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.View;

/**
 * View implementation vended by {@link View#viewDifference}.
 *
 * @author Brian S O'Neill
 */
public final class DifferenceView extends MergeView {
    public DifferenceView(Combiner combiner, View first, View second) {
        super(combiner, first, second);
    }

    @Override
    protected byte[] doLoad(Transaction txn, byte[] key) throws IOException {
        return mCombiner.loadDifference(txn, key, mFirst, mSecond);
    }

    @Override
    protected MergeCursor newCursor(Transaction txn, MergeView view,
                                    Cursor first, Cursor second)
    {
        return new DifferenceCursor(txn, view, first, second);
    }

    @Override
    protected String type() {
        return "difference";
    }
}
