/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.snapshots.mockstore;

import org.elasticsearch.Version;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.RepositoryMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.collect.Iterators;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indices.recovery.RecoverySettings;
import org.elasticsearch.repositories.RepositoryData;
import org.elasticsearch.repositories.ShardGenerations;
import org.elasticsearch.repositories.blobstore.BlobStoreRepository;
import org.elasticsearch.repositories.blobstore.BlobStoreTestUtil;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotInfo;
import org.elasticsearch.test.ESTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Function;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

public class MockEventuallyConsistentRepositoryTests extends ESTestCase {

    private final RecoverySettings recoverySettings = new RecoverySettings(
        Settings.EMPTY,
        new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
    );

    public void testReadAfterWriteConsistently() throws IOException {
        MockEventuallyConsistentRepository.Context blobStoreContext = new MockEventuallyConsistentRepository.Context();
        try (
            BlobStoreRepository repository = new MockEventuallyConsistentRepository(
                new RepositoryMetadata("testRepo", "mockEventuallyConsistent", Settings.EMPTY),
                xContentRegistry(),
                BlobStoreTestUtil.mockClusterService(),
                recoverySettings,
                blobStoreContext,
                random()
            )
        ) {
            repository.start();
            final BlobContainer blobContainer = repository.blobStore().blobContainer(repository.basePath());
            final String blobName = randomAlphaOfLength(10);
            final int lengthWritten = randomIntBetween(1, 100);
            final byte[] blobData = randomByteArrayOfLength(lengthWritten);
            blobContainer.writeBlob(blobName, new ByteArrayInputStream(blobData), lengthWritten, true);
            try (InputStream in = blobContainer.readBlob(blobName)) {
                final byte[] readBytes = new byte[lengthWritten + 1];
                final int lengthSeen = in.read(readBytes);
                assertThat(lengthSeen, equalTo(lengthWritten));
                assertArrayEquals(blobData, Arrays.copyOf(readBytes, lengthWritten));
            }
        }
    }

    public void testReadAfterWriteAfterReadThrows() throws IOException {
        MockEventuallyConsistentRepository.Context blobStoreContext = new MockEventuallyConsistentRepository.Context();
        try (
            BlobStoreRepository repository = new MockEventuallyConsistentRepository(
                new RepositoryMetadata("testRepo", "mockEventuallyConsistent", Settings.EMPTY),
                xContentRegistry(),
                BlobStoreTestUtil.mockClusterService(),
                recoverySettings,
                blobStoreContext,
                random()
            )
        ) {
            repository.start();
            final BlobContainer blobContainer = repository.blobStore().blobContainer(repository.basePath());
            final String blobName = randomAlphaOfLength(10);
            final int lengthWritten = randomIntBetween(1, 100);
            final byte[] blobData = randomByteArrayOfLength(lengthWritten);
            expectThrows(NoSuchFileException.class, () -> blobContainer.readBlob(blobName));
            blobContainer.writeBlob(blobName, new ByteArrayInputStream(blobData), lengthWritten, true);
            assertThrowsOnInconsistentRead(blobContainer, blobName);
        }
    }

    public void testReadAfterDeleteAfterWriteThrows() throws IOException {
        MockEventuallyConsistentRepository.Context blobStoreContext = new MockEventuallyConsistentRepository.Context();
        try (
            BlobStoreRepository repository = new MockEventuallyConsistentRepository(
                new RepositoryMetadata("testRepo", "mockEventuallyConsistent", Settings.EMPTY),
                xContentRegistry(),
                BlobStoreTestUtil.mockClusterService(),
                recoverySettings,
                blobStoreContext,
                random()
            )
        ) {
            repository.start();
            final BlobContainer blobContainer = repository.blobStore().blobContainer(repository.basePath());
            final String blobName = randomAlphaOfLength(10);
            final int lengthWritten = randomIntBetween(1, 100);
            final byte[] blobData = randomByteArrayOfLength(lengthWritten);
            blobContainer.writeBlob(blobName, new ByteArrayInputStream(blobData), lengthWritten, true);
            blobContainer.deleteBlobsIgnoringIfNotExists(Iterators.single(blobName));
            assertThrowsOnInconsistentRead(blobContainer, blobName);
            blobStoreContext.forceConsistent();
            expectThrows(NoSuchFileException.class, () -> blobContainer.readBlob(blobName));
        }
    }

    public void testOverwriteRandomBlobFails() throws IOException {
        MockEventuallyConsistentRepository.Context blobStoreContext = new MockEventuallyConsistentRepository.Context();
        try (
            BlobStoreRepository repository = new MockEventuallyConsistentRepository(
                new RepositoryMetadata("testRepo", "mockEventuallyConsistent", Settings.EMPTY),
                xContentRegistry(),
                BlobStoreTestUtil.mockClusterService(),
                recoverySettings,
                blobStoreContext,
                random()
            )
        ) {
            repository.start();
            final BlobContainer container = repository.blobStore().blobContainer(repository.basePath());
            final String blobName = randomAlphaOfLength(10);
            final int lengthWritten = randomIntBetween(1, 100);
            final byte[] blobData = randomByteArrayOfLength(lengthWritten);
            container.writeBlob(blobName, new ByteArrayInputStream(blobData), lengthWritten, false);
            final AssertionError assertionError = expectThrows(
                AssertionError.class,
                () -> container.writeBlob(blobName, new ByteArrayInputStream(blobData), lengthWritten - 1, false)
            );
            assertThat(assertionError.getMessage(), startsWith("Tried to overwrite blob [" + blobName + "]"));
        }
    }

