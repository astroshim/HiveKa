/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.kafka;

import java.io.IOException;
import java.util.HashSet;

import kafka.message.Message;
import org.apache.hadoop.fs.ChecksumException;
import org.apache.hadoop.hive.kafka.camus.CamusWrapper;
import org.apache.hadoop.hive.kafka.camus.ExceptionWritable;
import org.apache.hadoop.hive.kafka.camus.KafkaKey;
import org.apache.hadoop.hive.kafka.camus.KafkaReader;
import org.apache.hadoop.hive.kafka.camus.KafkaRequest;
import org.apache.hadoop.hive.kafka.camus.KafkaSplit;
import org.apache.hadoop.hive.kafka.camus.MessageDecoder;
import org.apache.hadoop.hive.kafka.camus.MessageDecoderFactory;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;

@SuppressWarnings({ "deprecation" })
public class KafkaRecordReader
    extends RecordReader<KafkaKey, CamusWrapper> {
  private static final String PRINT_MAX_DECODER_EXCEPTIONS = "max.decoder.exceptions.to.print";
  private static final String DEFAULT_SERVER = "server";
  private static final String DEFAULT_SERVICE = "service";
  private TaskAttemptContext context;

  private Mapper<KafkaKey, Writable, KafkaKey, Writable>.Context mapperContext;
  private KafkaReader reader;

  private long totalBytes;
  private long readBytes = 0;

  private boolean skipSchemaErrors = false;
  private MessageDecoder decoder;
  private final BytesWritable msgValue = new BytesWritable();
  private final BytesWritable msgKey = new BytesWritable();
  private final KafkaKey key = new KafkaKey();
  private CamusWrapper value;

  private int maxPullHours = 0;
  private int exceptionCount = 0;
  private long maxPullTime = 0;
  private long beginTimeStamp = 0;
  private long endTimeStamp = 0;
  private HashSet<String> ignoreServerServiceList = null;

  private String statusMsg = "";

  KafkaSplit split;
  private static Logger log = Logger.getLogger(KafkaRecordReader.class);

  /**
   * Record reader to fetch directly from Kafka
   *
   * @param split
   * @throws IOException
   * @throws InterruptedException
   */
  public KafkaRecordReader(InputSplit split, TaskAttemptContext context) throws IOException,
      InterruptedException {
    initialize(split, context);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override
  public void initialize(InputSplit split, TaskAttemptContext context) throws IOException,
      InterruptedException {
    // For class path debugging
    log.info("classpath: " + System.getProperty("java.class.path"));
    ClassLoader loader = KafkaRecordReader.class.getClassLoader();
    log.info("PWD: " + System.getProperty("user.dir"));
    log.info("classloader: " + loader.getClass());
    log.info("org.apache.avro.Schema: " + loader.getResource("org/apache/avro/Schema.class"));

    this.split = (KafkaSplit) split;
    this.context = context;

    if (context instanceof Mapper.Context) {
      mapperContext = (Mapper.Context) context;
    }

    this.skipSchemaErrors = false;

    this.endTimeStamp = Long.MAX_VALUE;

    this.maxPullTime = Long.MAX_VALUE;

    beginTimeStamp = 0;

    ignoreServerServiceList = new HashSet<String>();

    this.totalBytes = this.split.getLength();
  }

  @Override
  public synchronized void close() throws IOException {
    if (reader != null) {
      reader.close();
    }
  }

  private CamusWrapper getWrappedRecord(String topicName, byte[] payload) throws IOException {
    CamusWrapper r = null;
    try {
      r = decoder.decode(payload);
    } catch (Exception e) {
      if (!skipSchemaErrors) {
        throw new IOException(e);
      }
    }
    return r;
  }

  private static byte[] getBytes(BytesWritable val) {
    byte[] buffer = val.getBytes();

        /*
         * FIXME: remove the following part once the below jira is fixed
         * https://issues.apache.org/jira/browse/HADOOP-6298
         */
    long len = val.getLength();
    byte[] bytes = buffer;
    if (len < buffer.length) {
      bytes = new byte[(int) len];
      System.arraycopy(buffer, 0, bytes, 0, (int) len);
    }

    return bytes;
  }

  @Override
  public float getProgress() throws IOException {
    if (getPos() == 0) {
      return 0f;
    }

    if (getPos() >= totalBytes) {
      return 1f;
    }
    return (float) ((double) getPos() / totalBytes);
  }

  private long getPos() throws IOException {
    return readBytes;
  }

  @Override
  public KafkaKey getCurrentKey() throws IOException, InterruptedException {
    return key;
  }

  @Override
  public CamusWrapper getCurrentValue() throws IOException, InterruptedException {
    return value;
  }

  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {

    Message message = null;

    while (true) {
      try {
        if (reader == null || !reader.hasNext()) {
          KafkaRequest request = (KafkaRequest) split.popRequest();
          if (request == null) {
            return false;
          }

          if (maxPullHours > 0) {
            endTimeStamp = 0;
          }

          key.set(request.getTopic(), request.getLeaderId(), request.getPartition(),
              request.getOffset(), request.getOffset(), 0);
          value = null;
          log.info("\n\ntopic:" + request.getTopic() + " partition:"
              + request.getPartition() + " beginOffset:" + request.getOffset()
              + " estimatedLastOffset:" + request.getLastOffset());

          statusMsg += statusMsg.length() > 0 ? "; " : "";
          statusMsg += request.getTopic() + ":" + request.getLeaderId() + ":"
              + request.getPartition();
          context.setStatus(statusMsg);

          if (reader != null) {
            closeReader();
          }
          reader = new KafkaReader(context, request,
              30000, // kafka timeout value
              1024 * 1024 // kafka buffer size
          );

          decoder = MessageDecoderFactory.createMessageDecoder(context, request.getTopic());
        }
        int count = 0;
        while (reader.getNext(key, msgValue, msgKey)) {
          readBytes += key.getMessageSize();
          count++;
          context.progress();
          mapperContext.getCounter("total", "data-read").increment(msgValue.getLength());
          mapperContext.getCounter("total", "event-count").increment(1);
          byte[] bytes = getBytes(msgValue);
          byte[] keyBytes = getBytes(msgKey);
          // check the checksum of message.
          // If message has partition key, need to construct it with Key for checkSum to match
          Message messageWithKey = new Message(bytes,keyBytes);
          Message messageWithoutKey = new Message(bytes);
          long checksum = key.getChecksum();
          if (checksum != messageWithKey.checksum() && checksum != messageWithoutKey.checksum()) {
            throw new ChecksumException("Invalid message checksum : MessageWithKey : "
                + messageWithKey.checksum() + " MessageWithoutKey checksum : "
                + messageWithoutKey.checksum()
                + ". Expected " + key.getChecksum(),
                key.getOffset());
          }

          long tempTime = System.currentTimeMillis();
          CamusWrapper wrapper;
          try {
            wrapper = getWrappedRecord(key.getTopic(), bytes);
          } catch (Exception e) {
            if (exceptionCount < getMaximumDecoderExceptionsToPrint(context)) {
              mapperContext.write(key, new ExceptionWritable(e));
              exceptionCount++;
            } else if (exceptionCount == getMaximumDecoderExceptionsToPrint(context)) {
              exceptionCount = Integer.MAX_VALUE; //Any random value
              log.info("The same exception has occured for more than " + getMaximumDecoderExceptionsToPrint(context) + " records. All further exceptions will not be printed");
            }
            continue;
          }

          if (wrapper == null) {
            mapperContext.write(key, new ExceptionWritable(new RuntimeException(
                "null record")));
            continue;
          }

          long timeStamp = wrapper.getTimestamp();
          try {
            key.setTime(timeStamp);
            key.addAllPartitionMap(wrapper.getPartitionMap());
            setServerService();
          } catch (Exception e) {
            mapperContext.write(key, new ExceptionWritable(e));
            continue;
          }

          if (timeStamp < beginTimeStamp) {
            mapperContext.getCounter("total", "skip-old").increment(1);
          } else if (endTimeStamp == 0) {
            DateTime time = new DateTime(timeStamp);
            statusMsg += " begin read at " + time.toString();
            context.setStatus(statusMsg);
            log.info(key.getTopic() + " begin read at " + time.toString());
            endTimeStamp = (time.plusHours(this.maxPullHours)).getMillis();
          } else if (timeStamp > endTimeStamp || System.currentTimeMillis() > maxPullTime) {
            if (timeStamp > endTimeStamp)
              log.info("Kafka Max history hours reached");
            if (System.currentTimeMillis() > maxPullTime)
              log.info("Kafka pull time limit reached");
            statusMsg += " max read at " + new DateTime(timeStamp).toString();
            context.setStatus(statusMsg);
            log.info(key.getTopic() + " max read at "
                + new DateTime(timeStamp).toString());
            mapperContext.getCounter("total", "request-time(ms)").increment(
                reader.getFetchTime());
            mapperContext.write(key, new ExceptionWritable("Topic not fully pulled, max partition hours reached"));
            closeReader();
          } else if (System.currentTimeMillis() > maxPullTime) {
            log.info("Max pull time reached");
            mapperContext.write(key, new ExceptionWritable("Topic not fully pulled, max task time reached"));
            closeReader();
          }

          long secondTime = System.currentTimeMillis();
          value = wrapper;
          long decodeTime = ((secondTime - tempTime));

          mapperContext.getCounter("total", "decode-time(ms)").increment(decodeTime);

          if (reader != null) {
            mapperContext.getCounter("total", "request-time(ms)").increment(
                reader.getFetchTime());
          }
          return true;
        }
        log.info("Records read : " + count);
        count = 0;
        reader = null;
      } catch (Throwable t) {
        Exception e = new Exception(t.getLocalizedMessage(), t);
        e.setStackTrace(t.getStackTrace());
        mapperContext.write(key, new ExceptionWritable(e));
        reader = null;
        continue;
      }
    }
  }

  private void closeReader() throws IOException {
    if (reader != null) {
      try {
        reader.close();
      } catch (Exception e) {
        // not much to do here but skip the task
      } finally {
        reader = null;
      }
    }
  }

  public void setServerService()
  {
    if(ignoreServerServiceList.contains(key.getTopic()) || ignoreServerServiceList.contains("all"))
    {
      key.setServer(DEFAULT_SERVER);
      key.setService(DEFAULT_SERVICE);
    }
  }

  public static int getMaximumDecoderExceptionsToPrint(JobContext job) {
    return job.getConfiguration().getInt(PRINT_MAX_DECODER_EXCEPTIONS, 10);
  }
}