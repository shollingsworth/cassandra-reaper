/*
 * Copyright 2014-2017 Spotify AB
 * Copyright 2016-2018 The Last Pickle Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cassandrareaper.jmx;

import io.cassandrareaper.core.Node;
import io.cassandrareaper.core.StreamSession;
import io.cassandrareaper.service.StreamSessionFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.management.openmbean.CompositeData;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.cassandra.streaming.ProgressInfo;
import org.apache.cassandra.streaming.SessionInfo;
import org.apache.cassandra.streaming.StreamState;
import org.apache.cassandra.streaming.StreamSummary;
import org.apache.cassandra.streaming.management.StreamStateCompositeData;
import org.apache.cassandra.streaming.management.StreamSummaryCompositeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class StreamsProxy {

  private static final Logger LOG = LoggerFactory.getLogger(StreamsProxy.class);

  private final JmxProxyImpl proxy;

  private StreamsProxy(JmxProxyImpl proxy) {
    this.proxy = proxy;
  }

  public static StreamsProxy create(JmxProxy proxy) {
    Preconditions.checkArgument(proxy instanceof JmxProxyImpl, "only JmxProxyImpl is supported");
    return new StreamsProxy((JmxProxyImpl)proxy);
  }

  public List<StreamSession> listStreams(Node node) {

    Set<CompositeData> streams = listStreamsInternal();

    return parseCompositeData(streams).stream()
        .map(streamState -> StreamSessionFactory.fromStreamState(node.getHostname(), streamState))
        .collect(Collectors.toList());
  }

  private Set<CompositeData> listStreamsInternal() {
    if (proxy.getStreamManagerMBean().isPresent()) {
      return proxy.getStreamManagerMBean().get().getCurrentStreams();
    } else {
      return ImmutableSet.of();
    }
  }

  private Set<StreamState> parseCompositeData(Set<CompositeData> payload) {
    Set<StreamState> result = Sets.newHashSet();

    for (CompositeData compositeData : payload) {

      try {
        // start by trying to parse with classes coming from Reaper's C* dependency
        StreamState streamState = StreamStateCompositeData.fromCompositeData(compositeData);
        result.add(streamState);
      } catch (AssertionError e) {
        // if that fails, try the older version
        StreamState olderStreamState = parseStreamStatePre2_1(compositeData);
        result.add(olderStreamState);
      }
    }
    return result;
  }

  private StreamState parseStreamStatePre2_1(CompositeData compositeData) {
    UUID planId = UUID.fromString((String)compositeData.get("planId"));
    String description = (String) compositeData.get("description");

    CompositeData[] sessionCompositeData = (CompositeData[]) compositeData.get("sessions");

    Set<SessionInfo> sessions = Arrays.stream(sessionCompositeData)
        .map(this::parseSessionInfoPre2_1)
        .collect(Collectors.toSet());

    return new StreamState(planId, description, sessions);
  }

  private SessionInfo parseSessionInfoPre2_1(CompositeData compositeData) {
    try {
      // these fields can be directly parsed
      InetAddress peer = InetAddress.getByName((String)compositeData.get("peer"));
      InetAddress connecting = InetAddress.getByName((String)compositeData.get("connecting"));
      org.apache.cassandra.streaming.StreamSession.State state
          = org.apache.cassandra.streaming.StreamSession.State.valueOf((String)compositeData.get("state"));

      // sending and receiving summaries parsing can be delegated to their composite data classes
      CompositeData[] receivingSummariesData = (CompositeData[]) compositeData.get("receivingSummaries");
      Set<StreamSummary> receivingSummaries = Arrays.stream(receivingSummariesData)
          .map(StreamSummaryCompositeData::fromCompositeData)
          .collect(Collectors.toSet());
      CompositeData[] sendingSummariesData = (CompositeData[]) compositeData.get("sendingSummaries");
      Set<StreamSummary> sendingSummaries = Arrays.stream(sendingSummariesData)
          .map(StreamSummaryCompositeData::fromCompositeData)
          .collect(Collectors.toSet());

      // Prior to 2.1, Session does not have session Index in the SessionInfo class
      int sessionIndex = Integer.MIN_VALUE;

      SessionInfo sessionInfo
          = new SessionInfo(peer, sessionIndex, connecting, receivingSummaries, sendingSummaries, state);

      // when pulling streams, C* also bundles in the progress of files. it's not possible to add them to SessionInfo
      // through the constructor, but it's possible via .updateProgress() calls

      CompositeData[] receivingFilesData = (CompositeData[]) compositeData.get("receivingFiles");
      Arrays.stream(receivingFilesData)
          .map(this::parseProgressInfoPre2_1)
          .forEach(sessionInfo::updateProgress);

      CompositeData[] sendingFilesData = (CompositeData[]) compositeData.get("sendingFiles");
      Arrays.stream(sendingFilesData)
          .map(this::parseProgressInfoPre2_1)
          .forEach(sessionInfo::updateProgress);

      return sessionInfo;
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  private ProgressInfo parseProgressInfoPre2_1(CompositeData compositeData) {
    InetAddress peer = null;
    try {
      peer = InetAddress.getByName((String) compositeData.get("peer"));
    } catch (UnknownHostException e) {
      LOG.warn("Could not resolve host when parsing ProgressInfo {}", compositeData.toString());
    }

    Preconditions.checkNotNull(peer);

    String fileName = (String) compositeData.get("fileName");
    ProgressInfo.Direction direction = ProgressInfo.Direction.valueOf((String) compositeData.get("direction"));
    long currentBytes = (long) compositeData.get("currentBytes");
    long totalBytes = (long) compositeData.get("totalBytes");

    // sessionIndex is not present in the ProgressInfo of C* before 2.1
    int sessionIndex = Integer.MIN_VALUE;

    return new ProgressInfo(peer, sessionIndex, fileName, direction, currentBytes, totalBytes);
  }

}
