/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.server;

import com.datastax.driver.core.Cluster;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import grakn.benchmark.lib.instrumentation.ServerTracing;
import grakn.core.common.config.Config;
import grakn.core.common.config.ConfigKey;
import grakn.core.server.keyspace.KeyspaceManager;
import grakn.core.server.rpc.KeyspaceRequestsHandler;
import grakn.core.server.rpc.KeyspaceService;
import grakn.core.server.rpc.OpenRequest;
import grakn.core.server.rpc.ServerKeyspaceRequestsHandler;
import grakn.core.server.rpc.ServerOpenRequest;
import grakn.core.server.rpc.SessionService;
import grakn.core.server.session.HadoopGraphFactory;
import grakn.core.server.session.JanusGraphFactory;
import grakn.core.server.session.SessionFactory;
import grakn.core.server.util.LockManager;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * This is a factory class which contains methods for instantiating a Server in different ways.
 */
public class ServerFactory {

    private static final int GRPC_EXECUTOR_THREADS = Runtime.getRuntime().availableProcessors() * 2; // default Netty way of assigning threads, probably expose in config in future
    private static final int ELG_THREADS = 4; // this could also be 1, but to avoid risks set it to 4, probably expose in config in future

    /**
     * Create a Server configured for Grakn Core.
     *
     * @return a Server instance configured for Grakn Core
     */
    public static Server createServer(boolean benchmark) {
        // Grakn Server configuration
        Config config = Config.create();

        JanusGraphFactory janusGraphFactory = new JanusGraphFactory(config);

        // locks
        LockManager lockManager = new LockManager();

        // CQL cluster used by KeyspaceManager to fetch all existing keyspaces
        Cluster cluster = Cluster.builder()
                .addContactPoint(config.getProperty(ConfigKey.STORAGE_HOSTNAME))
                .withPort(config.getProperty(ConfigKey.STORAGE_CQL_NATIVE_PORT))
                .build();

        KeyspaceManager keyspaceManager = new grakn.core.server.keyspace.KeyspaceManager(cluster);
        HadoopGraphFactory hadoopGraphFactory = new HadoopGraphFactory(config);

        // session factory
        SessionFactory sessionFactory = new SessionFactory(lockManager, janusGraphFactory, hadoopGraphFactory, config);

        // Enable server tracing
        if (benchmark) {
            ServerTracing.initInstrumentation("server-instrumentation");
        }

        // create gRPC server
        io.grpc.Server serverRPC = createServerRPC(config, sessionFactory, keyspaceManager, janusGraphFactory);

        return createServer(serverRPC);
    }

    /**
     * Allows the creation of a Server instance with various configurations
     *
     * @return a Server instance
     */

    public static Server createServer(io.grpc.Server rpcServer) {
        Server server = new Server(rpcServer);

        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "grakn-server-shutdown"));

        return server;
    }

    /**
     * Build a GrpcServer using the Netty default builder.
     * The Netty builder accepts 3 thread executors (threadpools):
     *  - Boss Event Loop Group  (a.k.a. bossEventLoopGroup() )
     *  - Worker Event Loop Group ( a.k.a. workerEventLoopGroup() )
     *  - Application Executor (a.k.a. executor() )
     *
     * The Boss group can be the same as the worker group.
     * It's purpose is to accept calls from the network, and create Netty channels (not gRPC Channels) to handle the socket.
     *
     * Once the Netty channel has been created it gets passes to the Worker Event Loop Group.
     * This is the threadpool dedicating to doing socket read() and write() calls.
     *
     * The last thread group is the application executor, also called the "app thread".
     * This is where the gRPC stubs do their main work.
     * It is for handling the callbacks that bubble up from the network thread.
     *
     * Note from grpc-java developers:
     * Most people should use either reuse the same boss event loop group as the worker group.
     * Barring this, the boss eventloop group should be a single thread, since it does very little work.
     * For the app thread, users should provide a fixed size thread pool, as the default unbounded cached threadpool
     * is not the most efficient, and can be dangerous in some circumstances.
     *
     * More info here: https://groups.google.com/d/msg/grpc-io/LrnAbWFozb0/VYCVarkWBQAJ
     */
    private static io.grpc.Server createServerRPC(Config config, SessionFactory sessionFactory, KeyspaceManager keyspaceManager, JanusGraphFactory janusGraphFactory) {
        int grpcPort = config.getProperty(ConfigKey.GRPC_PORT);
        OpenRequest requestOpener = new ServerOpenRequest(sessionFactory);

        SessionService sessionService = new SessionService(requestOpener);

        KeyspaceRequestsHandler requestsHandler = new ServerKeyspaceRequestsHandler(
                keyspaceManager, sessionFactory, janusGraphFactory);

        Runtime.getRuntime().addShutdownHook(new Thread(sessionService::shutdown, "session-service-shutdown"));

        NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(ELG_THREADS, new ThreadFactoryBuilder().setNameFormat("grpc-ELG-handler-%d").build());

        ExecutorService grpcExecutorService = Executors.newFixedThreadPool(GRPC_EXECUTOR_THREADS, new ThreadFactoryBuilder().setNameFormat("grpc-request-handler-%d").build());

        return NettyServerBuilder.forPort(grpcPort)
                .executor(grpcExecutorService)
                .workerEventLoopGroup(eventLoopGroup)
                .bossEventLoopGroup(eventLoopGroup)
                .maxConnectionIdle(1, TimeUnit.HOURS)
                .channelType(NioServerSocketChannel.class)
                .addService(sessionService)
                .addService(new KeyspaceService(requestsHandler))
                .build();
    }

}