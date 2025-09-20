/*
 *  Copyright (C) 2017 Cojen.org
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

package org.cojen.tupl.repl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;

import java.net.Socket;
import java.net.SocketAddress;

import java.util.Collections;
import java.util.Map;

import org.cojen.tupl.io.Utils;

/**
 * 
 *
 * @author Brian S O'Neill
 */
abstract class SocketSnapshotSender extends OutputStream implements SnapshotSender {
    private final GroupFile mGroupFile;
    private final Socket mSocket;
    private final OutputStream mOut;
    private final Map<String, String> mOptions;

    private static final VarHandle cSendingHandle;

    static {
        try {
            cSendingHandle =
                MethodHandles.lookup().findVarHandle
                (SocketSnapshotSender.class, "mSending", int.class);
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
    }

    private volatile int mSending;

    SocketSnapshotSender(GroupFile groupFile, Socket socket) throws IOException {
        OptionsDecoder dec;
        try {
            dec = new OptionsDecoder(socket.getInputStream());
        } catch (EOFException e) {
            Utils.closeQuietly(socket);
            throw new IOException("Disconnected");
        }

        int encoding = dec.decodeIntLE();
        if (encoding != 0) {
            Utils.closeQuietly(socket);
            throw new IOException("Unknown encoding: " + encoding);
        }

        mGroupFile = groupFile;
        mSocket = socket;
        mOut = socket.getOutputStream();
        mOptions = dec.decodeMap();
    }

    @Override
    public final SocketAddress receiverAddress() {
        return mSocket.getRemoteSocketAddress();
    }

    @Override
    public final Map<String, String> options() {
        return mOptions;
    }

    @Override
    public final OutputStream begin(long length, long position, Map<String, String> options)
        throws IOException
    {
        if (!cSendingHandle.compareAndSet(this, 0, 1)) {
            throw new IllegalStateException("Already began");
        }

        try {
            TermLog termLog = termLogAt(position);
            if (termLog == null) {
                throw new IllegalStateException("Unknown term at position: " + position);
            }

            var enc = new OptionsEncoder();
            enc.encodeIntLE(0); // encoding format
            enc.encodeLongLE(length);
            enc.encodeLongLE(termLog.prevTermAt(position));
            enc.encodeLongLE(termLog.term());
            enc.encodeLongLE(position);
            enc.encodeMap(options == null ? Collections.emptyMap() : options);
            enc.writeTo(this);

            // Write the current group file, which should be up-to-date, for the given log
            // position. The receiver accepts the group file if it's newer than what it already
            // has, bypassing the normal sequence of control messages. This is fine because a
            // new leader isn't expected to generate new data (and perform consensus checks)
            // until all outstanding control messages have been applied.
            mGroupFile.writeTo(this);

            return this;
        } catch (Throwable e) {
            Utils.closeQuietly(this);
            throw e;
        }
    }

    @Override
    public final void write(int b) throws IOException {
        mOut.write(b);
    }

    @Override
    public final void write(byte[] b, int off, int len) throws IOException {
        mOut.write(b, off, len);
    }

    @Override
    public final void flush() throws IOException {
        mOut.flush();
    }

    @Override
    public void close() throws IOException {
        mSocket.close();
    }

    abstract TermLog termLogAt(long position) throws IOException;
}
