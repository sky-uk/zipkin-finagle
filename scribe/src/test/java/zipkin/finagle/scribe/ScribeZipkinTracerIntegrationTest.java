/**
 * Copyright 2016-2018 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.finagle.scribe;

import com.twitter.finagle.tracing.Annotation.ClientRecv;
import com.twitter.finagle.tracing.Annotation.ClientSend;
import com.twitter.finagle.tracing.Annotation.Rpc;
import com.twitter.finagle.tracing.Annotation.ServiceName;
import com.twitter.finagle.tracing.Record;
import com.twitter.util.Duration;
import com.twitter.util.Time;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import scala.Option;
import scala.collection.Seq;
import zipkin.Span;
import zipkin.collector.CollectorMetrics;
import zipkin.collector.scribe.ScribeCollector;
import zipkin.finagle.ZipkinTracer;
import zipkin.finagle.ZipkinTracerIntegrationTest;
import zipkin.finagle.scribe.ScribeZipkinTracer.Config;
import zipkin.reporter.libthrift.InternalScribeCodec;
import zipkin.storage.InMemoryStorage;
import zipkin.storage.QueryRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static scala.collection.JavaConversions.mapAsJavaMap;
import static zipkin.finagle.FinagleTestObjects.TODAY;
import static zipkin.finagle.FinagleTestObjects.root;
import static zipkin.finagle.FinagleTestObjects.seq;

public class ScribeZipkinTracerIntegrationTest extends ZipkinTracerIntegrationTest {
  final Option<Duration> none = Option.empty(); // avoid having to force generics

  InMemoryStorage storage = new InMemoryStorage();
  ScribeCollector scribe;

  @Before
  public void start() {
    scribe = ScribeCollector.builder()
        .metrics(CollectorMetrics.NOOP_METRICS)
        .storage(storage).build();
    scribe.start();
  }

  @After
  public void close() {
    scribe.close();
  }

  Config config = Config.builder().initialSampleRate(1.0f).host("127.0.0.1:9410").build();

  @Override protected ZipkinTracer newTracer() {
    return new ScribeZipkinTracer(config, stats);
  }

  @Override protected List<List<Span>> getTraces() {
    return storage.spanStore().getTraces(QueryRequest.builder().build());
  }

  @Override protected int messageSizeInBytes(List<byte[]> encodedSpans) {
    return InternalScribeCodec.messageSizeInBytes("zipkin".getBytes(), encodedSpans);
  }

  @Test
  public void whenScribeIsDown() throws Exception {
    scribe.close();

    tracer.record(new Record(root, Time.fromMilliseconds(TODAY), new ServiceName("web"), none));
    tracer.record(new Record(root, Time.fromMilliseconds(TODAY), new Rpc("get"), none));
    tracer.record(new Record(root, Time.fromMilliseconds(TODAY), new ClientSend(), none));
    tracer.record(new Record(root, Time.fromMilliseconds(TODAY + 1), new ClientRecv(), none));

    Thread.sleep(1500); // wait for scribe request attempt to go through

    Map<Seq<String>, Object> map = mapAsJavaMap(stats.counters());
    assertThat(map.get(seq("spans"))).isEqualTo(1L);
    assertThat(map.get(seq("span_bytes"))).isEqualTo(165L);
    assertThat(map.get(seq("spans_dropped"))).isEqualTo(1L);
    assertThat(map.get(seq("messages"))).isEqualTo(1L);
    assertThat(map.get(seq("message_bytes"))).isEqualTo(273L);
    assertThat(map.get(seq("messages_dropped"))).isEqualTo(1L);
    assertThat(map.get(seq("messages_dropped", "com.twitter.finagle.Failure"))).isEqualTo(1L);
    assertThat(map.get(seq("messages_dropped", "com.twitter.finagle.Failure",
            "com.twitter.finagle.ConnectionFailedException"))).isEqualTo(1L);
    assertThat(map.get(seq("messages_dropped", "com.twitter.finagle.Failure",
            "com.twitter.finagle.ConnectionFailedException",
            "io.netty.channel.AbstractChannel$AnnotatedConnectException"))).isEqualTo(1L);
    assertThat(map.get(seq("messages_dropped", "com.twitter.finagle.Failure",
            "com.twitter.finagle.ConnectionFailedException",
            "io.netty.channel.AbstractChannel$AnnotatedConnectException",
            "java.net.ConnectException"))).isEqualTo(1L);

    assertThat(map.size()).isEqualTo(10);
  }
}
