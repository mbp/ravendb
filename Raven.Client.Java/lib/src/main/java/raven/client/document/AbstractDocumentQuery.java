package raven.client.document;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.lang.time.FastDateFormat;

import raven.abstractions.Default;
import raven.abstractions.basic.Lazy;
import raven.abstractions.basic.Reference;
import raven.abstractions.basic.Tuple;
import raven.abstractions.closure.Action1;
import raven.abstractions.closure.Action2;
import raven.abstractions.closure.Delegates;
import raven.abstractions.closure.Function1;
import raven.abstractions.closure.Function2;
import raven.abstractions.data.AggregationOperation;
import raven.abstractions.data.Constants;
import raven.abstractions.data.Etag;
import raven.abstractions.data.Facet;
import raven.abstractions.data.FacetResults;
import raven.abstractions.data.HighlightedField;
import raven.abstractions.data.IndexQuery;
import raven.abstractions.data.QueryOperator;
import raven.abstractions.data.QueryResult;
import raven.abstractions.data.SortedField;
import raven.abstractions.data.SpatialIndexQuery;
import raven.abstractions.extensions.ExpressionExtensions;
import raven.abstractions.extensions.JsonExtensions;
import raven.abstractions.indexing.NumberUtil;
import raven.abstractions.indexing.SpatialOptions.SpatialRelation;
import raven.abstractions.indexing.SpatialOptions.SpatialUnits;
import raven.abstractions.json.linq.RavenJToken;
import raven.abstractions.json.linq.RavenJTokenWriter;
import raven.abstractions.spatial.WktSanitizer;
import raven.abstractions.util.RavenQuery;
import raven.abstractions.util.ValueTypeUtils;
import raven.client.EscapeQueryOptions;
import raven.client.FieldHighlightings;
import raven.client.IDocumentQuery;
import raven.client.RavenQueryHighlightings;
import raven.client.RavenQueryStatistics;
import raven.client.WhereParams;
import raven.client.connection.IDatabaseCommands;
import raven.client.connection.IRavenQueryInspector;
import raven.client.document.batches.LazyQueryOperation;
import raven.client.document.sessionoperations.QueryOperation;
import raven.client.linq.LinqPathProvider;
import raven.client.listeners.IDocumentQueryListener;
import raven.client.spatial.SpatialCriteria;

import com.mysema.query.types.Expression;
import com.mysema.query.types.Path;

/**
 * A query against a Raven index
 *
 */
public abstract class AbstractDocumentQuery<T, TSelf extends AbstractDocumentQuery<T, TSelf>> implements IRavenQueryInspector, IAbstractDocumentQuery<T> {

  protected Class<T> clazz;

  protected boolean isSpatialQuery;
  protected String spatialFieldName, queryShape;
  protected SpatialUnits spatialUnits;
  protected SpatialRelation spatialRelation;
  protected double distanceErrorPct;
  private final LinqPathProvider linqPathProvider;
  protected Action1<IndexQuery> beforeQueryExecutionAction;

  protected final Set<Class<?>> rootTypes;

  static Map<Class< ? >, Function1<Object, String>> implicitStringsCache = new HashMap<Class< ? >, Function1<Object, String>>();

  /**
   * Whatever to negate the next operation
   */
  protected boolean negate;

  /**
   * The database commands to use
   */
  protected final IDatabaseCommands theDatabaseCommands;

  /**
   * The index to query
   */
  protected final String indexName;

  protected Function2<IndexQuery, Collection<Object>, Collection<Object>> transformResultsFunc;

  protected String defaultField;

  private int currentClauseDepth;

  protected Tuple<String, String> lastEquality;

  protected Map<String, RavenJToken> queryInputs = new HashMap<>();

  /**
   * The list of fields to project directly from the results
   */
  protected final String[] projectionFields;

  /**
   * The list of fields to project directly from the index on the server
   */
  protected final String[] fieldsToFetch;

  /**
   * The query listeners for this query
   */
  protected final IDocumentQueryListener[] queryListeners;
  protected final boolean isMapReduce;

  /**
   * The session for this query
   */
  protected final InMemoryDocumentSessionOperations theSession;

  /**
   * The cutoff date to use for detecting staleness in the index
   */
  protected Date cutoff;

  /**
   * The fields to order the results by
   */
  protected String[] orderByFields = new String[0];

  /**
   * The fields to highlight
   */
  protected List<HighlightedField> highlightedFields = new ArrayList<HighlightedField>();

  /**
   * Highlighter pre tags
   */
  protected String[] highlighterPreTags = new String[0];

  /**
   * Highlighter post tags
   */
  protected String[] highlighterPostTags = new String[0];

  /**
   * The types to sort the fields by (NULL if not specified)
   */
  protected Set<Tuple<String, Class< ? >>> sortByHints = new HashSet<>();

  /**
   * The page size to use when querying the index
   */
  protected Integer pageSize;

  protected QueryOperation queryOperation;

  /**
   * The query to use
   */
  protected StringBuilder queryText = new StringBuilder();

  /**
   * which record to start reading from
   */
  protected int start;

  private DocumentConvention conventions;
  /**
   * Timeout for this query
   */
  protected long timeout;
  /**
   * Should we wait for non stale results
   */
  protected boolean theWaitForNonStaleResults;
  /**
   * The paths to include when loading the query
   */
  protected Set<String> includes = new HashSet<>();
  /**
   * What aggregated operation to execute
   */
  protected EnumSet<AggregationOperation> aggregationOp = EnumSet.of(AggregationOperation.NONE);

  /**
   * Fields to group on
   */
  protected String[] groupByFields;

  /**
   * Holds the query stats
   */
  protected RavenQueryStatistics queryStats = new RavenQueryStatistics();

  /**
   * Holds the query highlightings
   */
  protected RavenQueryHighlightings highlightings = new RavenQueryHighlightings();

  /**
   * The name of the results transformer to use after executing this query
   */
  protected String resultsTransformer;

  /**
   * Determines if entities should be tracked and kept in memory
   */
  protected boolean disableEntitiesTracking;

  /*
   * Determine if query results should be cached.
   */
  protected boolean disableCaching;

  /**
   * Determine if scores of query results should be explained
   */
  protected boolean shouldExplainScores;

  protected Action1<QueryResult> afterQueryExecutedCallback;
  protected Etag cutoffEtag;
  private QueryOperator defaultOperator = QueryOperator.OR;

  private static final Pattern ESPACE_POSTFIX_WILDCARD = Pattern.compile("\\\\\\*($|\\s)");

  /**
   * Get the name of the index being queried
   */
  public String getIndexQueried() {
    return indexName;
  }

  /**
   * Grant access to the database commands
   */
  public IDatabaseCommands getDatabaseCommands() {
    return theDatabaseCommands;
  }

