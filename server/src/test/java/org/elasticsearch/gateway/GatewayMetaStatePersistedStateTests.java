/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.gateway;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.CoordinationMetaData;
import org.elasticsearch.cluster.coordination.CoordinationMetaData.VotingTombstone;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.Manifest;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.metadata.MetaDataIndexUpgradeService;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugins.MetaDataUpgrader;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Collections;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;

public class GatewayMetaStatePersistedStateTests extends ESTestCase {
    private class GatewayMetaStateUT extends GatewayMetaState {
        private final DiscoveryNode localNode;

        GatewayMetaStateUT(Settings settings, NodeEnvironment nodeEnvironment, DiscoveryNode localNode) throws IOException {
            super(settings, nodeEnvironment, new MetaStateService(nodeEnvironment, xContentRegistry()),
                    mock(MetaDataIndexUpgradeService.class), mock(MetaDataUpgrader.class),
                    mock(TransportService.class), mock(ClusterService.class),
                    mock(IndicesService.class));
            this.localNode = localNode;
        }

        @Override
        protected void upgradeMetaData(MetaDataIndexUpgradeService metaDataIndexUpgradeService, MetaDataUpgrader metaDataUpgrader) {
            // MetaData upgrade is tested in GatewayMetaStateTests, we override this method to NOP to make mocking easier
        }

        @Override
        public void applyClusterStateUpdaters() {
            // Just set localNode here, not to mess with ClusterService and IndicesService mocking
            previousClusterState = ClusterStateUpdaters.setLocalNode(previousClusterState, localNode);
        }
    }

    private NodeEnvironment nodeEnvironment;
    private ClusterName clusterName;
    private Settings settings;
    private DiscoveryNode localNode;

    @Override
    public void setUp() throws Exception {
        nodeEnvironment = newNodeEnvironment();
        localNode = new DiscoveryNode("node1", buildNewFakeTransportAddress(), Collections.emptyMap(),
                Sets.newHashSet(DiscoveryNode.Role.MASTER), Version.CURRENT);
        clusterName = new ClusterName(randomAlphaOfLength(10));
        settings = Settings.builder().put(ClusterName.CLUSTER_NAME_SETTING.getKey(), clusterName.value()).build();
        super.setUp();
    }

    @Override
    public void tearDown() throws Exception {
        nodeEnvironment.close();
        super.tearDown();
    }

    private GatewayMetaStateUT newGateway() throws IOException {
        GatewayMetaStateUT gateway = new GatewayMetaStateUT(settings, nodeEnvironment, localNode);
        gateway.applyClusterStateUpdaters();
        return gateway;
    }

    private GatewayMetaStateUT maybeNew(GatewayMetaStateUT gateway) throws IOException {
        if (randomBoolean()) {
            return newGateway();
        }
        return gateway;
    }

    public void testInitialState() throws IOException {
        GatewayMetaStateUT gateway = newGateway();
        ClusterState state = gateway.getLastAcceptedState();
        assertThat(state.getClusterName(), equalTo(clusterName));
        assertTrue(MetaData.isGlobalStateEquals(state.metaData(), MetaData.EMPTY_META_DATA));
        assertThat(state.getVersion(), equalTo(Manifest.empty().getClusterStateVersion()));
        assertThat(state.getNodes().getLocalNode(), equalTo(localNode));

        long currentTerm = gateway.getCurrentTerm();
        assertThat(currentTerm, equalTo(Manifest.empty().getCurrentTerm()));
    }

    public void testSetCurrentTerm() throws IOException {
        GatewayMetaStateUT gateway = newGateway();

        for (int i = 0; i < randomIntBetween(1, 5); i++) {
            final long currentTerm = randomNonNegativeLong();
            gateway.setCurrentTerm(currentTerm);
            gateway = maybeNew(gateway);
            assertThat(gateway.getCurrentTerm(), equalTo(currentTerm));
        }
    }

    private ClusterState createClusterState(long version, MetaData metaData) {
        return ClusterState.builder(clusterName).
                nodes(DiscoveryNodes.builder().add(localNode).localNodeId(localNode.getId()).build()).
                version(version).
                metaData(metaData).
                build();
    }

    private CoordinationMetaData createCoordinationMetaData(long term) {
        CoordinationMetaData.Builder builder = CoordinationMetaData.builder();
        builder.term(term);
        builder.lastAcceptedConfiguration(
                new CoordinationMetaData.VotingConfiguration(
                        Sets.newHashSet(generateRandomStringArray(10, 10, false))));
        builder.lastCommittedConfiguration(
                new CoordinationMetaData.VotingConfiguration(
                        Sets.newHashSet(generateRandomStringArray(10, 10, false))));
        for (int i = 0; i < randomIntBetween(0, 5); i++) {
            builder.addVotingTombstone(new VotingTombstone(randomAlphaOfLength(10), randomAlphaOfLength(10)));
        }

        return builder.build();
    }

