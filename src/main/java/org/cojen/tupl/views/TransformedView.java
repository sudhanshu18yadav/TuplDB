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

import java.util.Comparator;

import org.cojen.tupl.Cursor;
import org.cojen.tupl.DurabilityMode;
import org.cojen.tupl.LockFailureException;
import org.cojen.tupl.LockResult;
import org.cojen.tupl.Ordering;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.Transformer;
import org.cojen.tupl.View;
import org.cojen.tupl.ViewConstraintException;

import org.cojen.tupl.core.Utils;

/**
 * View implementation vended by {@link View#viewTransformed}.
 *
 * @author Brian S O'Neill
 */
public final class TransformedView implements View {
    public static View apply(View source, Transformer transformer) {
        if (transformer == null) {
            throw new NullPointerException("Transformer is null");
        }
        return new TransformedView(source, transformer);
    }

    private final View mSource;
    private final Transformer mTransformer;

    private TransformedView(View source, Transformer transformer) {
        mSource = source;
        mTransformer = transformer;
    }

    @Override
    public Ordering ordering() {
        return mTransformer.transformedOrdering(mSource.ordering());
    }

    @Override
    public Comparator<byte[]> comparator() {
        return mTransformer.transformedComparator(mSource.comparator());
    }

    @Override
    public Cursor newCursor(Transaction txn) {
        return new TransformedCursor(mSource.newCursor(txn), mTransformer);
    }

    @Override
    public Transaction newTransaction(DurabilityMode durabilityMode) {
        return mSource.newTransaction(durabilityMode);
    }

    @Override
    public long count(byte[] lowKey, byte[] highKey) throws IOException {
        return ViewUtils.count(this, mTransformer.requireValue() == Boolean.TRUE,
                               lowKey, true, highKey, 0);
    }

    @Override
    public long count(byte[] lowKey, boolean lowInclusive,
                      byte[] highKey, boolean highInclusive)
        throws IOException
    {
        return ViewUtils.count(this, mTransformer.requireValue() == Boolean.TRUE,
                               lowKey, lowInclusive, highKey, highInclusive ? 1 : 0);
    }

    @Override
    public byte[] load(final Transaction txn, final byte[] tkey) throws IOException {
        if (mTransformer.requireValue() == null) {
            Cursor c = newCursor(txn);
            try {
                c.autoload(false);
                c.find(tkey);
                byte[] value = c.value();
                if (value == Cursor.NOT_LOADED) {
                    c.load();
                    value = c.value();
                }
                return value;
            } finally {
                c.reset();
            }
        }

        final byte[] key = inverseTransformKey(tkey);

        if (key == null) {
            return null;
        }

        if (txn == null || !txn.lockMode().isRepeatable()) {
            return mTransformer.transformValue(mSource.load(txn, key), key, tkey);
        }

        LockResult result = mSource.touch(txn, key);
        try {
            byte[] value = mSource.load(txn, key);
            if (value != null
                && (value = mTransformer.transformValue(value, key, tkey)) == null
                && result == LockResult.ACQUIRED)
            {
                // Release the lock if value exists but was disallowed by the transformer.
                txn.unlock();
            }
            return value;
        } catch (Throwable e) {
            throw ViewUtils.lockCleanup(e, txn, result);
        }
    }

    @Override
    public boolean exists(final Transaction txn, final byte[] tkey) throws IOException {
        final Boolean requireValue = mTransformer.requireValue();

        if (requireValue == null) {
            Cursor c = newCursor(txn);
            try {
                c.autoload(false);
                c.find(tkey);
                return c.value() != null;
            } finally {
                c.reset();
            }
        }

        final byte[] key = inverseTransformKey(tkey);

        if (key == null) {
            return false;
        }

        if (requireValue == Boolean.FALSE) {
            return mSource.exists(txn, key);
        }

        if (txn == null || !txn.lockMode().isRepeatable()) {
            return mTransformer.transformValue(mSource.load(txn, key), key, tkey) != null;
        }

        LockResult result = mSource.touch(txn, key);
        try {
            byte[] value = mSource.load(txn, key);
            if (value != null
                && (value = mTransformer.transformValue(value, key, tkey)) == null
                && result == LockResult.ACQUIRED)
            {
                // Release the lock if value exists but was disallowed by the transformer.
                txn.unlock();
            }
            return value != null;
        } catch (Throwable e) {
            throw ViewUtils.lockCleanup(e, txn, result);
        }
    }

