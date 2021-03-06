/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.neo4j.driver.internal.cluster;

import java.util.HashSet;
import java.util.Set;

import org.neo4j.driver.internal.net.BoltServerAddress;
import org.neo4j.driver.internal.util.Clock;

import static java.lang.String.format;
import static java.util.Arrays.asList;

public class ClusterRoutingTable implements RoutingTable
{
    private static final int MIN_ROUTERS = 1;

    private final Clock clock;
    private long expirationTimeout;
    private final RoundRobinAddressSet readers;
    private final RoundRobinAddressSet writers;
    private final RoundRobinAddressSet routers;

    public ClusterRoutingTable( Clock clock, BoltServerAddress... routingAddresses )
    {
        this( clock );
        routers.update( new HashSet<>( asList( routingAddresses ) ), new HashSet<BoltServerAddress>() );
    }

    private ClusterRoutingTable( Clock clock )
    {
        this.clock = clock;
        this.expirationTimeout = clock.millis() - 1;

        this.readers = new RoundRobinAddressSet();
        this.writers = new RoundRobinAddressSet();
        this.routers = new RoundRobinAddressSet();
    }

    @Override
    public boolean isStale()
    {
        return expirationTimeout < clock.millis() || // the expiration timeout has been reached
               routers.size() <= MIN_ROUTERS || // we need to discover more routing servers
               readers.size() == 0 || // we need to discover more read servers
               writers.size() == 0; // we need to discover more write servers
    }

    @Override
    public synchronized Set<BoltServerAddress> update( ClusterComposition cluster )
    {
        expirationTimeout = cluster.expirationTimestamp();
        HashSet<BoltServerAddress> removed = new HashSet<>();
        readers.update( cluster.readers(), removed );
        writers.update( cluster.writers(), removed );
        routers.update( cluster.routers(), removed );
        return removed;
    }

    @Override
    public synchronized void forget( BoltServerAddress address )
    {
        // Don't remove it from the set of routers, since that might mean we lose our ability to re-discover,
        // just remove it from the set of readers and writers, so that we don't use it for actual work without
        // performing discovery first.
        readers.remove( address );
        writers.remove( address );
    }

    @Override
    public RoundRobinAddressSet readers()
    {
        return readers;
    }

    @Override
    public RoundRobinAddressSet writers()
    {
        return writers;
    }

    @Override
    public BoltServerAddress nextRouter()
    {
        return routers.next();
    }

    @Override
    public int routerSize()
    {
        return routers.size();
    }

    @Override
    public void removeWriter( BoltServerAddress toRemove )
    {
        writers.remove( toRemove );
    }

    @Override
    public void removeRouter( BoltServerAddress toRemove )
    {
        routers.remove( toRemove );
    }

    @Override
    public String toString()
    {
        return format( "Ttl %s, currentTime %s, routers %s, writers %s, readers %s",
                expirationTimeout, clock.millis(), routers, writers, readers );
    }
}
