/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.indexer.searches;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.ImmutableSortedSet;
import io.searchbox.action.Action;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestResult;
import io.searchbox.core.Count;
import io.searchbox.core.Search;
import io.searchbox.core.search.aggregation.CardinalityAggregation;
import io.searchbox.core.search.aggregation.ExtendedStatsAggregation;
import io.searchbox.core.search.aggregation.FilterAggregation;
import io.searchbox.core.search.aggregation.HistogramAggregation;
import io.searchbox.core.search.aggregation.MissingAggregation;
import io.searchbox.core.search.aggregation.TermsAggregation;
import io.searchbox.core.search.aggregation.ValueCountAggregation;
import io.searchbox.core.search.sort.Sort;
import io.searchbox.params.Parameters;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortParseElement;
import org.graylog2.Configuration;
import org.graylog2.database.NotFoundException;
import org.graylog2.indexer.IndexHelper;
import org.graylog2.indexer.IndexMapping;
import org.graylog2.indexer.IndexSet;
import org.graylog2.indexer.indices.Indices;
import org.graylog2.indexer.ranges.IndexRange;
import org.graylog2.indexer.ranges.IndexRangeService;
import org.graylog2.indexer.results.CountResult;
import org.graylog2.indexer.results.DateHistogramResult;
import org.graylog2.indexer.results.FieldHistogramResult;
import org.graylog2.indexer.results.FieldStatsResult;
import org.graylog2.indexer.results.HistogramResult;
import org.graylog2.indexer.results.ResultMessage;
import org.graylog2.indexer.results.ScrollResult;
import org.graylog2.indexer.results.SearchResult;
import org.graylog2.indexer.results.TermsResult;
import org.graylog2.indexer.results.TermsStatsResult;
import org.graylog2.indexer.searches.timeranges.TimeRanges;
import org.graylog2.plugin.indexer.searches.timeranges.TimeRange;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.streams.StreamService;
import org.joda.time.Period;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.codahale.metrics.MetricRegistry.name;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.index.query.QueryBuilders.queryStringQuery;

@Singleton
public class Searches {
    private static final Logger LOG = LoggerFactory.getLogger(Searches.class);

    public final static String AGG_TERMS = "gl2_terms";
    public final static String AGG_STATS = "gl2_stats";
    public final static String AGG_TERMS_STATS = "gl2_termsstats";
    public static final String AGG_FILTER = "gl2_filter";
    public static final String AGG_HISTOGRAM = "gl2_histogram";
    public static final String AGG_EXTENDED_STATS = "gl2_extended_stats";
    public static final String AGG_CARDINALITY = "gl2_field_cardinality";
    public static final String AGG_VALUE_COUNT = "gl2_value_count";
    private static final Pattern filterStreamIdPattern = Pattern.compile("^(.+[^\\p{Alnum}])?streams:([\\p{XDigit}]+)");

    public enum TermsStatsOrder {
        TERM,
        REVERSE_TERM,
        COUNT,
        REVERSE_COUNT,
        TOTAL,
        REVERSE_TOTAL,
        MIN,
        REVERSE_MIN,
        MAX,
        REVERSE_MAX,
        MEAN,
        REVERSE_MEAN
    }

    public enum DateHistogramInterval {
        YEAR(Period.years(1)),
        QUARTER(Period.months(3)),
        MONTH(Period.months(1)),
        WEEK(Period.weeks(1)),
        DAY(Period.days(1)),
        HOUR(Period.hours(1)),
        MINUTE(Period.minutes(1));

        @SuppressWarnings("ImmutableEnumChecker")
        private final Period period;

        DateHistogramInterval(Period period) {
            this.period = period;
        }

        public Period getPeriod() {
            return period;
        }

        public org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval toESInterval() {
            switch (this.name()) {
                case "MINUTE":
                    return org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval.MINUTE;
                case "HOUR":
                    return org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval.HOUR;
                case "DAY":
                    return org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval.DAY;
                case "WEEK":
                    return org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval.WEEK;
                case "MONTH":
                    return org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval.MONTH;
                case "QUARTER":
                    return org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval.QUARTER;
                default:
                    return org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval.YEAR;
            }
        }
    }


    private final Configuration configuration;
    private final IndexRangeService indexRangeService;
    private final Timer esRequestTimer;
    private final Histogram esTimeRangeHistogram;
    private final StreamService streamService;
    private final Indices indices;
    private final JestClient jestClient;
    private final ScrollResult.Factory scrollResultFactory;

