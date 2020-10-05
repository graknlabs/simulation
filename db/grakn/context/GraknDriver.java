package grakn.simulation.db.grakn.context;

import grabl.tracing.client.GrablTracingThreadStatic;
import grakn.client.GraknClient;
import grakn.simulation.db.common.agents.base.DbOperationController;
import grakn.simulation.db.common.context.LogWrapper;
import grakn.simulation.db.common.context.TransactionalDbDriver;
import grakn.simulation.db.common.world.Region;
import grakn.simulation.db.grakn.agents.interaction.GraknDbOperationController;
import grakn.simulation.db.grakn.driver.GraknTransaction;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

import static grabl.tracing.client.GrablTracingThreadStatic.traceOnThread;
import static grakn.simulation.db.common.context.TransactionalDbDriver.TracingLabel.CLOSE_CLIENT;
import static grakn.simulation.db.common.context.TransactionalDbDriver.TracingLabel.CLOSE_SESSION;
import static grakn.simulation.db.common.context.TransactionalDbDriver.TracingLabel.OPEN_CLIENT;
import static grakn.simulation.db.common.context.TransactionalDbDriver.TracingLabel.OPEN_SESSION;
import static grakn.simulation.db.common.context.TransactionalDbDriver.TracingLabel.OPEN_TRANSACTION;

public class GraknDriver extends TransactionalDbDriver<GraknTransaction, GraknClient.Session> {

    private final GraknClient client;
    private final String database;
    private final ConcurrentHashMap<String, GraknClient.Session> sessionMap = new ConcurrentHashMap<>();

    public GraknDriver(String hostUri, String database) {
        try (GrablTracingThreadStatic.ThreadTrace trace = traceOnThread(OPEN_CLIENT.getName())) {
            this.client = new GraknClient(hostUri);
        }
        this.database = database;
    }

    @Override
    public GraknClient.Session session(String sessionKey) {
        return sessionMap.computeIfAbsent(sessionKey, k -> {
            try (GrablTracingThreadStatic.ThreadTrace trace = traceOnThread(OPEN_SESSION.getName())) {
                return client.session(database);
            }
        });
    }

    @Override
    public void closeSessions() {
        for (GraknClient.Session session : sessionMap.values()) {
            try (GrablTracingThreadStatic.ThreadTrace trace = traceOnThread(CLOSE_SESSION.getName())) {
                session.close();
            }
        }
        sessionMap.clear();
    }

    @Override
    public void close() {
        closeSessions();
        try (GrablTracingThreadStatic.ThreadTrace trace = traceOnThread(CLOSE_CLIENT.getName())) {
            client.close();
        }
    }

    @Override
    public DbOperationController getDbOpController(Region region, Logger logger) {
        return new GraknDbOperationController(new GraknSession(session(region.continent().name())), logger);
    }

    public class GraknSession extends Session {

        private final GraknClient.Session session;

        public GraknSession(GraknClient.Session session) {
            this.session = session;
        }

        @Override
        public GraknTransaction tx(LogWrapper log, String tracker) {
            try (GrablTracingThreadStatic.ThreadTrace trace = traceOnThread(OPEN_TRANSACTION.getName())) {
                return new GraknTransaction(session.transaction(GraknClient.Transaction.Type.WRITE), log, tracker);
            }
        }
    }
}