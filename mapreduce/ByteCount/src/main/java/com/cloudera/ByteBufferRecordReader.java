/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.EnumSet;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.ReadOption;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.hdfs.DFSInputStream;
import org.apache.hadoop.hdfs.DFSInputStream.ReadStatistics;
import org.apache.hadoop.io.ElasticByteBufferPool;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.CompressionInputStream;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

/**
 * A reader that returns the split as a single ByteBuffer.
 * <p>
 * Borrowed heavily from FixedLengthRecordReader.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class ByteBufferRecordReader
    extends RecordReader<LongWritable, ByteBufferWritable> {
  private static final Log LOG 
      = LogFactory.getLog(ByteBufferRecordReader.class);

  private long start;
  private long end;
  private long pos;
  private FSDataInputStream fileIn;
  private Seekable filePosition;
  private LongWritable key;
  private ByteBufferWritable value;
  private boolean isCompressedInput;
  private Decompressor decompressor;
  private InputStream inputStream;
  private TaskAttemptContext context;
  private ReadStatistics readStats;
  private ElasticByteBufferPool bufferPool;
  private EnumSet<ReadOption> readOption;

  /**
   * Enum for accessing read statistics.
   */
  public static enum READ_COUNTER {
    BYTES_READ,
    LOCAL_BYTES_READ,
    SCR_BYTES_READ,
    ZCR_BYTES_READ
  };

  public ByteBufferRecordReader() {
  }

  @Override
  public void initialize(InputSplit genericSplit,
                         TaskAttemptContext context) throws IOException {
    FileSplit split = (FileSplit) genericSplit;
    Configuration job = context.getConfiguration();
    this.context = context;
    final Path file = split.getPath();
    initialize(job, split.getStart(), split.getLength(), file);
  }

  private void initialize(Configuration job, long splitStart, long splitLength,
                         Path file) throws IOException {
    start = splitStart;
    end = start + splitLength;
    pos = start;

    // open the file and seek to the start of the split
    final FileSystem fs = file.getFileSystem(job);
    fileIn = fs.open(file);

    this.readStats = new ReadStatistics();
    this.bufferPool = new ElasticByteBufferPool();
    boolean skipChecksums = job.getBoolean("bytecount.skipChecksums", false);
    this.readOption =
        skipChecksums ? EnumSet.of(ReadOption.SKIP_CHECKSUMS) : EnumSet
            .noneOf(ReadOption.class);
    
    CompressionCodec codec = new CompressionCodecFactory(job).getCodec(file);
    if (null != codec) {
      isCompressedInput = true; 
      decompressor = CodecPool.getDecompressor(codec);
      CompressionInputStream cIn
          = codec.createInputStream(fileIn, decompressor);
      filePosition = cIn;
      inputStream = cIn;
      LOG.info(
          "Compressed input; cannot compute number of records in the split");
    } else {
      fileIn.seek(start);
      filePosition = fileIn;
      inputStream = fileIn;
      LOG.info("Split pos = " + start + " length " + splitLength);
    }
  }

  @Override
  public synchronized boolean nextKeyValue() throws IOException {
    if (key == null) {
      key = new LongWritable();
    }
    if (value == null) {
      value = new ByteBufferWritable();
    }
    if (pos >= end) {
      return false;
    }
    
    int numBytesRead = 0;
    // Use zero-copy ByteBuffer reads if available
    if (inputStream instanceof FSDataInputStream) {
      FSDataInputStream fsIn = (FSDataInputStream)inputStream;
      ByteBuffer buf = fsIn.read(bufferPool, (int)(end-start), readOption);
      numBytesRead += buf.limit();
      pos += buf.limit();
      // Update stats
      InputStream wrappedStream = fsIn.getWrappedStream();
      if (wrappedStream instanceof DFSInputStream) {
        DFSInputStream dfsIn = (DFSInputStream)wrappedStream;
        updateStats(dfsIn.getReadStatistics());
      }
      // Switch out the buffers
      if (value.getBuffer() != null) {
        fsIn.releaseBuffer(value.getBuffer());
      }
      value.setByteBuffer(buf);
    }
    // Fallback to normal byte[] based reads with a copy to the ByteBuffer
    else {
      byte[] b = new byte[(int)(end-start)];
      IOUtils.readFully(inputStream, b);
      numBytesRead += b.length;
      pos += b.length;
      value.setByteBuffer(ByteBuffer.wrap(b));
    }
    
    return numBytesRead > 0;
  }

  private void updateStats(ReadStatistics newStats) {
    context.getCounter(READ_COUNTER.BYTES_READ).increment(
        newStats.getTotalBytesRead() - readStats.getTotalBytesRead());
    context.getCounter(READ_COUNTER.LOCAL_BYTES_READ).increment(
        newStats.getTotalLocalBytesRead() - readStats.getTotalLocalBytesRead());
    context.getCounter(READ_COUNTER.SCR_BYTES_READ).increment(
        newStats.getTotalShortCircuitBytesRead() - readStats.getTotalShortCircuitBytesRead());
    context.getCounter(READ_COUNTER.ZCR_BYTES_READ).increment(
        newStats.getTotalZeroCopyBytesRead() - readStats.getTotalZeroCopyBytesRead());

    this.readStats = new ReadStatistics(newStats);
  }

  @Override
  public LongWritable getCurrentKey() {
    return key;
  }

  @Override
  public ByteBufferWritable getCurrentValue() {
    return value;
  }

  @Override
  public synchronized float getProgress() throws IOException {
    if (start == end) {
      return 0.0f;
    } else {
      return Math.min(1.0f, (getFilePosition() - start) / (float)(end - start));
    }
  }
  
  @Override
  public synchronized void close() throws IOException {
    try {
      if (inputStream != null) {
        inputStream.close();
        inputStream = null;
      }
    } finally {
      if (decompressor != null) {
        CodecPool.returnDecompressor(decompressor);
        decompressor = null;
      }
    }
  }

  private long getFilePosition() throws IOException {
    long retVal;
    if (isCompressedInput && null != filePosition) {
      retVal = filePosition.getPos();
    } else {
      retVal = pos;
    }
    return retVal;
  }

}