    @Inject
    public Searches(Configuration configuration,
                    IndexRangeService indexRangeService,
                    MetricRegistry metricRegistry,
                    StreamService streamService,
                    Indices indices,
                    JestClient jestClient,
                    ScrollResult.Factory scrollResultFactory) {
        this.configuration = checkNotNull(configuration);
        this.indexRangeService = checkNotNull(indexRangeService);

        this.esRequestTimer = metricRegistry.timer(name(Searches.class, "elasticsearch", "requests"));
        this.esTimeRangeHistogram = metricRegistry.histogram(name(Searches.class, "elasticsearch", "ranges"));
        this.streamService = streamService;
        this.indices = indices;
        this.jestClient = jestClient;
        this.scrollResultFactory = scrollResultFactory;
    }

    public CountResult count(String query, TimeRange range) {
        return count(query, range, null);
    }

    public CountResult count(String query, TimeRange range, String filter) {
        final SearchSourceBuilder searchSourceBuilder;
        if (filter == null) {
            searchSourceBuilder = standardSearchRequest(query, range);
        } else {
            searchSourceBuilder = filteredSearchRequest(query, filter, range);
        }
        searchSourceBuilder.size(0);

        final Set<String> indices = determineAffectedIndices(range, filter);

        final Count.Builder builder = new Count.Builder()
            .query(searchSourceBuilder.toString())
            .addIndex(indices);

        final Count count = builder.build();

        final io.searchbox.core.CountResult countResult = execute(count);
        // TODO: fix usage of tookms
        recordEsMetrics(0, range);
        return CountResult.create(countResult.getCount().longValue(), 0);
    }

    public ScrollResult scroll(String query, TimeRange range, int limit, int offset, List<String> fields, String filter) {
        final Set<String> indices = determineAffectedIndices(range, filter);

        // only request the fields we asked for otherwise we can't figure out which fields will be in the result set
        // until we've scrolled through the entire set.
        // TODO: Check if we can get away without loading the _source field.
        // http://www.elasticsearch.org/guide/en/elasticsearch/reference/current/search-request-fields.html#search-request-fields
        // "For backwards compatibility, if the fields parameter specifies fields which are not stored , it will
        // load the _source and extract it from it. This functionality has been replaced by the source filtering
        // parameter." -- So we should look at the source filtering parameter once we switched to ES 1.x.

        final Search.Builder initialSearchBuilder = new Search.Builder(query)
            .addType(IndexMapping.TYPE_MESSAGE)
            .setParameter(Parameters.SIZE, limit)
            .setParameter(Parameters.SCROLL, "1m")
            .addSort(new Sort(SortParseElement.DOC_FIELD_NAME))
            .addSourceIncludePattern("_source")
            .addIndex(indices);
        fields.forEach(initialSearchBuilder::addSourceIncludePattern);

        final io.searchbox.core.SearchResult initialResult = execute(initialSearchBuilder.build());
        final long tookMs = tookMsFromSearchResult(initialResult);
        recordEsMetrics(tookMs, range);

        return scrollResultFactory.create(initialResult, query, fields);
    }

    public SearchResult search(String query, TimeRange range, int limit, int offset, Sorting sorting) {
        return search(query, null, range, limit, offset, sorting);
    }

    public SearchResult search(String query, String filter, TimeRange range, int limit, int offset, Sorting sorting) {
        final SearchesConfig searchesConfig = SearchesConfig.builder()
                .query(query)
                .filter(filter)
                .range(range)
                .limit(limit)
                .offset(offset)
                .sorting(sorting)
                .build();

        return search(searchesConfig);
    }

    public SearchResult search(SearchesConfig config) {
        final Set<IndexRange> indexRanges = determineAffectedIndicesWithRanges(config.range(), config.filter());
        final Set<String> indices = indexRanges.stream().map(IndexRange::indexName).collect(Collectors.toSet());

        final SearchSourceBuilder requestBuilder = searchRequest(config);

        final Search.Builder searchBuilder = new Search.Builder(requestBuilder.toString())
            .addType(IndexMapping.TYPE_MESSAGE)
            .addIndex(indices);

        final io.searchbox.core.SearchResult searchResult = execute(searchBuilder.build());
        final List<ResultMessage> hits = searchResult.getHits(Map.class).stream()
            .map(hit -> ResultMessage.parseFromSource(hit.id, hit.index, (Map<String, Object>)hit.source))
            .collect(Collectors.toList());
        final long tookMs = tookMsFromSearchResult(searchResult);
        recordEsMetrics(tookMs, config.range());

        return new SearchResult(hits, indexRanges, config.query(), requestBuilder.buildAsBytes(), new TimeValue(tookMs));
    }

