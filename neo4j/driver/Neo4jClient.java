/*
 * Copyright (C) 2020 Grakn Labs
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
 */

package grakn.benchmark.neo4j.driver;

import grabl.tracing.client.GrablTracingThreadStatic;
import grakn.benchmark.simulation.driver.Session;
import grakn.benchmark.simulation.driver.Client;
import grakn.benchmark.simulation.world.Region;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Query;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.slf4j.Logger;

import java.text.DecimalFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.collect.Iterables.getOnlyElement;
import static grabl.tracing.client.GrablTracingThreadStatic.traceOnThread;
import static grakn.benchmark.simulation.driver.Client.TracingLabel.OPEN_SESSION;

public class Neo4jClient extends Client<Neo4jSession, Neo4jTransaction> {

    private final Driver nativeDriver;
    private final ConcurrentHashMap<String, Neo4jSession> sessionMap = new ConcurrentHashMap<>();

    public Neo4jClient(String hostUri) {
        this.nativeDriver = GraphDatabase.driver(hostUri, AuthTokens.basic("neo4j", "admin"));
    }

    @Override
    public Neo4jSession session(String sessionKey) {
        return sessionMap.computeIfAbsent(sessionKey, k -> {
            try (GrablTracingThreadStatic.ThreadTrace ignored = traceOnThread(OPEN_SESSION.getName())) {
                return new Neo4jSession(nativeDriver.session());
            }
        });
    }

    @Override
    public Session<Neo4jTransaction> session(Region region) {
        return session(region.name());
    }

    @Override
    public void printStatistics(Logger LOG) {
        try (Neo4jSession session = session("statisticsSession")) {
            org.neo4j.driver.Session nativeSession = session.unpack();
            DecimalFormat formatter = new DecimalFormat("#,###");

            String numberOfNodesQ = "MATCH (n)\n RETURN count(n)";
            List<Record> numberOfNodesList = nativeSession.writeTransaction(tx -> {
                Result result = tx.run(new Query(numberOfNodesQ));
                return result.list();
            });
            long numberOfNodes = (long) getOnlyElement(numberOfNodesList).asMap().get("count(n)");

            String numberOfRelationshipsQ = "MATCH ()-->()\n RETURN count(*)";
            List<Record> numberOfRelationshipsList = nativeSession.writeTransaction(tx -> {
                Result result = tx.run(new Query(numberOfRelationshipsQ));
                return result.list();
            });
            long numberOfRelationships = (long) getOnlyElement(numberOfRelationshipsList).asMap().get("count(*)");

            LOG.info("");
            LOG.info("Benchmark statistic:");
            LOG.info("");

            LOG.info("Count 'node': {}", formatter.format(numberOfNodes));
            LOG.info("Count 'relationship': {}", formatter.format(numberOfRelationships));
            LOG.info("");
        }
    }

    @Override
    public void closeSessions() {
        for (Neo4jSession session : sessionMap.values()) {
            session.close();
        }
        sessionMap.clear();
    }

    @Override
    public void close() {
        closeSessions();
        nativeDriver.close();
    }
}