    @Override
    public void store(final Transaction txn, final byte[] tkey, final byte[] tvalue)
        throws IOException
    {
        final byte[] key = inverseTransformKey(tkey);

        if (key == null) {
            if (tvalue == null) {
                return;
            }
            throw fail();
        }

        mSource.store(txn, key, mTransformer.inverseTransformValue(tvalue, key, tkey));
    }

    @Override
    public byte[] exchange(final Transaction txn, final byte[] tkey, final byte[] tvalue)
        throws IOException
    {
        final byte[] key = inverseTransformKey(tkey);

        if (key == null) {
            if (tvalue == null) {
                return null;
            }
            throw fail();
        }

        return mTransformer.transformValue
            (mSource.exchange(txn, key, mTransformer.inverseTransformValue(tvalue, key, tkey)),
             key, tkey);
    }

    @Override
    public boolean replace(final Transaction txn, final byte[] tkey, final byte[] tvalue)
        throws IOException
    {
        final byte[] key = inverseTransformKey(tkey);

        if (key == null) {
            return false;
        }

        final byte[] value = mTransformer.inverseTransformValue(tvalue, key, tkey);

        if (value == null) {
            return mSource.delete(txn, key);
        } else if (tvalue != null) {
            return mSource.replace(txn, key, value);
        } else {
            return mSource.update(txn, key, value);
        }
    }

    @Override
    public boolean update(final Transaction txn, final byte[] tkey, final byte[] tvalue)
        throws IOException
    {
        final byte[] key = inverseTransformKey(tkey);

        if (key == null) {
            if (tvalue != null) {
                return false;
            }
            throw fail();
        }

        final byte[] value = mTransformer.inverseTransformValue(tvalue, key, tkey);

        return mSource.update(txn, key, value);
    }

    @Override
    public boolean update(final Transaction txn, final byte[] tkey,
                          final byte[] oldTValue, final byte[] newTValue)
        throws IOException
    {
        final byte[] key = inverseTransformKey(tkey);

        if (key == null) {
            if (oldTValue == null) {
                if (newTValue == null) {
                    return true;
                }
                throw fail();
            }
            return false;
        }

        final byte[] oldValue = mTransformer.inverseTransformValue(oldTValue, key, tkey);
        final byte[] newValue = mTransformer.inverseTransformValue(newTValue, key, tkey);

        return mSource.update(txn, key, oldValue, newValue);
    }

    @Override
    public LockResult touch(Transaction txn, byte[] tkey) throws LockFailureException {
        byte[] key = inverseTransformKey(tkey);
        return key == null ? LockResult.UNOWNED : mSource.touch(txn, key);
    }

    @Override
    public LockResult tryLockShared(Transaction txn, byte[] tkey, long nanosTimeout)
        throws LockFailureException, ViewConstraintException
    {
        byte[] key = inverseTransformKey(tkey);
        if (key != null) {
            return mSource.tryLockShared(txn, key, nanosTimeout);
        }
        throw fail();
    }

    @Override
    public LockResult lockShared(Transaction txn, byte[] tkey)
        throws LockFailureException, ViewConstraintException
    {
        byte[] key = inverseTransformKey(tkey);
        if (key != null) {
            return mSource.lockShared(txn, key);
        }
        throw fail();
    }

    @Override
    public LockResult tryLockUpgradable(Transaction txn, byte[] tkey, long nanosTimeout)
        throws LockFailureException, ViewConstraintException
    {
        byte[] key = inverseTransformKey(tkey);
        if (key != null) {
            return mSource.tryLockUpgradable(txn, key, nanosTimeout);
        }
        throw fail();
    }

    @Override
    public LockResult lockUpgradable(Transaction txn, byte[] tkey)
        throws LockFailureException, ViewConstraintException
    {
        byte[] key = inverseTransformKey(tkey);
        if (key != null) {
            return mSource.lockUpgradable(txn, key);
        }
        throw fail();
    }

    @Override
    public LockResult tryLockExclusive(Transaction txn, byte[] tkey, long nanosTimeout)
        throws LockFailureException, ViewConstraintException
    {
        byte[] key = inverseTransformKey(tkey);
        if (key != null) {
            return mSource.tryLockExclusive(txn, key, nanosTimeout);
        }
        throw fail();
    }