    private long tookMsFromSearchResult(io.searchbox.core.SearchResult searchResult) {
        return new Double(searchResult.getValue("took").toString()).longValue();
    }

    public TermsResult terms(String field, int size, String query, String filter, TimeRange range, Sorting.Direction sorting) {
        final Terms.Order termsOrder = sorting == Sorting.Direction.DESC ? Terms.Order.count(false) : Terms.Order.count(true);

        final SearchSourceBuilder searchSourceBuilder = filter == null ? standardSearchRequest(query, range) : filteredSearchRequest(query, filter, range);

        final FilterAggregationBuilder filterBuilder = AggregationBuilders.filter(AGG_FILTER)
            .subAggregation(
                AggregationBuilders.terms(AGG_TERMS)
                    .field(field)
                    .size(size > 0 ? size : 50)
                    .order(termsOrder)
            )
            .subAggregation(
                AggregationBuilders.missing("missing")
                    .field(field)
            )
            .filter(standardAggregationFilters(range, filter));

        searchSourceBuilder.aggregation(filterBuilder);

        final Set<String> affectedIndices = determineAffectedIndices(range, filter);
        final Search.Builder searchBuilder = new Search.Builder(searchSourceBuilder.toString())
            .addType(IndexMapping.TYPE_MESSAGE)
            .addIndex(affectedIndices);

        final io.searchbox.core.SearchResult searchResult = execute(searchBuilder.build());
        final long tookMs = tookMsFromSearchResult(searchResult);

        recordEsMetrics(tookMs, range);

        final FilterAggregation filterAggregation = searchResult.getAggregations().getFilterAggregation(AGG_FILTER);
        final TermsAggregation termsAggregation = filterAggregation.getTermsAggregation(AGG_TERMS);
        final MissingAggregation missing = filterAggregation.getMissingAggregation("missing");

        return new TermsResult(
                termsAggregation,
                missing.getMissing(),
                filterAggregation.getCount(),
                query,
                searchSourceBuilder.buildAsBytes(),
                new TimeValue(tookMs)
        );
    }

    public TermsResult terms(String field, int size, String query, String filter, TimeRange range) {
        return terms(field, size, query, filter, range, Sorting.Direction.DESC);
    }

    public TermsResult terms(String field, int size, String query, TimeRange range) {
        return terms(field, size, query, null, range, Sorting.Direction.DESC);
    }

