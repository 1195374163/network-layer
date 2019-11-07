package network;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.GenericFutureListener;
import network.messaging.NetworkMessage;
import network.pipeline.MessageDecoder;
import network.pipeline.MessageEncoder;
import network.pipeline.OutExceptionHandler;
import network.pipeline.OutHandshakeHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class PeerOutConnection extends ChannelInitializer<SocketChannel> implements GenericFutureListener<ChannelFuture> {

    private static final Logger logger = LogManager.getLogger(PeerOutConnection.class);

    private EventLoop loop;
    // Only change in event loop! ----- START
    private volatile Status status;
    private int reconnectAttempts;
    private boolean outsideNodeUp;
    private Channel channel;
    private boolean markForDisconnect;
    // Only change in event loop! ------ END

    private final Host peerHost, myHost;
    private final Queue<NetworkMessage> messageLog; //Concurrent
    private final Bootstrap clientBootstrap;

    private Map<Channel, NetworkMessage> transientChannels;

    private Map<Short, ISerializer> serializers;
    private Set<INodeListener> nodeListeners;

    private NetworkConfiguration config;

    enum Status {DISCONNECTED, ACTIVE, HANDSHAKING, RETRYING}


    PeerOutConnection(Host peerHost, Host myHost, Bootstrap bootstrap, Set<INodeListener> nodeListeners,
                      Map<Short, ISerializer> serializers, NetworkConfiguration config, EventLoop loop) {
        this.peerHost = peerHost;
        this.myHost = myHost;
        this.nodeListeners = nodeListeners;
        this.serializers = serializers;
        this.config = config;
        this.loop = loop;

        this.status = Status.DISCONNECTED;
        this.channel = null;
        this.transientChannels = new ConcurrentHashMap<>();
        this.reconnectAttempts = 0;
        this.clientBootstrap = bootstrap.clone();
        this.clientBootstrap.remoteAddress(peerHost.getAddress(), peerHost.getPort());
        this.clientBootstrap.handler(this);
        this.clientBootstrap.group(loop);
        this.outsideNodeUp = false;

        this.messageLog = new ConcurrentLinkedQueue<>();
    }

    //Concurrent - Adds event to loop
    void connect() {
        loop.execute(() -> {
            if (status == Status.DISCONNECTED) {
                markForDisconnect = false;
                logger.debug("Connecting to " + peerHost);
                status = Status.RETRYING;
                reconnect();
            }
        });
    }

    //Call from event loop only!
    private void reconnect() {
        if (status == Status.DISCONNECTED)
            return;
        assert loop.inEventLoop();
        assert status == Status.RETRYING;
        reconnectAttempts++;
        if (channel != null && channel.isOpen())
            throw new AssertionError("Channel open in reconnect: " + peerHost);
        channel = clientBootstrap.attr(NetworkService.TRANSIENT_KEY, false).connect().channel();
        channel.closeFuture().addListener(this);
    }

    // inEventLoop!
    public void channelActiveCallback(Channel c) {
        assert loop.inEventLoop();
        if (c.attr(NetworkService.TRANSIENT_KEY).get())
            return; //Ignore transient channel TODO could just not add listener
        if (status != Status.RETRYING || c != channel)
            throw new AssertionError("Channel active without being in disconnected state: " + peerHost);
        status = Status.HANDSHAKING;
    }

    // inEventLoop!
    public void handshakeCompletedCallback(Channel c) {
        assert loop.inEventLoop();
        if (c.attr(NetworkService.TRANSIENT_KEY).get()) {
            NetworkMessage networkMessage = transientChannels.remove(c);
            assert networkMessage != null;
            logger.debug("Side channel handshake completed to " + peerHost +". Writing: " + networkMessage);
            c.writeAndFlush(networkMessage);
            return;
        }

        if (status != Status.HANDSHAKING || c != channel)
            throw new AssertionError("Handshake completed without being in handshake state: " + peerHost);
        status = Status.ACTIVE;

        logger.debug("Handshake completed to: " + c.remoteAddress());
        writeMessageLog();

        if (!outsideNodeUp) {
            outsideNodeUp = true;
            nodeListeners.forEach(l -> l.nodeUp(peerHost));
        } else {
            logger.warn("Node connection reestablished: " + peerHost);
            nodeListeners.forEach(l -> l.nodeConnectionReestablished(peerHost));
        }
        reconnectAttempts = 0;

        if(markForDisconnect){
            logger.debug("Connection to " + peerHost + " was marked for disconnection, disconnecting.");
            status = Status.DISCONNECTED;
            channel.close();
        }
    }

    // Call from event loop only!
    private void writeMessageLog() {
        assert loop.inEventLoop();
        if (status == Status.DISCONNECTED) {
            logger.error("Writing message " + messageLog.poll() + " to disconnected channel " + peerHost + ". Probably forgot to call addNetworkPeer");
            return;
        }
        if(status != Status.ACTIVE) return;

        int count = 0;
        NetworkMessage msg;
        while (channel.isActive() && (msg = messageLog.poll()) != null) {
            logger.debug("Writing " + msg + " to outChannel of " + peerHost);
            channel.write(msg);
            count++;
        }
        if (count > 0)
            channel.flush();
    }


    //Channel Close - inEventLoop!
    @Override
    public void operationComplete(ChannelFuture future) {
        assert loop.inEventLoop();
        if (future != channel.closeFuture())
            throw new AssertionError("Future called for not current channel: " + peerHost);
        if (status == Status.DISCONNECTED) return;

        if (reconnectAttempts == config.RECONNECT_ATTEMPTS_BEFORE_DOWN) {
            logger.debug("Connection to " + peerHost + " failed after max " + reconnectAttempts + " retries, will continue trying...");
            if(outsideNodeUp) {
                nodeListeners.forEach(n -> n.nodeDown(peerHost));
                outsideNodeUp = false;
            }
            if(markForDisconnect){
                //TODO kill if marked
                logger.debug("Connection to " + peerHost + " was marked for disconnection, disconnecting.");
            }
        }

        status = Status.RETRYING;
        loop.schedule(this::reconnect, reconnectAttempts >= config.RECONNECT_ATTEMPTS_BEFORE_DOWN ?
                config.RECONNECT_INTERVAL_AFTER_DOWN_MILLIS :
                config.RECONNECT_INTERVAL_BEFORE_DOWN_MILLIS, TimeUnit.MILLISECONDS);
    }

    //Concurrent - Adds event to loop
    void disconnect() {
        loop.execute(() -> {
            if(status == Status.ACTIVE) {
                logger.debug("Disconnecting channel to: " + peerHost + ", status was " + status);
                status = Status.DISCONNECTED;
                channel.close();
            } else {
                logger.debug("Marking channel for disconnection: " + peerHost + ", status is " + status);
                markForDisconnect = true;
            }
        });
    }

    //Concurrent - Adds event to loop
    void forceDisconnect() {
        loop.execute(() -> {
            if (status != Status.DISCONNECTED) {
                logger.debug("Force disconnecting channel to: " + peerHost + ", status was " + status);
                status = Status.DISCONNECTED;
                channel.close();
            }
        });
    }

    //Concurrent - Adds event to loop
    void sendMessage(NetworkMessage msg) {
        logger.debug("Adding " + msg + " to msgOutQueue of " + peerHost);
        //TODO should we skip messageLog and just send it here? (inside the loop)
        messageLog.add(msg);
        loop.execute(this::writeMessageLog);
    }

    //Concurrent - Adds event to loop
    void sendMessageTransientChannel(NetworkMessage msg) {
        loop.execute(() -> {
            Channel transientChannel = clientBootstrap.attr(NetworkService.TRANSIENT_KEY, true).connect().channel();
            transientChannels.put(transientChannel, msg);
        });
    }

    Status getStatus() {
        return status;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast("ReadTimeoutHandler",
                              new ReadTimeoutHandler(config.IDLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        ch.pipeline().addLast("MessageDecoder", new MessageDecoder(serializers));
        ch.pipeline().addLast("MessageEncoder", new MessageEncoder(serializers));
        ch.pipeline().addLast("OutHandshakeHandler", new OutHandshakeHandler(myHost, this));
        ch.pipeline().addLast("OutEventExceptionHandler", new OutExceptionHandler());
    }

}