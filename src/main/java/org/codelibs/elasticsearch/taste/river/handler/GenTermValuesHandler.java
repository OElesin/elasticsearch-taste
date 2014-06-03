package org.codelibs.elasticsearch.taste.river.handler;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import org.apache.lucene.index.DocsAndPositionsEnum;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.UnicodeUtil;
import org.codelibs.elasticsearch.taste.TasteConstants;
import org.codelibs.elasticsearch.taste.exception.InvalidParameterException;
import org.codelibs.elasticsearch.taste.rest.handler.DefaultRequestHandler;
import org.codelibs.elasticsearch.taste.rest.handler.ItemRequestHandler;
import org.codelibs.elasticsearch.taste.rest.handler.PreferenceRequestHandler;
import org.codelibs.elasticsearch.taste.rest.handler.RequestHandler;
import org.codelibs.elasticsearch.taste.rest.handler.RequestHandlerChain;
import org.codelibs.elasticsearch.taste.rest.handler.UserRequestHandler;
import org.codelibs.elasticsearch.util.SettingsUtils;
import org.codelibs.elasticsearch.util.StringUtils;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.termvector.MultiTermVectorsItemResponse;
import org.elasticsearch.action.termvector.MultiTermVectorsRequestBuilder;
import org.elasticsearch.action.termvector.MultiTermVectorsResponse;
import org.elasticsearch.action.termvector.MultiTermVectorsResponse.Failure;
import org.elasticsearch.action.termvector.TermVectorRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent.Params;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.river.RiverSettings;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.SearchHits;

public class GenTermValuesHandler extends ActionHandler {
    private Number keepAlive;

    private String sourceIndex;

    private String sourceType;

    private String[] sourceFields;

    private RequestHandler[] requestHandlers;

    private Params eventParams;

    private CountDownLatch scrollSearchGate;

    public GenTermValuesHandler(final RiverSettings settings,
            final Client client) {
        super(settings, client);
    }