    public TermsStatsResult termsStats(String keyField, String valueField, TermsStatsOrder order, int size, String query, String filter, TimeRange range) {
        if (size == 0) {
            size = 50;
        }

        final Set<String> affectedIndices = determineAffectedIndices(range, filter);

        final SearchSourceBuilder searchSourceBuilder;
        if (filter == null) {
            searchSourceBuilder = standardSearchRequest(query, range);
        } else {
            searchSourceBuilder = filteredSearchRequest(query, filter, range);
        }


        Terms.Order termsOrder;
        switch (order) {
            case COUNT:
                termsOrder = Terms.Order.count(true);
                break;
            case REVERSE_COUNT:
                termsOrder = Terms.Order.count(false);
                break;
            case TERM:
                termsOrder = Terms.Order.term(true);
                break;
            case REVERSE_TERM:
                termsOrder = Terms.Order.term(false);
                break;
            case MIN:
                termsOrder = Terms.Order.aggregation(AGG_STATS, "min", true);
                break;
            case REVERSE_MIN:
                termsOrder = Terms.Order.aggregation(AGG_STATS, "min", false);
                break;
            case MAX:
                termsOrder = Terms.Order.aggregation(AGG_STATS, "max", true);
                break;
            case REVERSE_MAX:
                termsOrder = Terms.Order.aggregation(AGG_STATS, "max", false);
                break;
            case MEAN:
                termsOrder = Terms.Order.aggregation(AGG_STATS, "avg", true);
                break;
            case REVERSE_MEAN:
                termsOrder = Terms.Order.aggregation(AGG_STATS, "avg", false);
                break;
            case TOTAL:
                termsOrder = Terms.Order.aggregation(AGG_STATS, "sum", true);
                break;
            case REVERSE_TOTAL:
                termsOrder = Terms.Order.aggregation(AGG_STATS, "sum", false);
                break;
            default:
                termsOrder = Terms.Order.count(true);
        }

        final FilterAggregationBuilder builder = AggregationBuilders.filter(AGG_FILTER)
            .subAggregation(
                AggregationBuilders.terms(AGG_TERMS_STATS)
                    .field(keyField)
                    .subAggregation(AggregationBuilders.stats(AGG_STATS).field(valueField))
                    .order(termsOrder)
                    .size(size)
            )
            .filter(standardAggregationFilters(range, filter));

        searchSourceBuilder.aggregation(builder);

        final Search.Builder searchBuilder = new Search.Builder(searchSourceBuilder.toString())
            .addType(IndexMapping.TYPE_MESSAGE)
            .addIndex(affectedIndices);

        final io.searchbox.core.SearchResult searchResult = execute(searchBuilder.build());
        final long tookMs = tookMsFromSearchResult(searchResult);
        recordEsMetrics(tookMs, range);

        final FilterAggregation filterAggregation = searchResult.getAggregations().getFilterAggregation(AGG_FILTER);
        final TermsAggregation termsAggregation = filterAggregation.getTermsAggregation(AGG_TERMS_STATS);
        return new TermsStatsResult(
            termsAggregation,
            query,
            searchSourceBuilder.buildAsBytes(),
            new TimeValue(tookMs)
        );
    }

    public TermsStatsResult termsStats(String keyField, String valueField, TermsStatsOrder order, int size, String query, TimeRange range) {
        return termsStats(keyField, valueField, order, size, query, null, range);
    }

    public FieldStatsResult fieldStats(String field, String query, TimeRange range) {
        return fieldStats(field, query, null, range);
    }

    public FieldStatsResult fieldStats(String field, String query, String filter, TimeRange range) {
        // by default include the cardinality aggregation, as well.
        return fieldStats(field, query, filter, range, true, true, true);
    }

    public FieldStatsResult fieldStats(String field,
                                       String query,
                                       String filter,
                                       TimeRange range,
                                       boolean includeCardinality,
                                       boolean includeStats,
                                       boolean includeCount)
        {
        SearchSourceBuilder searchSourceBuilder;

        final Set<String> indices = indicesContainingField(determineAffectedIndices(range, filter), field);

        if (filter == null) {
            searchSourceBuilder = standardSearchRequest(query, range);
        } else {
            searchSourceBuilder = filteredSearchRequest(query, filter, range);
        }

        final FilterAggregationBuilder filterBuilder = AggregationBuilders.filter(AGG_FILTER)
                .filter(standardAggregationFilters(range, filter));
        if (includeCount) {
            filterBuilder.subAggregation(AggregationBuilders.count(AGG_VALUE_COUNT).field(field));
        }
        if (includeStats) {
            filterBuilder.subAggregation(AggregationBuilders.extendedStats(AGG_EXTENDED_STATS).field(field));
        }
        if (includeCardinality) {
            filterBuilder.subAggregation(AggregationBuilders.cardinality(AGG_CARDINALITY).field(field));
        }

        searchSourceBuilder.aggregation(filterBuilder);

        final Search.Builder searchBuilder = new Search.Builder(searchSourceBuilder.toString())
            .addType(IndexMapping.TYPE_MESSAGE)
            .addIndex(indices);

        final io.searchbox.core.SearchResult searchResponse = execute(searchBuilder.build());
        final List<ResultMessage> hits = searchResponse.getHits(Map.class).stream()
            .map(hit -> ResultMessage.parseFromSource(hit.id, hit.index, (Map<String, Object>)hit.source))
            .collect(Collectors.toList());

        final long tookMs = tookMsFromSearchResult(searchResponse);
        recordEsMetrics(tookMs, range);

        final FilterAggregation filterAggregation = searchResponse.getAggregations().getFilterAggregation(AGG_FILTER);
        final ExtendedStatsAggregation extendedStatsAggregation = filterAggregation.getExtendedStatsAggregation(AGG_EXTENDED_STATS);
        final ValueCountAggregation valueCountAggregation = filterAggregation.getValueCountAggregation(AGG_VALUE_COUNT);
        final CardinalityAggregation cardinalityAggregation = filterAggregation.getCardinalityAggregation(AGG_CARDINALITY);

        return new FieldStatsResult(
                valueCountAggregation,
                extendedStatsAggregation,
                cardinalityAggregation,
                hits,
                query,
                searchSourceBuilder.buildAsBytes(),
                new TimeValue(tookMs)
        );
    }