  /**
   * Gets the document convention from the query session
   * @return
   */
  public DocumentConvention getDocumentConvention() {
    return conventions;
  }

  /**
   * Gets the session associated with this document query
   */
  public InMemoryDocumentSessionOperations getSession() {
    return (InMemoryDocumentSessionOperations) theSession;
  }

  private long getDefaultTimeout() {
    return 15 * 1000;
  }

  public AbstractDocumentQuery(Class<T> clazz, InMemoryDocumentSessionOperations theSession, IDatabaseCommands databaseCommands, String indexName, String[] fieldsToFetch, String[] projectionFields,
      List<IDocumentQueryListener> queryListeners, boolean isMapReduce) {
    this.clazz = clazz;
    rootTypes = new HashSet<>();
    rootTypes.add(clazz);
    this.theDatabaseCommands = databaseCommands;
    this.projectionFields = projectionFields;
    this.fieldsToFetch = fieldsToFetch;
    this.queryListeners = queryListeners.toArray(new IDocumentQueryListener[0]);
    this.isMapReduce = isMapReduce;
    this.indexName = indexName;
    this.theSession = theSession;
    this.afterQueryExecutedCallback = new Action1<QueryResult>() {
      @Override
      public void apply(QueryResult result) {
        updateStatsAndHighlightings(result);
      }
    };

    conventions = theSession == null ? new DocumentConvention() : theSession.getConventions();
    linqPathProvider = new LinqPathProvider(conventions);

    if (conventions.getDefaultQueryingConsistency() == ConsistencyOptions.QUERY_YOUR_WRITES) {
      waitForNonStaleResultsAsOfLastWrite();
    }
  }

  private void updateStatsAndHighlightings(QueryResult queryResult) {
    this.queryStats.updateQueryStats(queryResult);
    this.highlightings.update(queryResult);
  }

  protected AbstractDocumentQuery(AbstractDocumentQuery<T, TSelf> other) {
    clazz = other.clazz;
    rootTypes = new HashSet<>(other.rootTypes);
    theDatabaseCommands = other.theDatabaseCommands;
    indexName = other.indexName;
    linqPathProvider = other.linqPathProvider;
    projectionFields = other.projectionFields;
    theSession = other.theSession;
    conventions = other.conventions;
    cutoff = other.cutoff;
    orderByFields = other.orderByFields;
    sortByHints = other.sortByHints;
    pageSize = other.pageSize;
    queryText = other.queryText;
    start = other.start;
    timeout = other.timeout;
    theWaitForNonStaleResults = other.theWaitForNonStaleResults;
    includes = other.includes;
    queryListeners = other.queryListeners;
    queryStats = other.queryStats;
    defaultOperator = other.defaultOperator;
    defaultField = other.defaultField;
    highlightedFields = other.highlightedFields;
    highlighterPreTags = other.highlighterPreTags;
    highlighterPostTags = other.highlighterPostTags;
    queryInputs = other.queryInputs;
    disableEntitiesTracking = other.disableEntitiesTracking;
    disableCaching = other.disableCaching;
    shouldExplainScores = other.shouldExplainScores;
    isMapReduce = false;
    fieldsToFetch = null;

    afterQueryExecuted(new Action1<QueryResult>() {
      @Override
      public void apply(QueryResult result) {
        updateStatsAndHighlightings(result);
      }
    });
  }