    public void testOverwriteShardSnapBlobFails() throws IOException {
        MockEventuallyConsistentRepository.Context blobStoreContext = new MockEventuallyConsistentRepository.Context();
        try (
            BlobStoreRepository repository = new MockEventuallyConsistentRepository(
                new RepositoryMetadata("testRepo", "mockEventuallyConsistent", Settings.EMPTY),
                xContentRegistry(),
                BlobStoreTestUtil.mockClusterService(),
                recoverySettings,
                blobStoreContext,
                random()
            )
        ) {
            repository.start();
            final BlobContainer container = repository.blobStore()
                .blobContainer(repository.basePath().add("indices").add("someindex").add("0"));
            final String blobName = BlobStoreRepository.SNAPSHOT_PREFIX + UUIDs.randomBase64UUID();
            final int lengthWritten = randomIntBetween(1, 100);
            final byte[] blobData = randomByteArrayOfLength(lengthWritten);
            container.writeBlob(blobName, new ByteArrayInputStream(blobData), lengthWritten, false);
            final AssertionError assertionError = expectThrows(
                AssertionError.class,
                () -> container.writeBlob(blobName, new ByteArrayInputStream(blobData), lengthWritten, false)
            );
            assertThat(assertionError.getMessage(), equalTo("Shard level snap-{uuid} blobs should never be overwritten"));
        }
    }

    public void testOverwriteSnapshotInfoBlob() throws Exception {
        MockEventuallyConsistentRepository.Context blobStoreContext = new MockEventuallyConsistentRepository.Context();
        final RepositoryMetadata metadata = new RepositoryMetadata("testRepo", "mockEventuallyConsistent", Settings.EMPTY);
        final ClusterService clusterService = BlobStoreTestUtil.mockClusterService(metadata);
        try (
            BlobStoreRepository repository = new MockEventuallyConsistentRepository(
                metadata,
                xContentRegistry(),
                clusterService,
                recoverySettings,
                blobStoreContext,
                random()
            )
        ) {
            clusterService.addStateApplier(event -> repository.updateState(event.state()));
            // Apply state once to initialize repo properly like RepositoriesService would
            repository.updateState(clusterService.state());
            repository.start();

            // We create a snap- blob for snapshot "foo" in the first generation
            final SnapshotId snapshotId = new SnapshotId("foo", UUIDs.randomBase64UUID());
            PlainActionFuture.<RepositoryData, Exception>get(f ->
            // We try to write another snap- blob for "foo" in the next generation. It fails because the content differs.
            repository.finalizeSnapshot(
                ShardGenerations.EMPTY,
                RepositoryData.EMPTY_REPO_GEN,
                Metadata.EMPTY_METADATA,
                new SnapshotInfo(
                    snapshotId,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null,
                    1L,
                    5,
                    Collections.emptyList(),
                    true,
                    Collections.emptyMap(),
                    0L,
                    Collections.emptyMap()
                ),
                Version.CURRENT,
                Function.identity(),
                f
            ));

            // We try to write another snap- blob for "foo" in the next generation. It fails because the content differs.
            final AssertionError assertionError = expectThrows(
                AssertionError.class,
                () -> PlainActionFuture.<RepositoryData, Exception>get(
                    f -> repository.finalizeSnapshot(
                        ShardGenerations.EMPTY,
                        0L,
                        Metadata.EMPTY_METADATA,
                        new SnapshotInfo(
                            snapshotId,
                            Collections.emptyList(),
                            Collections.emptyList(),
                            Collections.emptyList(),
                            null,
                            1L,
                            6,
                            Collections.emptyList(),
                            true,
                            Collections.emptyMap(),
                            0L,
                            Collections.emptyMap()
                        ),
                        Version.CURRENT,
                        Function.identity(),
                        f
                    )
                )
            );
            assertThat(assertionError.getMessage(), equalTo("\nExpected: <6>\n     but: was <5>"));

            // We try to write yet another snap- blob for "foo" in the next generation.
            // It passes cleanly because the content of the blob except for the timestamps.
            PlainActionFuture.<RepositoryData, Exception>get(
                f -> repository.finalizeSnapshot(
                    ShardGenerations.EMPTY,
                    0L,
                    Metadata.EMPTY_METADATA,
                    new SnapshotInfo(
                        snapshotId,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        null,
                        2L,
                        5,
                        Collections.emptyList(),
                        true,
                        Collections.emptyMap(),
                        0L,
                        Collections.emptyMap()
                    ),
                    Version.CURRENT,
                    Function.identity(),
                    f
                )
            );
        }
    }

    private static void assertThrowsOnInconsistentRead(BlobContainer blobContainer, String blobName) {
        final AssertionError assertionError = expectThrows(AssertionError.class, () -> blobContainer.readBlob(blobName));
        assertThat(assertionError.getMessage(), equalTo("Inconsistent read on [" + blobName + ']'));
    }
}
