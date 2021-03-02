package org.eclipse.jetty.http3.server;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;

import org.eclipse.jetty.http3.common.QuicConnection;
import org.eclipse.jetty.http3.common.QuicConnectionManager;
import org.eclipse.jetty.http3.quic.QuicheConfig;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Server;

public class QuicConnector extends AbstractNetworkConnector
{
    // TODO this protocols field is to be able to respond to http/0.9 clients, eventually get rid of that
    private final String[] protocols;
    private QuicheConfig quicheConfig;
    private SSLKeyPair keyPair;
    private QuicConnectionManager quicConnectionManager;

    public QuicConnector(Server server, String... protocols)
    {
        super(server, null, null, null, 0);
        this.protocols = protocols;
    }

    public SSLKeyPair getKeyPair()
    {
        return keyPair;
    }

    public void setKeyPair(SSLKeyPair keyPair)
    {
        this.keyPair = keyPair;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        quicConnectionManager.start();
    }

    @Override
    public void open() throws IOException
    {
        if (quicConnectionManager != null)
            return;

        if (keyPair == null)
            throw new IllegalStateException("Missing key pair");

        File[] files;
        try
        {
            files = keyPair.export(new File(System.getProperty("java.io.tmpdir")));
        }
        catch (Exception e)
        {
            throw new IOException("Error exporting key pair", e);
        }

        quicheConfig = new QuicheConfig();
        quicheConfig.setPrivKeyPemPath(files[0].getPath());
        quicheConfig.setCertChainPemPath(files[1].getPath());
        quicheConfig.setVerifyPeer(false);
        quicheConfig.setMaxIdleTimeout(5000L);
        quicheConfig.setInitialMaxData(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiLocal(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiRemote(10000000L);
        quicheConfig.setInitialMaxStreamDataUni(10000000L);
        quicheConfig.setInitialMaxStreamsBidi(100L);
        quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.RENO);
        if (protocols.length == 0)
            quicheConfig.setApplicationProtos(getProtocols().toArray(new String[0]));
        else
            quicheConfig.setApplicationProtos(protocols);

        quicConnectionManager = new QuicServerConnectionManager(this, getExecutor(), getScheduler(), getByteBufferPool(), this::createQuicStreamEndPoint, quicheConfig);
        quicConnectionManager.getChannel().bind(bindAddress());
    }

    @Override
    public void close()
    {
        if (quicConnectionManager == null)
            return;

        quicConnectionManager.close();
        quicConnectionManager = null;
        quicheConfig = null;
    }

    public QuicServerStreamEndPoint createQuicStreamEndPoint(QuicConnection quicConnection, long streamId)
    {
        QuicServerStreamEndPoint endPoint = new QuicServerStreamEndPoint(getScheduler(), quicConnection, streamId);
        String negotiatedProtocol = quicConnection.getNegotiatedProtocol();
        ConnectionFactory connectionFactory = getConnectionFactory(negotiatedProtocol);
        if (connectionFactory == null)
        {
            if (Arrays.asList(protocols).contains(negotiatedProtocol))
                connectionFactory = getDefaultConnectionFactory();
            else
                throw new RuntimeException("No configured connection factory can handle protocol '" + negotiatedProtocol + "'");
        }
        Connection connection = connectionFactory.newConnection(this, endPoint);
        endPoint.setConnection(connection);
        connection.onOpen();
        return endPoint;
    }

    private SocketAddress bindAddress()
    {
        String host = getHost();
        if (host == null)
            host = "0.0.0.0";
        int port = getPort();
        if (port < 0)
            throw new IllegalArgumentException("port cannot be negative: " + port);
        return new InetSocketAddress(host, port);
    }

    @Override
    public Object getTransport()
    {
        return quicConnectionManager == null ? null : quicConnectionManager.getChannel();
    }

    @Override
    public boolean isOpen()
    {
        DatagramChannel channel = quicConnectionManager == null ? null : quicConnectionManager.getChannel();
        return channel != null && channel.isOpen();
    }

    @Override
    protected void accept(int acceptorID)
    {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has its own accepting mechanism");
    }
}