  @SuppressWarnings("unchecked")
  @Override
  public IDocumentQuery<T> include(String path) {
    includes.add(path);
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  @Override
  public IDocumentQuery<T> waitForNonStaleResults(long waitTimeout) {
    theWaitForNonStaleResults = true;
    cutoffEtag = null;
    cutoff = null;
    timeout = waitTimeout;
    return (IDocumentQuery<T>) this;
  }



  @SuppressWarnings("unchecked")
  protected TSelf generateQueryWithinRadiusOf(String fieldName, double radius, double latitude, double longitude) {
    generateQueryWithinRadiusOf(fieldName, radius, latitude, longitude, 0.025, (SpatialUnits) null);
    return (TSelf) this;
  }

  @SuppressWarnings("unchecked")
  protected TSelf generateQueryWithinRadiusOf(String fieldName, double radius, double latitude, double longitude, double distanceErrorPct) {
    generateQueryWithinRadiusOf(fieldName, radius, latitude, longitude, distanceErrorPct, (SpatialUnits) null);
    return (TSelf) this;
  }

  /**
   * Filter matches to be inside the specified radius
   * @param fieldName
   * @param radius
   * @param latitude
   * @param longitude
   * @param distanceErrorPct
   * @return
   */
  protected TSelf generateQueryWithinRadiusOf(String fieldName, double radius, double latitude, double longitude, double distanceErrorPct, SpatialUnits radiusUnits) {
    return generateSpatialQueryData(fieldName, SpatialIndexQuery.getQueryShapeFromLatLon(latitude, longitude, radius), SpatialRelation.WITHIN, distanceErrorPct, radiusUnits);
  }

  @SuppressWarnings("unchecked")
  protected TSelf generateSpatialQueryData(String fieldName, String shapeWKT, SpatialRelation relation) {
    generateSpatialQueryData(fieldName, shapeWKT, relation, 0.025, null);
    return (TSelf) this;
  }

  @SuppressWarnings("unchecked")
  protected TSelf generateSpatialQueryData(String fieldName, String shapeWKT, SpatialRelation relation, double distanceErrorPct) {
    generateSpatialQueryData(fieldName, shapeWKT, relation, distanceErrorPct, null);
    return (TSelf) this;
  }

  @SuppressWarnings("unchecked")
  protected TSelf generateSpatialQueryData(String fieldName, String shapeWKT, SpatialRelation relation, double distanceErrorPct, SpatialUnits radiusUnits) {
    isSpatialQuery = true;
    spatialFieldName = fieldName;
    queryShape = new WktSanitizer().sanitize(shapeWKT);
    spatialRelation = relation;
    this.distanceErrorPct = distanceErrorPct;
    spatialUnits = radiusUnits;
    return (TSelf) this;
  }

  @SuppressWarnings("unchecked")
  protected TSelf generateSpatialQueryData(String fieldName, SpatialCriteria criteria) {
    generateSpatialQueryData(fieldName, criteria, 0.025);
    return (TSelf) this;
  }

  @SuppressWarnings("unchecked")
  protected TSelf generateSpatialQueryData(String fieldName, SpatialCriteria criteria, double distanceErrorPct) {
    /*TODO:
    var wkt = criteria.Shape as string;
    if (wkt == null && criteria.Shape != null)
    {
      var jsonSerializer = DocumentConvention.CreateSerializer();

      using (var jsonWriter = new RavenJTokenWriter())
      {
        var converter = new ShapeConverter();
        jsonSerializer.Serialize(jsonWriter, criteria.Shape);
        if (!converter.TryConvert(jsonWriter.Token, out wkt))
          throw new ArgumentException("Shape");
      }
    }

    if (wkt == null)
      throw new ArgumentException("Shape");

    isSpatialQuery = true;
    spatialFieldName = fieldName;
    queryShape = new WktSanitizer().Sanitize(wkt);
    spatialRelation = criteria.Relation;
    this.distanceErrorPct = distanceErrorPct; */
    return (TSelf) this;
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> usingDefaultField(String field) {
    defaultField = field;
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> usingDefaultOperator(QueryOperator operator) {
    defaultOperator = operator;
    return (IDocumentQuery<T>) this;
  }

  //TODO: we omit  public IDocumentQueryCustomization Include<TResult, TInclude>(Expression<Func<TResult, object>> path)
  @SuppressWarnings("unchecked")
  @Override
  public IDocumentQuery<T> include(Path< ? > path) {
    include(ExpressionExtensions.toPropertyPath(path));
    return (IDocumentQuery<T>) this;
  }

  protected QueryOperation initializeQueryOperation(Action2<String, String> setOperationHeaders) {
    IndexQuery indexQuery = getIndexQuery();

    if (beforeQueryExecutionAction != null) {
      beforeQueryExecutionAction.apply(indexQuery);
    }
    return new QueryOperation(theSession,
        indexName,
        indexQuery,
        projectionFields,
        sortByHints,
        theWaitForNonStaleResults,
        setOperationHeaders,
        timeout,
        transformResultsFunc,
        includes,
        disableEntitiesTracking);
  }

  public IndexQuery getIndexQuery() {
    String query = queryText.toString();
    IndexQuery indexQuery = generateIndexQuery(query);
    return indexQuery;
  }

  public FacetResults getFacets(String facetSetupDoc, int facetStart, Integer facetPageSize) {
    IndexQuery q = getIndexQuery();
    return getDatabaseCommands().getFacets(indexName, q, facetSetupDoc, facetStart, facetPageSize);
  }

  @Override
  public FacetResults getFacets(List<Facet> facets, int facetStart, Integer facetPageSize) {
    IndexQuery q = getIndexQuery();
    return getDatabaseCommands().getFacets(indexName, q, facets, facetStart, facetPageSize);
  }

  /**
   *  Gets the query result
   *  Execute the query the first time that this is called.
   * @return The query result.
   */
  public QueryResult getQueryResult() {
    initSync();
    return queryOperation.getCurrentQueryResults().createSnapshot();
  }

  protected void initSync() {
    if (queryOperation != null) {
      return;
    }
    theSession.incrementRequestCount();
    clearSortHints(getDatabaseCommands());
    executeBeforeQueryListeners();
    queryOperation = initializeQueryOperation(new Action2<String, String>() {

      @Override
      public void apply(String first, String second) {
        getDatabaseCommands().getOperationsHeaders().put(first, second);
      }
    });
    executeActualQuery();
  }

  protected void clearSortHints(IDatabaseCommands dbCommands) {
    Set<String> keys = new HashSet<>();
    for (String key : dbCommands.getOperationsHeaders().keySet()) {
      if (key.startsWith("SortHint")) {
        keys.add(key);
      }
    }
    for (String key : keys) {
      dbCommands.getOperationsHeaders().remove(key);
    }
  }

  protected void executeActualQuery() {
    while (true) {
      try (AutoCloseable context = queryOperation.enterQueryContext()) {
        queryOperation.logQuery();
        QueryResult result = getDatabaseCommands().query(indexName, queryOperation.getIndexQuery(), includes.toArray(new String[0]));
        if (!queryOperation.isAcceptable(result)) {
          Thread.sleep(100);
          continue;
        }
        break;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    invokeAfterQueryExecuted(queryOperation.getCurrentQueryResults());
  }

  /**
   * Register the query as a lazy query in the session and return a lazy
   *  instance that will evaluate the query only when needed
   * @return
   */
  public Lazy<List<T>> lazily() {
    return lazily(null);
  }

  /**
   * Register the query as a lazy query in the session and return a lazy
   * instance that will evaluate the query only when needed
   * @param onEval
   * @return
   */
  public Lazy<List<T>> lazily(Action1<List<T>> onEval) {
    final Map<String, String> headers = new HashMap<>();
    if (queryOperation == null) {
      executeBeforeQueryListeners();
      queryOperation = initializeQueryOperation(new Action2<String, String>() {

        @Override
        public void apply(String first, String second) {
          headers.put(first, second);
        }
      });
    }

    LazyQueryOperation<T> lazyQueryOperation = new LazyQueryOperation<T>(clazz, queryOperation, afterQueryExecutedCallback, includes);
    lazyQueryOperation.setHeaders(headers);

    return ((DocumentSession) theSession).addLazyOperation(lazyQueryOperation, onEval);
  }

  protected void executeBeforeQueryListeners() {
    for (IDocumentQueryListener documentQueryListener : queryListeners) {
      documentQueryListener.beforeQueryExecuted(new DocumentQueryCustomiation((DocumentQuery< ? >) this));
    }
  }

  /**
   * Gets the fields for projection
   * @return
   */
  public Collection<String> getProjectionFields() {
    return (projectionFields != null) ? Arrays.asList(projectionFields) : Collections.<String> emptyList();
  }

  /**
   * Order the search results randomly
   */
  @SuppressWarnings("unchecked")
  @Override
  public IDocumentQuery<T> randomOrdering() {
    addOrder(Constants.RANDOM_FIELD_NAME + ";" + UUID.randomUUID(), false);
    return (IDocumentQuery<T>) this;
  }

  /**
   * Order the search results randomly using the specified seed
   * this is useful if you want to have repeatable random queries
   */
  @SuppressWarnings("unchecked")
  @Override
  public IDocumentQuery<T> randomOrdering(String seed) {
    addOrder(Constants.RANDOM_FIELD_NAME + ";" + seed, false);
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> beforeQueryExecution(Action1<IndexQuery> action) {
    beforeQueryExecutionAction = Delegates.combine(beforeQueryExecutionAction, action);
    return (IDocumentQuery<T>) this;
  }


  @SuppressWarnings("unchecked")
  @Override
  public IDocumentQuery<T> highlight(String fieldName, int fragmentLength, int fragmentCount, String fragmentsField) {
    highlightedFields.add(new HighlightedField(fieldName, fragmentLength, fragmentCount, fragmentsField));
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  @Override
  public IDocumentQuery<T> highlight(String fieldName, int fragmentLength, int fragmentCount, Reference<FieldHighlightings> fieldHighlightings) {
    highlightedFields.add(new HighlightedField(fieldName, fragmentLength, fragmentCount, null));
    fieldHighlightings.value = highlightings.addField(fieldName);
    return ((IDocumentQuery<T>) this);
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> setHighlighterTags(String preTag, String postTag) {
    this.setHighlighterTags(new String[] { preTag }, new String[] { postTag });
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> setHighlighterTags(String[] preTags, String[] postTags) {
    highlighterPreTags = preTags;
    highlighterPostTags = postTags;
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> noTracking() {
    disableEntitiesTracking = true;
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> noCaching() {
    disableCaching = true;
    return (IDocumentQuery<T>) this;
  }

  /**
   * Adds an ordering for a specific field to the query
   * @param fieldName Name of the field.
   * @param descending If set to true [descending]
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> addOrder(String fieldName, boolean descending) {
    addOrder(fieldName, descending, null);
    return (IDocumentQuery<T>) this;
  }

  /**
   * Adds an ordering for a specific field to the query and specifies the type of field for sorting purposes
   * @param fieldName Name of the field.
   * @param descending If set to true [descending]
   * @param fieldType The type of the field to be sorted.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> addOrder(String fieldName, boolean descending, Class< ? > fieldType) {
    WhereParams whereParamas = new WhereParams();
    whereParamas.setFieldName(fieldName);
    fieldName = ensureValidFieldName(whereParamas);
    fieldName = descending ? "-" + fieldName : fieldName;
    orderByFields = (String[]) ArrayUtils.add(orderByFields, fieldName);
    sortByHints.add(new Tuple<String, Class< ? >>(fieldName, fieldType));
    return (IDocumentQuery<T>) this;
  }



  //TODO public virtual IEnumerator<T> GetEnumerator() {

  @SuppressWarnings("unchecked")
  @Override
  public IDocumentQuery<T> take(int count) {
    pageSize = count;
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  @Override
  public IDocumentQuery<T> skip(int count) {
    start = count;
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> where(String whereClause) {
    appendSpaceIfNeeded(queryText.length() > 0 && queryText.charAt(queryText.length() - 1) != '(');
    queryText.append(whereClause);
    return (IDocumentQuery<T>) this;
  }

  private void appendSpaceIfNeeded(boolean shouldAppendSpace) {
    if (shouldAppendSpace) {
      queryText.append(" ");
    }
  }

  /**
   * Matches exact value
   * @param fieldName
   * @param value
   * @return
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereEquals(String fieldName, Object value) {
    WhereParams whereParams = new WhereParams();
    ;
    whereParams.setFieldName(fieldName);
    whereParams.setValue(value);
    whereEquals(whereParams);
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereEquals(String fieldName, Object value, boolean isAnalyzed) {
    WhereParams whereParams = new WhereParams();
    whereParams.setAllowWildcards(isAnalyzed);
    whereParams.setAnalyzed(isAnalyzed);
    whereParams.setFieldName(fieldName);
    whereParams.setValue(value);
    whereEquals(whereParams);
    return (IDocumentQuery<T>) this;
  }

  /**
   * Simplified method for opening a new clause within the query
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> openSubclause() {
    currentClauseDepth++;
    appendSpaceIfNeeded(queryText.length() > 0 && queryText.charAt(queryText.length() - 1) != '(');
    negateIfNeeded();
    queryText.append("(");
    return (IDocumentQuery<T>) this;
  }

  /**
   * Instruct the index to group by the specified fields using the specified aggregation operation
   *
   * This is only valid on dynamic indexes queries
   * @param aggregationOperation
   * @param string
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> groupBy(EnumSet<AggregationOperation> aggregationOperation, String... fieldsToGroupBy) {
    groupByFields = fieldsToGroupBy;
    aggregationOp = aggregationOperation;
    return (IDocumentQuery<T>) this;
  }

  /**
   * Simplified method for closing a clause within the query
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> closeSubclause() {
    currentClauseDepth--;
    queryText.append(")");
    return (IDocumentQuery<T>) this;
  }

  /**
   * Matches exact value
   * @param whereParams
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereEquals(WhereParams whereParams) {
    ensureValidFieldName(whereParams);
    String transformToEqualValue = transformToEqualValue(whereParams);
    lastEquality = new Tuple<String, String>(whereParams.getFieldName(), transformToEqualValue);

    appendSpaceIfNeeded(queryText.length() > 0 && queryText.charAt(queryText.length() - 1) != '(');
    negateIfNeeded();

    queryText.append(RavenQuery.escapeField(whereParams.getFieldName()));
    queryText.append(":");
    queryText.append(transformToEqualValue);
    return (IDocumentQuery<T>) this;
  }

  private String ensureValidFieldName(WhereParams whereParams) {
    if (theSession == null || theSession.getConventions() == null || whereParams.isNestedPath() || isMapReduce) {
      return whereParams.getFieldName();
    }

    for (Class<?> rootType : rootTypes) {
      Field identityProperty = theSession.getConventions().getIdentityProperty(rootType);
      if (identityProperty != null && identityProperty.getName().equals(whereParams.getFieldName())) {
        whereParams.setFieldTypeForIdentifier(rootType);
        whereParams.setFieldName(Constants.DOCUMENT_ID_FIELD_NAME);
        return Constants.DOCUMENT_ID_FIELD_NAME;
      }
    }
    return whereParams.getFieldName();
  }

  /**
   * Negate the next operation
   */
  public void negateNext() {
    negate = !negate;
  }

  private void negateIfNeeded() {
    if (negate == false) {
      return;
    }
    negate = false;
    queryText.append("-");
  }

  /**
   * Check that the field has one of the specified value
   * @param fieldName
   * @param values
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereIn(String fieldName, Collection<? super Object> values) {
    appendSpaceIfNeeded(queryText.length() > 0 && !Character.isWhitespace(queryText.charAt(queryText.length() - 1)));
    negateIfNeeded();

    Collection<Object> list = new ArrayList<>(values);

    if (list.size() == 0) {
      queryText.append("@emptyIn<").append(fieldName).append(">:(no-results)");
      return (IDocumentQuery<T>) this;
    }

    queryText.append("@in<").append(fieldName).append(">:(");

    boolean first = true;
    addItemToInClause(fieldName, list, first);
    queryText.append(") ");
    return (IDocumentQuery<T>) this;
  }

  @SuppressWarnings("unchecked")
  private void addItemToInClause(String fieldName, Collection<Object> list, boolean first) {
    for (Object value : list) {
      if (value instanceof Collection) {
        addItemToInClause(fieldName, (Collection<Object>) value, first);
        return;
      }
      if (first == false) {
        queryText.append(",");
      }
      first = false;
      WhereParams whereParams = new WhereParams();
      whereParams.setAllowWildcards(true);
      whereParams.setAnalyzed(true);
      whereParams.setFieldName(fieldName);
      whereParams.setValue(value);
      ensureValidFieldName(whereParams);
      queryText.append(StringUtils.replace(transformToEqualValue(whereParams), ",", "`,`"));
    }
  }

  /**
   * Matches fields which starts with the specified value.
   * @param fieldName Name of the field.
   * @param value The value.s
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereStartsWith(String fieldName, Object value) {
    // NOTE: doesn't fully match startsWith semantics
    WhereParams whereParams = new WhereParams();
    whereParams.setFieldName(fieldName);
    whereParams.setValue(value.toString() + "*");
    whereParams.setAnalyzed(true);
    whereParams.setAllowWildcards(true);
    whereEquals(whereParams);
    return (IDocumentQuery<T>) this;
  }

  /**
   * Matches fields which ends with the specified value.
   * @param fieldName Name of the field
   * @param value The value.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereEndsWith(String fieldName, Object value) {
    // http://lucene.apache.org/java/2_4_0/queryparsersyntax.html#Wildcard%20Searches
    // You cannot use a * or ? symbol as the first character of a search

    // NOTE: doesn't fully match EndsWith semantics
    WhereParams whereParams = new WhereParams();
    whereParams.setFieldName(fieldName);
    whereParams.setValue("*" + value.toString());
    whereParams.setAllowWildcards(true);
    whereParams.setAnalyzed(true);
    whereEquals(whereParams);
    return (IDocumentQuery<T>) this;
  }

  /**
   * Matches fields where the value is between the specified start and end, exclusive
   * @param fieldName Name of the field.
   * @param start The start.
   * @param end The end.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereBetween(String fieldName, Object start, Object end) {
    appendSpaceIfNeeded(queryText.length() > 0);

    if ((start != null ? start : end) != null) {
      sortByHints.add(new Tuple<String, Class< ? >>(fieldName, (start != null ? start : end).getClass()));
    }

    negateIfNeeded();

    fieldName = getFieldNameForRangeQueries(fieldName, start, end);

    queryText.append(RavenQuery.escapeField(fieldName)).append(":{");
    WhereParams startParams = new WhereParams();
    startParams.setValue(start);
    startParams.setFieldName(fieldName);

    WhereParams endParams = new WhereParams();
    endParams.setValue(end);
    endParams.setFieldName(fieldName);
    queryText.append(start == null ? "*" : transformToRangeValue(startParams));
    queryText.append(" TO ");
    queryText.append(end == null ? "NULL" : transformToRangeValue(endParams));
    queryText.append("}");
    return (IDocumentQuery<T>) this;
  }

  /**
   *  Matches fields where the value is between the specified start and end, inclusive
   * @param fieldName Name of the field.
   * @param start The start.
   * @param end The end.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereBetweenOrEqual(String fieldName, Object start, Object end) {
    appendSpaceIfNeeded(queryText.length() > 0);
    if ((start != null ? start : end) != null) {
      sortByHints.add(new Tuple<String, Class< ? >>(fieldName, (start != null ? start : end).getClass()));
    }

    negateIfNeeded();

    fieldName = getFieldNameForRangeQueries(fieldName, start, end);

    queryText.append(RavenQuery.escapeField(fieldName)).append(":[");
    WhereParams startWhere = new WhereParams();
    startWhere.setValue(start);
    startWhere.setFieldName(fieldName);
    queryText.append(start == null ? "*" : transformToRangeValue(startWhere));
    queryText.append(" TO ");
    WhereParams endWhere = new WhereParams();
    endWhere.setFieldName(fieldName);
    endWhere.setValue(end);
    queryText.append(end == null ? "NULL" : transformToRangeValue(endWhere));
    queryText.append("]");
    return (IDocumentQuery<T>) this;
  }

  private String getFieldNameForRangeQueries(String fieldName, Object start, Object end) {
    WhereParams whereParams = new WhereParams();
    whereParams.setFieldName(fieldName);
    fieldName = ensureValidFieldName(whereParams);

    if (fieldName == Constants.DOCUMENT_ID_FIELD_NAME) {
      return fieldName;
    }

    Object val = start != null ? start : end;
    if (conventions.usesRangeType(val) && !fieldName.endsWith("_Range")) {
      fieldName = fieldName + "_Range";
    }
    return fieldName;
  }

  /**
   * Matches fields where the value is greater than the specified value
   * @param fieldName Name of the field.
   * @param value The value.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereGreaterThan(String fieldName, Object value) {
    whereBetween(fieldName, value, null);
    return (IDocumentQuery<T>) this;
  }

  /**
   * Matches fields where the value is greater than or equal to the specified value
   * @param fieldName Name of the field.
   * @param value The value.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereGreaterThanOrEqual(String fieldName, Object value) {
    whereBetweenOrEqual(fieldName, value, null);
    return (IDocumentQuery<T>) this;
  }

  /**
   * Matches fields where the value is less than the specified value
   * @param fieldName Name of the field.
   * @param value The value.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereLessThan(String fieldName, Object value) {
    whereBetween(fieldName, null, value);
    return (IDocumentQuery<T>) this;
  }

  /**
   * Matches fields where the value is less than or equal to the specified value
   * @param fieldName Name of the field.
   * @param value the value.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> whereLessThanOrEqual(String fieldName, Object value) {
    whereBetweenOrEqual(fieldName, null, value);
    return (IDocumentQuery<T>) this;
  }

  /**
   *  Add an AND to the query
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> andAlso() {
    if (queryText.length() < 1)
      return (IDocumentQuery<T>) this;

    queryText.append(" AND");
    return (IDocumentQuery<T>) this;
  }

  /**
   * Add an OR to the query
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> orElse() {
    if (queryText.length() < 1) {
      return (IDocumentQuery<T>) this;
    }

    queryText.append(" OR");
    return (IDocumentQuery<T>) this;
  }

  /**
   * Specifies a boost weight to the last where clause.
   * The higher the boost factor, the more relevant the term will be.
   *
   *  http://lucene.apache.org/java/2_4_0/queryparsersyntax.html#Boosting%20a%20Term
   * @param boost boosting factor where 1.0 is default, less than 1.0 is lower weight, greater than 1.0 is higher weight
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> boost(Double boost) {
    if (queryText.length() < 1) {
      throw new IllegalStateException("Missing where clause");
    }

    if (boost <= 0.0) {
      throw new IllegalArgumentException("Boost factor must be a positive number");
    }

    if (boost != 1.0) {
      // 1.0 is the default
      queryText.append("^").append(boost);
    }
    return (IDocumentQuery<T>) this;
  }

  /**
   * Specifies a fuzziness factor to the single word term in the last where clause
   *
   * http://lucene.apache.org/java/2_4_0/queryparsersyntax.html#Fuzzy%20Searches
   * @param fuzzy 0.0 to 1.0 where 1.0 means closer match
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> fuzzy(Double fuzzy) {
    if (queryText.length() < 1) {
      throw new IllegalStateException("Missing where clause");
    }

    if (fuzzy < 0 || fuzzy > 1) {
      throw new IllegalArgumentException("Fuzzy distance must be between 0.0 and 1.0");
    }

    char ch = queryText.charAt(queryText.length() - 1);
    if (ch == '"' || ch == ']') {
      // this check is overly simplistic
      throw new IllegalStateException("Fuzzy factor can only modify single word terms");
    }

    queryText.append("~");
    if (fuzzy != 0.5) {
      // 0.5 is the default
      queryText.append(fuzzy);
    }
    return ((IDocumentQuery<T>) this);
  }

  /**
   * Specifies a proximity distance for the phrase in the last where clause
   *
   *  http://lucene.apache.org/java/2_4_0/queryparsersyntax.html#Proximity%20Searches
   * @param proximity number of words within
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> proximity(int proximity) {
    if (queryText.length() < 1) {
      throw new IllegalStateException("Missing where clause");
    }

    if (proximity < 1) {
      throw new IllegalArgumentException("Proximity distance must be a positive number");
    }

    if (queryText.charAt(queryText.length() - 1) != '"') {
      // this check is overly simplistic
      throw new IllegalStateException("Proximity distance can only modify a phrase");
    }

    queryText.append("~").append(proximity);
    return (IDocumentQuery<T>) this;
  }

  /**
   * Order the results by the specified fields
   * The fields are the names of the fields to sort, defaulting to sorting by ascending.
   * You can prefix a field name with '-' to indicate sorting by descending or '+' to sort by ascending
   * @param fields The fields.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> orderBy(String... fields) {
    orderByFields = (String[]) ArrayUtils.addAll(orderByFields, fields);
    return (IDocumentQuery<T>) this;
  }

  /**
   * Order the results by the specified fields
   * The fields are the names of the fields to sort, defaulting to sorting by descending.
   * You can prefix a field name with '-' to indicate sorting by descending or '+' to sort by ascending
   * @param fields The fields.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> orderByDescending(String... fields) {
    List<String> fieldsTranformed = new ArrayList<>();
    for (String field : fields) {
      fieldsTranformed.add(makeFieldSortDescending(field));
    }
    orderBy(fieldsTranformed.toArray(new String[0]));
    return (IDocumentQuery<T>) this;
  }

  String makeFieldSortDescending(String field) {
    if (StringUtils.isBlank(field) || field.startsWith("+") || field.startsWith("-")) {
      return field;
    }
    return "-" + field;
  }

  /**
   * Instructs the query to wait for non stale results as of now.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> waitForNonStaleResultsAsOfNow() {
    theWaitForNonStaleResults = true;
    cutoff = new Date();
    timeout = getDefaultTimeout();
    return (IDocumentQuery<T>) this;
  }

  /**
   * Instructs the query to wait for non stale results as of now for the specified timeout.
   * @param waitTimeout The wait timeout in milis
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> waitForNonStaleResultsAsOfNow(long waitTimeout) {
    theWaitForNonStaleResults = true;
    cutoff = new Date();
    timeout = waitTimeout;
    return (IDocumentQuery<T>) this;
  }

  /**
   * Instructs the query to wait for non stale results as of the cutoff date.
   * @param cutOff The cut off.
   */
  public IDocumentQuery<T> waitForNonStaleResultsAsOf(Date cutOff) {
    return waitForNonStaleResultsAsOf(cutOff, getDefaultTimeout());
  }

  /**
   * Instructs the query to wait for non stale results as of the cutoff date for the specified timeout
   * @param cutOff The cut off.
   * @param waitTimeout the wait timeout in milis
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> waitForNonStaleResultsAsOf(Date cutOff, long waitTimeout) {
    theWaitForNonStaleResults = true;
    cutoff = cutOff;
    timeout = waitTimeout;
    return (IDocumentQuery<T>) this;
  }

  /**
   * Instructs the query to wait for non stale results as of the cutoff etag.
   */
  public IDocumentQuery<T> waitForNonStaleResultsAsOf(Etag cutOffEtag) {
    return waitForNonStaleResultsAsOf(cutOffEtag, getDefaultTimeout());
  }

  /**
   * Instructs the query to wait for non stale results as of the cutoff etag.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> waitForNonStaleResultsAsOf(Etag cutOffEtag, long waitTimeout) {
    theWaitForNonStaleResults = true;
    timeout = waitTimeout;
    cutoffEtag = cutOffEtag;
    return (IDocumentQuery<T>) this;
  }

  /**
   * Instructs the query to wait for non stale results as of the last write made by any session belonging to the
   * current document store.
   * This ensures that you'll always get the most relevant results for your scenarios using simple indexes (map only or dynamic queries).
   * However, when used to query map/reduce indexes, it does NOT guarantee that the document that this etag belong to is actually considered for the results.
   */
  public IDocumentQuery<T> waitForNonStaleResultsAsOfLastWrite() {
    return waitForNonStaleResultsAsOfLastWrite(getDefaultTimeout());
  }

  /**
   * Instructs the query to wait for non stale results as of the last write made by any session belonging to the
   * current document store.
   * This ensures that you'll always get the most relevant results for your scenarios using simple indexes (map only or dynamic queries).
   * However, when used to query map/reduce indexes, it does NOT guarantee that the document that this etag belong to is actually considered for the results.
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> waitForNonStaleResultsAsOfLastWrite(long waitTimeout) {
    theWaitForNonStaleResults = true;
    timeout = waitTimeout;
    cutoffEtag = theSession.getDocumentStore().getLastWrittenEtag();
    return (IDocumentQuery<T>) this;
  }

  /**
   * EXPERT ONLY: Instructs the query to wait for non stale results.
   * This shouldn't be used outside of unit tests unless you are well aware of the implications
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> waitForNonStaleResults() {
    waitForNonStaleResults(getDefaultTimeout());
    return (IDocumentQuery<T>) this;
  }

  /**
   * Provide statistics about the query, such as total count of matching records
   * @param stats
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> statistics(Reference<RavenQueryStatistics> stats) {
    stats.value = queryStats;
    return (IDocumentQuery<T>) this;
  }

  /**
   * Callback to get the results of the query
   * @param afterQueryExecutedCallback
   */
  public void afterQueryExecuted(Action1<QueryResult> afterQueryExecutedCallback) {
    this.afterQueryExecutedCallback = Delegates.combine(this.afterQueryExecutedCallback, afterQueryExecutedCallback);
  }

  /**
   * Called externally to raise the after query executed callback
   * @param result
   */
  public void invokeAfterQueryExecuted(QueryResult result) {
    Action1<QueryResult> queryExecuted = afterQueryExecutedCallback;
    if (queryExecuted != null) {
      queryExecuted.apply(result);
    }
  }

  /**
   * Generates the index query.
   * @param query The query.
   * @return
   */
  protected IndexQuery generateIndexQuery(String query) {
    if(isSpatialQuery) {
      if ("dynamic".equals(indexName) || indexName.startsWith("dynamic/")) {
        throw new IllegalStateException("Dynamic indexes do not support spatial queries. A static index, with spatial field(s), must be defined.");
      }

      SpatialIndexQuery spatialIndexQuery = new SpatialIndexQuery();
      spatialIndexQuery.setGroupBy(groupByFields);
      spatialIndexQuery.setAggregationOperation(aggregationOp);
      spatialIndexQuery.setQuery(query);
      spatialIndexQuery.setPageSize(pageSize != null ? pageSize : 128);
      spatialIndexQuery.setStart(start);
      spatialIndexQuery.setCutoff(cutoff);
      spatialIndexQuery.setCutoffEtag(cutoffEtag);
      List<SortedField> sortedFields =new ArrayList<>();
      for (String orderByField: orderByFields) {
        sortedFields.add(new SortedField(orderByField));
      }
      spatialIndexQuery.setSortedFields(sortedFields.toArray(new SortedField[0]));
      spatialIndexQuery.setFieldsToFetch(fieldsToFetch);
      spatialIndexQuery.setSpatialFieldName(spatialFieldName);
      spatialIndexQuery.setQueryShape(queryShape);
      spatialIndexQuery.setRadiusUnitOverride(spatialUnits);
      spatialIndexQuery.setSpatialRelation(spatialRelation);
      spatialIndexQuery.setDistanceErrorPercentage(distanceErrorPct);
      spatialIndexQuery.setDefaultField(defaultField);
      spatialIndexQuery.setDefaultOperator(defaultOperator);
      List<HighlightedField> highlightFldCopy = new ArrayList<>();
      for (HighlightedField highFld: highlightedFields) {
        highlightFldCopy.add(highFld.clone());
      }
      spatialIndexQuery.setHighlightedFields(highlightFldCopy.toArray(new HighlightedField[0]));
      spatialIndexQuery.setHighlighterPreTags(highlighterPreTags);
      spatialIndexQuery.setHighlighterPostTags(highlighterPostTags);
      spatialIndexQuery.setResultsTransformer(resultsTransformer);
      spatialIndexQuery.setQueryInputs(queryInputs);
      spatialIndexQuery.setDisableCaching(disableCaching);
      spatialIndexQuery.setExplainScores(shouldExplainScores);

      return spatialIndexQuery;

    }

    IndexQuery indexQuery = new IndexQuery();
    indexQuery.setGroupBy(groupByFields);
    indexQuery.setAggregationOperation(aggregationOp);
    indexQuery.setQuery(query);
    indexQuery.setStart(start);
    indexQuery.setCutoff(cutoff);
    indexQuery.setCutoffEtag(cutoffEtag);
    List<SortedField> sortedFields =new ArrayList<>();
    for (String orderByField: orderByFields) {
      sortedFields.add(new SortedField(orderByField));
    }
    indexQuery.setSortedFields(sortedFields.toArray(new SortedField[0]));
    indexQuery.setFieldsToFetch(fieldsToFetch);
    indexQuery.setDefaultField(defaultField);
    indexQuery.setDefaultOperator(defaultOperator);
    List<HighlightedField> highlightFldCopy = new ArrayList<>();
    for (HighlightedField highFld: highlightedFields) {
      highlightFldCopy.add(highFld.clone());
    }
    indexQuery.setHighlightedFields(highlightFldCopy.toArray(new HighlightedField[0]));
    indexQuery.setHighlighterPreTags(highlighterPreTags);
    indexQuery.setHighlighterPostTags(highlighterPostTags);
    indexQuery.setResultsTransformer(resultsTransformer);
    indexQuery.setQueryInputs(queryInputs);
    indexQuery.setDisableCaching(disableCaching);
    indexQuery.setExplainScores(shouldExplainScores);

    if (pageSize != null) {
      indexQuery.setPageSize(pageSize);
    }

    return indexQuery;
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> search(String fieldName, String searchTerms) {
    search(fieldName, searchTerms, EscapeQueryOptions.RAW_QUERY);
    return (IDocumentQuery<T>) this;
  }


  /**
   * Perform a search for documents which fields that match the searchTerms.
   * If there is more than a single term, each of them will be checked independently.
   * @param fieldName
   * @param searchTerms
   * @param escapeQueryOptions
   */
  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> search(String fieldName, String searchTerms, EscapeQueryOptions escapeQueryOptions) {
    queryText.append(' ');

    negateIfNeeded();
    switch (escapeQueryOptions) {
    case ESCAPE_ALL:
      searchTerms = RavenQuery.escape(searchTerms, false, false);
      break;
    case ALLOW_POSTFIX_WILDCARD:
      searchTerms = RavenQuery.escape(searchTerms, false, false);
      searchTerms = ESPACE_POSTFIX_WILDCARD.matcher(searchTerms).replaceFirst("*");
      break;
    case ALLOW_ALL_WILDCARDS:
      searchTerms = RavenQuery.escape(searchTerms, false, false);
      searchTerms = searchTerms.replace("\\*", "*");
      break;
    case RAW_QUERY:
      break;
    default:
      throw new IllegalArgumentException("Value:" + escapeQueryOptions);
    }
    lastEquality = Tuple.create(fieldName, "(" + searchTerms + ")");

    queryText.append(fieldName).append(":").append("(").append(searchTerms).append(")");
    return (IDocumentQuery<T>) this;
  }

  private String transformToEqualValue(WhereParams whereParams) {
    if (whereParams.getValue() == null) {
      return Constants.NULL_VALUE_NOT_ANALYZED;
    }
    if (StringUtils.isEmpty(whereParams.getValue().toString())) {
      return Constants.EMPTY_STRING_NOT_ANALYZED;
    }

    Class<?> type = whereParams.getValue().getClass();
    if (Boolean.class.equals(type)) {
      return (boolean) whereParams.getValue() ? "true" : "false";
    }
    /*TODO
    if (type == typeof(DateTime))
    {
      var val = (DateTime)whereParams.Value;
      var s = val.ToString(Default.DateTimeFormatsToWrite);
      if (val.Kind == DateTimeKind.Utc)
        s += "Z";
      return s;
    }
     */
    if (Number.class.isAssignableFrom(type)) {
      return RavenQuery.escape(whereParams.getValue().toString(), false, false);
    }

    if (whereParams.getFieldName().equals(Constants.DOCUMENT_ID_FIELD_NAME) && !(whereParams.getValue() instanceof String)) {
      return theSession.getConventions().getFindFullDocumentKeyFromNonStringIdentifier().apply(whereParams.getValue(),
          whereParams.getFieldTypeForIdentifier() != null ? whereParams.getFieldTypeForIdentifier() : clazz, false);
    }

    if (whereParams.getValue() instanceof String) {
      String strValue = (String) whereParams.getValue();
      strValue = RavenQuery.escape(strValue, whereParams.isAllowWildcards() && !whereParams.isAnalyzed(), true);
      return whereParams.isAnalyzed() ? strValue : "[[" + strValue + "]]";
    }

    Reference<String> strValue = new Reference<>();

    if (conventions.tryConvertValueForQuery(whereParams.getFieldName(), whereParams.getValue(), QueryValueConvertionType.EQUALITY, strValue)) {
      return strValue.value;
    }

    if (ValueTypeUtils.isValueType(type)) {
      String escaped = RavenQuery.escape(whereParams.getValue().toString(), whereParams.isAllowWildcards() && whereParams.isAnalyzed(), true);
      return escaped;
    }

    try {
      RavenJTokenWriter ravenJTokenWriter = new RavenJTokenWriter();
      JsonExtensions.getDefaultObjectMapper().writeValue(ravenJTokenWriter, whereParams.getValue());
      String term = ravenJTokenWriter.getToken().toString();

      if(term.length() > 1 && term.charAt(0) == '"' && term.charAt(term.length() - 1) == '"') {
        term = term.substring(1, term.length() - 2);
      }
      switch (ravenJTokenWriter.getToken().getType())
      {
      case OBJECT:
      case ARRAY:
        return "[[" + RavenQuery.escape(term, whereParams.isAllowWildcards() && whereParams.isAnalyzed(), false) + "]]";

      default:
        return RavenQuery.escape(term, whereParams.isAllowWildcards() && whereParams.isAnalyzed(), true);
      }
    } catch (IOException e) {
      throw new RuntimeException("Unable to serialize token", e);
    }

  }


  private String transformToRangeValue(WhereParams whereParams) {
    if (whereParams.getValue() == null) {
      return Constants.NULL_VALUE_NOT_ANALYZED;
    }
    if ("".equals(whereParams.getValue())) {
      return Constants.EMPTY_STRING_NOT_ANALYZED;
    }
    if (whereParams.getValue() instanceof Date) {
      Date dateTime = (Date) whereParams.getValue();
      FastDateFormat fdf = FastDateFormat.getInstance(Default.DATE_TIME_FORMATS_TO_WRITE);
      String dateStr = fdf.format(dateTime);
      return dateStr;
    }
    if (Constants.DOCUMENT_ID_FIELD_NAME.equals(whereParams.getFieldName()) && !(whereParams.getValue() instanceof String))  {
      return theSession.getConventions().getFindFullDocumentKeyFromNonStringIdentifier().apply(whereParams.getValue(), clazz, false);
    }
    if (whereParams.getValue() instanceof Integer) {
      return NumberUtil.numberToString((Integer)whereParams.getValue());
    }
    if (whereParams.getValue() instanceof Long) {
      return NumberUtil.numberToString((Long) whereParams.getValue());
    }
    if (whereParams.getValue() instanceof Double) {
      return NumberUtil.numberToString((Double) whereParams.getValue());
    }
    if (whereParams.getValue() instanceof Float) {
      return NumberUtil.numberToString((Float) whereParams.getValue());
    }
    if (whereParams.getValue() instanceof String) {
      return RavenQuery.escape(whereParams.getValue().toString(), false, true);
    }
    Reference<String> strVal = new Reference<>();
    if (conventions.tryConvertValueForQuery(whereParams.getFieldName(), whereParams.getValue(), QueryValueConvertionType.RANGE, strVal)) {
      return strVal.value;
    }

    if (ValueTypeUtils.isValueType(whereParams.getValue().getClass())) {
      return RavenQuery.escape(whereParams.getValue().toString(), false, true);
    }
    try {
      String term = JsonExtensions.getDefaultObjectMapper().writeValueAsString(whereParams.getValue());
      if(term.length() > 1 && term.charAt(0) == '"' && term.charAt(term.length() - 1) == '"') {
        term = term.substring(1, term.length() - 2);
      }
      return RavenQuery.escape(term, false, true);
    } catch (IOException e) {
      throw new RuntimeException("Unable to serialize token", e);
    }

  }

  /**
   * Returns a {@link String} that represents the query for this instance.
   */
  public String toString() {
    if (currentClauseDepth != 0) {
      throw new IllegalStateException("A clause was not closed correctly within this query, current clause depth = " + currentClauseDepth);
    }

    return queryText.toString().trim();
  }

  /**
   * The last term that we asked the query to use equals on
   */
  public Tuple<String, String> getLastEqualityTerm() {
    return lastEquality;
  }

  @SuppressWarnings("unchecked")
  public IDocumentQuery<T> intersect() {
    queryText.append(Constants.INTERSECT_SEPARATOR);
    return (IDocumentQuery<T>) this;
  }

  public void addRootType(Class<T> type) {
    rootTypes.add(type);
  }

  protected String[] getMemberQueryPaths(Expression<?> ...expressions) {
    List<String> result = new ArrayList<>();
    for (Expression<?> expr: expressions) {
      result.add(getMemberQueryPath(expr));
    }
    return result.toArray(new String[0]);
  }

  protected String[] getMemberQueryPathsForOrderBy(Expression<?> ...expressions) {
    List<String> result = new ArrayList<>();
    for (Expression<?> expr: expressions) {
      result.add(getMemberQueryPathForOrderBy(expr));
    }
    return result.toArray(new String[0]);
  }

  public String getMemberQueryPathForOrderBy(Expression< ? > expression) {
    String memberQueryPath = getMemberQueryPath(expression);
    if (getDocumentConvention().usesRangeType(expression.getType())) {
      return memberQueryPath + "_Range";
    }
    return memberQueryPath;
  }

  public String getMemberQueryPath(Expression< ? > expression) {

    LinqPathProvider.Result result = linqPathProvider.getPath(expression);
    result.setPath(result.getPath().substring(result.getPath().indexOf('.') + 1));

    /*TODO:
    if (expression.NodeType == ExpressionType.ArrayLength)
      result.Path += ".Length";
     */

    String propertyName = indexName == null || indexName.toLowerCase().startsWith("dynamic/")
        ? conventions.getFindPropertyNameForDynamicIndex().apply(clazz, indexName, "", result.getPath())
            : conventions.getFindPropertyNameForIndex().apply(clazz, indexName, "", result.getPath());
        return propertyName;
  }

}