    @Override
    public void execute() {
        final Map<String, Object> sourceIndexSettings = SettingsUtils.get(
                rootSettings, "source");
        sourceIndex = SettingsUtils.get(sourceIndexSettings, "index",
                StringUtils.EMPTY_STRING);
        if (StringUtils.isBlank(sourceIndex)) {
            throw new InvalidParameterException("source.index is invalid: "
                    + sourceIndex);
        }
        sourceType = SettingsUtils.get(sourceIndexSettings, "type",
                StringUtils.EMPTY_STRING);
        if (StringUtils.isBlank(sourceType)) {
            throw new InvalidParameterException("source.type is invalid: "
                    + sourceType);
        }
        final List<String> fieldList = SettingsUtils.get(sourceIndexSettings,
                "fields");
        if (fieldList == null || fieldList.isEmpty()) {
            throw new InvalidParameterException("source.fields is empty.");
        }
        sourceFields = fieldList.toArray(new String[fieldList.size()]);

        final Map<String, Object> scrollSettings = SettingsUtils.get(
                rootSettings, "scroll");
        keepAlive = SettingsUtils.get(scrollSettings, "keep_alive", 600000); //10min
        final Number size = SettingsUtils.get(scrollSettings, "size", 100);

        requestHandlers = new RequestHandler[] {
                new UserRequestHandler(settings, client),
                new ItemRequestHandler(settings, client),
                new PreferenceRequestHandler(settings, client), };

        final Map<String, Object> eventSettings = SettingsUtils.get(
                rootSettings, "event", new HashMap<String, Object>());
        eventParams = new EventSettingParams(eventSettings);

        scrollSearchGate = new CountDownLatch(1);

        client.prepareSearch(sourceIndex).setTypes(sourceType)
                .setSearchType(SearchType.SCAN)
                .setScroll(new TimeValue(keepAlive.longValue()))
                .setQuery(QueryBuilders.matchAllQuery())
                .setSize(size.intValue()).addField("id")
                .setListenerThreaded(false).execute(new ScrollSearchListener());

        try {
            scrollSearchGate.await();
        } catch (final InterruptedException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Interrupted.", e);
            }
        }
    }

    private class ScrollSearchListener implements
            ActionListener<SearchResponse> {
        private volatile boolean initialized = false;

        private volatile MultiTermVectorsListener mTVListener;

        @Override
        public void onResponse(final SearchResponse response) {
            if (!initialized) {
                initialized = true;
                client.prepareSearchScroll(response.getScrollId())
                        .setScroll(new TimeValue(keepAlive.longValue()))
                        .setListenerThreaded(false).execute(this);
                return;
            }

            if (mTVListener != null) {
                try {
                    mTVListener.await();
                } catch (final InterruptedException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Interrupted.", e);
                    }
                }
            }

            final SearchHits searchHits = response.getHits();
            final SearchHit[] hits = searchHits.getHits();
            if (hits.length == 0) {
                scrollSearchGate.countDown();
            } else {
                final Map<String, String> idMap = new HashMap<>(hits.length);
                final MultiTermVectorsRequestBuilder requestBuilder = client
                        .prepareMultiTermVectors();
                for (final SearchHit hit : hits) {
                    final String id = hit.getId();
                    final SearchHitField searchHitField = hit.field("id");
                    if (searchHitField != null) {
                        idMap.put(id, (String) searchHitField.getValue());
                    }
                    final TermVectorRequest termVectorRequest = new TermVectorRequest(
                            sourceIndex, sourceType, id);
                    termVectorRequest.selectedFields(sourceFields);
                    requestBuilder.add(termVectorRequest);
                }
                mTVListener = new MultiTermVectorsListener(requestHandlers,
                        eventParams, idMap, logger);
                requestBuilder.execute(mTVListener);

                client.prepareSearchScroll(response.getScrollId())
                        .setScroll(new TimeValue(keepAlive.longValue()))
                        .listenerThreaded(true).execute(this);
            }
        }

        @Override
        public void onFailure(final Throwable e) {
            scrollSearchGate.countDown();
            logger.error("Failed to parse and write term vectors.", e);
        }
    }

    private static class MultiTermVectorsListener implements
            ActionListener<MultiTermVectorsResponse> {

        protected final ESLogger logger;

        private RequestHandler[] requestHandlers;

        private Params eventParams;

        private Map<String, String> idMap;

        private volatile List<CountDownLatch> genTVGateList;

        public MultiTermVectorsListener(final RequestHandler[] requestHandlers,
                final Params eventParams, final Map<String, String> idMap,
                final ESLogger logger) {
            this.requestHandlers = requestHandlers;
            this.eventParams = eventParams;
            this.idMap = idMap;
            this.logger = logger;
        }

        public void await() throws InterruptedException {
            validateGate();
            if (genTVGateList != null) {
                for (final CountDownLatch genTVGate : genTVGateList) {
                    genTVGate.await();
                }
            }
        }

        @Override
        public void onResponse(final MultiTermVectorsResponse response) {
            final Date now = new Date();
            final List<CountDownLatch> gateList = new ArrayList<>();
            try {
                for (final MultiTermVectorsItemResponse mTVItemResponse : response) {
                    if (mTVItemResponse.isFailed()) {
                        final Failure failure = mTVItemResponse.getFailure();
                        logger.error("[{}/{}/{}] {}", failure.getIndex(),
                                failure.getType(), failure.getId(),
                                failure.getMessage());
                    } else {
                        final String userId = mTVItemResponse.getId();
                        final String id = idMap.get(userId);
                        if (StringUtils.isBlank(id)) {
                            logger.warn("No id of {}.", userId);
                            continue;
                        }
                        final CharsRef spare = new CharsRef();
                        try {
                            final Fields fields = mTVItemResponse.getResponse()
                                    .getFields();
                            final Iterator<String> fieldIter = fields
                                    .iterator();
                            final List<Map<String, Object>> eventMapList = new ArrayList<>();
                            while (fieldIter.hasNext()) {
                                final String fieldName = fieldIter.next();
                                final Terms curTerms = fields.terms(fieldName);
                                final TermsEnum termIter = curTerms
                                        .iterator(null);
                                for (int i = 0; i < curTerms.size(); i++) {
                                    final BytesRef term = termIter.next();
                                    UnicodeUtil.UTF8toUTF16(term, spare);
                                    final String termValue = spare.toString();
                                    final DocsAndPositionsEnum posEnum = termIter
                                            .docsAndPositions(null, null);
                                    final int termFreq = posEnum.freq();

                                    final Map<String, Object> requestMap = new HashMap<>();
                                    final Map<String, Object> userMap = new HashMap<>();
                                    userMap.put("id", id);
                                    requestMap.put("user", userMap);
                                    final Map<String, Object> itemMap = new HashMap<>();
                                    itemMap.put("id", termValue);
                                    requestMap.put("item", itemMap);
                                    requestMap.put(eventParams.param(
                                            DefaultRequestHandler.FIELD_VALUE,
                                            TasteConstants.VALUE_FIELD),
                                            termFreq);
                                    requestMap
                                            .put(eventParams
                                                    .param(DefaultRequestHandler.FIELD_TIMESTAMP,
                                                            TasteConstants.TIMESTAMP_FIELD),
                                                    now);

                                    eventMapList.add(requestMap);
                                }
                            }

                            final CountDownLatch genTVGate = new CountDownLatch(
                                    eventMapList.size());
                            for (final Map<String, Object> eventMap : eventMapList) {
                                final RequestHandler[] handlers = new RequestHandler[requestHandlers.length + 1];
                                for (int i = 0; i < requestHandlers.length; i++) {
                                    handlers[i] = requestHandlers[i];
                                }
                                handlers[requestHandlers.length] = new RequestHandler() {
                                    boolean done = false;

                                    @Override
                                    public void execute(
                                            final Params params,
                                            final RequestHandler.OnErrorListener listener,
                                            final Map<String, Object> requestMap,
                                            final Map<String, Object> paramMap,
                                            final RequestHandlerChain chain) {
                                        if (!done) {
                                            done = true;
                                            genTVGate.countDown();
                                        }
                                    }
                                };
                                final Map<String, Object> paramMap = new HashMap<>();
                                new RequestHandlerChain(handlers).execute(
                                        eventParams,
                                        new RequestHandler.OnErrorListener() {
                                            boolean done = false;

                                            @Override
                                            public void onError(
                                                    final Throwable t) {
                                                if (!done) {
                                                    done = true;
                                                    genTVGate.countDown();
                                                }
                                            }
                                        }, eventMap, paramMap);
                            }
                            gateList.add(genTVGate);
                        } catch (final Exception e) {
                            logger.error("[{}/{}/{}] Failed to send an event.",
                                    e, mTVItemResponse.getIndex(),
                                    mTVItemResponse.getType(),
                                    mTVItemResponse.getId());
                        }
                    }
                }
            } finally {
                genTVGateList = gateList;
            }
        }

        @Override
        public void onFailure(final Throwable e) {
            logger.error("Failed to write term vectors.", e);
            validateGate();
            if (genTVGateList != null) {
                for (final CountDownLatch genTVGate : genTVGateList) {
                    for (int i = 0; i < genTVGate.getCount(); i++) {
                        genTVGate.countDown();
                    }
                }
            }
        }

        private void validateGate() {
            try {
                while (true) {
                    if (genTVGateList != null) {
                        break;
                    } else {
                        Thread.sleep(1000);
                    }
                }
            } catch (final InterruptedException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Interrupted.", e);
                }
            }
        }
    }

    private static class EventSettingParams implements Params {
        Map<String, Object> requestSettings;

        EventSettingParams(final Map<String, Object> requestSettings) {
            this.requestSettings = new ConcurrentHashMap<String, Object>(
                    requestSettings);
        }

        @Override
        public Boolean paramAsBooleanOptional(final String key,
                final Boolean defaultValue) {
            return paramAsBoolean(key, defaultValue);
        }

        @Override
        public Boolean paramAsBoolean(final String key,
                final Boolean defaultValue) {
            final String value = param(key);
            return value != null ? Boolean.parseBoolean(value) : defaultValue;
        }

        @Override
        public boolean paramAsBoolean(final String key,
                final boolean defaultValue) {
            final String value = param(key);
            return value != null ? Boolean.parseBoolean(value) : defaultValue;
        }

        @Override
        public String param(final String key, final String defaultValue) {
            final Object value = requestSettings.get(key);
            return value != null ? value.toString() : defaultValue;
        }

        @Override
        public String param(final String key) {
            return param(key, null);
        }
    }
}