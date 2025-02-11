/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.locator;

import java.io.IOException;
import java.util.*;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.Util;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.tcm.ClusterMetadataService;
import org.apache.cassandra.tcm.StubClusterMetadataService;
import org.apache.cassandra.utils.FBUtilities;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class DynamicEndpointSnitchTest
{
    private double oldBadness;

    @Before
    public void before()
    {
        oldBadness = DatabaseDescriptor.getDynamicBadnessThreshold();
        DatabaseDescriptor.setDynamicBadnessThreshold(0.1);
        ClusterMetadataService.unsetInstance();
        ClusterMetadataService.setInstance(StubClusterMetadataService.forTesting());
    }

    @After
    public void after()
    {
        DatabaseDescriptor.setDynamicBadnessThreshold(oldBadness);
    }

    @BeforeClass
    public static void setupDD()
    {
        DatabaseDescriptor.daemonInitialization();
    }

    private static void setScores(DynamicEndpointSnitch dsnitch, int rounds, List<InetAddressAndPort> hosts, Integer... scores) throws InterruptedException
    {
        for (int round = 0; round < rounds; round++)
        {
            for (int i = 0; i < hosts.size(); i++)
                dsnitch.receiveTiming(hosts.get(i), scores[i], MILLISECONDS);
        }
        Thread.sleep(150);
    }

    private static EndpointsForRange full(InetAddressAndPort... endpoints)
    {
        EndpointsForRange.Builder rlist = EndpointsForRange.builder(ReplicaUtils.FULL_RANGE, endpoints.length);
        for (InetAddressAndPort endpoint: endpoints)
        {
            rlist.add(ReplicaUtils.full(endpoint));
        }
        return rlist.build();
    }

    @Test
    public void testSnitch() throws InterruptedException, IOException, ConfigurationException
    {
        // do this because SS needs to be initialized before DES can work properly.
        DatabaseDescriptor.setDynamicBadnessThreshold(0.1);
        StorageService.instance.unsafeInitialize();
        NodeProximity proximity = new NoOpProximity();
        DynamicEndpointSnitch dsnitch = new DynamicEndpointSnitch(proximity, String.valueOf(proximity.hashCode()));
        InetAddressAndPort self = FBUtilities.getBroadcastAddressAndPort();
        InetAddressAndPort host1 = InetAddressAndPort.getByName("127.0.0.2");
        InetAddressAndPort host2 = InetAddressAndPort.getByName("127.0.0.3");
        InetAddressAndPort host3 = InetAddressAndPort.getByName("127.0.0.4");
        InetAddressAndPort host4 = InetAddressAndPort.getByName("127.0.0.5");
        List<InetAddressAndPort> hosts = Arrays.asList(host1, host2, host3);

        // first, make all hosts equal
        setScores(dsnitch, 1, hosts, 10, 10, 10);
        EndpointsForRange order = full(host1, host2, host3);
        Util.assertRCEquals(order, dsnitch.sortedByProximity(self, full(host1, host2, host3)));

        // make host1 a little worse
        setScores(dsnitch, 1, hosts, 20, 10, 10);
        order = full(host2, host3, host1);
        Util.assertRCEquals(order, dsnitch.sortedByProximity(self, full(host1, host2, host3)));

        // make host2 as bad as host1
        setScores(dsnitch, 2, hosts, 15, 20, 10);
        order = full(host3, host1, host2);
        Util.assertRCEquals(order, dsnitch.sortedByProximity(self, full(host1, host2, host3)));

        // make host3 the worst
        setScores(dsnitch, 3, hosts, 10, 10, 30);
        order = full(host1, host2, host3);
        Util.assertRCEquals(order, dsnitch.sortedByProximity(self, full(host1, host2, host3)));

        // make host3 equal to the others
        setScores(dsnitch, 5, hosts, 10, 10, 10);
        order = full(host1, host2, host3);
        Util.assertRCEquals(order, dsnitch.sortedByProximity(self, full(host1, host2, host3)));

        /// Tests CASSANDRA-6683 improvements
        // make the scores differ enough from the ideal order that we sort by score; under the old
        // dynamic snitch behavior (where we only compared neighbors), these wouldn't get sorted
        setScores(dsnitch, 20, hosts, 10, 70, 20);
        order = full(host1, host3, host2);
        Util.assertRCEquals(order, dsnitch.sortedByProximity(self, full(host1, host2, host3)));

        order = full(host4, host1, host3, host2);
        Util.assertRCEquals(order, dsnitch.sortedByProximity(self, full(host1, host2, host3, host4)));


        setScores(dsnitch, 20, hosts, 10, 10, 10);
        order = full(host4, host1, host2, host3);
        Util.assertRCEquals(order, dsnitch.sortedByProximity(self, full(host1, host2, host3, host4)));
    }
}
