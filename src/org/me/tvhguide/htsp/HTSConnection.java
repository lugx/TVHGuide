/*
 *  Copyright (C) 2011 John Törnblom
 *
 * This file is part of TVHGuide.
 *
 * TVHGuide is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TVHGuide is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TVHGuide.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.me.tvhguide.htsp;

import android.util.Log;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 * @author john-tornblom
 */
public class HTSConnection extends Thread {

    public static final int TIMEOUT_ERROR = 1;
    public static final int CONNECTION_REFUSED_ERROR = 2;
    public static final int CONNECTION_LOST_ERROR = 3;
    public static final int HTS_AUTH_ERROR = 4;
    public static final int HTS_MESSAGE_ERROR = 5;
    private static final String TAG = "HTSPConnection";
    private volatile boolean running;
    private Lock lock;
    private SocketChannel socketChannel;
    private ByteBuffer inBuf;
    private int seq;
    private String clientName;
    private String clientVersion;
    private HTSConnectionListener listener;
    private Map<Integer, HTSResponseHandler> responseHandelers;
    private LinkedList<HTSMessage> messageQueue;
    private boolean auth;
    private Selector selector;

    public HTSConnection(HTSConnectionListener listener, String clientName, String clientVersion) {
        running = false;
        lock = new ReentrantLock();
        inBuf = ByteBuffer.allocateDirect(1024 * 1024);
        inBuf.limit(4);
        responseHandelers = new HashMap<Integer, HTSResponseHandler>();
        messageQueue = new LinkedList<HTSMessage>();

        this.listener = listener;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
    }

    public void setRunning(boolean b) {
        try {
            lock.lock();
            running = false;
        } finally {
            lock.unlock();
        }
    }

    //sychronized, blocking connect
    public void open(String hostname, int port) {
        if (running) {
            return;
        }

        final Object signal = new Object();

        lock.lock();
        try {
            selector = Selector.open();
            socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);
            socketChannel.socket().setKeepAlive(true);
            socketChannel.socket().setSoTimeout(5000);
            socketChannel.register(selector, SelectionKey.OP_CONNECT, signal);
            socketChannel.connect(new InetSocketAddress(hostname, port));

            running = true;
            start();
        } catch (Exception ex) {
            Log.e(TAG, "Can't open connection", ex);
            listener.onError(CONNECTION_REFUSED_ERROR);
            return;
        } finally {
            lock.unlock();
        }

        synchronized (signal) {
            try {
                signal.wait(5000);
                if (socketChannel.isConnectionPending()) {
                    listener.onError(TIMEOUT_ERROR);
                    close();
                }
            } catch (InterruptedException ex) {
            }
        }
    }

    public boolean isConnected() {
        return socketChannel != null
                && socketChannel.isOpen()
                && socketChannel.isConnected()
                && running;
    }

    //sycnronized, blocking auth
    public void authenticate(String username, final String password) {
        if (auth || !running) {
            return;
        }

        auth = false;
        final HTSMessage authMessage = new HTSMessage();
        authMessage.setMethod("enableAsyncMetadata");
        authMessage.putField("username", username);
        final HTSResponseHandler authHandler = new HTSResponseHandler() {

            public void handleResponse(HTSMessage response) {
                auth = response.getInt("noaccess", 0) != 1;
                if (!auth) {
                    listener.onError(HTS_AUTH_ERROR);
                }
                synchronized (authMessage) {
                    authMessage.notify();
                }
            }
        };

        HTSMessage helloMessage = new HTSMessage();
        helloMessage.setMethod("hello");
        helloMessage.putField("clientname", this.clientName);
        helloMessage.putField("clientversion", this.clientVersion);
        helloMessage.putField("htspversion", HTSMessage.HTSP_VERSION);
        helloMessage.putField("username", username);
        sendMessage(helloMessage, new HTSResponseHandler() {

            public void handleResponse(HTSMessage response) {
                MessageDigest md;
                try {
                    md = MessageDigest.getInstance("SHA1");
                    md.update(password.getBytes());
                    md.update(response.getByteArray("challenge"));
                    authMessage.putField("digest", md.digest());
                    sendMessage(authMessage, authHandler);
                } catch (NoSuchAlgorithmException ex) {
                    return;
                }
            }
        });

        synchronized (authMessage) {
            try {
                authMessage.wait(5000);
                if (!auth) {
                    listener.onError(TIMEOUT_ERROR);
                }
                return;
            } catch (InterruptedException ex) {
                return;
            }
        }
    }

    public boolean isAuthenticated() {
        return auth;
    }

    public void sendMessage(HTSMessage message, HTSResponseHandler listener) {
        if (!isConnected()) {
            return;
        }

        lock.lock();
        try {
            seq++;
            message.putField("seq", seq);
            responseHandelers.put(seq, listener);
            socketChannel.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ | SelectionKey.OP_CONNECT);
            messageQueue.add(message);
            selector.wakeup();
        } catch (Exception ex) {
            Log.e(TAG, "Can't transmit message", ex);
            this.listener.onError(ex);
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            responseHandelers.clear();
            messageQueue.clear();
            auth = false;
            running = false;
            socketChannel.register(selector, 0);
            socketChannel.close();
        } catch (Exception ex) {
            Log.e(TAG, "Can't close connection", ex);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void run() {
        while (running) {
            try {
                selector.select(5000);
            } catch (IOException ex) {
                listener.onError(ex);
                running = false;
                continue;
            }

            lock.lock();

            try {
                Iterator it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey selKey = (SelectionKey) it.next();
                    it.remove();
                    processTcpSelectionKey(selKey);
                }

                int ops = SelectionKey.OP_READ;
                if (!messageQueue.isEmpty()) {
                    ops |= SelectionKey.OP_WRITE;
                }
                socketChannel.register(selector, ops);
            } catch (Exception ex) {
                Log.e(TAG, "Can't read message", ex);
                listener.onError(ex);
                running = false;
            } finally {
                lock.unlock();
            }
        }

        close();
    }

    private void processTcpSelectionKey(SelectionKey selKey) throws IOException {
        if (selKey.isConnectable() && selKey.isValid()) {
            SocketChannel sChannel = (SocketChannel) selKey.channel();
            sChannel.finishConnect();
            final Object signal = selKey.attachment();
            synchronized (signal) {
                signal.notify();
            }
            sChannel.register(selector, SelectionKey.OP_READ);
        }

        if (selKey.isReadable() && selKey.isValid()) {
            SocketChannel sChannel = (SocketChannel) selKey.channel();
            int len = sChannel.read(inBuf);
            if (len < 0) {
                throw new IOException("Server went down");
            }

            HTSMessage msg = HTSMessage.parse(inBuf);
            if (msg != null) {
                handleMessage(msg);
            }
        }
        if (selKey.isWritable() && selKey.isValid()) {
            SocketChannel sChannel = (SocketChannel) selKey.channel();
            HTSMessage msg = messageQueue.poll();
            if (msg != null) {
                msg.transmit(sChannel);
            }
        }
    }

    private void handleMessage(HTSMessage msg) {
        if (msg.containsFiled("seq")) {
            int respSeq = msg.getInt("seq");
            HTSResponseHandler handler = responseHandelers.get(respSeq);
            responseHandelers.remove(respSeq);

            if (handler != null) {
                handler.handleResponse(msg);
                return;
            }
        }

        listener.onMessage(msg);
    }
}
