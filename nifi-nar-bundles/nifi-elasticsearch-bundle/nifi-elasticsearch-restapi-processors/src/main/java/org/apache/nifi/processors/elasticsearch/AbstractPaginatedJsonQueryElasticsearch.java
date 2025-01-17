/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.elasticsearch;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.elasticsearch.SearchResponse;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.elasticsearch.api.PaginatedJsonQueryParameters;
import org.apache.nifi.util.StopWatch;
import org.apache.nifi.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public abstract class AbstractPaginatedJsonQueryElasticsearch extends AbstractJsonQueryElasticsearch<PaginatedJsonQueryParameters> {
    public static final AllowableValue FLOWFILE_PER_QUERY = new AllowableValue(
            "splitUp-query",
            "Per Query",
            "Combine results from all query responses (one flowfile per entire paginated result set of hits). " +
                    "Note that aggregations cannot be paged, they are generated across the entire result set and " +
                    "returned as part of the first page. Results are output with one JSON object per line " +
                    "(allowing hits to be combined from multiple pages without loading all results into memory)."
    );

    public static final PropertyDescriptor SEARCH_RESULTS_SPLIT = new PropertyDescriptor.Builder()
            .fromPropertyDescriptor(AbstractJsonQueryElasticsearch.SEARCH_RESULTS_SPLIT)
            .description("Output a flowfile containing all hits or one flowfile for each individual hit " +
                    "or one flowfile containing all hits from all paged responses.")
            .allowableValues(FLOWFILE_PER_RESPONSE, FLOWFILE_PER_HIT, FLOWFILE_PER_QUERY)
            .build();

    public static final AllowableValue PAGINATION_SEARCH_AFTER = new AllowableValue(
            "pagination-search_after",
            "Search After",
            "Use Elasticsearch \"search_after\" to page sorted results."
    );
    public static final AllowableValue PAGINATION_POINT_IN_TIME = new AllowableValue(
            "pagination-pit",
            "Point in Time",
            "Use Elasticsearch (7.10+ with XPack) \"point in time\" to page sorted results."
    );
    public static final AllowableValue PAGINATION_SCROLL = new AllowableValue(
            "pagination-scroll",
            "Scroll",
            "Use Elasticsearch \"scroll\" to page results."
    );

    public static final PropertyDescriptor PAGINATION_TYPE = new PropertyDescriptor.Builder()
            .name("el-rest-pagination-type")
            .displayName("Pagination Type")
            .description("Pagination method to use. Not all types are available for all Elasticsearch versions, " +
                    "check the Elasticsearch docs to confirm which are applicable and recommended for your service.")
            .allowableValues(PAGINATION_SCROLL, PAGINATION_SEARCH_AFTER, PAGINATION_POINT_IN_TIME)
            .defaultValue(PAGINATION_SCROLL.getValue())
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .build();

    public static final PropertyDescriptor PAGINATION_KEEP_ALIVE = new PropertyDescriptor.Builder()
            .name("el-rest-pagination-keep-alive")
            .displayName("Pagination Keep Alive")
            .description("Pagination \"keep_alive\" period. Period Elasticsearch will keep the scroll/pit cursor alive " +
                    "in between requests (this is not the time expected for all pages to be returned, but the maximum " +
                    "allowed time for requests between page retrievals).")
            .required(true)
            .defaultValue("10 mins")
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .addValidator(StandardValidators.createTimePeriodValidator(1, TimeUnit.SECONDS, 24, TimeUnit.HOURS))
            .build();

    static final List<PropertyDescriptor> paginatedPropertyDescriptors;
    static {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(QUERY_ATTRIBUTE);
        descriptors.add(INDEX);
        descriptors.add(TYPE);
        descriptors.add(CLIENT_SERVICE);
        descriptors.add(SEARCH_RESULTS_SPLIT);
        descriptors.add(AGGREGATION_RESULTS_SPLIT);
        descriptors.add(PAGINATION_TYPE);
        descriptors.add(PAGINATION_KEEP_ALIVE);

        paginatedPropertyDescriptors = Collections.unmodifiableList(descriptors);
    }

    // output as newline delimited JSON (allows for multiple pages of results to be appended to existing FlowFiles without retaining all hits in memory)
    private final ObjectWriter writer = mapper.writer().withRootValueSeparator("\n");

    String paginationType;

    @Override
    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        super.onScheduled(context);

        paginationType = context.getProperty(PAGINATION_TYPE).getValue();
    }

    @Override
    SearchResponse doQuery(final PaginatedJsonQueryParameters paginatedJsonQueryParameters, List<FlowFile> hitsFlowFiles,
                           final ProcessSession session, final ProcessContext context, final FlowFile input,
                           final StopWatch stopWatch) throws IOException {
        SearchResponse response = null;
        do {
            // check any previously started query hasn't expired
            final boolean expiredQuery = isExpired(paginatedJsonQueryParameters, context, response);
            final boolean newQuery = StringUtils.isBlank(paginatedJsonQueryParameters.getPageExpirationTimestamp()) || expiredQuery;

            // execute query/scroll
            final String queryJson = updateQueryJson(newQuery, paginatedJsonQueryParameters);
            if (!newQuery && PAGINATION_SCROLL.getValue().equals(paginationType)) {
                response = clientService.get().scroll(queryJson);
            } else {
                response = clientService.get().search(
                        queryJson,
                        // Point in Time uses general /_search API not /index/_search
                        PAGINATION_POINT_IN_TIME.getValue().equals(paginationType) ? null : paginatedJsonQueryParameters.getIndex(),
                        paginatedJsonQueryParameters.getType(),
                        PAGINATION_SCROLL.getValue().equals(paginationType)
                                ? Collections.singletonMap("scroll", paginatedJsonQueryParameters.getKeepAlive())
                                : null
                );
                paginatedJsonQueryParameters.setPitId(response.getPitId());
                paginatedJsonQueryParameters.setSearchAfter(response.getSearchAfter());
            }
            paginatedJsonQueryParameters.setScrollId(response.getScrollId());
            if (newQuery && input != null) {
                session.getProvenanceReporter().send(
                        input,
                        clientService.get().getTransitUrl(paginatedJsonQueryParameters.getIndex(), paginatedJsonQueryParameters.getType()),
                        stopWatch.getElapsed(TimeUnit.MILLISECONDS)
                );
            }

            // mark the paginated query for expiry if there are no hits (no more pages to obtain so stop looping on this query)
            updatePageExpirationTimestamp(paginatedJsonQueryParameters, !response.getHits().isEmpty());

            hitsFlowFiles = handleResponse(response, newQuery, paginatedJsonQueryParameters, hitsFlowFiles, session, input, stopWatch);
        } while (!response.getHits().isEmpty() && (input != null || FLOWFILE_PER_QUERY.getValue().equals(splitUpHits)));

        if (response.getHits().isEmpty()) {
            getLogger().debug("No more results for paginated query, clearing Elasticsearch resources");
            clearElasticsearchState(context, response);
        }

        return response;
    }

    @Override
    PaginatedJsonQueryParameters buildJsonQueryParameters(final FlowFile input, final ProcessContext context, final ProcessSession session) throws IOException {
        final PaginatedJsonQueryParameters paginatedJsonQueryParameters = new PaginatedJsonQueryParameters();
        populateCommonJsonQueryParameters(paginatedJsonQueryParameters, input, context, session);

        paginatedJsonQueryParameters.setKeepAlive(context.getProperty(PAGINATION_KEEP_ALIVE).asTimePeriod(TimeUnit.SECONDS) + "s");

        return paginatedJsonQueryParameters;
    }

    abstract boolean isExpired(final PaginatedJsonQueryParameters paginatedQueryParameters, final ProcessContext context,
                               final SearchResponse response) throws IOException;

    abstract String getScrollId(final ProcessContext context, final SearchResponse response) throws IOException;

    abstract String getPitId(final ProcessContext context, final SearchResponse response) throws IOException;

    private void prepareNextPageQuery(final ObjectNode queryJson, final PaginatedJsonQueryParameters paginatedJsonQueryParameters) throws IOException {
        // prepare to get next page of results (depending on pagination type)
        if (PAGINATION_SCROLL.getValue().equals(paginationType)) {
            // overwrite query JSON with existing Scroll details
            queryJson.removeAll().put("scroll_id", paginatedJsonQueryParameters.getScrollId());
            if (StringUtils.isNotBlank(paginatedJsonQueryParameters.getKeepAlive())) {
                queryJson.put("scroll", paginatedJsonQueryParameters.getKeepAlive());
            }
        } else {
            // add search_after to query JSON
            queryJson.set("search_after", mapper.readValue(paginatedJsonQueryParameters.getSearchAfter(), ArrayNode.class));

            // remove any request for aggregations as they were dealt with in the first page
            if (queryJson.has("aggs")) {
                getLogger().debug("Removing \"aggs\" from non-initial paged query");
                queryJson.remove("aggs");
            }
        }
    }

    private String updateQueryJson(final boolean newQuery, final PaginatedJsonQueryParameters paginatedJsonQueryParameters) throws IOException {
        final ObjectNode queryJson = mapper.readValue(paginatedJsonQueryParameters.getQuery(), ObjectNode.class);

        if (!newQuery) {
            prepareNextPageQuery(queryJson, paginatedJsonQueryParameters);
        } else if ((PAGINATION_POINT_IN_TIME.getValue().equals(paginationType) || PAGINATION_SEARCH_AFTER.getValue().equals(paginationType))
                && !queryJson.has("sort")) {
            // verify query contains a "sort" field if pit/search_after requested
            throw new IllegalArgumentException("Query using pit/search_after must contain a \"sort\" field");
        }

        if (PAGINATION_POINT_IN_TIME.getValue().equals(paginationType)) {
            // add pit_id to query JSON
            final String queryPitId = newQuery
                    ? clientService.get().initialisePointInTime(paginatedJsonQueryParameters.getIndex(), paginatedJsonQueryParameters.getKeepAlive())
                    : paginatedJsonQueryParameters.getPitId();

            final ObjectNode pit = JsonNodeFactory.instance.objectNode().put("id", queryPitId);
            if (StringUtils.isNotBlank(paginatedJsonQueryParameters.getKeepAlive())) {
                pit.put("keep_alive", paginatedJsonQueryParameters.getKeepAlive());
            }
            queryJson.set("pit", pit);
        }

        return mapper.writeValueAsString(queryJson);
    }

    private FlowFile writeCombinedHitFlowFile(final int count, final List<Map<String, Object>> hits, final ProcessSession session,
                                              final FlowFile hitFlowFile, final Map<String, String> attributes, final boolean append) {
        FlowFile ff;
        if (append) {
            // separate new from existing hits with a newline
            ff = session.append(hitFlowFile, out -> out.write('\n'));
            ff = session.append(ff, out -> writer.writeValues(out).writeAll(hits));
        } else {
            ff = session.write(hitFlowFile, out -> writer.writeValues(out).writeAll(hits));
        }

        attributes.put("hit.count", Integer.toString(count));
        return session.putAllAttributes(ff, attributes);
    }

    private void combineHits(final List<Map<String, Object>> hits, final PaginatedJsonQueryParameters paginatedJsonQueryParameters,
                             final ProcessSession session, final FlowFile parent,
                             final Map<String, String> attributes, final List<FlowFile> hitsFlowFiles) {
        if (hits != null && !hits.isEmpty()) {
            final FlowFile hitFlowFile;
            final boolean append = !hitsFlowFiles.isEmpty();
            if (!hitsFlowFiles.isEmpty()) {
                hitFlowFile = hitsFlowFiles.remove(0);
            } else {
                hitFlowFile = createChildFlowFile(session, parent);
            }

            hitsFlowFiles.add(writeCombinedHitFlowFile(paginatedJsonQueryParameters.getHitCount() + hits.size(),
                    hits, session, hitFlowFile, attributes, append));
        }
    }

    /*
     * The List<FlowFile> hitsFlowFiles parameter and return value are used in order to allow pagination of query results.
     * The List is created in AbstractJsonQueryElasticsearch#onTrigger and passed to doQuery => handleResponse => handleHits,
     * for paginated queries, the List could contain one (or more) FlowFiles, to which further hits may be appended when the next
     * SearchResponse is processed, i.e. this approach allows recursion for paginated queries, but is unnecessary for single-response queries.
     */
    @Override
    List<FlowFile> handleHits(final List<Map<String, Object>> hits, final PaginatedJsonQueryParameters paginatedJsonQueryParameters,
                              final ProcessSession session, final FlowFile parent, final Map<String, String> attributes,
                              final List<FlowFile> hitsFlowFiles, final String transitUri, final StopWatch stopWatch) throws IOException {
        paginatedJsonQueryParameters.incrementPageCount();
        attributes.put("page.number", Integer.toString(paginatedJsonQueryParameters.getPageCount()));

        if (FLOWFILE_PER_QUERY.getValue().equals(splitUpHits)) {
            combineHits(hits, paginatedJsonQueryParameters, session, parent, attributes, hitsFlowFiles);

            // output results if it seems we've combined all available results (i.e. no hits in this page and therefore no more expected)
            if (!hitsFlowFiles.isEmpty() && (hits == null || hits.isEmpty())) {
                session.transfer(hitsFlowFiles, REL_HITS);
                hitsFlowFiles.forEach(ff -> session.getProvenanceReporter().receive(ff, transitUri, stopWatch.getElapsed(TimeUnit.MILLISECONDS)));
                hitsFlowFiles.clear();
            }
        } else {
            super.handleHits(hits, paginatedJsonQueryParameters, session, parent, attributes, hitsFlowFiles, transitUri, stopWatch);
        }

        return hitsFlowFiles;
    }

    private void updatePageExpirationTimestamp(final PaginatedJsonQueryParameters paginatedJsonQueryParameters, final boolean hasHits) {
        final String keepAliveDuration = "PT" + (hasHits ? paginatedJsonQueryParameters.getKeepAlive() : "0s");
        paginatedJsonQueryParameters.setPageExpirationTimestamp(
                String.valueOf(Instant.now().plus(Duration.parse(keepAliveDuration)).toEpochMilli())
        );
    }

    void clearElasticsearchState(final ProcessContext context, final SearchResponse response) {
        try {
            if (PAGINATION_SCROLL.getValue().equals(paginationType)) {
                final String scrollId = getScrollId(context, response);

                if (StringUtils.isNotBlank(scrollId)) {
                    clientService.get().deleteScroll(scrollId);
                }
            } else if (PAGINATION_POINT_IN_TIME.getValue().equals(paginationType)) {
                final String pitId = getPitId(context, response);

                if (StringUtils.isNotBlank(pitId)) {
                    clientService.get().deletePointInTime(pitId);
                }
            }
        } catch (Exception ex) {
            getLogger().warn("Error while cleaning up Elasticsearch pagination resources, ignoring", ex);
        }
    }
}
