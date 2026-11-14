package com.cdeer.shieldx.internal;

import com.cdeer.shieldx.client.Format;
import com.cdeer.shieldx.client.Limits;
import com.cdeer.shieldx.client.ReportOptions;
import com.cdeer.shieldx.error.BatchTooLargeException;
import com.cdeer.shieldx.model.ClientStats;
import com.cdeer.shieldx.model.Event;
import com.cdeer.shieldx.model.EventBatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Encodes an event batch as JSON or NDJSON and optionally gzip-compresses it. */
public final class BatchEncoder {

  private BatchEncoder() {}

  public static EncodedBatch encode(
      ObjectMapper mapper,
      EventBatch batch,
      String agentVersion,
      String gatewayId,
      ReportOptions options) {
    if (batch.events().size() > Limits.MAX_BATCH_EVENTS) {
      throw new BatchTooLargeException(
          "batch has " + batch.events().size() + " events, max " + Limits.MAX_BATCH_EVENTS);
    }

    ObjectNode meta = mapper.createObjectNode();
    meta.put("agent_version", agentVersion);
    meta.put("gateway_id", gatewayId);
    meta.put("config_revision", batch.configRevision());
    meta.put("policy_count", batch.policyCount());
    ClientStats clientStats = batch.clientStats();
    if (clientStats != null) {
      meta.set("client_stats", mapper.valueToTree(clientStats));
    }

    byte[] raw;
    try {
      raw =
          options.format() == Format.JSON
              ? encodeJson(mapper, meta, batch.events())
              : encodeNdjson(mapper, meta, batch.events());
    } catch (IOException e) {
      throw new IllegalStateException("failed to encode event batch", e);
    }

    if (raw.length > Limits.MAX_BATCH_BYTES) {
      throw new BatchTooLargeException(
          "batch is " + raw.length + " bytes before compression, max " + Limits.MAX_BATCH_BYTES);
    }

    if (!options.gzip()) {
      return new EncodedBatch(raw, options.format().contentType(), null);
    }
    return new EncodedBatch(gzip(raw), options.format().contentType(), "gzip");
  }

  private static byte[] encodeJson(ObjectMapper mapper, ObjectNode meta, List<Event> events)
      throws IOException {
    ObjectNode root = meta.deepCopy();
    ArrayNode array = mapper.createArrayNode();
    for (Event event : events) {
      array.add(mapper.valueToTree(event));
    }
    root.set("events", array);
    return mapper.writeValueAsBytes(root);
  }

  private static byte[] encodeNdjson(ObjectMapper mapper, ObjectNode meta, List<Event> events)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(mapper.writeValueAsBytes(meta));
    out.write('\n');
    for (Event event : events) {
      out.write(mapper.writeValueAsBytes(event));
      out.write('\n');
    }
    return out.toByteArray();
  }

  private static byte[] gzip(byte[] raw) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
        gzip.write(raw);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("failed to gzip event batch", e);
    }
  }

  /** Encoded request body and its content headers. */
  public record EncodedBatch(byte[] body, String contentType, String contentEncoding) {}
}