    private Set<String> indicesContainingField(Set<String> strings, String field) {
        return indices.getAllMessageFieldsForIndices(strings.toArray(new String[strings.size()]))
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue().contains(field))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    public HistogramResult histogram(String query, DateHistogramInterval interval, TimeRange range) {
        return histogram(query, interval, null, range);
    }

    public HistogramResult histogram(String query, DateHistogramInterval interval, String filter, TimeRange range) {
        final FilterAggregationBuilder builder = AggregationBuilders.filter(AGG_FILTER)
            .subAggregation(
                AggregationBuilders.dateHistogram(AGG_HISTOGRAM)
                    .field("timestamp")
                    .interval(interval.toESInterval())
            )
            .filter(standardAggregationFilters(range, filter));

        final QueryStringQueryBuilder qs = queryStringQuery(query)
            .allowLeadingWildcard(configuration.isAllowLeadingWildcardSearches());

        final SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
            .query(qs)
            .aggregation(builder);

        final Set<String> affectedIndices = determineAffectedIndices(range, filter);
        final Search.Builder searchBuilder = new Search.Builder(searchSourceBuilder.toString())
            .addType(IndexMapping.TYPE_MESSAGE)
            .addIndex(affectedIndices)
            .refresh(true)
            .addType(IndexMapping.TYPE_MESSAGE)
            .ignoreUnavailable(true)
            .allowNoIndices(true);

        final io.searchbox.core.SearchResult searchResult = execute(searchBuilder.build());

        final long tookMs = tookMsFromSearchResult(searchResult);
        recordEsMetrics(tookMs, range);

        final FilterAggregation filterAggregation = searchResult.getAggregations().getFilterAggregation(AGG_FILTER);
        final HistogramAggregation histogramAggregation = filterAggregation.getHistogramAggregation(AGG_HISTOGRAM);

        return new DateHistogramResult(
            histogramAggregation,
            query,
            searchSourceBuilder.buildAsBytes(),
            interval,
            new TimeValue(tookMs)
        );
    }

    public HistogramResult fieldHistogram(String query,
                                          String field,
                                          DateHistogramInterval interval,
                                          String filter,
                                          TimeRange range,
                                          boolean includeCardinality) {
        final DateHistogramBuilder dateHistogramBuilder = AggregationBuilders.dateHistogram(AGG_HISTOGRAM)
                .field("timestamp")
                .subAggregation(AggregationBuilders.stats(AGG_STATS).field(field))
                .interval(interval.toESInterval());

        if (includeCardinality) {
            dateHistogramBuilder.subAggregation(AggregationBuilders.cardinality(AGG_CARDINALITY).field(field));
        }

        final FilterAggregationBuilder filterBuilder = AggregationBuilders.filter(AGG_FILTER)
                .subAggregation(dateHistogramBuilder)
                .filter(standardAggregationFilters(range, filter));

        final QueryStringQueryBuilder qs = queryStringQuery(query)
            .allowLeadingWildcard(configuration.isAllowLeadingWildcardSearches());

        final SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
            .query(qs)
            .aggregation(filterBuilder);

        final Set<String> affectedIndices = determineAffectedIndices(range, filter);
        final Search.Builder searchBuilder = new Search.Builder(searchSourceBuilder.toString())
            .addType(IndexMapping.TYPE_MESSAGE)
            .addIndex(affectedIndices);

        final io.searchbox.core.SearchResult searchResult = execute(searchBuilder.build());

        final long tookMs = tookMsFromSearchResult(searchResult);
        recordEsMetrics(tookMs, range);

        final FilterAggregation filterAggregation = searchResult.getAggregations().getFilterAggregation(AGG_FILTER);
        final HistogramAggregation histogramAggregation = filterAggregation.getHistogramAggregation(AGG_HISTOGRAM);

        return new FieldHistogramResult(
                histogramAggregation,
                query,
                searchSourceBuilder.buildAsBytes(),
                interval,
                new TimeValue(tookMs));
    }