    @Override
    public LockResult lockExclusive(Transaction txn, byte[] tkey)
        throws LockFailureException, ViewConstraintException
    {
        byte[] key = inverseTransformKey(tkey);
        if (key != null) {
            return mSource.lockExclusive(txn, key);
        }
        throw fail();
    }

    @Override
    public LockResult lockCheck(Transaction txn, byte[] tkey)
        throws ViewConstraintException
    {
        byte[] key = inverseTransformKey(tkey);
        if (key != null) {
            return mSource.lockCheck(txn, key);
        }
        throw fail();
    }

    @Override
    public View viewGe(byte[] tkey) {
        byte[] key = inverseTransformKey(tkey);
        if (key == null) {
            key = mTransformer.inverseTransformKeyGt(tkey);
            if (key == null) {
                return nonView();
            }
        }
        return new TransformedView(mSource.viewGe(key), mTransformer);
    }

    @Override
    public View viewGt(byte[] tkey) {
        View subView;
        byte[] key = inverseTransformKey(tkey);
        if (key == null) {
            key = mTransformer.inverseTransformKeyGt(tkey);
            if (key == null) {
                return nonView();
            }
            subView = mSource.viewGe(key);
        } else {
            subView = mSource.viewGt(key);
        }
        return new TransformedView(subView, mTransformer);
    }

    @Override
    public View viewLe(byte[] tkey) {
        byte[] key = inverseTransformKey(tkey);
        if (key == null) {
            key = mTransformer.inverseTransformKeyLt(tkey);
            if (key == null) {
                return nonView();
            }
        }
        return new TransformedView(mSource.viewLe(key), mTransformer);
    }

    @Override
    public View viewLt(byte[] tkey) {
        View subView;
        byte[] key = inverseTransformKey(tkey);
        if (key == null) {
            key = mTransformer.inverseTransformKeyLt(tkey);
            if (key == null) {
                return nonView();
            }
            subView = mSource.viewLe(key);
        } else {
            subView = mSource.viewLt(key);
        }
        return new TransformedView(subView, mTransformer);
    }

    @Override
    public View viewPrefix(byte[] tprefix, int trim) {
        SubView.prefixCheck(tprefix, trim);

        final byte[] key = inverseTransformKey(tprefix);

        byte[] lowKey = key;
        if (lowKey == null) {
            lowKey = mTransformer.inverseTransformKeyGt(tprefix);
            if (lowKey == null) {
                return nonView();
            }
        }

        View subView = mSource.viewGe(lowKey);

        byte[] highTKey = tprefix.clone();
        if (Utils.increment(highTKey, 0, highTKey.length)) {
            byte[] highKey = inverseTransformKey(highTKey);
            if (highKey == null) {
                highKey = mTransformer.inverseTransformKeyLt(highTKey);
                if (highKey == null) {
                    return nonView();
                }
                subView = subView.viewLe(highKey);
            } else {
                subView = subView.viewLt(highKey);
            }
        }

        View view = new TransformedView(subView, mTransformer);

        if (trim > 0) {
            view = new TrimmedView(view, tprefix, trim);
        }

        return view;
    }

    @Override
    public View viewTransformed(Transformer transformer) {
        // Note: Could chain the transformers together, but it can be tricky to get right.
        return apply(this, transformer);
    }

    @Override
    public View viewReverse() {
        return new ReverseView(this);
    }

    @Override
    public View viewUnmodifiable() {
        return UnmodifiableView.apply(this);
    }

    @Override
    public boolean isUnmodifiable() {
        return mSource.isUnmodifiable();
    }

    @Override
    public boolean isModifyAtomic() {
        return mSource.isModifyAtomic();
    }

    private byte[] inverseTransformKey(final byte[] tkey) {
        Utils.keyCheck(tkey);
        return mTransformer.inverseTransformKey(tkey);
    }

    private View nonView() {
        return new TransformedView
            (new BoundedView(mSource, Utils.EMPTY_BYTES, Utils.EMPTY_BYTES,
                             BoundedView.START_EXCLUSIVE | BoundedView.END_EXCLUSIVE),
             mTransformer);
    }

    static ViewConstraintException fail() {
        return new ViewConstraintException("Unsupported key");
    }
}
