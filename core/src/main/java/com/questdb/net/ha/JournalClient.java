/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2016 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.net.ha;

import com.questdb.JournalKey;
import com.questdb.JournalWriter;
import com.questdb.PartitionBy;
import com.questdb.ex.IncompatibleJournalException;
import com.questdb.ex.JournalException;
import com.questdb.ex.JournalNetworkException;
import com.questdb.ex.JournalRuntimeException;
import com.questdb.factory.JournalWriterFactory;
import com.questdb.factory.configuration.JournalMetadata;
import com.questdb.factory.configuration.JournalStructure;
import com.questdb.log.Log;
import com.questdb.log.LogFactory;
import com.questdb.misc.Chars;
import com.questdb.misc.Files;
import com.questdb.mp.MPSequence;
import com.questdb.mp.RingQueue;
import com.questdb.mp.SCSequence;
import com.questdb.mp.Sequence;
import com.questdb.net.SecureSocketChannel;
import com.questdb.net.SslConfig;
import com.questdb.net.StatsCollectingReadableByteChannel;
import com.questdb.net.ha.auth.AuthenticationConfigException;
import com.questdb.net.ha.auth.AuthenticationProviderException;
import com.questdb.net.ha.auth.CredentialProvider;
import com.questdb.net.ha.auth.UnauthorizedException;
import com.questdb.net.ha.comsumer.HugeBufferConsumer;
import com.questdb.net.ha.comsumer.JournalDeltaConsumer;
import com.questdb.net.ha.config.ClientConfig;
import com.questdb.net.ha.model.Command;
import com.questdb.net.ha.model.IndexedJournal;
import com.questdb.net.ha.model.IndexedJournalKey;
import com.questdb.net.ha.producer.JournalClientStateProducer;
import com.questdb.net.ha.protocol.CommandConsumer;
import com.questdb.net.ha.protocol.CommandProducer;
import com.questdb.net.ha.protocol.Version;
import com.questdb.net.ha.protocol.commands.*;
import com.questdb.std.CharSequenceHashSet;
import com.questdb.std.IntList;
import com.questdb.std.ObjList;
import com.questdb.std.ObjectFactory;
import com.questdb.store.TxListener;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class JournalClient {
    public static final int EVT_NONE = 0;
    public static final int EVT_RUNNING = 2;
    public static final int EVT_CLIENT_HALT = 4;
    public static final int EVT_CLIENT_EXCEPTION = 8;
    public static final int EVT_INCOMPATIBLE_JOURNAL = 16;
    public static final int EVT_CONNECTED = 32;
    public static final int EVT_AUTH_CONFIG_ERROR = 64;
    public static final int EVT_SERVER_ERROR = 1;
    public static final int EVT_AUTH_ERROR = 128;
    public static final int EVT_TERMINATED = 256;

    private static final AtomicInteger counter = new AtomicInteger(0);
    private static final Log LOG = LogFactory.getLog(JournalClient.class);
    private final ObjList<JournalWriter> writers = new ObjList<>();
    private final ObjList<JournalWriter> writersToClose = new ObjList<>();
    private final ObjList<JournalDeltaConsumer> deltaConsumers = new ObjList<>();
    private final IntList statusSentList = new IntList();
    private final CharSequenceHashSet subscribedJournals = new CharSequenceHashSet();
    private final JournalWriterFactory factory;
    private final CommandProducer commandProducer = new CommandProducer();
    private final CommandConsumer commandConsumer = new CommandConsumer();
    private final ObjList<SubscriptionHolder> subscriptions = new ObjList<>();
    private final SetKeyRequestProducer setKeyRequestProducer = new SetKeyRequestProducer();
    private final CharSequenceResponseConsumer charSequenceResponseConsumer = new CharSequenceResponseConsumer();
    private final JournalClientStateProducer journalClientStateProducer = new JournalClientStateProducer();
    private final IntResponseConsumer intResponseConsumer = new IntResponseConsumer();
    private final IntResponseProducer intResponseProducer = new IntResponseProducer();
    private final ByteArrayResponseProducer byteArrayResponseProducer = new ByteArrayResponseProducer();
    private final ClientConfig config;
    private final CredentialProvider credentialProvider;
    private final RingQueue<SubscriptionHolder> subscriptionQueue = new RingQueue<>(SubscriptionHolder.FACTORY, 64);
    private final Sequence subscriptionPubSequence = new MPSequence(subscriptionQueue.getCapacity());
    private final Sequence subscriptionSubSequence = new SCSequence();
    private final CountDownLatch haltLatch = new CountDownLatch(1);
    private final Callback callback;
    private ByteChannel channel;
    private StatsCollectingReadableByteChannel statsChannel;
    private volatile boolean running = false;

    public JournalClient(JournalWriterFactory factory) {
        this(factory, null);
    }

    public JournalClient(JournalWriterFactory factory, CredentialProvider credentialProvider) {
        this(new ClientConfig(), factory, credentialProvider, null);
    }

    public JournalClient(ClientConfig config, JournalWriterFactory factory) {
        this(config, factory, null, null);
    }

    public JournalClient(ClientConfig config, JournalWriterFactory factory, CredentialProvider credentialProvider) {
        this(config, factory, credentialProvider, null);
    }

    public JournalClient(ClientConfig config, JournalWriterFactory factory, CredentialProvider credentialProvider, Callback callback) {
        this.config = config;
        this.factory = factory;
        this.credentialProvider = credentialProvider;
        this.callback = callback;
        subscriptionPubSequence.then(subscriptionSubSequence).then(subscriptionPubSequence);
    }

    public void halt() {
        long cursor = subscriptionPubSequence.next();
        if (cursor < 0) {
            throw new JournalRuntimeException("start client before subscribing");
        }

        SubscriptionHolder h = subscriptionQueue.get(cursor);
        h.type = 1; // todo: make named constant
        subscriptionPubSequence.done(cursor);

        try {
            if (!haltLatch.await(5, TimeUnit.SECONDS)) {
                closeChannel();
            }
        } catch (InterruptedException e) {
            LOG.error().$("Got interrupted while halting journal client").$();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        new Handler().start();
    }

    public <T> void subscribe(Class<T> clazz) {
        subscribe(clazz, (TxListener) null);
    }

    @SuppressWarnings("unused")
    public <T> void subscribe(Class<T> clazz, String location) {
        subscribe(clazz, location, (TxListener) null);
    }

    public <T> void subscribe(Class<T> clazz, String remote, String local) {
        subscribe(clazz, remote, local, null);
    }

    public <T> void subscribe(Class<T> clazz, String remote, String local, TxListener txListener) {
        subscribe(new JournalKey<>(clazz, remote), new JournalKey<>(clazz, local), txListener);
    }

    public <T> void subscribe(Class<T> clazz, String remote, String local, int recordHint) {
        subscribe(clazz, remote, local, recordHint, null);
    }

    public <T> void subscribe(Class<T> clazz, String remote, String local, int recordHint, TxListener txListener) {
        subscribe(new JournalKey<>(clazz, remote, PartitionBy.DEFAULT, recordHint), new JournalKey<>(clazz, local, PartitionBy.DEFAULT, recordHint), txListener);
    }

    public <T> void subscribe(JournalKey<T> remoteKey, JournalWriter<T> writer, TxListener txListener) {
        subscribe(remoteKey, writer.getKey(), txListener, writer);
    }

    public void subscribe(JournalKey remote, JournalKey local, TxListener txListener) {
        subscribe(remote, local, txListener, null);
    }

    private void checkAck() throws JournalNetworkException {
        charSequenceResponseConsumer.read(channel);
        CharSequence value = charSequenceResponseConsumer.getValue();
        fail(Chars.equals("OK", value), value);
    }

    private void checkAuthAndSendCredential() throws
            JournalNetworkException,
            AuthenticationProviderException,
            UnauthorizedException,
            AuthenticationConfigException {

        commandProducer.write(channel, Command.HANDSHAKE_COMPLETE);
        CharSequence cs = readString();
        if (Chars.equals("AUTH", cs)) {
            if (credentialProvider == null) {
                throw new AuthenticationConfigException();
            }
            commandProducer.write(channel, Command.AUTHORIZATION);
            byteArrayResponseProducer.write(channel, getToken());
            CharSequence response = readString();
            if (!Chars.equals("OK", response)) {
                LOG.error().$(response).$();
                throw new UnauthorizedException();
            }
        } else if (!Chars.equals("OK", cs)) {
            fail(true, "Unknown server response");
        }
    }

    private void close0() {
        for (int i = 0, sz = writersToClose.size(); i < sz; i++) {
            writersToClose.getQuick(i).close();
        }
        writersToClose.clear();
        writers.clear();

        for (int i = 0, k = deltaConsumers.size(); i < k; i++) {
            deltaConsumers.getQuick(i).free();
        }
        deltaConsumers.clear();

        commandConsumer.free();
        charSequenceResponseConsumer.free();
        intResponseConsumer.free();
        statusSentList.clear();
    }

    private void closeChannel() {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (Throwable e) {
                LOG.error().$("Error closing channel").$(e).$();
            }
        }
    }

    private void fail(boolean condition, CharSequence message) throws JournalNetworkException {
        if (!condition) {
            throw new JournalNetworkException(message.toString());
        }
    }

    private byte[] getToken() throws JournalNetworkException, AuthenticationProviderException {
        try {
            return credentialProvider.createToken();
        } catch (Throwable e) {
            LOG.error().$("Error in credential provider: ").$(e).$();
            throw new AuthenticationProviderException();
        }
    }

    private void openChannel() throws JournalNetworkException {
        if (this.channel == null || !this.channel.isOpen()) {
            SocketChannel channel = config.openSocketChannel();
            try {
                statsChannel = new StatsCollectingReadableByteChannel(channel.getRemoteAddress());
            } catch (IOException e) {
                throw new JournalNetworkException("Cannot get remote address", e);
            }

            SslConfig sslConfig = config.getSslConfig();
            if (sslConfig.isSecure()) {
                this.channel = new SecureSocketChannel(channel, sslConfig);
            } else {
                this.channel = channel;
            }
        }
    }

    private CharSequence readString() throws JournalNetworkException {
        charSequenceResponseConsumer.read(channel);
        return charSequenceResponseConsumer.getValue();
    }

    private void resubscribe() throws JournalNetworkException {
        for (int i = 0, n = subscriptions.size(); i < n; i++) {
            SubscriptionHolder h = subscriptions.get(i);
            subscribeOne(i, h, h.local.derivedLocation(), false);
        }
    }

    private void sendDisconnect() throws JournalNetworkException {
        commandProducer.write(channel, Command.CLIENT_DISCONNECT);
    }

    private void sendProtocolVersion() throws JournalNetworkException {
        commandProducer.write(channel, Command.PROTOCOL_VERSION);
        intResponseProducer.write(channel, Version.PROTOCOL_VERSION);
        checkAck();
    }

    private void sendReady() throws JournalNetworkException {
        commandProducer.write(channel, Command.CLIENT_READY_CMD);
        LOG.debug().$("Client ready: ").$(channel).$();
    }

    private void sendState() throws JournalNetworkException {
        for (int i = 0, sz = writers.size(); i < sz; i++) {
            if (statusSentList.get(i) == 0) {
                commandProducer.write(channel, Command.DELTA_REQUEST_CMD);
                journalClientStateProducer.write(channel, new IndexedJournal(i, writers.getQuick(i)));
                checkAck();
                statusSentList.setQuick(i, 1);
            }
        }
    }

    private void subscribe(JournalKey remote, JournalKey local, TxListener txListener, JournalWriter writer) {
        long cursor = subscriptionPubSequence.next();
        if (cursor < 0) {
            throw new JournalRuntimeException("start client before subscribing");
        }

        SubscriptionHolder h = subscriptionQueue.get(cursor);
        h.type = 0;
        h.remote = remote;
        h.local = local;
        h.listener = txListener;
        h.writer = writer;
        subscriptionPubSequence.done(cursor);
    }

    /**
     * Configures client to subscribe given journal class when client is started
     * and connected. Journals of given class at default location are opened on
     * both client and server. Optionally provided listener will be called back
     * when client journal is committed. Listener is called synchronously with
     * client thread, so callback implementation must be fast.
     *
     * @param clazz      journal class on both client and server
     * @param txListener callback listener to get receive commit notifications.
     * @param <T>        generics to comply with Journal API.
     */
    private <T> void subscribe(Class<T> clazz, TxListener txListener) {
        subscribe(new JournalKey<>(clazz), new JournalKey<>(clazz), txListener);
    }

    private <T> void subscribe(Class<T> clazz, String location, TxListener txListener) {
        subscribe(new JournalKey<>(clazz, location), new JournalKey<>(clazz, location), txListener);
    }

    private void subscribeOne(int index, SubscriptionHolder holder, String loc, boolean newSubscription) throws JournalNetworkException {

        if (newSubscription) {
            SubscriptionHolder sub = new SubscriptionHolder();
            sub.local = holder.local;
            sub.remote = holder.remote;
            sub.listener = holder.listener;
            sub.writer = holder.writer;
            subscriptions.add(sub);
        }

        commandProducer.write(channel, Command.SET_KEY_CMD);
        setKeyRequestProducer.write(channel, new IndexedJournalKey(index, holder.remote));
        checkAck();

        //todo: do we really have to use file here?
        JournalMetadata metadata;
        File file = Files.makeTempFile();
        try {
            try (HugeBufferConsumer h = new HugeBufferConsumer(file)) {
                h.read(channel);
                metadata = new JournalMetadata(h.getHb());
            } catch (JournalException e) {
                throw new JournalNetworkException(e);
            }
        } finally {
            Files.delete(file);
        }

        try {
            boolean validate = true;
            JournalWriter writer = writers.getQuiet(index);
            if (writer == null) {
                if (holder.writer == null) {
                    writer = factory.writer(new JournalStructure(metadata).location(loc));
                    writersToClose.add(writer);
                    validate = false;
                } else {
                    writer = holder.writer;
                }

                statusSentList.extendAndSet(index, 0);
                deltaConsumers.extendAndSet(index, new JournalDeltaConsumer(writer.setCommitOnClose(false)));
                writers.extendAndSet(index, writer);
                writer.setTxListener(holder.listener);
            } else {
                statusSentList.setQuick(index, 0);
            }

            if (validate && !metadata.isCompatible(writer.getMetadata(), false)) {
                LOG.error().$("Journal ").$(holder.local.getLocation()).$(" is not compatible with ").$(holder.remote.getLocation()).$("(remote)").$();
                // todo: unsubscribe
                return;
            }

            commandProducer.write(channel, Command.DELTA_REQUEST_CMD);
            journalClientStateProducer.write(channel, new IndexedJournal(index, writer));
            checkAck();
            statusSentList.setQuick(index, 1);

            LOG.info().$("Subscribed ").$(loc).$(" to ").$(holder.remote.getLocation()).$("(remote)").$();
        } catch (JournalException e) {
            throw new JournalNetworkException(e);
        }
    }

    public interface Callback {
        void onEvent(int evt);
    }

    private static class SubscriptionHolder {
        private static final ObjectFactory<SubscriptionHolder> FACTORY = new ObjectFactory<SubscriptionHolder>() {
            @Override
            public SubscriptionHolder newInstance() {
                return new SubscriptionHolder();
            }
        };

        private int type = 0;
        private JournalKey remote;
        private JournalKey local;
        private TxListener listener;
        private JournalWriter writer;
    }

    private final class Handler extends Thread {

        public boolean isRunning() throws JournalNetworkException {
            long cursor = subscriptionSubSequence.next();
            if (cursor < 0) {
                return true;
            }

            long available = subscriptionSubSequence.available();
            while (cursor < available) {
                SubscriptionHolder holder = subscriptionQueue.get(cursor++);
                if (holder.type == 1) {
                    return false;
                }
            }

            return true;
        }

        public boolean processSubscriptionQueue() throws JournalNetworkException {
            long cursor = subscriptionSubSequence.next();
            if (cursor < 0) {
                return true;
            }

            long available = subscriptionSubSequence.available();

            int i = writers.size();

            while (cursor < available) {

                SubscriptionHolder holder = subscriptionQueue.get(cursor++);

                if (holder.type == 0) {
                    String loc = holder.local.derivedLocation();

                    if (subscribedJournals.add(loc)) {
                        subscribeOne(i++, holder, loc, true);
                    } else {
                        holder.listener.onError();
                        LOG.error().$("Already subscribed ").$(loc).$();
                    }
                } else if (holder.type == 1) {
                    return false;
                }
            }

            subscriptionSubSequence.done(available - 1);
            return true;
        }

        @Override
        public void run() {

            running = true;
            notifyCallback(EVT_RUNNING);
            int event = EVT_NONE;
            boolean connected = false;

            try {
                while (true) {
                    // reconnect code
                    if (!connected) {
                        int retryCount = config.getReconnectPolicy().getRetryCount();
                        int loginRetryCount = config.getReconnectPolicy().getLoginRetryCount();
                        do {
                            try {
                                closeChannel();

                                // if we cannot connect - move on to retry
                                try {
                                    openChannel();
                                    counter.incrementAndGet();
                                } catch (JournalNetworkException e) {
                                    if (retryCount-- > 0) {
                                        continue;
                                    } else {
                                        break;
                                    }
                                }

                                sendProtocolVersion();
                                checkAuthAndSendCredential();
                                resubscribe();
                                sendReady();
                                connected = true;
                                notifyCallback(EVT_CONNECTED);
                            } catch (UnauthorizedException e) {
                                notifyCallback(EVT_AUTH_ERROR);
                                loginRetryCount--;
                            } catch (AuthenticationConfigException | AuthenticationProviderException e) {
                                closeChannel();
                                close0();
                                notifyCallback(EVT_AUTH_CONFIG_ERROR);
                                return;
                            } catch (JournalNetworkException e) {
                                LOG.info().$(e.getMessage()).$();
                                closeChannel();
                            }

                            if (!connected && retryCount-- > 0 && loginRetryCount > 0) {
                                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(config.getReconnectPolicy().getSleepBetweenRetriesMillis()));
                                LOG.info().$("Retrying reconnect ... [").$(retryCount + 1).$(']').$();
                            } else {
                                break;
                            }
                        } while (true);

                        if (!connected && (retryCount == 0 || loginRetryCount == 0)) {
                            event = EVT_SERVER_ERROR;
                        }
                    }


                    // protocol code

                    try {
                        if (connected && channel.isOpen() && isRunning()) {
                            commandConsumer.read(channel);
                            byte cmd = commandConsumer.getCommand();
                            switch (cmd) {
                                case Command.JOURNAL_DELTA_CMD:
                                    statsChannel.setDelegate(channel);
                                    int index = intResponseConsumer.getValue(statsChannel);
                                    deltaConsumers.getQuick(index).read(statsChannel);
                                    statusSentList.set(index, 0);
                                    statsChannel.logStats();
                                    break;
                                case Command.SERVER_READY_CMD:
                                    sendState();
                                    sendReady();
                                    break;
                                case Command.SERVER_HEARTBEAT:
                                    if (processSubscriptionQueue()) {
                                        sendReady();
                                    } else {
                                        event = EVT_CLIENT_HALT;
                                    }
                                    break;
                                case Command.SERVER_SHUTDOWN:
                                    connected = false;
                                    break;
                                default:
                                    LOG.info().$("Unknown command: ").$(cmd).$();
                                    break;
                            }
                        } else if (event == EVT_NONE) {
                            event = EVT_CLIENT_HALT;
                        }
                    } catch (IncompatibleJournalException e) {
                        // unsubscribe journal
                        LOG.error().$(e.getMessage()).$();
                        event = EVT_INCOMPATIBLE_JOURNAL;
                    } catch (JournalNetworkException e) {
                        LOG.error().$("Network error. Server died?").$();
                        LOG.debug().$("Network error details: ").$(e).$();
                        connected = false;
                    } catch (Throwable e) {
                        LOG.error().$("Unhandled exception in client").$(e).$();
                        event = EVT_CLIENT_EXCEPTION;
                    }

                    if (event != EVT_NONE) {
                        // client gracefully disconnects
                        if (channel != null && channel.isOpen()) {
                            sendDisconnect();
                        }
                        closeChannel();
                        close0();
                        notifyCallback(event);
                        break;
                    }
                }
            } catch (Throwable e) {
                LOG.error().$("Fatal exception when closing client").$(e).$();
                closeChannel();
                close0();
            } finally {
                running = false;
                notifyCallback(EVT_TERMINATED);
                haltLatch.countDown();
                LOG.info().$("Terminated").$();
            }
        }

        private void notifyCallback(int event) {
            if (callback != null) {
                callback.onEvent(event);
            }
        }
    }
}
