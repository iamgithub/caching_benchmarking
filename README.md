caching_benchmarking
====================

Andrew Wang (andrew.wang@cloudera.com, @umbrant)
Colin McCabe (cmccabe@cloudera.com)

This repository contains benchmarks, scripts, and data related to our Hadoop Summit Amsterdam 2014 presentation, "In-memory Caching in HDFS: Lower latency, Same great taste."

Contents
--------

* `micro`: vecsum2 microbenchmark, libhdfs program used to test raw throughput of different HDFS read methods
* `mapreduce`: ByteCount MapReduce job which performs zero-copy reads
* `graphs`: flamegraphs and plotting scripts from vecsum microbenchmark
* `scripts`: collection of helper scripts used to run multi-node experiments with MapReduce and Impala, including the SQL queries used. Also contains raw data and parsing scripts used to make our graphs.

License
-------

Everything here is under the Apache License, Version 2.0. We're in the process of contributing some of the benchmark code back to core Hadoop.
