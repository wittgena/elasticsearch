/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.sql.plugin;

import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.ql.plugin.AbstractTransportQlAsyncGetResultsAction;
import org.elasticsearch.xpack.sql.action.SqlQueryResponse;
import org.elasticsearch.xpack.sql.action.SqlQueryTask;

public class TransportSqlAsyncGetResultsAction extends AbstractTransportQlAsyncGetResultsAction<SqlQueryResponse, SqlQueryTask> {

    @Inject
    public TransportSqlAsyncGetResultsAction(TransportService transportService,
                                             ActionFilters actionFilters,
                                             ClusterService clusterService,
                                             NamedWriteableRegistry registry,
                                             Client client,
                                             ThreadPool threadPool) {
        super(SqlAsyncGetResultsAction.NAME, transportService, actionFilters, clusterService, registry, client, threadPool,
              SqlQueryTask.class);
    }

    @Override
    public Writeable.Reader<SqlQueryResponse> responseReader() {
        return SqlQueryResponse::new;
    }
}
