## onyx-kafka

Onyx plugin providing read and write facilities for Kafka. This plugin automatically discovers broker locations from ZooKeeper and updates the consumers when there is a broker failover.

This plugin version is *only compatible with Kafka 0.9+*. Please use [onyx-kafka-0.8](https://github.com/onyx-platform/onyx-kafka-0.8) with Kafka 0.8.

#### Installation

In your project file:

```clojure
[org.onyxplatform/onyx-kafka "0.10.0.0-beta14"]
```

In your peer boot-up namespace:

```clojure
(:require [onyx.plugin.kafka])
```

#### Functions

##### read-messages

Reads segments from a Kafka topic. Peers will automatically be assigned to each
of the topics partitions, balancing the number of partitions over the number of
peers, unless `:kafka/partition` is supplied in which case only one partition
will be read from.

Catalog entry:

```clojure
{:onyx/name :read-messages
 :onyx/plugin :onyx.plugin.kafka/read-messages
 :onyx/type :input
 :onyx/medium :kafka
 :kafka/topic "my topic"
 :kafka/receive-buffer-bytes 65536
 :kafka/zookeeper "127.0.0.1:2181"
 :kafka/offset-reset :earliest
 :kafka/deserializer-fn :my.ns/deserializer-fn
 :kafka/wrap-with-metadata? false
 ;; :kafka/start-offsets {p1 offset1, p2, offset2}
 :onyx/batch-timeout 50
 :onyx/n-peers << NUMBER OF PEERS TO READ PARTITIONS, UP TO N-PARTITION MAX >>
 :onyx/batch-size 100
 :onyx/doc "Reads messages from a Kafka topic"}
```

Lifecycle entry:

```clojure
{:lifecycle/task :read-messages
 :lifecycle/calls :onyx.plugin.kafka/read-messages-calls}
```

###### Attributes

|key                          | type      | default | description
|-----------------------------|-----------|---------|------------
|`:kafka/topic`               | `string`  |         | The topic name to connect to
|`:kafka/partition`           | `string`  |         | Optional: partition to read or write to from if auto-assignment is not used
|`:kafka/zookeeper`           | `string`  |         | The ZooKeeper connection string
|`:kafka/offset-reset`        | `keyword` |         | Offset bound to seek to when not found - `:earliest` or `:latest`
|`:kafka/receive-buffer-bytes`| `integer` |`65536`  | The size in the receive buffer in the Kafka consumer.
|`:kafka/deserializer-fn`     | `keyword` |         | A keyword that represents a fully qualified namespaced function to deserialize a message. Takes one argument - a byte array
|`:kafka/wrap-with-metadata?` | `boolean` |`false`  | Wraps message into map with keys `:offset`, `:partitions`, `:topic` and `:message` itself
|`:kafka/start-offsets`       | `map`     |         | Allows a task to be supplied with the starting offsets for all partitions. Maps partition to offset, e.g. `{0 50, 1, 90}` will start at offset 50 for partition 0, and offset 90 for partition 1
|`:kafka/consumer-opts`       | `map`     |         | A map of arbitrary configuration to merge into the underlying Kafka consumer base configuration. Map should contain keywords as keys, and the valid values described in the [Kafka Docs](http://kafka.apache.org/documentation.html#newconsumerconfigs). Please note that key values such as `fetch.min.bytes` must be in keyword form, i.e. `:fetch.min.bytes`.

##### write-messages

Writes segments to a Kafka topic using the Kafka "new" producer.

Catalog entry:

```clojure
{:onyx/name :write-messages
 :onyx/plugin :onyx.plugin.kafka/write-messages
 :onyx/type :output
 :onyx/medium :kafka
 :kafka/topic "topic"
 :kafka/zookeeper "127.0.0.1:2181"
 :kafka/serializer-fn :my.ns/serializer-fn
 :kafka/request-size 307200
 :onyx/batch-size batch-size
 :onyx/doc "Writes messages to a Kafka topic"}
```

Lifecycle entry:

```clojure
{:lifecycle/task :write-messages
 :lifecycle/calls :onyx.plugin.kafka/write-messages-calls}
```

Segments supplied to a `:onyx.plugin.kafka/write-messages` task should be in in
the following form: `{:message message-body}` with optional partition, topic and
key values.

``` clj
{:message message-body
 :key optional-key
 :partition optional-partition
 :topic optional-topic}
```

###### Attributes

|key                         | type      | default | description
|----------------------------|-----------|---------|------------
|`:kafka/topic`              | `string`  |         | The topic name to connect to
|`:kafka/zookeeper`          | `string`  |         | The ZooKeeper connection string
|`:kafka/serializer-fn`      | `keyword` |         | A keyword that represents a fully qualified namespaced function to serialize a message. Takes one argument - the segment
|`:kafka/request-size`       | `number`  |`307200` | The maximum size of request messages.  Maps to the `max.request.size` value of the internal kafka producer.
|`:kafka/no-seal?`           | `boolean` |`false`  | Do not write :done to the topic when task receives the sentinel signal (end of batch job)
|`:kafka/producer-opts`      | `map`     |         | A map of arbitrary configuration to merge into the underlying Kafka producer base configuration. Map should contain keywords as keys, and the valid values described in the [Kafka Docs](http://kafka.apache.org/documentation.html#producerconfigs). Please note that key values such as `buffer.memory` must be in keyword form, i.e. `:buffer.memory`.

#### Test Utilities

A take-segments utility function is provided for use when testing the results
of jobs with kafka output tasks. take-segments reads from a topic until a :done
is reached, and then returns the results. Note, if a `:done` is never written to a
topic, this will hang forever as there is no timeout.

```clojure
(ns your-ns.a-test
  (:require [onyx.kafka.utils :as kpu]))

;; insert code to run a job here

;; retrieve the segments on the topic
(def results
  (kpu/take-segments (:zookeeper/address peer-config) "yourtopic" your-decompress-fn))

(last results)
; :done

```

#### Embedded Kafka Server

An embedded Kafka server is included for use in test cases where jobs output to
kafka output tasks. Note, stopping the server will *not* perform a [graceful shutdown](http://kafka.apache.org/documentation.html#basic_ops_restarting) -
please do not use this embedded server for anything other than tests.

This can be used like so:

```clojure
(ns your-ns.a-test
  (:require [onyx.kafka.embedded-server :as ke]
            [com.stuartsierra.component :as component]))

(def kafka-server
  (component/start
    (ke/embedded-kafka {:advertised.host.name "127.0.0.1"
                        :port 9092
                        :embedded-kafka? embedded-kafka?
                        :broker.id 0
                        :zookeeper.connect "127.0.0.1:2188"
                        :controlled.shutdown.enable false
                        :num-partitions 1
                        ; optional log dir name - randomized dir will be created if none is supplied
                        ; :log-dir "/tmp/embedded-kafka"})))

;; insert code to run a test here

;; stop the embedded server
(component/stop kafka-server)

```

#### Development

To benchmark, start a real ZooKeeper instance (at 127.0.0.1:2181) and Kafka instance, and run the following benchmarks.

Write perf, single peer writer:
```
TIMBRE_LOG_LEVEL=info lein test onyx.plugin.output-bench-test :benchmark
```

Read perf, single peer reader:
```
TIMBRE_LOG_LEVEL=info lein test onyx.plugin.input-benchmark-test :benchmark
```

Past results are maintained in `dev-resources/benchmarking/results.txt`.

#### Contributing

Pull requests into the master branch are welcomed.

#### License

Copyright © 2015 Michael Drogalis

Distributed under the Eclipse Public License, the same as Clojure.
