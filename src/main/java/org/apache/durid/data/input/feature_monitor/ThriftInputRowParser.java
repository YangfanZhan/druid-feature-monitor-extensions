/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.durid.data.input.feature_monitor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.pm.nb_serving.api.thriftjava.FeatureValues;
import com.pm.nb_serving.api.thriftjava.PredictionRequest;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.MapBasedInputRow;
import org.apache.druid.data.input.impl.InputRowParser;
import org.apache.druid.data.input.impl.ParseSpec;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.parsers.Parser;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;

/**
 * 1. load thrift class from classpath or provided jar 2. deserialize content bytes and serialize to
 * json 3. use JsonSpec to do things left
 */
public class ThriftInputRowParser implements InputRowParser<Object> {

  private final ParseSpec parseSpec;
  private final String jarPath;
  private final String thriftClassName;

  private Parser<String, Object> parser;
  private volatile Class<TBase> thriftClass = null;
  private final List<String> dimensions;

  @JsonCreator
  public ThriftInputRowParser(
      @JsonProperty("parseSpec") ParseSpec parseSpec,
      @JsonProperty("thriftJar") String jarPath,
      @JsonProperty("thriftClass") String thriftClassName
  ) {
    this.jarPath = jarPath;
    this.thriftClassName = thriftClassName;
    Preconditions.checkNotNull(thriftClassName, "thrift class name");

    this.parseSpec = parseSpec;
    this.dimensions = parseSpec.getDimensionsSpec().getDimensionNames();
  }


  @Override
  public List<InputRow> parseBatch(Object input) {
    PredictionRequest predictionRequest = new PredictionRequest();
    try {
      if (input instanceof ByteBuffer) { // realtime stream
        final byte[] bytes = ((ByteBuffer) input).array();
        ThriftDeserialization.detectAndDeserialize(bytes, predictionRequest);
      } else {
        throw new IAE("unsupport input class of [%s]", input.getClass());
      }
    } catch (TException e) {
      throw new IAE("some thing wrong with your thrift?");
    }

    // 把PredictionRequest按照Item来摊平
    int contextFeatureCount = predictionRequest.getContext().getFeatureValuesSize();
    Map<String, Object> contextMap = new HashMap<>(contextFeatureCount);
    for (Map.Entry<String, FeatureValues> entry : predictionRequest.getContext().getFeatureValues()
        .entrySet()) {
      String featureName = entry.getKey();
      switch (entry.getValue().getSetField()) {
        case DENSE_FEATURES:
          Double denseValue = entry.getValue().getDenseFeatures().get(0);
          contextMap.put(featureName, denseValue);
          break;
        case SPARSE_FEATURES:
          List<String> sparseValue = entry.getValue().getSparseFeatures().get(0);
          contextMap.put(featureName, sparseValue);
          break;
        case EMBEDDING_FEATURES:
          List<Double> embeddingValue = entry.getValue().getEmbeddingFeatures().get(0);
          contextMap.put(featureName, embeddingValue);
          break;
        default:
          throw new RuntimeException("unsupported featureType");
      }
    }
    int batchSize = (int) predictionRequest.getFeatures().getBatchSize();
    int totalFeatureCount =
        contextFeatureCount + predictionRequest.getFeatures().getFeatureValuesSize();
    List<Map<String, Object>> result = Lists.newArrayListWithCapacity(batchSize);
    for (int i = 0; i < predictionRequest.getFeatures().getBatchSize(); i++) {
      Map<String, Object> objectMap = new HashMap<>(totalFeatureCount);
      objectMap.putAll(contextMap);
      result.add(objectMap);
    }
    for (Map.Entry<String, FeatureValues> itemEntry : predictionRequest.getFeatures()
        .getFeatureValues()
        .entrySet()) {
      String featureName = itemEntry.getKey();
      switch (itemEntry.getValue().getSetField()) {
        case DENSE_FEATURES:
          for (int i = 0; i < batchSize; i++) {
            Double denseValue = itemEntry.getValue().getDenseFeatures().get(i);
            result.get(i).put(featureName, denseValue);
          }
          break;
        case SPARSE_FEATURES:
          for (int i = 0; i < batchSize; i++) {
            List<String> sparseValue = itemEntry.getValue().getSparseFeatures().get(i);
            result.get(i).put(featureName, sparseValue);
          }
          break;
        case EMBEDDING_FEATURES:
          for (int i = 0; i < batchSize; i++) {
            List<Double> embeddingValue = itemEntry.getValue().getEmbeddingFeatures().get(i);
            result.get(i).put(featureName, embeddingValue);
          }
          break;
        default:
          throw new RuntimeException("unsupported featureType");
      }
    }

    List<InputRow> rows = new ArrayList<>(result.size());
    for (int i = 0; i < batchSize; i++) {
      rows.add(new MapBasedInputRow(System.currentTimeMillis(), Collections.emptyList(),
          result.get(i)));
    }
    return rows;
  }

  @Override
  public ParseSpec getParseSpec() {
    return parseSpec;
  }

  @Override
  public InputRowParser withParseSpec(ParseSpec parseSpec) {
    return new ThriftInputRowParser(parseSpec, jarPath, thriftClassName);
  }
}