    private void checkForFailedShards(SearchResponse r) throws FieldTypeException {
        // unwrap shard failure due to non-numeric mapping. this happens when searching across index sets
        // if at least one of the index sets comes back with a result, the overall result will have the aggregation
        // but not considered failed entirely. however, if one shard has the error, we will refuse to respond
        // otherwise we would be showing empty graphs for non-numeric fields.
        if (r.getFailedShards() > 0) {
            final Optional<ShardSearchFailure> failure = Arrays.stream(r.getShardFailures())
                    .filter(shardSearchFailure -> shardSearchFailure.getCause() instanceof IllegalArgumentException)
                    .findFirst();
            if (failure.isPresent()) {
                throw new FieldTypeException(failure.get().getCause());
            }
        }
    }

    private SearchSourceBuilder searchRequest(SearchesConfig config) {
        final SearchSourceBuilder request;

        if (config.filter() == null) {
            request = standardSearchRequest(config.query(), config.limit(), config.offset(), config.range(), config.sorting());
        } else {
            request = filteredSearchRequest(config.query(), config.filter(), config.limit(), config.offset(), config.range(), config.sorting());
        }

        if (config.fields() != null) {
            // http://www.elasticsearch.org/guide/en/elasticsearch/reference/current/search-request-fields.html#search-request-fields
            // "For backwards compatibility, if the fields parameter specifies fields which are not stored , it will
            // load the _source and extract it from it. This functionality has been replaced by the source filtering
            // parameter."
            // TODO: Look at the source filtering parameter once we switched to ES 1.x.
            request.fields(config.fields());
        }

        return request;
    }

    private SearchSourceBuilder standardSearchRequest(String query, TimeRange range) {
        return standardSearchRequest(query, 0, 0, range, null);
    }

    private SearchSourceBuilder standardSearchRequest(String query,
                                                      int limit,
                                                      int offset,
                                                      TimeRange range,
                                                      Sorting sort) {
        return standardSearchRequest(query, limit, offset, range, sort, true);
    }

    private SearchSourceBuilder standardSearchRequest(
            String query,
            int limit,
            int offset,
            TimeRange range,
            Sorting sort,
            boolean highlight) {
        return standardSearchRequest(query, limit, offset, range, null, sort, highlight);
    }

    private SearchSourceBuilder standardSearchRequest(
            String query,
            int limit,
            int offset,
            TimeRange range,
            String filter,
            Sorting sort,
            boolean highlight) {
        if (query == null || query.trim().isEmpty()) {
            query = "*";
        }

        final QueryBuilder queryBuilder;
        if ("*".equals(query.trim())) {
            queryBuilder = matchAllQuery();
        } else {
            queryBuilder = queryStringQuery(query).allowLeadingWildcard(configuration.isAllowLeadingWildcardSearches());
        }

        final SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
            .query(QueryBuilders.boolQuery().must(queryBuilder).filter(standardFilters(range, filter)))
            .from(offset);

        if (limit > 0) {
            searchSourceBuilder.size(limit);
        }

        if (sort != null) {
            searchSourceBuilder.sort(sort.getField(), sort.asElastic());
        }

        if (highlight && configuration.isAllowHighlighting()) {
            searchSourceBuilder.highlighter()
                .requireFieldMatch(false)
                .field("*")
                .fragmentSize(0)
                .numOfFragments(0);
        }

        return searchSourceBuilder;
    }

    private SearchSourceBuilder filteredSearchRequest(String query, String filter, TimeRange range) {
        return filteredSearchRequest(query, filter, 0, 0, range, null);
    }

    private SearchSourceBuilder filteredSearchRequest(String query, String filter, int limit, int offset, TimeRange range, Sorting sort) {
        return standardSearchRequest(query, limit, offset, range, filter, sort, true);
    }

    private void recordEsMetrics(long tookMs, @Nullable TimeRange range) {
        esRequestTimer.update(tookMs, TimeUnit.MILLISECONDS);

        if (range != null) {
            esTimeRangeHistogram.update(TimeRanges.toSeconds(range));
        }
    }

