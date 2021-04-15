/*
 * Copyright (C) 2021 Grakn Labs
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

package grakn.benchmark.simulation;

import grakn.benchmark.common.params.Config;
import grakn.benchmark.simulation.agent.Agent;
import grakn.benchmark.common.seed.GeoData;
import grakn.benchmark.common.seed.RandomSource;
import grakn.benchmark.common.params.Context;
import grakn.benchmark.simulation.driver.Client;
import grakn.benchmark.simulation.driver.Session;
import grakn.benchmark.simulation.driver.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static grakn.benchmark.common.Util.printDuration;

public abstract class Simulation<
        CLIENT extends Client<SESSION, TX>,
        SESSION extends Session<TX>,
        TX extends Transaction> implements AutoCloseable {

    public static final Set<Class<? extends Agent>> REGISTERED_AGENTS = new HashSet<>();
    private static final Logger LOG = LoggerFactory.getLogger(Simulation.class);
    private static final String AGENT_PACKAGE = Agent.class.getPackageName();

    private final CLIENT client;
    private final List<Agent<?, TX>> agents;
    private final RandomSource randomSource;
    private final Context context;
    private final Map<Class<? extends Agent>, Map<String, List<Agent.Report>>> agentReports;

    public Simulation(CLIENT client, Context context) throws Exception {
        this.client = client;
        this.randomSource = new RandomSource(context.seed());
        this.context = context;
        this.agentReports = new ConcurrentHashMap<>();
        this.agents = initialiseAgents();
        initialiseDatabase();
        initialiseData(context.geoData());
    }

    protected abstract void initialiseDatabase() throws IOException;

    protected abstract void initialiseData(GeoData geoData);

    @SuppressWarnings("unchecked")
    protected List<Agent<?, TX>> initialiseAgents() throws ClassNotFoundException {
        Map<Class<? extends Agent>, Supplier<Agent<?, TX>>> agentBuilders = initialiseAgentBuilders();
        List<Agent<?, TX>> agents = new ArrayList<>();
        for (Config.Agent agentConfig : context.agentConfigs()) {
            if (agentConfig.isRun()) {
                String className = AGENT_PACKAGE + "." + agentConfig.getName();
                Class<? extends Agent> agentClass = (Class<? extends Agent>) Class.forName(className);
                assert agentBuilders.containsKey(agentClass);
                agents.add(agentBuilders.get(agentClass).get().setTracing(agentConfig.isTracing()));
                REGISTERED_AGENTS.add(agentClass);
            }
        }
        return agents;
    }

    private Map<Class<? extends Agent>, Supplier<Agent<?, TX>>> initialiseAgentBuilders() {
        return Collections.emptyMap();
//        return new HashMap<>() {{
//            // TODO
//        }};
    }

    public CLIENT client() {
        return client;
    }

    public Context context() {
        return context;
    }

    public Map<String, List<Agent.Report>> getReport(Class<? extends Agent> agentName) {
        return agentReports.get(agentName);
    }

    public void run() {
        Instant start = Instant.now();
        for (int i = 0; i < context.iterationMax(); i++) {
            Instant iterStart = Instant.now();
            iterate();
            Instant iterEnd = Instant.now();
            LOG.info("Iteration {}: {}", i, printDuration(iterStart, iterEnd));
        }
        LOG.info("Simulation run duration: " + printDuration(start, Instant.now()));
        LOG.info(client.printStatistics());
    }

    public void iterate() {
        agentReports.clear();
        for (Agent<?, ?> agent : agents) {
            agentReports.put(agent.getClass(), agent.iterate(randomSource.next()));
        }
        // We want to test.md opening new sessions each iteration.
        client.closeSessions();
        context.incrementIteration();
    }

    @Override
    public void close() {
        client.close();
    }
}
