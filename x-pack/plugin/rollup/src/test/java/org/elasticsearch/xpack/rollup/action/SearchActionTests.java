/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.rollup.action;

import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.MockPageCacheRecycler;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.BoostingQueryBuilder;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.DisMaxQueryBuilder;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.MatchPhraseQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.InternalFilter;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.metrics.avg.Avg;
import org.elasticsearch.search.aggregations.metrics.avg.InternalAvg;
import org.elasticsearch.search.aggregations.metrics.sum.InternalSum;
import org.elasticsearch.search.aggregations.metrics.sum.SumAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.rollup.ConfigTestHelpers;
import org.elasticsearch.xpack.core.rollup.RollupField;
import org.elasticsearch.xpack.core.rollup.action.RollupJobCaps;
import org.elasticsearch.xpack.core.rollup.job.DateHistoGroupConfig;
import org.elasticsearch.xpack.core.rollup.job.GroupConfig;
import org.elasticsearch.xpack.core.rollup.job.RollupJobConfig;
import org.elasticsearch.xpack.rollup.Rollup;
import org.hamcrest.core.IsEqual;
import org.joda.time.DateTimeZone;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.xpack.core.rollup.RollupField.COUNT_FIELD;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SearchActionTests extends ESTestCase {

    private NamedWriteableRegistry namedWriteableRegistry;
    public void setUp() throws Exception {
        super.setUp();
        IndicesModule indicesModule = new IndicesModule(Collections.emptyList());
        SearchModule searchModule = new SearchModule(Settings.EMPTY, false, Collections.emptyList());
        List<NamedWriteableRegistry.Entry> entries = new ArrayList<>();
        entries.addAll(indicesModule.getNamedWriteables());
        entries.addAll(searchModule.getNamedWriteables());
        namedWriteableRegistry = new NamedWriteableRegistry(entries);
    }

    public void testNonZeroSize() {
        String[] normalIndices = new String[]{randomAlphaOfLength(10)};
        String[] rollupIndices = new String[]{randomAlphaOfLength(10)};
        TransportRollupSearchAction.RollupSearchContext ctx
                = new TransportRollupSearchAction.RollupSearchContext(normalIndices, rollupIndices, Collections.emptySet());
        SearchSourceBuilder source = new SearchSourceBuilder();
        source.query(new MatchAllQueryBuilder());
        source.size(100);
        source.aggregation(new DateHistogramAggregationBuilder("foo").field("foo").interval(123));
        SearchRequest request = new SearchRequest(normalIndices, source);
        NamedWriteableRegistry registry = mock(NamedWriteableRegistry.class);
        Exception e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.createMSearchRequest(request, registry, ctx));
        assertThat(e.getMessage(), equalTo("Rollup does not support returning search hits, please try again with [size: 0]."));
    }

    public void testBadQuery() {
        SearchSourceBuilder source = new SearchSourceBuilder();
        source.query(new MatchPhraseQueryBuilder("foo", "bar"));
        source.aggregation(new DateHistogramAggregationBuilder("foo").field("foo").interval(123));
        source.size(0);
        Exception e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.rewriteQuery(new MatchPhraseQueryBuilder("foo", "bar"), Collections.emptySet()));
        assertThat(e.getMessage(), equalTo("Unsupported Query in search request: [match_phrase]"));
    }

    public void testRange() {
        RollupJobConfig.Builder job = ConfigTestHelpers.getRollupJob("foo");
        GroupConfig.Builder group = ConfigTestHelpers.getGroupConfig();
        group.setDateHisto(new DateHistoGroupConfig.Builder().setField("foo").setInterval(new DateHistogramInterval("1h")).build());
        job.setGroupConfig(group.build());
        RollupJobCaps cap = new RollupJobCaps(job.build());
        Set<RollupJobCaps> caps = new HashSet<>();
        caps.add(cap);
        QueryBuilder rewritten = null;
        try {
            rewritten = TransportRollupSearchAction.rewriteQuery(new RangeQueryBuilder("foo").gt(1), caps);
        } catch (Exception e) {
            fail("Should not have thrown exception when parsing query.");
        }
        assertThat(rewritten, instanceOf(RangeQueryBuilder.class));
        assertThat(((RangeQueryBuilder)rewritten).fieldName(), equalTo("foo.date_histogram.timestamp"));
    }

    public void testTerms() {
        RollupJobConfig.Builder job = ConfigTestHelpers.getRollupJob("foo");
        GroupConfig.Builder group = ConfigTestHelpers.getGroupConfig();
        group.setTerms(ConfigTestHelpers.getTerms().setFields(Collections.singletonList("foo")).build());
        job.setGroupConfig(group.build());
        RollupJobCaps cap = new RollupJobCaps(job.build());
        Set<RollupJobCaps> caps = new HashSet<>();
        caps.add(cap);
        QueryBuilder rewritten = null;
        try {
            rewritten = TransportRollupSearchAction.rewriteQuery(new TermQueryBuilder("foo", "bar"), caps);
        } catch (Exception e) {
            fail("Should not have thrown exception when parsing query.");
        }
        assertThat(rewritten, instanceOf(TermQueryBuilder.class));
        assertThat(((TermQueryBuilder)rewritten).fieldName(), equalTo("foo.terms.value"));
    }

    public void testCompounds() {
        RollupJobConfig.Builder job = ConfigTestHelpers.getRollupJob("foo");
        GroupConfig.Builder group = ConfigTestHelpers.getGroupConfig();
        group.setDateHisto(new DateHistoGroupConfig.Builder().setField("foo").setInterval(new DateHistogramInterval("1h")).build());
        job.setGroupConfig(group.build());
        RollupJobCaps cap = new RollupJobCaps(job.build());
        Set<RollupJobCaps> caps = new HashSet<>();
        caps.add(cap);

        BoolQueryBuilder builder = new BoolQueryBuilder();
        builder.must(getQueryBuilder(2));
        QueryBuilder rewritten = null;
        try {
            rewritten = TransportRollupSearchAction.rewriteQuery(builder, caps);
        } catch (Exception e) {
            fail("Should not have thrown exception when parsing query.");
        }
        assertThat(rewritten, instanceOf(BoolQueryBuilder.class));
        assertThat(((BoolQueryBuilder)rewritten).must().size(), equalTo(1));
    }

    public void testMatchAll() {
        RollupJobConfig.Builder job = ConfigTestHelpers.getRollupJob("foo");
        GroupConfig.Builder group = ConfigTestHelpers.getGroupConfig();
        group.setDateHisto(new DateHistoGroupConfig.Builder().setField("foo").setInterval(new DateHistogramInterval("1h")).build());
        job.setGroupConfig(group.build());
        RollupJobCaps cap = new RollupJobCaps(job.build());
        Set<RollupJobCaps> caps = new HashSet<>();
        caps.add(cap);
        try {
            QueryBuilder rewritten = TransportRollupSearchAction.rewriteQuery(new MatchAllQueryBuilder(), caps);
            assertThat(rewritten, instanceOf(MatchAllQueryBuilder.class));
        } catch (Exception e) {
            fail("Should not have thrown exception when parsing query.");
        }
    }

    public void testAmbiguousResolution() {
        RollupJobConfig.Builder job = ConfigTestHelpers.getRollupJob("foo");
        GroupConfig.Builder group = ConfigTestHelpers.getGroupConfig();
        group.setDateHisto(new DateHistoGroupConfig.Builder().setField("foo").setInterval(new DateHistogramInterval("1h")).build());
        group.setTerms(ConfigTestHelpers.getTerms().setFields(Collections.singletonList("foo")).build()).build();
        job.setGroupConfig(group.build());
        RollupJobCaps cap = new RollupJobCaps(job.build());
        Set<RollupJobCaps> caps = new HashSet<>();
        caps.add(cap);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.rewriteQuery(new RangeQueryBuilder("foo").gt(1), caps));
        assertThat(e.getMessage(), equalTo("Ambiguous field name resolution when mapping to rolled fields.  " +
                "Field name [foo] was mapped to: [foo.date_histogram.timestamp,foo.terms.value]."));
    }

    public static QueryBuilder getQueryBuilder(int levels) {
        if (levels == 0) {
            return ESTestCase.randomBoolean() ? new MatchAllQueryBuilder() : new RangeQueryBuilder("foo").gt(1);
        }

        int choice = ESTestCase.randomIntBetween(0,5);
        if (choice == 0) {
            BoolQueryBuilder b = new BoolQueryBuilder();
            b.must(getQueryBuilder(levels - 1));
            b.must(getQueryBuilder(levels - 1));
            b.mustNot(getQueryBuilder(levels - 1));
            b.should(getQueryBuilder(levels - 1));
            b.filter(getQueryBuilder(levels - 1));
            return b;
        } else if (choice == 1) {
            return new ConstantScoreQueryBuilder(getQueryBuilder(levels - 1));
        } else if (choice == 2) {
            return new BoostingQueryBuilder(getQueryBuilder(levels - 1), getQueryBuilder(levels - 1));
        } else if (choice == 3) {
            DisMaxQueryBuilder b = new DisMaxQueryBuilder();
            b.add(getQueryBuilder(levels - 1));
            b.add(getQueryBuilder(levels - 1));
        } else if (choice == 4) {
            return new MatchAllQueryBuilder();
        } else if (choice == 5) {
            return new TermQueryBuilder("foo", "bar");
        }

        return new RangeQueryBuilder("foo").gt(1);
    }

    public void testPostFilter() {
        String[] normalIndices = new String[]{randomAlphaOfLength(10)};
        String[] rollupIndices = new String[]{randomAlphaOfLength(10)};
        TransportRollupSearchAction.RollupSearchContext ctx
                = new TransportRollupSearchAction.RollupSearchContext(normalIndices, rollupIndices, Collections.emptySet());
        SearchSourceBuilder source = new SearchSourceBuilder();
        source.aggregation(new DateHistogramAggregationBuilder("foo").field("foo").interval(123));
        source.postFilter(new TermQueryBuilder("foo", "bar"));
        source.size(0);
        SearchRequest request = new SearchRequest(normalIndices, source);
        NamedWriteableRegistry registry = mock(NamedWriteableRegistry.class);
        Exception e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.createMSearchRequest(request, registry, ctx));
        assertThat(e.getMessage(), equalTo("Rollup search does not support post filtering."));
    }

    public void testSuggest() {
        String[] normalIndices = new String[]{ESTestCase.randomAlphaOfLength(10)};
        SearchSourceBuilder source = new SearchSourceBuilder();
        source.suggest(new SuggestBuilder());
        source.size(0);
        SearchRequest request = new SearchRequest(normalIndices, source);
        Exception e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.validateSearchRequest(request));
        assertThat(e.getMessage(), equalTo("Rollup search does not support suggestors."));
    }

    public void testHighlighters() {
        String[] normalIndices = new String[]{ESTestCase.randomAlphaOfLength(10)};
        SearchSourceBuilder source = new SearchSourceBuilder();
        source.highlighter(new HighlightBuilder());
        source.size(0);
        SearchRequest request = new SearchRequest(normalIndices, source);
        Exception e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.validateSearchRequest(request));
        assertThat(e.getMessage(), equalTo("Rollup search does not support highlighting."));
    }

    public void testProfiling() {
        String[] normalIndices = new String[]{ESTestCase.randomAlphaOfLength(10)};
        SearchSourceBuilder source = new SearchSourceBuilder();
        source.profile(true);
        source.size(0);
        SearchRequest request = new SearchRequest(normalIndices, source);
        Exception e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.validateSearchRequest(request));
        assertThat(e.getMessage(), equalTo("Rollup search does not support profiling at the moment."));
    }

    public void testExplain() {
        String[] normalIndices = new String[]{ESTestCase.randomAlphaOfLength(10)};
        SearchSourceBuilder source = new SearchSourceBuilder();
        source.explain(true);
        source.size(0);
        SearchRequest request = new SearchRequest(normalIndices, source);
        Exception e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.validateSearchRequest(request));
        assertThat(e.getMessage(), equalTo("Rollup search does not support explaining."));
    }

    public void testNoAgg() {
        String[] normalIndices = new String[]{randomAlphaOfLength(10)};
        String[] rollupIndices = new String[]{randomAlphaOfLength(10)};
        TransportRollupSearchAction.RollupSearchContext ctx
                = new TransportRollupSearchAction.RollupSearchContext(normalIndices, rollupIndices, Collections.emptySet());
        SearchSourceBuilder source = new SearchSourceBuilder();
        source.query(new MatchAllQueryBuilder());
        source.size(0);
        SearchRequest request = new SearchRequest(normalIndices, source);
        NamedWriteableRegistry registry = mock(NamedWriteableRegistry.class);
        Exception e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.createMSearchRequest(request, registry, ctx));
        assertThat(e.getMessage(), equalTo("Rollup requires at least one aggregation to be set."));
    }

    public void testNoLiveNoRollup() {
        String[] normalIndices = new String[0];
        String[] rollupIndices = new String[0];
        TransportRollupSearchAction.RollupSearchContext ctx
                = new TransportRollupSearchAction.RollupSearchContext(normalIndices, rollupIndices, Collections.emptySet());
        SearchSourceBuilder source = new SearchSourceBuilder();
        source.query(new MatchAllQueryBuilder());
        source.size(0);
        SearchRequest request = new SearchRequest(normalIndices, source);
        NamedWriteableRegistry registry = mock(NamedWriteableRegistry.class);
        Exception e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.createMSearchRequest(request, registry, ctx));
        assertThat(e.getMessage(), equalTo("Must specify at least one rollup index in _rollup_search API"));
    }

    public void testLiveOnlyCreateMSearch() {
        String[] normalIndices = new String[]{randomAlphaOfLength(10)};
        String[] rollupIndices = new String[0];
        TransportRollupSearchAction.RollupSearchContext ctx
                = new TransportRollupSearchAction.RollupSearchContext(normalIndices, rollupIndices, Collections.emptySet());
        SearchSourceBuilder source = new SearchSourceBuilder();
        source.query(new MatchAllQueryBuilder());
        source.size(0);
        SearchRequest request = new SearchRequest(normalIndices, source);
        NamedWriteableRegistry registry = mock(NamedWriteableRegistry.class);
        MultiSearchRequest msearch = TransportRollupSearchAction.createMSearchRequest(request, registry, ctx);
        assertThat(msearch.requests().size(), equalTo(1));
        assertThat(msearch.requests().get(0), equalTo(request));
    }

    public void testGood() {
        RollupJobConfig.Builder job = ConfigTestHelpers.getRollupJob("foo");
        GroupConfig.Builder group = ConfigTestHelpers.getGroupConfig();
        group.setDateHisto(new DateHistoGroupConfig.Builder().setField("foo").setInterval(new DateHistogramInterval("1h")).build());
        job.setGroupConfig(group.build());
        RollupJobCaps cap = new RollupJobCaps(job.build());
        Set<RollupJobCaps> caps = singletonSet(cap);

        String[] normalIndices = new String[]{ESTestCase.randomAlphaOfLength(10)};
        String[] rollupIndices = new String[]{ESTestCase.randomAlphaOfLength(10)};
        String[] combinedIndices = new String[]{normalIndices[0], rollupIndices[0]};

        TransportRollupSearchAction.RollupSearchContext ctx
                = new TransportRollupSearchAction.RollupSearchContext(normalIndices, rollupIndices, caps);

        SearchSourceBuilder source = new SearchSourceBuilder();
        source.query(getQueryBuilder(1));
        source.size(0);
        source.aggregation(new DateHistogramAggregationBuilder("foo").field("foo")
                .dateHistogramInterval(job.getGroupConfig().getDateHisto().getInterval()));
        SearchRequest request = new SearchRequest(combinedIndices, source);

        MultiSearchRequest msearch = TransportRollupSearchAction.createMSearchRequest(request, namedWriteableRegistry, ctx);
        assertThat(msearch.requests().size(), equalTo(2));
        assertThat(msearch.requests().get(0), equalTo(new SearchRequest(normalIndices, request.source())));

        SearchRequest normal = msearch.requests().get(0);
        assertThat(normal.indices().length, equalTo(1));
        assertThat(normal.indices()[0], equalTo(normalIndices[0]));

        SearchRequest rollup = msearch.requests().get(1);
        assertThat(rollup.indices().length, equalTo(1));
        assertThat(rollup.indices()[0], equalTo(rollupIndices[0]));
        assert(rollup.source().aggregations().getAggregatorFactories().get(0) instanceof FilterAggregationBuilder);
    }

    public void testGoodButNullQuery() {
        String[] normalIndices = new String[]{randomAlphaOfLength(10)};
        String[] rollupIndices = new String[]{randomAlphaOfLength(10)};
        String[] combinedIndices = new String[]{normalIndices[0], rollupIndices[0]};

        SearchSourceBuilder source = new SearchSourceBuilder();
        source.query(null);
        source.size(0);
        source.aggregation(new DateHistogramAggregationBuilder("foo").field("foo").dateHistogramInterval(new DateHistogramInterval("1d")));
        SearchRequest request = new SearchRequest(combinedIndices, source);

        RollupJobConfig job = ConfigTestHelpers.getRollupJob("foo")
                .setGroupConfig(ConfigTestHelpers.getGroupConfig()
                        .setDateHisto(new DateHistoGroupConfig.Builder()
                                .setInterval(new DateHistogramInterval("1d"))
                                .setField("foo")
                                .setTimeZone(DateTimeZone.UTC)
                                .build())
                        .build())
                .build();
        Set<RollupJobCaps> caps = singletonSet(new RollupJobCaps(job));

        TransportRollupSearchAction.RollupSearchContext ctx
                = new TransportRollupSearchAction.RollupSearchContext(normalIndices, rollupIndices, caps);

        MultiSearchRequest msearch = TransportRollupSearchAction.createMSearchRequest(request, namedWriteableRegistry, ctx);
        assertThat(msearch.requests().size(), equalTo(2));
        assertThat(msearch.requests().get(0), equalTo(new SearchRequest(normalIndices, request.source())));

        SearchRequest normal = msearch.requests().get(0);
        assertThat(normal.indices().length, equalTo(1));
        assertThat(normal.indices()[0], equalTo(normalIndices[0]));

        SearchRequest rollup = msearch.requests().get(1);
        assertThat(rollup.indices().length, equalTo(1));
        assertThat(rollup.indices()[0], equalTo(rollupIndices[0]));
        assert(rollup.source().aggregations().getAggregatorFactories().get(0) instanceof FilterAggregationBuilder);
    }

    public void testTwoMatchingJobs() {
        RollupJobConfig.Builder job = ConfigTestHelpers.getRollupJob("foo");
        GroupConfig.Builder group = ConfigTestHelpers.getGroupConfig();
        group.setDateHisto(new DateHistoGroupConfig.Builder().setField("foo").setInterval(new DateHistogramInterval("1h")).build())
                .setHisto(null)
                .setTerms(null);
        job.setGroupConfig(group.build());
        RollupJobCaps cap = new RollupJobCaps(job.build());

        RollupJobConfig.Builder job2 = ConfigTestHelpers.getRollupJob("foo2").setRollupIndex(job.getRollupIndex());
        job2.setGroupConfig(group.build());

        // so that the jobs aren't exactly equal
        job2.setMetricsConfig(Collections.singletonList(ConfigTestHelpers.getMetricConfig().build()));
        RollupJobCaps cap2 = new RollupJobCaps(job2.build());

        Set<RollupJobCaps> caps = new HashSet<>(2);
        caps.add(cap);
        caps.add(cap2);

        String[] normalIndices = new String[]{ESTestCase.randomAlphaOfLength(10)};
        String[] rollupIndices = new String[]{ESTestCase.randomAlphaOfLength(10)};
        String[] combinedIndices = new String[]{normalIndices[0], rollupIndices[0]};

        TransportRollupSearchAction.RollupSearchContext ctx
                = new TransportRollupSearchAction.RollupSearchContext(normalIndices, rollupIndices, caps);

        SearchSourceBuilder source = new SearchSourceBuilder();
        source.query(getQueryBuilder(1));
        source.size(0);
        source.aggregation(new DateHistogramAggregationBuilder("foo").field("foo")
                .dateHistogramInterval(job.getGroupConfig().getDateHisto().getInterval()));
        SearchRequest request = new SearchRequest(combinedIndices, source);

        MultiSearchRequest msearch = TransportRollupSearchAction.createMSearchRequest(request, namedWriteableRegistry, ctx);
        assertThat(msearch.requests().size(), equalTo(2));

        assertThat(msearch.requests().get(0), equalTo(new SearchRequest(normalIndices, request.source())));
        SearchRequest normal = msearch.requests().get(0);
        assertThat(normal.indices().length, equalTo(1));
        assertThat(normal.indices()[0], equalTo(normalIndices[0]));

        SearchRequest rollup = msearch.requests().get(1);
        assertThat(rollup.indices().length, equalTo(1));
        assertThat(rollup.indices()[0], equalTo(rollupIndices[0]));
        assert(rollup.source().aggregations().getAggregatorFactories().get(0) instanceof FilterAggregationBuilder);

        assertThat(msearch.requests().size(), equalTo(2));
    }

    public void testTwoMatchingJobsOneBetter() {
        RollupJobConfig.Builder job = ConfigTestHelpers.getRollupJob("foo");
        GroupConfig.Builder group = ConfigTestHelpers.getGroupConfig();
        group.setDateHisto(new DateHistoGroupConfig.Builder().setField("foo").setInterval(new DateHistogramInterval("1h")).build())
            .setHisto(null)
            .setTerms(null);
        job.setGroupConfig(group.build());
        RollupJobCaps cap = new RollupJobCaps(job.build());

        RollupJobConfig.Builder job2 = ConfigTestHelpers.getRollupJob("foo2").setRollupIndex(job.getRollupIndex());
        GroupConfig.Builder group2 = ConfigTestHelpers.getGroupConfig();
        group2.setDateHisto(group.getDateHisto())
                .setHisto(ConfigTestHelpers.getHisto().build())
                .setTerms(null);
        job2.setGroupConfig(group2.build());
        RollupJobCaps cap2 = new RollupJobCaps(job2.build());

        Set<RollupJobCaps> caps = new HashSet<>(2);
        caps.add(cap);
        caps.add(cap2);

        String[] normalIndices = new String[]{ESTestCase.randomAlphaOfLength(10)};
        String[] rollupIndices = new String[]{ESTestCase.randomAlphaOfLength(10)};
        String[] combinedIndices = new String[]{normalIndices[0], rollupIndices[0]};

        TransportRollupSearchAction.RollupSearchContext ctx
                = new TransportRollupSearchAction.RollupSearchContext(normalIndices, rollupIndices, caps);

        SearchSourceBuilder source = new SearchSourceBuilder();
        source.query(getQueryBuilder(1));
        source.size(0);
        source.aggregation(new DateHistogramAggregationBuilder("foo").field("foo")
                .dateHistogramInterval(job.getGroupConfig().getDateHisto().getInterval()));
        SearchRequest request = new SearchRequest(combinedIndices, source);

        MultiSearchRequest msearch = TransportRollupSearchAction.createMSearchRequest(request, namedWriteableRegistry, ctx);
        assertThat(msearch.requests().size(), equalTo(2));

        assertThat(msearch.requests().get(0), equalTo(new SearchRequest(normalIndices, request.source())));
        SearchRequest normal = msearch.requests().get(0);
        assertThat(normal.indices().length, equalTo(1));
        assertThat(normal.indices()[0], equalTo(normalIndices[0]));

        SearchRequest rollup = msearch.requests().get(1);
        assertThat(rollup.indices().length, equalTo(1));
        assertThat(rollup.indices()[0], equalTo(rollupIndices[0]));
        assert(rollup.source().aggregations().getAggregatorFactories().get(0) instanceof FilterAggregationBuilder);


        // The executed query should match the first job ("foo") because the second job contained a histo and the first didn't,
        // so the first job will be "better"
        BoolQueryBuilder bool1 = new BoolQueryBuilder()
                .must(TransportRollupSearchAction.rewriteQuery(request.source().query(), caps))
                .filter(new TermQueryBuilder(RollupField.formatMetaField(RollupField.ID.getPreferredName()), "foo"))
                .filter(new TermQueryBuilder(RollupField.formatMetaField(RollupField.VERSION_FIELD), Rollup.ROLLUP_VERSION));
        assertThat(msearch.requests().get(1).source().query(), equalTo(bool1));
    }

    public void testNoIndicesToSeparate() {
        String[] indices = new String[]{};
        ImmutableOpenMap<String, IndexMetaData> meta = ImmutableOpenMap.<String, IndexMetaData>builder().build();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.separateIndices(indices, meta));
    }

    public void testSeparateAll() {
        String[] indices = new String[]{MetaData.ALL, "foo"};
        ImmutableOpenMap<String, IndexMetaData> meta = ImmutableOpenMap.<String, IndexMetaData>builder().build();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.separateIndices(indices, meta));
        assertThat(e.getMessage(), equalTo("Searching _all via RollupSearch endpoint is not supported at this time."));
    }

    public void testEmptyMetadata() {
        String[] indices = new String[]{"foo", "bar"};
        ImmutableOpenMap<String, IndexMetaData> meta = ImmutableOpenMap.<String, IndexMetaData>builder().build();
        TransportRollupSearchAction.RollupSearchContext result
                = TransportRollupSearchAction.separateIndices(indices, meta);
        assertThat(result.getLiveIndices().length, equalTo(2));
        assertThat(result.getRollupIndices().length, equalTo(0));
        assertThat(result.getJobCaps().size(), equalTo(0));
    }

    public void testNoMatchingIndexInMetadata() {
        String[] indices = new String[]{"foo"};
        IndexMetaData indexMetaData = mock(IndexMetaData.class);
        ImmutableOpenMap.Builder<String, IndexMetaData> meta = ImmutableOpenMap.builder(1);
        meta.put("bar", indexMetaData);
        TransportRollupSearchAction.RollupSearchContext result
                = TransportRollupSearchAction.separateIndices(indices, meta.build());
        assertThat(result.getLiveIndices().length, equalTo(1));
        assertThat(result.getRollupIndices().length, equalTo(0));
        assertThat(result.getJobCaps().size(), equalTo(0));
    }

    public void testMatchingIndexInMetadata() throws IOException {
        String[] indices = new String[]{"foo"};

        String jobName = randomAlphaOfLength(5);
        RollupJobConfig job = ConfigTestHelpers.getRollupJob(jobName).build();

        MappingMetaData mappingMeta = new MappingMetaData(RollupField.TYPE_NAME,
                Collections.singletonMap(RollupField.TYPE_NAME,
                        Collections.singletonMap("_meta",
                                Collections.singletonMap(RollupField.ROLLUP_META,
                                        Collections.singletonMap(jobName, job)))));

        ImmutableOpenMap.Builder<String, MappingMetaData> mappings = ImmutableOpenMap.builder(1);
        mappings.put(RollupField.TYPE_NAME, mappingMeta);
        IndexMetaData meta = Mockito.mock(IndexMetaData.class);
        when(meta.getMappings()).thenReturn(mappings.build());

        ImmutableOpenMap.Builder<String, IndexMetaData> metaMap = ImmutableOpenMap.builder(1);
        metaMap.put("foo", meta);
        TransportRollupSearchAction.RollupSearchContext result
                = TransportRollupSearchAction.separateIndices(indices, metaMap.build());
        assertThat(result.getLiveIndices().length, equalTo(0));
        assertThat(result.getRollupIndices().length, equalTo(1));
        assertThat(result.getRollupIndices()[0], equalTo("foo"));
        assertThat(result.getJobCaps().size(), equalTo(1));
    }

    public void testLiveOnlyProcess() {
        String[] indices = new String[]{"foo"};
        IndexMetaData indexMetaData = mock(IndexMetaData.class);
        ImmutableOpenMap.Builder<String, IndexMetaData> meta = ImmutableOpenMap.builder(1);
        meta.put("bar", indexMetaData);
        TransportRollupSearchAction.RollupSearchContext result
                = TransportRollupSearchAction.separateIndices(indices, meta.build());

        SearchResponse response = mock(SearchResponse.class);
        MultiSearchResponse.Item item = new MultiSearchResponse.Item(response, null);
        MultiSearchResponse msearchResponse = new MultiSearchResponse(new MultiSearchResponse.Item[]{item});

        SearchResponse r = TransportRollupSearchAction.processResponses(result,
                msearchResponse, mock(InternalAggregation.ReduceContext.class));
        assertThat(r, equalTo(response));
    }

    public void testRollupOnly() throws IOException {
        String[] indices = new String[]{"foo"};

        String jobName = randomAlphaOfLength(5);
        RollupJobConfig job = ConfigTestHelpers.getRollupJob(jobName).build();

        MappingMetaData mappingMeta = new MappingMetaData(RollupField.TYPE_NAME,
                Collections.singletonMap(RollupField.TYPE_NAME,
                        Collections.singletonMap("_meta",
                                Collections.singletonMap(RollupField.ROLLUP_META,
                                        Collections.singletonMap(jobName, job)))));

        ImmutableOpenMap.Builder<String, MappingMetaData> mappings = ImmutableOpenMap.builder(1);
        mappings.put(RollupField.TYPE_NAME, mappingMeta);
        IndexMetaData indexMeta = Mockito.mock(IndexMetaData.class);
        when(indexMeta.getMappings()).thenReturn(mappings.build());

        ImmutableOpenMap.Builder<String, IndexMetaData> metaMap = ImmutableOpenMap.builder(1);
        metaMap.put("foo", indexMeta);
        TransportRollupSearchAction.RollupSearchContext result
                = TransportRollupSearchAction.separateIndices(indices, metaMap.build());

        SearchResponse response = mock(SearchResponse.class);
        when(response.getTook()).thenReturn(new TimeValue(100));
        List<InternalAggregation> aggTree = new ArrayList<>(1);
        InternalFilter filter = mock(InternalFilter.class);

        List<InternalAggregation> subaggs = new ArrayList<>(2);
        Map<String, Object> metadata = new HashMap<>(1);
        metadata.put(RollupField.ROLLUP_META + "." + COUNT_FIELD, "foo." + COUNT_FIELD);
        InternalSum sum = mock(InternalSum.class);
        when(sum.getValue()).thenReturn(10.0);
        when(sum.value()).thenReturn(10.0);
        when(sum.getName()).thenReturn("foo");
        when(sum.getMetaData()).thenReturn(metadata);
        when(sum.getType()).thenReturn(SumAggregationBuilder.NAME);
        subaggs.add(sum);

        InternalSum count = mock(InternalSum.class);
        when(count.getValue()).thenReturn(2.0);
        when(count.value()).thenReturn(2.0);
        when(count.getName()).thenReturn("foo." + RollupField.COUNT_FIELD);
        when(count.getMetaData()).thenReturn(null);
        when(count.getType()).thenReturn(SumAggregationBuilder.NAME);
        subaggs.add(count);

        when(filter.getAggregations()).thenReturn(new InternalAggregations(subaggs));
        when(filter.getName()).thenReturn("filter_foo");
        aggTree.add(filter);

        Aggregations mockAggs = new InternalAggregations(aggTree);
        when(response.getAggregations()).thenReturn(mockAggs);
        MultiSearchResponse.Item item = new MultiSearchResponse.Item(response, null);
        MultiSearchResponse msearchResponse = new MultiSearchResponse(new MultiSearchResponse.Item[]{item});

        SearchResponse r = TransportRollupSearchAction.processResponses(result,
                msearchResponse, mock(InternalAggregation.ReduceContext.class));

        assertNotNull(r);
        Aggregations responseAggs = r.getAggregations();
        Avg avg = responseAggs.get("foo");
        assertThat(avg.getValue(), IsEqual.equalTo(5.0));
    }

    public void testTooManyRollups() throws IOException {
        String[] indices = new String[]{"foo", "bar"};

        String jobName = randomAlphaOfLength(5);
        RollupJobConfig job = ConfigTestHelpers.getRollupJob(jobName).build();

        MappingMetaData mappingMeta = new MappingMetaData(RollupField.TYPE_NAME,
                Collections.singletonMap(RollupField.TYPE_NAME,
                        Collections.singletonMap("_meta",
                                Collections.singletonMap(RollupField.ROLLUP_META,
                                        Collections.singletonMap(jobName, job)))));

        ImmutableOpenMap.Builder<String, MappingMetaData> mappings = ImmutableOpenMap.builder(1);
        mappings.put(RollupField.TYPE_NAME, mappingMeta);
        IndexMetaData indexMeta = Mockito.mock(IndexMetaData.class);
        when(indexMeta.getMappings()).thenReturn(mappings.build());

        ImmutableOpenMap.Builder<String, IndexMetaData> metaMap = ImmutableOpenMap.builder(2);
        metaMap.put("foo", indexMeta);
        metaMap.put("bar", indexMeta);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> TransportRollupSearchAction.separateIndices(indices, metaMap.build()));
        assertThat(e.getMessage(), equalTo("RollupSearch currently only supports searching one rollup index at a time."));
    }

    public void testEmptyMsearch() {
        TransportRollupSearchAction.RollupSearchContext result
                = new TransportRollupSearchAction.RollupSearchContext(new String[0], new String[0], Collections.emptySet());
        MultiSearchResponse msearchResponse = new MultiSearchResponse(new MultiSearchResponse.Item[0]);

        RuntimeException e = expectThrows(RuntimeException.class, () -> TransportRollupSearchAction.processResponses(result,
                msearchResponse, mock(InternalAggregation.ReduceContext.class)));
        assertThat(e.getMessage(), equalTo("MSearch response was empty, cannot unroll RollupSearch results"));
    }

    public void testBoth() throws IOException {
        String[] indices = new String[]{"foo", "bar"};

        String jobName = randomAlphaOfLength(5);
        RollupJobConfig job = ConfigTestHelpers.getRollupJob(jobName).build();

        MappingMetaData mappingMeta = new MappingMetaData(RollupField.TYPE_NAME,
                Collections.singletonMap(RollupField.TYPE_NAME,
                        Collections.singletonMap("_meta",
                                Collections.singletonMap(RollupField.ROLLUP_META,
                                        Collections.singletonMap(jobName, job)))));

        ImmutableOpenMap.Builder<String, MappingMetaData> mappings = ImmutableOpenMap.builder(1);
        mappings.put(RollupField.TYPE_NAME, mappingMeta);
        IndexMetaData indexMeta = Mockito.mock(IndexMetaData.class);
        when(indexMeta.getMappings()).thenReturn(mappings.build());

        MappingMetaData liveMappingMetadata = new MappingMetaData("bar", Collections.emptyMap());

        ImmutableOpenMap.Builder<String, MappingMetaData> liveMappings = ImmutableOpenMap.builder(1);
        liveMappings.put("bar", liveMappingMetadata);
        IndexMetaData liveIndexMeta = Mockito.mock(IndexMetaData.class);
        when(liveIndexMeta.getMappings()).thenReturn(liveMappings.build());

        ImmutableOpenMap.Builder<String, IndexMetaData> metaMap = ImmutableOpenMap.builder(2);
        metaMap.put("foo", indexMeta);
        metaMap.put("bar", liveIndexMeta);
        TransportRollupSearchAction.RollupSearchContext separateIndices
                = TransportRollupSearchAction.separateIndices(indices, metaMap.build());


        SearchResponse protoResponse = mock(SearchResponse.class);
        when(protoResponse.getTook()).thenReturn(new TimeValue(100));
        List<InternalAggregation> protoAggTree = new ArrayList<>(1);
        InternalAvg internalAvg = new InternalAvg("foo", 10, 2, DocValueFormat.RAW, Collections.emptyList(), null);
        protoAggTree.add(internalAvg);
        Aggregations protoMockAggs = new InternalAggregations(protoAggTree);
        when(protoResponse.getAggregations()).thenReturn(protoMockAggs);
        MultiSearchResponse.Item unrolledResponse = new MultiSearchResponse.Item(protoResponse, null);

        SearchResponse responseWithout = mock(SearchResponse.class);
        when(responseWithout.getTook()).thenReturn(new TimeValue(100));
        List<InternalAggregation> aggTree = new ArrayList<>(1);
        InternalFilter filter = mock(InternalFilter.class);

        List<InternalAggregation> subaggs = new ArrayList<>(2);
        Map<String, Object> metadata = new HashMap<>(1);
        metadata.put(RollupField.ROLLUP_META + "." + COUNT_FIELD, "foo." + COUNT_FIELD);
        InternalSum sum = mock(InternalSum.class);
        when(sum.getValue()).thenReturn(10.0);
        when(sum.value()).thenReturn(10.0);
        when(sum.getName()).thenReturn("foo");
        when(sum.getMetaData()).thenReturn(metadata);
        when(sum.getType()).thenReturn(SumAggregationBuilder.NAME);
        subaggs.add(sum);

        InternalSum count = mock(InternalSum.class);
        when(count.getValue()).thenReturn(2.0);
        when(count.value()).thenReturn(2.0);
        when(count.getName()).thenReturn("foo." + RollupField.COUNT_FIELD);
        when(count.getMetaData()).thenReturn(null);
        when(count.getType()).thenReturn(SumAggregationBuilder.NAME);
        subaggs.add(count);

        when(filter.getAggregations()).thenReturn(new InternalAggregations(subaggs));
        when(filter.getName()).thenReturn("filter_foo");
        aggTree.add(filter);

        Aggregations mockAggsWithout = new InternalAggregations(aggTree);
        when(responseWithout.getAggregations()).thenReturn(mockAggsWithout);
        MultiSearchResponse.Item rolledResponse = new MultiSearchResponse.Item(responseWithout, null);

        MultiSearchResponse msearchResponse
                = new MultiSearchResponse(new MultiSearchResponse.Item[]{unrolledResponse, rolledResponse});

        BigArrays bigArrays = new MockBigArrays(new MockPageCacheRecycler(Settings.EMPTY), new NoneCircuitBreakerService());
        ScriptService scriptService = mock(ScriptService.class);

        SearchResponse response = TransportRollupSearchAction.processResponses(separateIndices, msearchResponse,
                mock(InternalAggregation.ReduceContext.class));


        assertNotNull(response);
        Aggregations responseAggs = response.getAggregations();
        assertNotNull(responseAggs);
        Avg avg = responseAggs.get("foo");
        assertThat(avg.getValue(), IsEqual.equalTo(5.0));

    }

    private Set<RollupJobCaps> singletonSet(RollupJobCaps cap) {
        Set<RollupJobCaps> caps = new HashSet<>();
        caps.add(cap);
        return caps;
    }
}