    @Nullable
    private QueryBuilder standardFilters(TimeRange range, String filter) {
        BoolQueryBuilder bfb = null;

        if (range != null) {
            bfb = QueryBuilders.boolQuery();
            bfb.must(IndexHelper.getTimestampRangeFilter(range));
        }

        // Not creating a filter for a "*" value because an empty filter used to be submitted that way.
        if (!isNullOrEmpty(filter) && !"*".equals(filter)) {
            if (bfb == null) {
                bfb = QueryBuilders.boolQuery();
            }
            bfb.must(queryStringQuery(filter));
        }

        return bfb;
    }

    private QueryBuilder standardAggregationFilters(TimeRange range, String filter) {
        final QueryBuilder filterBuilder = standardFilters(range, filter);

        // Throw an exception here to avoid exposing an internal Elasticsearch exception later.
        if (filterBuilder == null) {
            throw new RuntimeException("Either range or filter must be set.");
        }

        return filterBuilder;
    }

    public static class FieldTypeException extends SearchException {
        public FieldTypeException(Throwable e) {
            super(e);
        }
    }


    /**
     * Extracts the last stream id from the filter string passed as part of the elasticsearch query. This is used later
     * to pass to possibly existing message decorators for stream-specific configurations.
     *
     * The assumption is that usually (when listing/searching messages for a stream) only a single stream filter is passed.
     * When this is not the case, only the last stream id will be taked into account.
     *
     * This is currently a workaround. A better solution would be to pass the stream id which is supposed to be the scope
     * for a search query as a separate parameter.
     *
     * @param filter the filter string like "streams:xxxyyyzzz"
     * @return the optional stream id
     */
    public static Optional<String> extractStreamId(String filter) {
        if (isNullOrEmpty(filter)) {
            return Optional.empty();
        }
        final Matcher streamIdMatcher = filterStreamIdPattern.matcher(filter);
        if (streamIdMatcher.find()) {
            return Optional.of(streamIdMatcher.group(2));
        }
        return Optional.empty();
    }

    public Set<String> determineAffectedIndices(TimeRange range,
                                                @Nullable String filter) {
        final Set<IndexRange> indexRanges = determineAffectedIndicesWithRanges(range, filter);
        return indexRanges.stream()
                .map(IndexRange::indexName)
                .collect(Collectors.toSet());
    }

    public Set<IndexRange> determineAffectedIndicesWithRanges(TimeRange range,
                                                              @Nullable String filter) {
        final Optional<String> streamId = extractStreamId(filter);
        IndexSet indexSet = null;
        // if we are searching in a stream, we are further restricting the indices using the currently
        // configure index set of that stream.
        // later on we will also test against each index range (we load all of them) to see if there are
        // additional index ranges that match, this can happen with restored archives or when the index set for
        // a stream has changed: a stream only knows about its currently configured index set, no the history
        if (streamId.isPresent()) {
            try {
                final Stream stream = streamService.load(streamId.get());
                indexSet = stream.getIndexSet();
            } catch (NotFoundException ignored) {
            }
        }

        final ImmutableSortedSet.Builder<IndexRange> indices = ImmutableSortedSet.orderedBy(IndexRange.COMPARATOR);
        final SortedSet<IndexRange> indexRanges = indexRangeService.find(range.getFrom(), range.getTo());
        for (IndexRange indexRange : indexRanges) {
            // if we aren't in a stream search, we look at all the ranges matching the time range.
            if (indexSet == null && filter == null) {
                indices.add(indexRange);
                continue;
            }

            // A range applies to this search if either: the current index set of the stream matches or a previous index set matched.
            final boolean streamInIndexRange = streamId.isPresent() && indexRange.streamIds() != null && indexRange.streamIds().contains(streamId.get());
            final boolean streamInCurrentIndexSet = indexSet != null && indexSet.isManagedIndex(indexRange.indexName());

            if (streamInIndexRange) {
                indices.add(indexRange);
            }
            if (streamInCurrentIndexSet) {
                indices.add(indexRange);
            }
        }

        return indices.build();
    }

    private <T extends JestResult> T execute(Action<T> clientRequest) {
        try {
            final T result = jestClient.execute(clientRequest);
            if (result.isSucceeded()) {
                return result;
            }

            throw new SearchException("Performing search request failed: " + result.getErrorMessage());
        } catch (IOException e) {
            throw new SearchException(e);
        }
    }
}
