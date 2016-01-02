/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.slb;

import com.facebook.buck.model.Pair;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ServerHealthManager {

  private static final Comparator<Pair<URI, Long>> LATENCY_COMPARATOR =
      new Comparator<Pair<URI, Long>>() {
        @Override
        public int compare(
            Pair<URI, Long> o1, Pair<URI, Long> o2) {
          return (int) (o1.getSecond() - o2.getSecond());
        }
      };

  // TODO(ruibm): It could be useful to preserve this state across runs in the local fs.
  private final ConcurrentHashMap<URI, ServerHealthState> servers;
  private final int maxAcceptableLatencyMillis;
  private final int latencyCheckTimeRangeMillis;
  private final float maxErrorsPerSecond;
  private final int errorCheckTimeRangeMillis;

  public ServerHealthManager(
      ImmutableList<URI> servers,
      int errorCheckTimeRangeMillis,
      float maxErrorsPerSecond,
      int latencyCheckTimeRangeMillis,
      int maxAcceptableLatencyMillis) {
    this.errorCheckTimeRangeMillis = errorCheckTimeRangeMillis;
    this.maxErrorsPerSecond = maxErrorsPerSecond;
    this.latencyCheckTimeRangeMillis = latencyCheckTimeRangeMillis;
    this.maxAcceptableLatencyMillis = maxAcceptableLatencyMillis;
    this.servers = new ConcurrentHashMap<>();
    for (URI server : servers) {
      this.servers.put(server, new ServerHealthState(server));
    }
  }

  public void reportLatency(URI uri, long epochMillis, long latencyMillis) {
    Preconditions.checkState(servers.containsKey(uri), "Unknown server [%s]", uri);
    servers.get(uri).reportLatency(epochMillis, latencyMillis);
  }

  public void reportError(URI uri, long epochMillis) {
    Preconditions.checkState(servers.containsKey(uri), "Unknown server [%s]", uri);
    servers.get(uri).reportError(epochMillis);
  }

  public URI getBestServer(long epochMillis) throws IOException {
    // TODO(ruibm): Computations in this method could be cached and only refreshed every 10 seconds
    // to avoid call bursts causing unnecessary CPU consumption.

    List<Pair<URI, Long>> serverLatencies = Lists.newArrayList();
    for (ServerHealthState state : servers.values()) {
      if (state.getErrorsPerSecond(epochMillis, errorCheckTimeRangeMillis) < maxErrorsPerSecond) {
        long latencyMillis = state.getLatencyMillis(epochMillis, latencyCheckTimeRangeMillis);
        if (latencyMillis <= maxAcceptableLatencyMillis) {
          serverLatencies.add(new Pair<>(state.getServer(), latencyMillis));
        }
      }
    }

    if (serverLatencies.size() == 0) {
      throw new NoHealthyServersException(String.format(
          "No servers available. Too many errors reported by all servers in the pool: [%s]",
          Joiner.on(", ").join(FluentIterable.from(servers.keySet()).transform(
              Functions.toStringFunction()))));
    }

    Collections.sort(serverLatencies, LATENCY_COMPARATOR);
    return serverLatencies.get(0).getFirst();
  }

  public String toString(long epochMillis) {
    StringBuilder builder = new StringBuilder("ServerHealthManager{\n");
    for (ServerHealthState server : servers.values()) {
      builder.append(String.format(
          "  %s\n",
          server.toString(epochMillis, latencyCheckTimeRangeMillis)));
    }

    builder.append("}");
    return builder.toString();
  }
}
