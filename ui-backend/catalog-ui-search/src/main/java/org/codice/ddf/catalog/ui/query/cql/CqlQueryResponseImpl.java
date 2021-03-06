/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.catalog.ui.query.cql;

import static ddf.catalog.Constants.EXPERIMENTAL_FACET_RESULTS_KEY;

import ddf.action.ActionRegistry;
import ddf.catalog.Constants;
import ddf.catalog.data.AttributeDescriptor;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.MetacardType;
import ddf.catalog.data.Result;
import ddf.catalog.filter.FilterAdapter;
import ddf.catalog.operation.FacetAttributeResult;
import ddf.catalog.operation.FacetValueCount;
import ddf.catalog.operation.Query;
import ddf.catalog.operation.QueryRequest;
import ddf.catalog.operation.QueryResponse;
import ddf.catalog.operation.ResultHighlight;
import ddf.catalog.source.UnsupportedQueryException;
import ddf.catalog.source.solr.SolrMetacardClientImpl;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.codice.ddf.catalog.ui.query.delegate.SearchTerm;
import org.codice.ddf.catalog.ui.query.delegate.SearchTermsDelegate;
import org.codice.ddf.catalog.ui.query.utility.CqlQueryResponse;
import org.codice.ddf.catalog.ui.query.utility.CqlResult;
import org.codice.ddf.catalog.ui.query.utility.MetacardAttribute;
import org.codice.ddf.catalog.ui.query.utility.Status;
import org.codice.ddf.catalog.ui.security.LogSanitizer;
import org.codice.ddf.catalog.ui.transformer.TransformerDescriptors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlQueryResponseImpl implements CqlQueryResponse {

  private static final Logger LOGGER = LoggerFactory.getLogger(CqlQueryResponseImpl.class);

  private static final SearchTermsDelegate SEARCH_TERMS_DELEGATE = new SearchTermsDelegate();

  private final List<CqlResult> results;

  private final String id;

  private final Map<String, Map<String, MetacardAttribute>> types;

  private final Status status;

  private final Map<String, List<FacetValueCount>> facets;

  private final List<String> showingResultsForFields, didYouMeanFields;

  private final Boolean userSpellcheckIsOn;

  private final List<ResultHighlight> highlights;

  private final Set<String> warnings = new HashSet<>();

  // Transient so as not to be serialized to/from JSON
  private final transient QueryResponse queryResponse;

  public CqlQueryResponseImpl(
      String id,
      QueryRequest request,
      QueryResponse queryResponse,
      String source,
      long elapsedTime,
      boolean normalize,
      FilterAdapter filterAdapter,
      ActionRegistry actionRegistry,
      TransformerDescriptors descriptors) {
    this.id = id;

    this.queryResponse = queryResponse;

    status = new StatusImpl(queryResponse, source, elapsedTime);

    AtomicBoolean logOnceState = new AtomicBoolean(false);
    Consumer<String> logOnce =
        (str) -> {
          if (logOnceState.compareAndSet(false, true)) {
            LOGGER.debug(str);
          }
        };

    types =
        queryResponse
            .getResults()
            .stream()
            .map(Result::getMetacard)
            .filter(Objects::nonNull)
            .map(Metacard::getMetacardType)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet())
            .stream()
            .collect(
                Collectors.toMap(
                    MetacardType::getName,
                    mt ->
                        mt.getAttributeDescriptors()
                            .stream()
                            .collect(
                                Collectors.toMap(
                                    AttributeDescriptor::getName,
                                    MetacardAttributeImpl::new,
                                    (ad1, ad2) -> {
                                      logOnce.accept(
                                          "Removed duplicate attribute descriptor(s). For more information:\n"
                                              + "(log:set trace org.codice.ddf.catalog.ui.query.cql)");
                                      LOGGER.trace(
                                          "Removed duplicate attribute descriptor.({})",
                                          LogSanitizer.sanitize(ad1));
                                      return ad1;
                                    })),
                    (mt1, mt2) -> {
                      LOGGER.debug("Removed duplicate metacard type.");
                      return mt1;
                    }));

    final Set<SearchTerm> searchTerms = extractSearchTerms(request.getQuery(), filterAdapter);
    results =
        queryResponse
            .getResults()
            .stream()
            .map(
                result ->
                    new CqlResultImpl(
                        result,
                        searchTerms,
                        queryResponse.getRequest(),
                        normalize,
                        filterAdapter,
                        actionRegistry))
            .map(cqlResult -> new CqlResultImpl(cqlResult, descriptors))
            .collect(Collectors.toList());

    this.facets = getFacetResults(queryResponse.getPropertyValue(EXPERIMENTAL_FACET_RESULTS_KEY));
    this.didYouMeanFields =
        (List<String>) queryResponse.getProperties().get(SolrMetacardClientImpl.DID_YOU_MEAN_KEY);
    this.showingResultsForFields =
        (List<String>)
            queryResponse.getProperties().get(SolrMetacardClientImpl.SHOWING_RESULTS_FOR_KEY);
    this.userSpellcheckIsOn =
        (Boolean) queryResponse.getProperties().get(SolrMetacardClientImpl.SPELLCHECK_KEY);
    this.highlights =
        (List<ResultHighlight>) queryResponse.getProperties().get(Constants.QUERY_HIGHLIGHT_KEY);
  }

  private Map<String, List<FacetValueCount>> getFacetResults(Serializable facetResults) {
    if (!(facetResults instanceof List)) {
      return Collections.emptyMap();
    }
    List<Object> list = (List<Object>) facetResults;
    return list.stream()
        .filter(result -> result instanceof FacetAttributeResult)
        .map(result -> (FacetAttributeResult) result)
        .collect(
            Collectors.toMap(
                FacetAttributeResult::getAttributeName,
                FacetAttributeResult::getFacetValues,
                (a, b) -> b));
  }

  private Set<SearchTerm> extractSearchTerms(Query query, FilterAdapter filterAdapter) {
    Set<SearchTerm> searchTerms = Collections.emptySet();
    try {
      searchTerms = filterAdapter.adapt(query, SEARCH_TERMS_DELEGATE);
    } catch (UnsupportedQueryException e) {
      LOGGER.debug("Unable to parse search terms", e);
    }
    return searchTerms;
  }

  public QueryResponse getQueryResponse() {
    return queryResponse;
  }

  public List<CqlResult> getResults() {
    return results;
  }

  public Map<String, Map<String, MetacardAttribute>> getTypes() {
    return types;
  }

  public String getId() {
    return id;
  }

  public Status getStatus() {
    return status;
  }

  public Set<String> getWarnings() {
    return warnings;
  }
}