    private IndexMetaData createIndexMetaData(String indexName, int numberOfShards, long version) {
        return IndexMetaData.builder(indexName).settings(
                Settings.builder()
                        .put(IndexMetaData.SETTING_INDEX_UUID, indexName)
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, numberOfShards)
                        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                        .build()
        ).version(version).build();
    }

    private void assertClusterStateEqual(ClusterState expected, ClusterState actual) {
        assertThat(actual.version(), equalTo(expected.version()));
        assertTrue(MetaData.isGlobalStateEquals(actual.metaData(), expected.metaData()));
        for (IndexMetaData indexMetaData : expected.metaData()) {
            assertThat(actual.metaData().index(indexMetaData.getIndex()), equalTo(indexMetaData));
        }
    }

    public void testSetLastAcceptedState() throws IOException {
        GatewayMetaStateUT gateway = newGateway();
        final long term = randomNonNegativeLong();

        for (int i = 0; i < randomIntBetween(1, 5); i++) {
            final long version = randomNonNegativeLong();
            final String indexName = randomAlphaOfLength(10);
            final IndexMetaData indexMetaData = createIndexMetaData(indexName, randomIntBetween(1,5), randomNonNegativeLong());
            final MetaData metaData = MetaData.builder().
                    persistentSettings(Settings.builder().put(randomAlphaOfLength(10), randomAlphaOfLength(10)).build()).
                    coordinationMetaData(createCoordinationMetaData(term)).
                    put(indexMetaData, false).
                    build();
            ClusterState state = createClusterState(version, metaData);

            gateway.setLastAcceptedState(state);
            gateway = maybeNew(gateway);

            ClusterState lastAcceptedState = gateway.getLastAcceptedState();
            assertClusterStateEqual(state, lastAcceptedState);
        }
    }

    public void testSetLastAcceptedStateTermChanged() throws IOException {
        GatewayMetaStateUT gateway = newGateway();

        final String indexName = randomAlphaOfLength(10);
        final int numberOfShards = randomIntBetween(1, 5);
        final long version = randomNonNegativeLong();
        final long term = randomNonNegativeLong();
        final IndexMetaData indexMetaData = createIndexMetaData(indexName, numberOfShards, version);
        final ClusterState state = createClusterState(randomNonNegativeLong(),
                MetaData.builder().coordinationMetaData(createCoordinationMetaData(term)).put(indexMetaData, false).build());
        gateway.setLastAcceptedState(state);

        gateway = maybeNew(gateway);
        final long newTerm = randomValueOtherThan(term, () -> randomNonNegativeLong());
        final int newNumberOfShards = randomValueOtherThan(numberOfShards, () -> randomIntBetween(1,5));
        final IndexMetaData newIndexMetaData = createIndexMetaData(indexName, newNumberOfShards, version);
        final ClusterState newClusterState = createClusterState(randomNonNegativeLong(),
                MetaData.builder().coordinationMetaData(createCoordinationMetaData(newTerm)).put(newIndexMetaData, false).build());
        gateway.setLastAcceptedState(newClusterState);

        gateway = maybeNew(gateway);
        assertThat(gateway.getLastAcceptedState().metaData().index(indexName), equalTo(newIndexMetaData));
    }

    public void testCurrentTermAndTermAreDifferent() throws IOException {
        GatewayMetaStateUT gateway = newGateway();

        long currentTerm = randomNonNegativeLong();
        long term  = randomValueOtherThan(currentTerm, () -> randomNonNegativeLong());

        gateway.setCurrentTerm(currentTerm);
        gateway.setLastAcceptedState(createClusterState(randomNonNegativeLong(),
                MetaData.builder().coordinationMetaData(CoordinationMetaData.builder().term(term).build()).build()));

        gateway = maybeNew(gateway);
        assertThat(gateway.getCurrentTerm(), equalTo(currentTerm));
        assertThat(gateway.getLastAcceptedState().coordinationMetaData().term(), equalTo(term));
    }

    public void testMarkAcceptedConfigAsCommitted() throws IOException {
        GatewayMetaStateUT gateway = newGateway();

        //generate random coordinationMetaData with different lastAcceptedConfiguration and lastCommittedConfiguration
        CoordinationMetaData coordinationMetaData;
        do {
            coordinationMetaData = createCoordinationMetaData(randomNonNegativeLong());
        } while (coordinationMetaData.getLastAcceptedConfiguration().equals(coordinationMetaData.getLastCommittedConfiguration()));

        ClusterState state = createClusterState(randomNonNegativeLong(),
                MetaData.builder().coordinationMetaData(coordinationMetaData).build());
        gateway.setLastAcceptedState(state);

        gateway = maybeNew(gateway);
        assertThat(gateway.getLastAcceptedState().getLastAcceptedConfiguration(),
                not(equalTo(gateway.getLastAcceptedState().getLastCommittedConfiguration())));
        gateway.markLastAcceptedConfigAsCommitted();

        CoordinationMetaData expectedCoordinationMetaData = CoordinationMetaData.builder(coordinationMetaData)
                .lastCommittedConfiguration(coordinationMetaData.getLastAcceptedConfiguration()).build();
        ClusterState expectedClusterState =
                ClusterState.builder(state).metaData(MetaData.builder().coordinationMetaData(expectedCoordinationMetaData).build()).build();

        gateway = maybeNew(gateway);
        assertClusterStateEqual(expectedClusterState, gateway.getLastAcceptedState());
        gateway.markLastAcceptedConfigAsCommitted();

        gateway = maybeNew(gateway);
        assertClusterStateEqual(expectedClusterState, gateway.getLastAcceptedState());
    }
}